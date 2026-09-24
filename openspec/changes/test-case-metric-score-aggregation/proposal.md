## Why

A test case run can produce many `test_case_eval_summaries` rows — one per `(run_index, request_index,
turn_index)` combination (e.g. 2 turns × 2 requests × 2 reruns = 8 rows per test case per computation).
Today's scoring pipeline has no concept of "the test case" as a unit:

- The per-row score (`eval-summary-scoring`, `EvalSummaryRowScoreComputer` → `test_case_eval_scores`)
  scores each raw row independently by grafting `id IN (:rowIds) GROUP BY id` onto the resolved
  `OverallScoreDefinition` query. Since every group has exactly one row, a metric that only fired on
  some of a test case's rows (e.g. a TSMD `condition` scoped to `request.index == 0`) makes `avg(field)`
  evaluate to `NULL` on the rows where it didn't fire, which the resolver's `coalesce(avg(field), 0)`
  silently turns into a **0** for that row — the score is wrong on exactly the rows the metric skipped.
- The run-level `overall` (`metric-score-statistics`, `MetricScoreComputationExecutor.computeOverall`)
  aggregates directly over all raw rows, ungrouped — a test case with 8 rows contributes 8 samples to
  the run's overall score, a test case with 1 row contributes 1. Test cases are silently weighted by how
  many turns/requests/reruns they happen to have, not counted equally.

This change introduces a normalization step that collapses a test case's rows for a computation into one
row of per-metric statistics, and rebuilds both existing overall-score computations on top of it, so a
metric's partial presence no longer skews a score and every test case counts equally.

## What Changes

- Add a new analytics table, `test_case_metric_scores_aggregated`, with **one row per
  `(test_suite_run_id, test_case_id, computation_id)`**, collapsing all of that test case's rows (every
  `run_index`/`request_index`/`turn_index` combination) into a single JSONB `metric_scores` column
  shaped `{"<metricName>": {"avg": ..., "min": ..., "max": ..., "count": ...}}`. A metric that never
  fired for that test case is simply absent from the map (never a zero/null entry).
- Add `TestCaseMetricScoreAggregator`, computing this aggregation via hand-written jOOQ against
  `analyticsDsl` (a `UNION ALL` per discovered metric field feeding `jsonb_object_agg`), hooked into
  `InProcessMetricEvaluationExecutor`'s existing flush cycle right after the current row-score write,
  with the same fail-soft (log + continue) convention as that write.
- Register the new table as a Query DSL entity, `test_case_metric_scores` (flattening the `metric_scores`
  JSONB into addressable fields `metric_scores::<name>::avg|min|max|count`, the same mechanism already
  used to flatten `eval_summaries.metric_values`), so the score computations below stay on the existing
  DSL/`StructuredQueryService` execution path instead of introducing a parallel Java-side SQL
  reimplementation. As a byproduct this also makes the table queryable/filterable for drill-down.
- **Rebuild `eval-summary-scoring`'s `Mean`/`WeightedMean` per-test-case score** to be built **directly**
  against the new entity by `OverallScoreDefinitionResolver` — `avg(metric_scores::<name>::avg)` per
  configured metric, the exact same `divide(add(coalesce(avg(f1),0), coalesce(avg(f2),0), ...), n)`
  formula this resolver has always built, just targeting `test_case_metric_scores` instead of
  `eval_summaries` — then grafted with `test_case_id IN (...) GROUP BY test_case_id` (reusing
  `EvalSummaryRowScoreComputer`'s existing graft mechanism) to turn it into one value per test case. Every
  `test_case_eval_scores` row belonging to the same test case (in the same computation) now receives the
  same, correctly-computed score; that table's schema/grain is unchanged. A metric that never fires
  across a whole test case's rows is coalesced to `0` for that term — same semantics as always — but the
  bug this change actually fixes (a metric firing on only *some* of a test case's rows getting coalesced
  toward `0` on the rows it skipped) is fixed upstream, at the aggregation layer: a partially-firing
  metric's `avg` in `test_case_metric_scores_aggregated` is always computed over just the rows it fired
  on, so it is never `NULL` at this layer to begin with.
- **Rebuild `metric-score-statistics`'s `Mean`/`WeightedMean` run-level `overall`** the same way — built
  directly against the new entity, `avg(metric_scores::<name>::avg)` per metric, ungrouped over the whole
  run — so every test case counts equally regardless of row count (each test case contributes exactly one
  sample per metric it has, instead of one sample per raw row). `metric_score_results`' schema is
  unchanged; only `overall`'s data source changes for these two definition types.
- **`CustomFunction`-defined run-level `overall`** (e.g. `roc_auc`) is **not** retargeted onto the new
  entity — it continues to resolve and execute directly against `eval_summaries`, ungrouped, exactly as
  before this change. An earlier version of this change retargeted it the same way `Mean`/`WeightedMean`
  are, via a `CustomFunctionQueryRewriter` that walked the resolved query and swapped `entity`/`metric::`
  field references, plus a guard for expressions referencing non-metric fields — reverted post-
  implementation once that complexity (a full tree-walk over opaque, client-authored content, plus a
  documented semantic redefinition of population functions) was judged disproportionate for fixing a
  weight-skew issue already accepted as out of scope elsewhere in this change (see below).
- **`testCaseOverallScore` is restricted to `Mean`/`WeightedMean` — `CustomFunction` is rejected with a
  hard 400 at suite create/update.** A population-dependent function like `roc_auc` is meaningless for a
  single test case, and `Mean`/`WeightedMean` are already structurally metric-only (a `WeightedMetric`
  names a `metricName`/`outputField` pair, never a non-metric field), so per-test-case scoring never needs
  the generic `CustomFunction` grafting machinery at all. Consequently, `testCaseOverallScore`'s existing
  "falls back to `overallScore` when unset" behavior no longer falls back when `overallScore` is a
  `CustomFunction` (that would silently defeat the new validation); per-test-case scoring is then simply
  not computed, same as an unconfigured suite.
- Per-metric `BuiltInMetricStatistics` (run-level AVG/P10/P90/MIN/MAX), `FilteredMetricScoreAggregator`/
  run-comparison (`run-comparison-metric-scores`), and `CustomFunction`-defined `overall` are explicitly
  **out of scope** — all three retain the same weight-skew issue this change fixes for `Mean`/
  `WeightedMean`; flagged as follow-ups.
- **Extend `test_case_eval_scores` with denormalized run/case context and expose it as its own Query DSL
  entity**, fixing that table's own remaining per-row storage duplication (discovered during manual
  verification of the above): it still had one row per raw `test_case_eval_summaries` row, all carrying
  the same, already-correct score. Rather than re-keying the table (which would force every consumer of
  the `eval_summaries` join to migrate at once), it's extended in place — `test_suite_run_id`/`test_case_id`/
  `test_case_name`/`computation_id` added and backfilled, `eval_summary_id` remaining the PK and write
  grain unchanged — and registered as a `test_case_eval_scores` entity presenting `SELECT DISTINCT ON
  (test_suite_run_id, test_case_id, computation_id) ... ORDER BY ..., computed_at_ms DESC`: one row per
  test case per computation at query time, the freshest wins. The existing `eval_summaries` join is
  completely untouched; the new entity is an additional surface, not a replacement, so no FE change is
  forced. As a side effect, the write path's upsert was strengthened from `ON CONFLICT (eval_summary_id)
  DO NOTHING` to `DO UPDATE`, so a stale score from an earlier, partial flush can now be corrected by a
  later one.

## Capabilities

### New Capabilities
- `test-case-metric-score-aggregation`: per-test-case, per-computation aggregation of raw eval-summary
  metric values (avg/min/max/count per metric, collapsed across turn/request/run index) into a new
  `test_case_metric_scores_aggregated` table, computed during Phase 2's flush cycle.

### Modified Capabilities
- `eval-summary-scoring`: the per-row score (`Mean` or `WeightedMean` — `CustomFunction` is no longer a
  valid `testCaseOverallScore`, see below) is now built directly against the new aggregated table (via
  `OverallScoreDefinitionResolver`) and grafted to compute once per test case, broadcast to every
  `test_case_eval_scores` row of that test case. A metric never firing for a whole test case is coalesced
  to `0` for its term (unchanged formula); the originally-reported partial-firing bug is fixed at the
  aggregation layer instead (see What Changes).
- `metric-score-statistics`: the run-level `overall` for `Mean`/`WeightedMean` is now computed from the
  new aggregated table so every test case is weighted equally — both are built directly against it. A
  `CustomFunction` (e.g. `roc_auc`) is unaffected by this change: it continues to resolve and execute
  directly against `eval_summaries`, ungrouped — the same accepted weight-skew limitation as the per-metric
  AVG/P10/P90/MIN/MAX statistics (also unchanged) and `FilteredMetricScoreAggregator`/run-comparison.
- `test-suites`: `testCaseOverallScore` now rejects `CustomFunction` with a hard 400 at suite create,
  update, and clone revalidation — only `Mean`/`WeightedMean` are accepted. `overallScore` is unaffected.
- `metrics-storage`: `test_case_eval_scores` gains `test_suite_run_id`/`test_case_id`/`test_case_name`/
  `computation_id` (denormalized, backfilled) and a new `test_case_eval_scores` Query DSL entity; its
  upsert is strengthened to `DO UPDATE`. Table PK, write grain, and the existing `eval_summaries` join are
  unchanged — this capability was not touched by the original scope of this change (see What Changes).

## Impact

- **Schema**: new analytics migration `V1.20__CreateTestCaseMetricScoresAggregatedTable.sql` (table +
  unique natural-key index + computation lookup index); `./gradlew generateJooq` regeneration required.
  No changes to `metric_score_results` schema. `test_case_eval_scores` was later extended (V1.21) — see
  below — its PK/write grain are unchanged.
- **New classes**: `TestCaseMetricScoreAggregated` (model), its `RecordMapper`/repository (interface +
  Postgres impl)/service/batch-write DTO (mirroring `TestCaseEvalScore`'s equivalents),
  `TestCaseMetricScoreAggregator` (aggregation SQL), a new `StructuredQueryEntityResolver` +
  schema-provider additions registering `test_case_metric_scores` in the Query DSL. `Mean`/`WeightedMean`
  are built directly against the new entity by `OverallScoreDefinitionResolver`, no separate combiner or
  rewrite step needed; `CustomFunction` needs no new class either, since it stays unretargeted.
  `PostgresTestCaseEvalScoreEntityResolver`/`TestCaseEvalScoresSchemaProvider` register the later
  `test_case_eval_scores` entity (see below).
- **Modified classes**: `InProcessMetricEvaluationExecutor` (flush-cycle hookup),
  `OverallScoreDefinitionResolver` (`Mean`/`WeightedMean` now build directly against
  `test_case_metric_scores`), `EvalSummaryRowScoreComputer` (per-test-case rebuild for `Mean`/
  `WeightedMean`, grafted onto the resolver's query — no `CustomFunction` support),
  `MetricScoreComputationExecutor.computeOverall` (run-level rebuild for `Mean`/`WeightedMean`;
  `CustomFunction` execution unchanged), `TestSuiteRequestValidator` (rejects a `CustomFunction`
  `testCaseOverallScore`), `TestSuiteEvaluationJob` (the `testCaseOverallScore`-absent fallback no longer
  inherits a `CustomFunction` `overallScore`). `TestCaseEvalScore`/`TestCaseEvalScoreBatchWriteItemDto`/
  `PostgresTestCaseEvalScoreRepository`/`TestCaseEvalScoreService`/`InProcessMetricEvaluationExecutor`
  (again, for the V1.21 extension — new fields, `DO UPDATE` upsert, `batchCreate`→`batchUpsert` rename).
- **API**: a new Query DSL entity (`test_case_metric_scores`) becomes queryable through the existing
  generic query endpoint, same as `metric_score_results` today — no new endpoint, but new queryable
  surface. `testCaseOverallScore` now rejects `CustomFunction` with a 400 at suite create/update (a new,
  narrower validation than before — see Behavior changes). A second new entity, `test_case_eval_scores`,
  is added the same way (V1.21) — see below.
- **Behavior changes**:
  - A metric that fires on only *some* of a test case's rows (a `condition`-scoped metric) no longer
    skews that test case's `Mean`/`WeightedMean` score toward `0` on the rows it didn't fire on — every
    row of the test case now shares one score computed from the metric's `avg` over just the rows it did
    fire on. A metric that never fires for a test case **at all** is still coalesced to `0` for its term
    (unchanged from today's formula, just now evaluated at the per-test-case/per-run grain instead of the
    per-raw-row grain).
  - `CustomFunction` `overall` (e.g. `roc_auc`) is **unaffected** by this change — same weight-skew
    limitation as before, now an explicit accepted Non-Goal alongside `BuiltInMetricStatistics`/
    `FilteredMetricScoreAggregator`, not a new redefinition.
  - `testCaseOverallScore` no longer accepts `CustomFunction` (previously accepted; a suite that set one
    now gets a 400 on its next create/update). A suite that already set `overallScore` to a `CustomFunction`
    and left `testCaseOverallScore` unset previously inherited that `CustomFunction` for per-row scoring
    via the fallback (producing a real value for a row-safe function, `null` for a population-dependent
    one like `roc_auc`); it now simply gets no per-row score at all, regardless of which kind of function.
  - `test_case_eval_scores` writes now correct a previously-frozen stale `score`/`passed` on a later flush
    (upsert strengthened from `DO NOTHING` to `DO UPDATE`) — previously, a value computed from a partial
    aggregate at one flush could never be corrected by a later flush recomputing a more complete one.
- **Docs**: `docs/database-schema.md` gets the new `test_case_metric_scores_aggregated` table and the
  `test_case_eval_scores` extension; `docs/patterns/query-dsl-entity-resolution.md` gets the new `DISTINCT
  ON` derived-table shape; no OpenAPI/REST changes (no new endpoints or DTO fields — both new surfaces are
  reachable only through the existing generic Query DSL endpoint).
- **Out of scope (flagged as follow-ups, not addressed by this change)**: per-metric
  `BuiltInMetricStatistics`, `CustomFunction`-defined `overall`, and `FilteredMetricScoreAggregator`/run-comparison
  (`run-comparison-metric-scores`) retain the same per-test-case weight-skew behavior they have today.
  Migrating the FE's "list/compare test-case scores" queries from `eval_summaries` to the new
  `test_case_eval_scores` entity is a follow-up on the FE side, not part of this change.
