## Context

Today a suite's `overallScore` definition (`Mean`/`WeightedMean`/`CustomFunction`) only ever produces one aggregate number **per run** (Phase 3, `MetricScoreComputationExecutor` → `metric_score_result`). There is no per-test-case pass/fail signal. Computing one naively — in a hand-written Java evaluator, in-memory, alongside the rest of the `EvalSummary` row — would only support `Mean`/`WeightedMean`: reproducing arbitrary SQL semantics in Java for `CustomFunction` isn't practical, and every future custom function (e.g. a simple `divide` of one metric by another) would need its own bespoke Java implementation, forever.

This design instead **reuses the existing Phase-3 SQL machinery**. The project already has everything needed: `OverallScoreDefinitionResolver` turns any `OverallScoreDefinition` into a `StructuredQuery`, and `FilteredMetricScoreAggregator` already demonstrates the exact reusable pattern for grafting an arbitrary filter onto *any* resolved query (including an opaque `CustomFunction`) without inspecting its shape. Scoping that same mechanism to a batch of specific row ids — via `id IN (:batchIds)` plus `GROUP BY id` — turns the run-level aggregate into a per-row aggregate, uniformly across `Mean`/`WeightedMean`/`CustomFunction`, at the cost of one extra SQL query per Phase-2 flush batch (not per row, and not a follow-up `UPDATE`).

Verified during design (not assumed):
- `StructuredQueryBuilder.buildAggregate`/`resolveGroupKey` already handles a select column that's also a `GROUP BY` key by referencing the select alias, not re-translating — grafting `id` as both a select column and the group key requires **zero** query-builder changes, for any query shape.
- `roc_auc_score` (`V1.11__CreateRocAucScoreFunction.sql`) divides by `NULLIF(n_pos*n_neg, 0)` — a single-row group has only one class present, so this is `NULL` **naturally**. `roc_auc` is the only population-dependent function in the entire catalog today (every other registered function — `avg`, `sum`, `min`, `max`, `count`, `percentile_cont/disc`, `add`/`multiply`/`subtract`/`divide`/`coalesce`, `lower`/`upper`/`length`/`trim`/`abs`/`width_bucket` — degenerates safely to one row or is a plain scalar).

## Goals / Non-Goals

**Goals:**
- Compute `score`/`passed` per row for `Mean`/`WeightedMean`/`CustomFunction` uniformly, by reusing `OverallScoreDefinitionResolver`'s output rather than a parallel implementation.
- One extra SQL query per Phase-2 flush batch, not per row.
- Freeze `overallScoreThreshold` into the run's snapshot so `passed` reflects the threshold as configured when the run started.
- Keep Phase 3 (`MetricScoreComputationExecutor`, `metric_score_result`) completely unchanged.

**Non-Goals:**
- Making population-dependent custom functions (`roc_auc`) meaningful per row — that's a mathematical limit, not an engineering one. They degrade to `NULL`, which is the correct outcome, not a bug to work around.
- Filtering/sorting the eval-summaries list API by `score`/`passed`. Deferred.

## Decisions

### 1. A new sibling component, not an extension of `FilteredMetricScoreAggregator`
**Decision**: `EvalSummaryRowScoreComputer` (`query.service.metricscore`) is a new class, not a method added to `FilteredMetricScoreAggregator`. That class's own doc comment scopes it to read-only what-if recomputation ("without persisting anything") over a run's full population minus an exclusion set. The new component groups an *inclusion* set into per-row results for a caller that *will* persist them — a different filter shape (`IN` vs. `NOT IN`) and a different purpose. Keeping them separate keeps both single-purpose; `EvalSummaryRowScoreComputer` still reuses `OverallScoreDefinitionResolver` and `StructuredQueryService` directly, so there is no duplicated query-construction logic.

### 2. Grafting `id` as both a select column and the `GROUP BY` key
**Decision**: For a batch of ids, add `id` to the resolved query's `select` list and set `groupBy = ["id"]`, ANDing `id IN (:rowIds)` onto the filter. Verified against `StructuredQueryBuilder.buildAggregate`: a select column whose `FieldExpr` name is also a `groupBy` key is resolved via the select's own output alias (`resolveGroupKey`), not re-translated — so this requires no new builder logic, for `Mean`/`WeightedMean`'s built-in expressions or an arbitrary `CustomFunction`'s stored query alike.

**Guard**: `EvalSummaryRowScoreComputer.requireGroupableShape` rejects (logs, does not execute) a `CustomFunction` that already specifies its own `groupBy`, rather than silently overwriting it — such a query's author had a different shape in mind, and grafting is only safe when there's no pre-existing group.

### 3. `CustomFunction` is attempted, with a real (not workaroundable) limit
**Decision**: Every `CustomFunction` is attempted through the same grafting mechanism, not excluded by name. A row-safe function (e.g. a simple `avg` over one metric field, or `divide` of one metric by another) gets a real per-row value. A population-dependent function (`roc_auc`) still returns `null` per row — not because it's special-cased, but because `roc_auc_score`'s own SQL naturally returns `NULL` when a `GROUP BY id` group has only one row (one class present, `NULLIF(n_pos*n_neg,0)` is `NULL`). No special-casing was added for this — it falls out of the existing stored function's semantics.

### 4. Per-flush batching, not per-row queries
**Decision**: One `EvalSummaryRowScoreComputer.computeBatch(...)` call per Phase-2 flush (the same batch that was just inserted into `test_case_eval_summaries`), covering every row's id in that batch via `id IN (...)` + `GROUP BY id`. This keeps the added SQL cost proportional to flush count (bounded by `metric-evaluation.batch-size`), not row count.

**Consequence**: `EvalSummaryBatchWriteItemDto` carries an optional `id`, generated by `InProcessMetricEvaluationExecutor.buildItem` before the batch insert, so the executor knows every id in the batch *before* the insert — no re-query needed afterward to learn what was written. `EvalSummaryMapper.toEntity` falls back to generating a fresh `UUID.randomUUID()` only when the item's `id` is absent, preserving the external batch-write REST API's existing contract (where this field didn't previously exist).

### 5. `Mean`'s metric field names, discovered once per `execute()` call
**Decision**: Add one call to `runMetricSnapshotRepository.findByRunIdAndComputationId(...)` + `MetricFieldDiscoverer.discover(...)` (the same mechanism Phase 3 uses) near the top of `InProcessMetricEvaluationExecutor.execute(...)`, right after `writeRunMetricSnapshots(context)` — one query per `execute()` call, not per flush, guaranteeing `Mean`'s divisor can never disagree between Phase 2 and Phase 3 for the same run.

### 6. New table, not new columns on `test_case_eval_summaries` — and no denormalized context
**Decision**: `test_case_eval_scores` is keyed by `eval_summary_id` alone (1:1 with `test_case_eval_summaries.id`), with just `score`/`passed`/`computed_at_ms` — no denormalized `test_suite_id`/`test_suite_run_id`/`computation_id`/`test_case_id`. Every read reaches this table via a join to `test_case_eval_summaries`, which already carries that context; nothing queries `test_case_eval_scores` directly today, so those columns would be pure YAGNI. Add them back in a follow-up migration if a genuine direct-query need shows up. LEFT-JOINed into all four `PostgresEvalSummaryRepository` query builders (list/export/export-with-bodies/detail) — `score`/`passed` are cheap scalars, so included in every tier, unlike the pre-existing `request_body`/`response_body` join which is deliberately excluded from lean tiers.

### 7. `passed` computed in Java, not SQL
**Decision**: `passed = (score != null && threshold != null) ? score >= threshold : null`, computed in `InProcessMetricEvaluationExecutor.toScoreItem` after `EvalSummaryRowScoreComputer` returns a plain `Map<UUID, Double>`. The threshold is a plain `Double` already on `MetricEvaluationContext` — not worth round-tripping through a SQL `CASE` bind parameter for a comparison this trivial, and it keeps `EvalSummaryRowScoreComputer` threshold-free (single-purpose: produce scores, not pass/fail).

## Risks / Trade-offs

- **[Risk]** A `CustomFunction` author might expect population-dependent functions to "just work" per row and be surprised by a blanket `null`. → **Mitigation**: documented in `docs/database-schema.md`'s `roc_auc_score` section, `docs/patterns/overall-score-definition.md`, and the `eval-summary-scoring` spec — framed as a mathematical limit, not a missing feature.
- **[Trade-off]** A second SQL query and a second batch write per flush, versus a hypothetical single-insert approach. → **Accepted**: the reused-SQL benefit (uniform `Mean`/`WeightedMean`/`CustomFunction` support, one code path shared with Phase 3, no bespoke Java to maintain for every future function) was judged worth the extra round-trip, and it's bounded by flush count, not row count.
- **[Risk]** `EvalSummaryBatchWriteItemDto.id` is now optional and client-settable on the external batch-write REST API (previously always server-generated). → **Mitigation**: `EvalSummaryMapper.toEntity` falls back to `UUID.randomUUID()` when the item's `id` is absent, so existing external callers are unaffected; only the internal Phase-2 path supplies it.
- **[Trade-off]** `writeRowScores` failures are logged but do not cancel the run (unlike a `test_case_eval_summaries` batch-write failure, which does). → **Accepted deliberately**: `score`/`passed` are regenerable derived data; the eval summaries themselves are not.

## Migration Plan

1. Add Flyway migration `V1.19__CreateTestCaseEvalScoresTable.sql`. Run `./gradlew generateJooq` and commit the regenerated sources.
2. Land `EvalSummaryRowScoreComputer` and its unit tests.
3. Land the `EvalSummaryScore`/`EvalSummaryScoreRepository`/`PostgresEvalSummaryScoreRepository`/`EvalSummaryScoreService`/`EvalSummaryScoreBatchWriteItemDto` write path.
4. Wire `InProcessMetricEvaluationExecutor`: id generation upstream of the insert, `metricFieldNames` discovery, `writeRowScores` after each flush.
5. LEFT JOIN `test_case_eval_scores` into `PostgresEvalSummaryRepository`'s four query builders and `EvalSummaryRecordMapper`'s four mapping methods.
6. No backfill of historical rows — forward-only; existing `EvalSummary` rows simply have no matching `test_case_eval_scores` row, read back as `score = null, passed = null` via the LEFT JOIN.

Rollback: revert the code changes; the new table can remain unused (no data loss, no constraint violations) or be dropped in a follow-up migration.

## Open Questions

None outstanding.
