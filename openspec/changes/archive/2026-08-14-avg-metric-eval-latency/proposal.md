## Why

Test-case-level runtime (`exec_duration_ms`) is already measured, stored, and surfaced (including an `avgExecDurationMs` aggregate in run comparison), but nothing today measures how long individual metric-provider `/evaluate` calls take. Operators debugging slow runs or comparing metric providers currently have no visibility into metric-evaluation latency, only whole-test-case execution time. We need to capture per-TSMD call duration and persist an average per eval-summary row so it can be displayed and queried the same way test case runtime already is.

## What Changes

- Time each TSMD `/evaluate` call dispatched in `InProcessMetricEvaluationExecutor.evaluateAndBuild` (currently untimed), including calls that fail or time out — elapsed time is recorded even on failure/timeout, mirroring how `exec_duration_ms` records duration on ERROR completion.
- Carry the per-call duration through `TsmdEvaluationResult` (`Success`/`Failure` variants); `ConditionError` (no provider call made) is excluded from aggregation.
- Compute a single scalar average across all dispatched TSMDs for a given (test case result, computation) and persist it as a new `avg_metric_eval_duration_ms BIGINT NOT NULL DEFAULT 0` column on `test_case_eval_summaries` (new Flyway migration `V1.16__AddAvgMetricEvalDurationToEvalSummaries.sql`).
- Wire the new field through the same touchpoints `exec_duration_ms` already has: `EvalSummary` model, `EvalSummaryRecordMapper`, `PostgresEvalSummaryRepository` (insert), `EvalSummaryBatchWriteItemDto`, `EvalSummaryMapper`, `EvalSummaryResponseDto`/`EvalSummaryDetailResponseDto`, `EvalSummaryExportColumnPlanner` (CSV export).
- No Query DSL resolver/registry changes are needed — `test_case_eval_summaries` columns are auto-discovered by `JooqTableSchemaResolver` from the generated jOOQ table, so the new column becomes filterable/aggregatable (`avg`, etc.) automatically once the migration is applied and jOOQ sources are regenerated (confirmed precedent: `V1.14` added `turn_index`/`total_turns` with zero DSL wiring).
- Optionally extend the existing `avgExecDurationMs` sibling aggregate (`EvalSummaryMatchStats`, `RunComparisonRunDto`, `RunComparisonService`) with an `avgMetricEvalDurationMs` counterpart for run-comparison summaries, for parity with how test case runtime is already surfaced there.

The DB migration itself is non-breaking — existing rows backfill to `0` via `DEFAULT 0`. However, `EvalSummaryBatchWriteItemDto.avgMetricEvalDurationMs` is `@NotNull`, so this IS a breaking change for the batch-write endpoint: any external producer calling `POST /api/v1/analytics/eval-summaries` without sending the new field will start receiving HTTP 400 until updated.

## Capabilities

### New Capabilities
(none — this extends existing metric evaluation and eval summary storage capabilities)

### Modified Capabilities
- `metric-evaluation`: `InProcessMetricEvaluationExecutor`/`MetricEvaluationWorker` SHALL measure per-TSMD evaluation call duration (including failed/timed-out calls) and compute an average across dispatched TSMDs per test case result, passed into the `EvalSummaryBatchWriteItemDto`.
- `metrics-storage`: `test_case_eval_summaries` SHALL gain an `avg_metric_eval_duration_ms BIGINT NOT NULL DEFAULT 0` column; `EvalSummary` model, `EvalSummaryRecordMapper`, and `EvalSummaryResponseDto`/`EvalSummaryDetailResponseDto` SHALL expose it; the batch-write item contract SHALL require `avgMetricEvalDurationMs`.
- `eval-summary-export`: the fixed identity/execution CSV column list SHALL gain `avgMetricEvalDurationMs` alongside `execDurationMs`.

## Impact

- **DB schema**: new Flyway migration `V1.16__AddAvgMetricEvalDurationToEvalSummaries.sql` on the analytics datasource; regenerate jOOQ (`./gradlew generateJooq`) and commit the diff under `src/main/java-generated/`.
- **Code**: `service.domain.job` package (`InProcessMetricEvaluationExecutor`, `TsmdEvaluationResult`, possibly `MetricEvaluationWorker`), `data.db.analytics.model`/`mapper`/`repository` (`EvalSummary`, `EvalSummaryRecordMapper`, `PostgresEvalSummaryRepository`), `service.domain.dto.analytics` (`EvalSummaryBatchWriteItemDto`, `EvalSummaryResponseDto`, `EvalSummaryDetailResponseDto`), `service.domain.mapper.EvalSummaryMapper`, `service.domain.analytics.EvalSummaryExportColumnPlanner`. Optionally `EvalSummaryMatchStats`, `RunComparisonRunDto`, `RunComparisonService`.
- **API**: `avgMetricEvalDurationMs` (Long) added to eval-summary response DTOs and CSV export; queryable through the existing structured Query DSL on the `eval_summaries` entity without resolver changes.
- **Docs**: `docs/database-schema.md` must be updated with the new column (per AGENTS.md rule for schema-changing migrations).
- **Breaking change (API)**: the DB schema change is additive and non-breaking, but the batch-write API contract is not — `avgMetricEvalDurationMs` is a required (`@NotNull`) field on `EvalSummaryBatchWriteItemDto`, so existing producers of `POST /api/v1/analytics/eval-summaries` must be updated to send it or their requests will fail with HTTP 400.
