## Context

`test_case_eval_summaries` (analytics DB) stores one row per `(test_suite_run_id, test_case_id,
run_index, request_index, turn_index, computation_id)` — a test case with 2 turns × 2 requests × 2
reruns produces 8 rows per computation. Each row's `metric_values` JSONB holds whichever metrics fired
on that specific row; a TSMD's optional `condition` (JSONata) can scope a metric to a subset of rows
(e.g. `request.index == 0`), so two rows of the same test case can have different sets of populated
metrics.

Two existing computations read these raw rows without any per-test-case grouping:

- `EvalSummaryRowScoreComputer` (`eval-summary-scoring` capability) grafts `id IN (:rowIds) GROUP BY id`
  onto the suite's resolved `OverallScoreDefinition` query to compute `test_case_eval_scores.score`/
  `.passed` per row. Every group has exactly one row, so `avg(field)` over a row where a metric didn't
  fire is `NULL`, and `OverallScoreDefinitionResolver`'s `coalesce(avg(field), 0)` turns that into a
  silent **0** — the row's score is wrong specifically where the metric was skipped.
- `MetricScoreComputationExecutor.computeOverall` (`metric-score-statistics` capability) aggregates the
  run's `overall` directly over all raw rows, ungrouped — a test case's row count (driven by how many
  turns/requests/reruns it has) directly and unintentionally weights its contribution to the run's score.

Both use the same typed, sealed `OverallScoreDefinition` (`Mean`/`WeightedMean`/`CustomFunction`,
`evaluation-runner-core/.../runner/dto/overallscore/`), resolved to a `StructuredQuery` by
`OverallScoreDefinitionResolver` and executed via the generic Query DSL translator
(`StructuredQueryService`).

## Goals / Non-Goals

**Goals:**
- Introduce one new table, `test_case_metric_scores_aggregated`, that collapses a test case's rows for a
  computation (all turn/request/run indices) into per-metric avg/min/max/count, stored as a JSONB map.
- Rebuild both existing overall-score computations (`eval-summary-scoring`'s per-test-case score,
  `metric-score-statistics`'s run-level `overall`) to read from this new table for all three
  `OverallScoreDefinition` variants (`Mean`, `WeightedMean`, `CustomFunction`), fixing the two problems
  in Context.
- Fix the "metric didn't fire → silently scored 0" bug for `Mean`/`WeightedMean` by excluding a
  never-fired metric from the average instead of coalescing it to 0.

**Non-Goals:**
- No change to per-metric `BuiltInMetricStatistics` (run-level AVG/P10/P90/MIN/MAX), to
  `FilteredMetricScoreAggregator`/run-comparison (`run-comparison-metric-scores`), or to a `CustomFunction`-
  defined run-level `overall` — all three have the same weight-skew issue but are out of scope for this
  change. `CustomFunction` was initially retargeted (see Decision 5's revision below) but that approach was
  reverted post-implementation once its complexity/fragility was judged disproportionate to the benefit.
- No schema change to `test_case_eval_scores` or `metric_score_results` — only where their values are
  computed from changes, not their shape. `test_case_eval_scores` was later extended in place (additive
  columns + a new Query DSL entity, not a shape change to its existing key/grain — see Decision 9), and
  later still re-keyed to one row per test case, but additively — no destructive change to any existing row
  (see Decision 10); `metric_score_results` remains untouched.
- No backfill for historical computations.

## Decisions

**1. Storage shape: one row per test case, JSONB map of per-metric stats (not one row per metric).**
`metric_scores` is shaped `{"<metricName>": {"avg":..,"min":..,"max":..,"count":..}}`. This mirrors
`test_case_eval_summaries.metric_values`'s existing shape family (`{metricName: {outputField: value}}`),
keeps the table at exactly one row per `(test_suite_run_id, test_case_id, computation_id)`, and avoids a
long/normalized `(test_case_id, metric_name)` table that would need per-metric-name `CASE`-shaped SQL to
reassemble into a single per-test-case score (the Query DSL's `CaseExpr` is rejected for every internal
entity today — confirmed by reading `ExprTranslator`/`CaseExpr`'s own javadoc — so a normalized table
would force hand-written SQL for reassembly anyway, with none of the "one row already is one test case's
answer" benefit).
*Alternative considered*: one row per `(test_case_id, metric_name)`. Rejected — more rows, and the
Mean/WeightedMean rebuild would need per-metric-name filtering logic the normalized shape doesn't need.

**2. Compute the aggregation with hand-written jOOQ, not the generic Query DSL translator.**
The `jsonb_object_agg` shape needed to build the JSONB map isn't expressible as an
`OverallScoreDefinition`-style query, and this table has nothing to do with scoring — it's a
data-normalization step. `TestCaseMetricScoreAggregator` walks `metric_values` (a two-level JSONB map,
`{"<tsmdName>": {"<outputField>": value}}`) generically in a single scan of
`test_case_eval_summaries`, via two chained `jsonb_each` calls (mirroring the raw-SQL table-function
pattern already used by `QueryDslRunnableTestCaseSelector#compile`) filtered to numeric leaves with
`jsonb_typeof(...) = 'number'`, rather than enumerating every discovered `MetricField` in Java and
`UNION ALL`-ing one arm per field (one scan per field). This means the component needs no
`MetricFieldDiscoverer`/`MetricField` input at all — it self-discovers whatever metric keys are actually
present in the data. Trade-off: numeric-field detection moves from schema-declared (the metric's output
schema says the field is `number`) to runtime-typed (the stored JSON value's type is `number`); a value
that doesn't match its declared schema type is silently excluded here rather than throwing a cast error,
consistent with this table's fail-soft, regenerable-derived-data philosophy.

**3. Register a new Query DSL entity (`test_case_metric_scores`) and keep both score computations on the
existing DSL/`StructuredQueryService` execution path, rather than a parallel Java-side SQL
reimplementation.** Both existing overall-score computations (`eval_summaries` per-row scoring, Phase 3
run-level `overall`) already go through `OverallScoreDefinitionResolver` → `StructuredQuery` →
`StructuredQueryService`. Introducing a second, hand-rolled Java evaluator for the same `Mean`/
`WeightedMean` formulas would mean maintaining two implementations of the same arithmetic (one in SQL,
one in Java) that must stay behaviorally identical. Instead, `metric_scores` gets flattened into
addressable fields `metric_scores::<name>::avg|min|max|count` the same way `eval_summaries.metric_values`
already is, and a new `StructuredQueryEntityResolver` (mirroring `PostgresMetricScoreResultEntityResolver`'s
plain-table template) registers `test_case_metric_scores` in the DSL. This also makes the table
queryable/filterable through the existing generic query endpoint, the same way `metric_score_results` is
today — a useful byproduct, not the primary motivation.
- **`Mean`/`WeightedMean`, both Phase 2 and Phase 3** (`OverallScoreDefinitionResolver`): built **directly**
  against `test_case_metric_scores` — no `eval_summaries`-authored intermediate, no rewrite step. We
  author these queries ourselves (unlike `CustomFunction`, see below), so there's nothing to retarget:
  `avg(metric_scores::<name>::avg)` per configured metric is composed straight into the resolver's
  existing formula, `divide(add(coalesce(avg(f1),0), coalesce(avg(f2),0), ...), n)` — the exact same
  formula this class has always built for `eval_summaries`, just pointed at the new entity/field names
  from the start. Phase 3 (`MetricScoreComputationExecutor.computeOverall`) executes the resolved query
  directly, ungrouped, over the whole run. Phase 2 (`EvalSummaryRowScoreComputer.computeByTestCase`)
  grafts `test_case_id IN (:ids) GROUP BY test_case_id` onto the same resolved query — reusing the graft
  mechanism `CustomFunction` already needed (`requireGroupableShape`/`groupByTestCaseId`, generalized past
  their original `CustomFunction`-only naming) — to turn it into one value per test case per batch. See
  Decision 8 for why this superseded an earlier, more complex approach.
- **`CustomFunction`** (Phase 3 / run-level `overall` only — see Decision 6 for why Phase 2 never needs
  this): an opaque, already-stored, client-authored expression written against `eval_summaries`/`metric::`
  fields. Unlike `Mean`/`WeightedMean`, it is **not** retargeted onto `test_case_metric_scores` — see
  Decision 5 for why an earlier retargeting approach was reverted. It resolves and executes exactly as it
  did before this change: directly against `eval_summaries`, ungrouped, unweighted by test-case row count.

**4. A metric absent from a test case's (or, at the run level, every test case's) aggregated data is
coalesced to `0` for `Mean`/`WeightedMean` — the same formula/semantics this class has always used,
unchanged.** This is *not* the fix for the originally reported bug — that bug (a metric scoped by
`condition` to a subset of a test case's rows dragging that test case's score toward `0` on the rows it
didn't fire on) is fixed one layer down, by `TestCaseMetricScoreAggregator`: a partially-firing metric's
`avg` in `test_case_metric_scores_aggregated` is always computed over just the rows it fired on, so it is
never `NULL` at this layer for a test case where the metric fired even once. `coalesce(avg, 0)` only still
applies to the narrower case of a metric that's absent from a test case's (or the run's) aggregated data
*entirely* — and reusing the exact formula `OverallScoreDefinitionResolver` already had, rather than
introducing a bespoke exclude-vs-coalesce combiner, was a deliberate simplification (see Decision 8).

**5. `CustomFunction` is *not* retargeted onto `test_case_metric_scores` — reverted post-implementation.**
An earlier version of this design retargeted a `CustomFunction`'s `entity`/field references the same way
`Mean`/`WeightedMean` are built (via a `CustomFunctionQueryRewriter` walking the resolved `StructuredQuery`
tree, replacing `entity: eval_summaries` with `entity: test_case_metric_scores` and every
`metric::<name>::<outputField>` with `metric_scores::<name>::avg`), plus a guard leaving a query referencing
any non-metric flattened field family (`data::`, `response::`, `metricInfo::`, `metricError::`) entirely
untouched. Reviewing the implemented rewriter after the fact, this was judged disproportionate complexity
for the value it added: a full tree-walk over an opaque, client-authored expression, a non-metric-field
guard to avoid retargeting onto columns that don't exist, and a documented semantic redefinition of
population functions (`roc_auc(label, probability)` measuring per-test-case averages instead of raw
observations) — all to fix a weight-skew issue that `BuiltInMetricStatistics` and
`FilteredMetricScoreAggregator` already carry as an accepted Non-Goal. `CustomFunction` was already fully
excluded from Phase 2 (Decision 6) precisely because it never had a real per-test-case use case, so Phase 3
was the *only* place retargeting mattered — not enough to justify the rewriter's fragility (it must
correctly recognize every `metric::` `FieldExpr` in an arbitrary expression tree, including nested/unusual
shapes) for a limitation already accepted elsewhere in this same change.
`CustomFunctionQueryRewriter` was deleted; `MetricScoreComputationExecutor.computeOverallScore` and
`FilteredMetricScoreAggregator.computeOverall` now execute a resolved `CustomFunction` query unchanged,
directly against `eval_summaries`, exactly as before this whole change (see the updated Non-Goals).
*Alternative considered*: keep the rewriter as originally implemented (retarget with a non-metric-field
guard). This was the original decision, not merely considered — reverted once its complexity/fragility was
weighed against the narrow, already-accepted-elsewhere benefit.

**6. `testCaseOverallScore` is restricted to `Mean`/`WeightedMean` — `CustomFunction` is rejected outright.**
Discovered late (post-implementation, during design discussion): a population-dependent function like
`roc_auc` is meaningless for a single test case, and `Mean`/`WeightedMean` are already structurally
metric-only (`WeightedMetric(metricName, outputField, weight)` — no way to reference a non-metric field at
all), so `CustomFunction` never had a real per-test-case use case — this is independent of Decision 5's
Phase 3 retargeting reversal; the reasoning here holds regardless of which entity `CustomFunction` executes
against.
`TestSuiteRequestValidator.validateTestCaseOverallScore` now rejects a `CustomFunction`
`testCaseOverallScore` with a 400 at suite create/update/clone-revalidation. Consequently
`EvalSummaryRowScoreComputer` no longer has a `CustomFunction` branch of substance — the sealed switch's
`CustomFunction` case is a defensive, logged no-op (unreachable given the validation), and the class's
`requireGroupableShape`/`groupByTestCaseId` graft methods (kept, generalized — see Decision 3/8) are only
ever exercised by `Mean`/`WeightedMean` in practice. `TestSuiteEvaluationJob`'s existing
"`testCaseOverallScore` null → falls back to `overallScore`" fallback
was also narrowed: it no longer inherits a `CustomFunction` `overallScore` (that would silently defeat the
validation via the fallback path), so per-test-case scoring is then simply not computed — the same outcome
as an unconfigured suite, not a warning-producing "unreachable" case.
*Alternative considered*: keep the fallback unconditional and let the (now log-only) `CustomFunction`
branch absorb it, logging a warning every time. Rejected — this is an extremely common configuration
(any suite with only an `overallScore` `CustomFunction` set, no explicit `testCaseOverallScore`), so it
would log on every such run for no actionable reason.

**7. `test_case_metric_scores_aggregated` is fully re-aggregated (not incrementally merged) on every
flush that touches a test case, written via `INSERT ... ON CONFLICT (...) DO UPDATE` (not `DO NOTHING`).**
`InProcessMetricEvaluationExecutor` flushes in cursor order over `TestCaseRunResult`, not grouped by test
case, so one test case's rows can straddle two flush batches. Each flush that touches a test case
re-aggregates that test case's *entire* row set for the computation (`WHERE test_case_id = ? AND
computation_id = ?`, not scoped to the current batch's rows), making the upsert idempotent and always
correct even though a case touched across multiple flushes gets recomputed more than once. This mirrors
`test_case_eval_scores`'s own precedent (it already re-derives its score from all
`OverallScoreDefinitionResolver`-selected rows, not just the current batch) rather than inventing a new
incremental-merge strategy.

**8. `Mean`/`WeightedMean` build directly against `test_case_metric_scores` — no combiner, no rewrite
step.** Superseded a more complex earlier approach that fetched each metric's `avg` as a separate nullable
column and combined them in a new `AggregatedOverallScoreComputer` class (`sum(present)/count(present)`,
excluding an absent metric rather than coalescing it). Discovered during a design discussion after initial
implementation: `Mean`/`WeightedMean` queries are authored **by us**, every time, in
`OverallScoreDefinitionResolver` — there is no client-authored content to preserve, so there is no reason
to build the query against `eval_summaries`/`metric::` and retarget it afterward (`CustomFunction`'s
original approach, since reverted — see Decision 5), nor to decompose it into separate per-metric fetches
and recombine in Java. The resolver simply
builds `entity: test_case_metric_scores` with `metric_scores::<name>::avg` field references from the
start, using its pre-existing `coalesce(avg(f), 0)` formula unchanged. This:
- **Eliminates `AggregatedOverallScoreComputer` entirely** — eleven fewer moving parts (a class, its two
  static helpers, and every call site).
- **Eliminates the "duplicate weighted term causes ambiguous column" bug** the fetch-and-combine approach
  introduced: that only existed because of the *one column per metric name* select shape, which a single
  arithmetic expression (evaluating the same `avg(field)` twice inline) never has.
- **Reintroduces `coalesce(avg, 0)`** for a metric absent from a test case's/the run's aggregated data
  entirely (see Decision 4) — accepted, since the bug this change actually targets is fixed at the
  aggregation layer (Decision 2/3), not by this formula.
*Alternative considered*: keep the fetch-and-combine approach, just fix its bugs in place (dedupe metric
keys, keep the exclude semantics). Rejected — the user found it "too complex... not maintainable" for what
is fundamentally "point the DSL at the new table on the new `avg` field," and the simpler approach removes
an entire class and a whole bug class rather than patching around it.

**9. `test_case_eval_scores` is extended in place, not re-keyed or merged, to fix its own remaining
per-row duplication — and exposed as its own `DISTINCT ON` Query DSL entity.** Manually verifying this
change's fix on a real multi-turn/multi-rerun run surfaced that `test_case_eval_scores` still has one row
per raw `test_case_eval_summaries` row (e.g. 6 rows for a 2-turn × 2-rerun test case), all carrying the
same, correctly-computed score — a genuine storage duplication, though not a value bug (the numbers are
correct), and an explicit Non-Goal of this change as originally scoped ("No schema change to
`test_case_eval_scores`"). Two other directions were discussed and rejected before this one:
- Merging `score`/`passed` as new columns directly onto `test_case_metric_scores_aggregated`, computed
  either via a hand-embedded `ExprTranslator`-rendered expression spliced into `TestCaseMetricScoreAggregator`'s
  existing query, or via a Java reimplementation of the `Mean`/`WeightedMean` combination formula. Both
  rejected: the first as needless SQL-embedding complexity (including a Postgres "aggregate function calls
  cannot be nested" hazard requiring an extra CTE level) for what should be simple; the second because it
  duplicates `OverallScoreDefinitionResolver`'s formula in a second, Java-side place — reintroducing exactly
  the "two implementations of the same arithmetic" risk Decision 3 already rejected once for `CustomFunction`.
- Re-keying `test_case_eval_scores` itself from `eval_summary_id` to `(test_suite_run_id, test_case_id,
  computation_id)`, collapsing it to one row per test case via a destructive `DROP TABLE`/recreate (later
  revised to a safer `INSERT ... SELECT ... DISTINCT ON` backfill once the data-loss risk was flagged) plus
  repointing all 5 places that join `eval_summaries` to it. Rejected once a second live FE query surfaced
  (see below) showing the join target needs to keep behaving exactly as it does today for whichever
  consumers haven't migrated to the new entity yet — re-keying would force that migration, not offer it.

**Final decision**: leave `test_case_eval_scores`'s primary key (`eval_summary_id`), write grain (one row
per raw row), upsert-per-row write path, and `eval_summaries` join **completely unchanged**. Just extend
it with denormalized `test_suite_run_id`/`test_case_id`/`test_case_name`/`computation_id` columns
(populated at write time, the same denormalization convention `test_case_eval_summaries` itself already
uses for `test_case_name`), and register it as its own Query DSL entity presented as `SELECT DISTINCT ON
(test_suite_run_id, test_case_id, computation_id) ... ORDER BY ..., computed_at_ms DESC` — one row per test
case per computation at query time, the freshest by `computed_at_ms` winning. Score computation itself
(`EvalSummaryRowScoreComputer`/`OverallScoreDefinitionResolver`) needs **no changes at all** — this is
purely a storage/read-surface addition.

This was validated against two live FE query shapes for "list/compare test-case scores for run(s)": a
single-run row-mode query against `eval_summaries` selecting `test_case_name`/`score`/`passed`, and a
multi-run variant (`test_suite_run_id in [...]`) doing the same across up to 10 runs at once. Both only
ever needed test-case grain, never the raw per-turn/per-request/per-rerun grain the `eval_summaries` entity
actually has — the multi-run query in particular over-fetches badly today (`N runs ×
turns×requests×reruns` instead of `N runs × test_case_count`). Neither query needs to change as part of
this group: the first is untouched by construction (nothing about `eval_summaries` changed), and the
second is offered a better home (the new entity) without being forced onto it. Migrating either query to
`test_case_eval_scores` is a follow-up on the FE side.

As a side effect, `PostgresTestCaseEvalScoreRepository.saveAll`'s upsert was strengthened from `ON CONFLICT
(eval_summary_id) DO NOTHING` to `DO UPDATE SET score, passed, computed_at_ms` — a previously-frozen stale
score (from the `DO NOTHING`-era inability to correct an earlier, partial computation) can now be corrected
by a later flush touching the same row. This is a genuine, if incidental, correctness improvement, not the
main point of this decision.

*Performance note, not a blocker*: `DISTINCT ON`'s `ORDER BY` is not something Postgres pulls up into the
outer query the way `test_suite_runs`' plain-projection derived table is (see
`docs/patterns/query-dsl-entity-resolution.md`), so a `test_suite_run_id` filter does not automatically
push down into the dedup subquery. The composite index `idx_test_case_eval_scores_natural_key` —
`(test_suite_run_id, test_case_id, computation_id, computed_at_ms DESC)`, matching the `DISTINCT
ON`/`ORDER BY` column order exactly — is what keeps this efficient in practice; worth confirming with
`EXPLAIN ANALYZE` once there's realistic data volume, but both confirmed FE query shapes filter by
`test_suite_run_id` (`eq`/`in`), which this index is shaped to serve.
*Alternative considered*: keep pursuing the re-key. Rejected once the second FE query made clear that
*any* forced repointing of the `eval_summaries` join was premature — better to add the new entity
alongside the existing surface and let consumers migrate deliberately.

**10. `test_case_eval_scores` is re-keyed to one row per test case after all — additively, once real
deployed data made the destructive alternative from Decision 9 unacceptable.** Manually verifying this
change against a real (already-deployed, not just local) environment surfaced that `test_case_eval_scores`
had shipped with only its original `V1.19` shape (`eval_summary_id` PK, `score`, `passed`,
`computed_at_ms` — none of Decision 9's denormalized columns, which were still unshipped at that point).
Real multi-turn runs had accumulated genuine duplicate rows per test case whose `score` values can
*disagree* — a stale value from an earlier flush batch that a later flush's re-aggregation never revisits,
since each flush only updates the raw rows in its own batch (`test_case_eval_summaries_...csv` evidence)
— and whose `computed_at_ms` can tie within a flush batch, so a "freshest row wins" backfill cannot even
deterministically pick between two disagreeing candidates. This made Decision 9's own "extend in place,
present via `DISTINCT ON`" framing insufficient on its own: it kept `test_case_eval_scores` at a *finer*
grain than `test_case_metric_scores_aggregated` indefinitely, which is what a test-case-scores table
should never be, by construction, once `test_case_metric_scores_aggregated` exists.

Two ways to actually collapse it to one row per test case were considered and rejected because both require
touching or reasoning about every existing duplicate row:
- **A real destructive `DROP`/recreate or `INSERT ... SELECT DISTINCT ON` backfill** (Decision 9's own
  rejected alternative) — unacceptable now that real data, not just local data, is at stake; Postgres
  cannot even resolve which of several tied-`computed_at_ms` legacy rows is "correct" when they disagree.
- **A real recompute via a one-off application-level backfill job replaying `TestCaseMetricScoreAggregator`
  + `EvalSummaryRowScoreComputer` against historical data** — mathematically correct, but these are full
  Spring beans (jOOQ `DSLContext`, `StructuredQueryService`, meta-DB reads) that don't exist yet when Flyway
  migrations run (Java Flyway migrations in this codebase, per `V1_33__CopyRunMetricSnapshotsFromAnalytics`,
  do raw JDBC only, registered before the rest of the Spring context). A correct version of this would need
  a new kind of component (an `ApplicationReadyEvent` listener, mirroring `TestSuiteRunReconciliation`) —
  real, but disproportionate machinery to correct data that is already being read correctly today via the
  existing `DISTINCT ON` entity.

**Final decision: additive-only — never delete or reconcile a single legacy row.** `eval_summary_id`
becomes a nullable *old/new-format discriminator* instead of the primary key: every legacy row keeps its
real value untouched; every row written from now on carries `eval_summary_id = NULL` and is written once
per `(test_suite_run_id, test_case_id, computation_id)`, computed directly from the already
one-row-per-test-case `test_case_metric_scores_aggregated` row rather than broadcast to every raw
`test_case_eval_summaries` row. A new surrogate `id` column becomes the primary key (backfilled from each
legacy row's own `eval_summary_id` — already unique, no new UUID generation needed), and a **partial
unique index** — `(test_suite_run_id, test_case_id, computation_id) WHERE eval_summary_id IS NULL` —
enforces the one-row-per-test-case invariant *only* among new-format rows. This achieves Decision 9's
original goal (`test_case_eval_scores` has the same row population as `test_case_metric_scores_aggregated`)
for every row written after this change, with zero data-loss risk and zero forced migration for whatever
hasn't adopted the new `test_case_eval_scores` entity yet — legacy rows keep reading exactly as they do
today via the existing dedup, indefinitely.

Consequences of the discriminator:
- `InProcessMetricEvaluationExecutor.writeRowScores` now writes one `TestCaseEvalScoreBatchWriteItemDto`
  per distinct test case in the flush batch (iterating the execution-status aggregate's keys), not one per
  buffered raw row — `eval_summary_id` is never populated by this write path again.
- `PostgresTestCaseEvalScoreRepository.saveAll`'s upsert conflict target becomes the partial index
  (`onConflict(TEST_SUITE_RUN_ID, TEST_CASE_ID, COMPUTATION_ID).where(EVAL_SUMMARY_ID.isNull())`), not the
  old `eval_summary_id` PK; each call mints a fresh surrogate `id`.
- `PostgresTestCaseEvalScoreEntityResolver`'s `DISTINCT ON` dedup is **not removed** (legacy duplicates
  still exist) but its tie-break gains a leading `(eval_summary_id IS NULL) DESC` clause: a new-format row
  (there is at most one) always wins over any number of legacy rows for the same key; only among legacy
  rows does the freshest `computed_at_ms` still apply.
- All 5 places that join `eval_summaries` to `test_case_eval_scores`
  (`PostgresEvalSummaryRepository`'s `findById`/`buildListQuery`/`buildExportQuery`/
  `buildExportWithBodiesQuery`, `PostgresEvalSummaryEntityResolver`) move from a flat
  `eval_summary_id`-equality join to a `LEFT JOIN LATERAL` with the identical `(new-format-first, then
  freshest) DESC LIMIT 1` pick — necessary because, unlike Decision 9's original join, the score side is no
  longer guaranteed unique on `(test_case_id, computation_id)` for legacy rows and a flat join would fan
  out. This is the real, permanent cost of going additive: these 5 joins can never become the trivial
  flat 3-column equality join a true (destructive) re-key would have allowed, for as long as any legacy row
  exists.
*Alternative considered*: keep pursuing either destructive option above. Rejected for the reasons stated —
neither can be done without real risk to real, already-accumulated data, and the additive approach reaches
the same end state for all new data at zero risk.
*Not implemented, documented only*: a real historical backfill (recomputing legacy `score`/`execution_status`
via the actual scoring flow, scoped to the bounded set of `(run, case, computation)` keys already present
in the legacy table) remains a valid future follow-up if a real deployment ever needs it, using the
`ApplicationReadyEvent`-listener shape described above — not undertaken now since nothing requires it: the
legacy rows already read correctly today, and this change does not regress that.

**11. `score`/`passed` are removed from the `eval_summaries` read surface entirely — superseding task
15.6's still-in-progress "repoint all 5 joins to LATERAL" plan.** Mid-implementation of Decision 10, with
`PostgresEvalSummaryRepository`'s 4 query builders already repointed to `LEFT JOIN LATERAL` but
`PostgresEvalSummaryEntityResolver`'s `SCORES_JOIN` still pending, the user determined that continuing to
broadcast a test case's score/passed onto every raw `eval_summaries` row — whichever join shape does it —
is unneeded complexity now that `test_case_eval_scores` exists as its own one-row-per-test-case,
deduplicated Query DSL entity (Decision 9/10). A client that wants a test case's score should query that
entity directly; `eval_summaries` goes back to being purely the raw per-row read surface (per-row/per-turn/
per-request grain, metric values, request/response bodies) it was before this change touched it, with no
join to `test_case_eval_scores` of any kind.

This is a deliberate API-breaking reversal of the "both surfaces read the same underlying table" framing
from Decision 9/10 — `EvalSummaryResponseDto`/`EvalSummaryDetailResponseDto` stop returning `score`/`passed`
altogether, not just changing how those values are joined in. It supersedes:
- The still-pending half of task 15.6 (`PostgresEvalSummaryEntityResolver`'s `SCORES_JOIN` repointing) —
  dropped rather than finished; `SCORES_JOIN` and its two `score`/`passed` `QueryFieldBinding`s are removed
  from the `eval_summaries` Query DSL entity instead.
- The already-completed half of task 15.6 (`PostgresEvalSummaryRepository`'s `scoresLateral()` join across
  `findById`/`buildListQuery`/`buildExportQuery`/`buildExportWithBodiesQuery`) — reverted; each builder's
  `SELECT` list drops its `scores.field(TEST_CASE_EVAL_SCORES.SCORE/PASSED)` pair and its
  `.leftJoin(scores).on(DSL.trueCondition())`, and `scoresLateral()` itself is deleted.

Consequences:
- `EvalSummary` (model): drop `score`/`passed` fields.
- `EvalSummaryRecordMapper`: drop the `.score(...)`/`.passed(...)` calls from `mapList`, `mapExport`, and
  `mapExportWithBodies` (the `TEST_CASE_EVAL_SCORES` import becomes unused).
- `EvalSummaryMapper`: drop the now-meaningless `@Mapping(target = "score", ignore = true)` /
  `@Mapping(target = "passed", ignore = true)` lines (there is no `score`/`passed` target field left to
  ignore).
- `EvalSummaryResponseDto`/`EvalSummaryDetailResponseDto`: drop the `score`/`passed` fields and their
  `@Schema` docs. This is the actual breaking change surfaced to API clients; any FE consumer still reading
  `score`/`passed` off an `eval-summaries` response must migrate to querying `test_case_eval_scores`
  (Query DSL entity) directly — the same migration path Decision 9's design already anticipated as a
  follow-up, just made mandatory instead of optional.
- `test_case_eval_scores` Query DSL entity requirement: drop the closing "exists alongside (not instead of)
  `score`/`passed` remaining queryable via the `eval_summaries` entity's join — both surfaces read the same
  underlying table" sentence — it is no longer true; `test_case_eval_scores` is now the *only* surface.
- Tests: rewrite/remove unit and functional assertions that expect `score`/`passed` on eval-summary reads
  (`EvalSummaryRecordMapperTest`, `PostgresEvalSummaryRepositoryFunctionalTests`,
  `EvalSummaryStructuredQueryFunctionalTests`'s score/passed cases, any controller test asserting the field
  in a list/detail/export response body).
- Docs: `docs/patterns/eval-summaries-read-surface.md` drops its planned "lateral join, new-format-first
  tie-break" description (Decision 10's task 15.8) — nothing to document there anymore; `docs/database-schema.md`'s
  `test_case_eval_scores` section is unaffected (the table itself, and its Query DSL entity, are unchanged).

*Alternative considered*: finish task 15.6 as originally planned (repoint `PostgresEvalSummaryEntityResolver`
too, keep both surfaces). Rejected by the user — `test_case_eval_scores` already gives every consumer a
correct, deduplicated, purpose-built read path; keeping a second, join-based path to the same data on
`eval_summaries` is exactly the kind of duplicated surface Decision 3/8 already rejected for score
*computation* now being carried over into score *exposure*.

## Risks / Trade-offs

- **[Risk]** Re-aggregating a test case's entire row set on every flush that touches it means a test case
  spread across many small flush batches does repeated, partially redundant work.
  → **Mitigation**: this is one grouped SQL query per flush over the batch's *distinct* test case ids
  (bounded by flush batch size), not per row; acceptable given `test_case_eval_scores`'s existing scoring
  already re-derives per flush in the same way, and both are fail-soft (a slow/failed aggregation never
  blocks the flush).
- **[Risk]** Moving `Mean`/`WeightedMean` from per-raw-row to per-test-case aggregation changes existing
  scores for suites that combine conditional metrics with a `Mean`/`WeightedMean` overall-score
  definition — a metric partially firing within a test case's rows no longer drags the score of the rows
  it skipped toward `0`.
  → **Mitigation**: this is the explicit bug fix being requested; called out prominently in the proposal
  as a deliberate behavior change, not a silent one. No migration path needed since scores are
  regenerable, derived data (recomputed on next evaluation run).
- **[Risk]** `Mean`/`WeightedMean` now resolve directly against `test_case_metric_scores`, a fully
  pre-aggregated entity with no per-row ids — `FilteredMetricScoreAggregator` (run comparison) can no
  longer apply its row-exclusion predicate for these definition types and instead runs the comparison's
  `overall` unfiltered whenever there are unmatched rows to exclude (see
  `FilteredMetricScoreAggregator#overallIdPredicate`'s javadoc), a case specific to
  multi-request/turn/rerun test cases with a partially-unmatched row set.
  → **Mitigation**: logged (`log.warn`) whenever it happens, not silent; the per-metric statistics above
  remain correctly filtered (they still run directly over `eval_summaries`), so only this one aggregate is
  affected. Deliberately **not fixed** in this change — a real fix would need to translate the excluded
  row ids into an equivalent `test_case_id` exclusion (or recompute the aggregate over only the matched
  rows); tracked as a follow-up.
- **[Risk]** Registering `test_case_metric_scores` in the Query DSL entity registry makes it reachable
  through the existing generic query endpoint, which was not originally scoped as a client-facing feature
  of this change.
  → **Mitigation**: this is an accepted byproduct, not a new endpoint or contract to design/version —
  the entity follows the exact same read-only, no-join template as `metric_score_results`. If it proves
  premature, filtering it out of the public registry (while keeping it usable internally) is a small,
  isolated follow-up.
- **[Risk]** Restricting `testCaseOverallScore` to `Mean`/`WeightedMean` (Decision 6) is a client-facing
  API behavior change — a suite that previously set `testCaseOverallScore` to a `CustomFunction` (accepted
  before this change) now gets a 400 on its next create/update, and a suite relying on the
  "`testCaseOverallScore` unset → inherit a `CustomFunction` `overallScore`" fallback for per-row scoring
  silently stops getting per-row scores instead.
  → **Mitigation**: called out explicitly in the proposal's Impact/behavior-change section. No data
  migration needed (scores are regenerable derived data); a suite already storing a `CustomFunction`
  `testCaseOverallScore` is only rejected on its *next* write, not retroactively invalidated — its existing
  stored value is left as-is until the suite is next created/updated/cloned.
- **[Risk]** The additive-only re-key (Decision 10) means the 5 `eval_summaries`↔`test_case_eval_scores`
  join sites can never simplify to a flat 3-column equality join for as long as any legacy (real
  `eval_summary_id`) row exists — they permanently carry a `LEFT JOIN LATERAL` correlated-subquery cost
  instead.
  → **Mitigation**: accepted deliberately in exchange for zero data-loss risk on real, already-accumulated
  data. The legacy row population only shrinks over time (no new duplicate rows are ever created for a run
  once it's already completed); a future cleanup (the documented, not-implemented historical backfill) could
  eliminate the legacy tail entirely and let the joins simplify, but nothing requires that now.

## Migration Plan

1. Add `V1.20__CreateTestCaseMetricScoresAggregatedTable.sql` (analytics DB); run `./gradlew generateJooq`
   and commit the generated diff.
2. Add the new table's model/mapper/repository/service/DTO (mirroring `TestCaseEvalScore`'s equivalents).
3. Add `TestCaseMetricScoreAggregator` and hook it into `InProcessMetricEvaluationExecutor`'s flush cycle,
   fail-soft, right after the existing row-score write.
4. Register the `test_case_metric_scores` Query DSL entity (JSONB flattening + `StructuredQueryEntityResolver`).
5. Change `OverallScoreDefinitionResolver` so `Mean`/`WeightedMean` build directly against
   `test_case_metric_scores` (Decision 8); rebuild `EvalSummaryRowScoreComputer`'s per-test-case score
   path on top of the resolved query for `Mean`/`WeightedMean` only (graft `test_case_id`/`GROUP BY`).
6. Rebuild `MetricScoreComputationExecutor.computeOverall`'s run-level `overall` path on top of the new
   entity for `Mean`/`WeightedMean` (execute the resolver's query directly); `CustomFunction` resolves and
   executes against `eval_summaries` unchanged, unretargeted (Decision 5).
7. Restrict `testCaseOverallScore` to `Mean`/`WeightedMean` (`TestSuiteRequestValidator`, Decision 6); stop
   `TestSuiteEvaluationJob`'s fallback from inheriting a `CustomFunction` `overallScore`.
8. Update `docs/database-schema.md`; add the functional-test scenarios proving the fix (uneven row counts
   across two test cases with a conditionally-skipped metric for `Mean`/`WeightedMean`; a `CustomFunction`
   `overallScore` — with or without a mixed non-metric field — staying row-weighted on raw `eval_summaries`
   for both `overallScore` and the — absent — `testCaseOverallScore`).
9. (Post-verification) Add `V1.21__AddRunCaseContextToTestCaseEvalScores.sql` extending `test_case_eval_scores`
   with denormalized run/case context, backfilled in place; strengthen its upsert to `DO UPDATE`; register
   `PostgresTestCaseEvalScoreEntityResolver`/`TestCaseEvalScoresSchemaProvider` for the new
   `test_case_eval_scores` Query DSL entity (Decision 9). No changes to score computation or the existing
   `eval_summaries` join.
10. (Post-real-deployment-verification) Rewrote `V1.21` (still unshipped at that point) in place a second
    time (Decision 10): add a surrogate `id` PRIMARY KEY (backfilled from each legacy row's own
    `eval_summary_id`), drop the old `eval_summary_id` PK, make `eval_summary_id` nullable, add the partial
    unique index scoping "one row per test case" to new-format (`eval_summary_id IS NULL`) rows only.
    Rewrote `InProcessMetricEvaluationExecutor.writeRowScores`/`toScoreItem` to write one item per test case
    instead of one per raw buffered row. Extended `PostgresTestCaseEvalScoreEntityResolver`'s `DISTINCT ON`
    tie-break with a leading new-format-first clause. Repointed all 5 `eval_summaries`↔`test_case_eval_scores`
    join sites from a flat `eval_summary_id` equality join to a `LEFT JOIN LATERAL` doing the identical pick.
    `./gradlew generateJooq` run again; diff committed.

**Rollback**: for `test_case_metric_scores_aggregated` (V1.20), no data migration or backfill exists to
reverse — reverting the code change stops writes to the new table and reverts scoring to reading raw
`eval_summaries` directly; the table can be dropped independently at any time since nothing outside this
feature reads it. For `test_case_eval_scores`'s `V1.21` extension (both the Decision 9 columns and the
Decision 10 re-key), the new columns/index/entity can be dropped independently too — a rollback of Decision
10 specifically would mean re-adding `eval_summary_id`'s `NOT NULL`/PK and dropping the surrogate `id` and
partial index; since Decision 10 never modified a legacy row's own data (only schema/constraints and the
write path), this is safe at any time — no legacy row's `score`/`passed`/`computed_at_ms` was ever touched.

## Open Questions

None outstanding — the missing-metric coalesce semantics (Decision 4), the built-in per-metric statistics
scope boundary (Non-Goals), the `testCaseOverallScore` metric-only restriction (Decision 6), and building
`Mean`/`WeightedMean` directly against the new entity instead of a bespoke combiner (Decision 8) were all
resolved during implementation, once discussing how a mixed metric/response-field `CustomFunction` and the
fetch-and-combine approach's own complexity would actually behave surfaced that the original design
over-engineered what `Mean`/`WeightedMean` needed. `CustomFunction`'s retargeting (originally Decision 5) was
revisited once more, post-implementation, and reverted (see Decision 5's current text) — not an open
question so much as a decision the team was willing to revisit after seeing the implemented complexity.
`FilteredMetricScoreAggregator`'s dropped exclusion predicate for `Mean`/`WeightedMean` (Risks) is a known,
accepted limitation with a real fix explicitly deferred as a follow-up, not resolved here.
`test_case_eval_scores`'s own residual per-row storage duplication (Decision 9) is resolved by that
decision — extend in place, expose a deduplicated entity, no re-key — not an open question either.
Decision 9's "no re-key" framing was itself later revisited (Decision 10) once real deployed data made the
destructive alternatives unacceptable, landing on an additive re-key (nullable `eval_summary_id`
discriminator + partial unique index) that reaches the same one-row-per-test-case goal for all new data
without touching a single existing row — also not an open question, a resolved decision.
Decision 10's own "repoint all 5 joins to LATERAL, keep both surfaces" plan was itself superseded
mid-implementation (Decision 11): `score`/`passed` are dropped from the `eval_summaries` surface entirely
rather than finishing the LATERAL repointing, leaving `test_case_eval_scores` as the sole read path for
test-case scores — also a resolved decision, not an open question.
