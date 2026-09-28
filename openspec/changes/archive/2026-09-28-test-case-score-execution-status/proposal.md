## Why

`test_case_eval_scores` currently derives `score`/`passed` purely from the suite's `overallScore`/
`testCaseOverallScore` definition, ignoring whether the test case's underlying
`test_case_eval_summaries` rows (across turns/requests/reruns) actually succeeded. A metric
execution failure (e.g. `{"Exact Match": {"exact_match": null}}`, already surfaced as
`execution_status = FAILED` on that row via `InProcessMetricEvaluationExecutor.checkForErrors`)
still lets a score be computed from whatever numeric samples remain, silently mixing failed and
successful data into one number. A condition-skipped metric (the field simply absent, e.g.
`{"Ragas: Answer Relevancy": {"score": 0.0}}` with `"Exact Match"` omitted) correctly stays
`SUCCESS` and must not be conflated with an actual failure. There is currently no way for a client
to tell these two situations apart from `test_case_eval_scores` alone.

## What Changes

- Add an `execution_status` column to `test_case_eval_scores` (same `SUCCESS`/`FAILED` vocabulary as
  `test_case_eval_summaries.execution_status`), aggregated per test case per computation across
  *all* of that test case's `test_case_eval_summaries` rows (every `run_index`/`request_index`/
  `turn_index` combination): `FAILED` if any row's `execution_status <> 'SUCCESS'`, else `SUCCESS`.
  A `ConditionError`-skipped metric (absent key) never flips a row's status, so it never affects
  this aggregate; a metric execution failure (null-valued output field) does, because it already
  flips the row's own `execution_status` to `FAILED` today.
- Stop computing `score`/`passed` for a test case whose aggregated `execution_status` is `FAILED` —
  both are written as `null` (the table's existing "honest null" contract), and the SQL aggregate
  query is skipped entirely for those test cases rather than computed and discarded.
- A test case with zero numeric metric samples (e.g. every metric condition-skipped, or the run
  itself failed before any metric ran) that also has a `FAILED` row now still gets a
  `test_case_eval_scores` row (`execution_status = FAILED`, `score = null`) instead of no row at
  all — today such a test case is silently absent from the table.
- `execution_status` is written in the **same** upsert as `score`/`passed`/`computed_at_ms`
  (`INSERT ... ON CONFLICT (eval_summary_id) DO UPDATE`, batched exactly as today) — never a
  separate follow-up `UPDATE`.
- New component `TestCaseExecutionStatusAggregator` computes the per-test-case aggregate directly
  from `test_case_eval_summaries` (`GROUP BY test_case_id`, `bool_or(execution_status <> 'SUCCESS')`).
  It is intentionally separate from `TestCaseMetricScoreAggregator`/`test_case_metric_scores_aggregated`,
  whose existing "absent metric = no numeric sample" contract is left untouched — this aggregator
  covers every row regardless of whether it produced any numeric metric value.
- Extend the `test_case_eval_scores` Query DSL entity (`PostgresTestCaseEvalScoreEntityResolver`) to
  expose `execution_status` alongside `score`/`passed`.
- The existing write gate is unchanged: if the suite has no `overallScoreDefinition`, no
  `test_case_eval_scores` rows are written at all (this includes the new `execution_status` column —
  it only exists where a score would already be attempted).
- **BREAKING (schema, local-dev only):** the `test_case_eval_scores` migration
  (`V1.21__AddRunCaseContextToTestCaseEvalScores.sql`) is edited in place to add the new column,
  rather than adding a new migration file, since the analytics schema has so far only been applied
  locally. Anyone with a locally migrated DB must recreate/re-migrate it.

## Capabilities

### New Capabilities
(none — this extends existing scoring behavior, it does not introduce a new capability)

### Modified Capabilities
- `eval-summary-scoring`: adds the per-test-case `execution_status` aggregation requirement and the
  requirement that `score`/`passed` are only computed when that aggregate is `SUCCESS`.

Note: `test-case-metric-score-aggregation`'s own requirements are unaffected — its
`test_case_metric_scores_aggregated` table and "absent metric = no numeric sample" contract are left
untouched (see Decisions in design.md). Only its Implementation Notes doc section gets a pointer to
the new sibling `TestCaseExecutionStatusAggregator`, added directly (not via a delta spec, since no
requirement text changes) as part of `tasks.md`.

## Impact

- **Schema**: `test_case_eval_scores` gains `execution_status VARCHAR(20) NOT NULL` (edited into
  `V1.21__AddRunCaseContextToTestCaseEvalScores.sql`, backfilled from each row's own
  `test_case_eval_summaries.execution_status` collapsed to SUCCESS/FAILED). Requires
  `./gradlew generateJooq` and committing the regenerated sources.
- **Code**: new `TestCaseExecutionStatusAggregator`
  (`query.service.metricscore`); `InProcessMetricEvaluationExecutor.writeRowScores`/`toScoreItem`
  gain the aggregate-status lookup and score-gating logic; `TestCaseEvalScore` model,
  `TestCaseEvalScoreBatchWriteItemDto`, `TestCaseEvalScoreService.batchUpsert`,
  `PostgresTestCaseEvalScoreRepository.saveAll`, and `PostgresTestCaseEvalScoreEntityResolver` all
  gain the new field.
- **Docs**: `docs/database-schema.md` (new column), `docs/patterns/eval-summaries-read-surface.md`,
  `docs/patterns/overall-score-definition.md`.
- **No API/DTO changes**: there is no external REST endpoint for `test_case_eval_scores`
  (`TestCaseEvalScoreService` javadoc); the new column is only reachable via the LEFT JOIN read
  surface and the `test_case_eval_scores` Query DSL entity, both of which already expose
  `score`/`passed` the same way.
- **No config changes.**
