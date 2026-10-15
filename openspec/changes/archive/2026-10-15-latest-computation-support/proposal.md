## Why

A computation-versioned analytics entity can only be asked for a run's latest computation when the filter pins exactly one run (`test_suite_run_id eq <uuid> AND computation_id eq "latest"`, resolved in `MetricScoreLatestComputationDefaulter` before SQL). For any multi-run filter the sentinel is rejected as an invalid UUID, and omitting `computation_id` instead spans **every** computation of every run, so a recomputed run is counted once per computation. A "last N runs" dashboard query over `test_case_eval_scores` (grouped counts of above/below threshold/error per run) therefore cannot be expressed correctly: the run set comes from a subquery, which a pre-SQL rewrite cannot see.

## What Changes

- Add a shared, opt-in component `LatestComputationSupport` (`query.service.repository`) that resolves `computation_id eq "latest"` **in SQL, per run**, whichever way the filter selected the runs (literal list, `in` subquery, or anything else):
  - an internal, binding-only virtual BOOLEAN field `is_latest_computation` — true when the row's computation is its run's canonical latest, defined as a correlated lookup in `test_case_eval_summaries` ordered `computed_at_ms DESC, computation_id ASC` (identical to `ComputationResolver` / `PostgresEvalSummaryRepository.findLatestComputationIds`);
  - a query rewrite that replaces any remaining `computation_id eq "latest"` (case-insensitive) with `is_latest_computation eq true`.
- The existing single-run literal path (`MetricScoreLatestComputationDefaulter`) stays first and unchanged; the SQL form handles only what survives it.
- Entities opt in with a few lines each (new bindings map + `rewrite`): `test_case_eval_scores` (required for the dashboard query), `metric_score_results`, `test_case_metric_scores` (gains a rewrite hook; `"latest"` was previously rejected there, so this is additive). `eval_summaries` is deferred pending `EXPLAIN` evidence (largest table, self-correlated lookup).
- The virtual field is **not** a table column: it is absent from default (empty-`select`) projections and from the published schema (`GET /api/v1/queries/entities/schema/{name}`).
- Behaviour changes on opted-in entities: a multi-run `"latest"` now resolves; a `"latest"` with no run predicate returns each run's latest instead of a 400 on the sentinel; a run with no eval summaries contributes no rows. Only `eq` is supported (`ne` / `in` with `"latest"` unchanged).
- **Not BREAKING.** Previously rejected requests now succeed; no accepted request changes meaning.

**Non-goals:** no new endpoint, DTO, page extender, `test_suite_id` column or Flyway migration (suite/last-N selection stays the existing `test_suite_run_id in (subquery on eval_summaries, group_by run, sort max(created_at_ms) desc, limit N)`); `eval_summaries` opt-in; closing the score-row gap (no `test_case_eval_scores` rows for suites without an overall-score definition, or for SUCCESS test cases without a computed score); adding an index on `test_case_eval_summaries.test_suite_id`; changing how "latest N runs" is ranked.

## Capabilities

### New Capabilities
- `latest-computation-resolution`: the shared contract for resolving `computation_id eq "latest"` across computation-versioned Query DSL entities — single-run literal fast path, multi-run SQL resolution through the internal `is_latest_computation` binding, canonical ordering and tiebreak, deleted-run exclusion, no-summaries behaviour, supported operators, and the binding never appearing in default projections or published schema.

### Modified Capabilities
- `metric-score-statistics`: the `metric_score_results` `computation_id eq "latest"` requirement (currently "for a query that targets a single run") extends to multi-run filters.
- `eval-summary-scoring`: the `test_case_eval_scores` query entity accepts `computation_id eq "latest"` for multi-run filters (so per-run outcome counts can be computed against each run's latest computation).
- `test-case-metric-score-aggregation`: the `test_case_metric_scores` query entity gains the `"latest"` sentinel.

## Impact

- **Code (new):** `LatestComputationSupport` (`@Component @LogExecution`, `query.service.repository`).
- **Code (modified):** `PostgresTestCaseEvalScoreEntityResolver`, `PostgresMetricScoreResultEntityResolver`, `PostgresTestCaseMetricScoreAggregatedEntityResolver` (bindings + `rewrite`); `MetricScoreLatestComputationDefaulter` (two helpers, `isLatestComputationEq` and `withFilter`, made package-private for reuse; behaviour unchanged).
- **API:** `POST /api/v1/queries/execute` accepts a previously rejected filter shape; no request/response schema change; no OpenAPI example required unless the endpoint already ships operation examples. Default row projections and schema discovery are unchanged.
- **Data:** no migration, no schema or jOOQ regeneration, no `docs/database-schema.md` change. Reads only.
- **Security / config:** none; no new property, so no `docs/configuration.md` row. Deleted runs stay hidden by each entity's existing `baseCondition()`.
- **Docs / specs:** `docs/patterns/query-dsl-entity-resolution.md`, `docs/patterns/computation-versioning.md` (the SQL expression becomes another place that resolves "latest" with the same tiebreak); `openspec/specs/README.md` gains the new spec folder.
- **Risks:**
  - *Performance:* the correlated lookup is evaluated per candidate row; it is index-served by `idx_eval_summaries_run_computed_at (test_suite_run_id, computed_at_ms DESC, computation_id)` and bounded by the run predicate. Mitigation: capture `EXPLAIN (ANALYZE)` for the dashboard query and record it; defer `eval_summaries`.
  - *Drift:* "latest" is now defined in SQL as well as in `ComputationResolver`. Mitigation: a unit test pins the rendered ordering, and a functional test covers a same-millisecond tie (smaller `computation_id` wins).
  - *Internal name exposure:* a client can type `is_latest_computation` directly although it is unpublished; treated as an undocumented internal alias, not a supported contract.
- **Rollout:** a single deploy, backwards compatible; no flag. Implemented in three iterations: (1) `LatestComputationSupport` + unit tests + `test_case_eval_scores` opt-in; (2) the other two entities; (3) functional tests, `EXPLAIN` evidence, docs/specs.
- **Test plan:** unit tests for the rewrite and binding (including rendered SQL ordering) and for each resolver (binding present, absent from table fields and published schema); functional tests per entity covering recomputed runs, a same-millisecond tie, a run whose latest computation has no score rows (no fallback to an older computation), a tombstoned run, the full dashboard query, and the unchanged single-run path.
