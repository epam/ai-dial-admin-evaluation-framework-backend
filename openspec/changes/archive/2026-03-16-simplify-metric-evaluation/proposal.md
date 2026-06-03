## Why

The in-process metric evaluation executor has unnecessary complexity: nested virtual thread executors, result-level parallelism on top of TSMD-level parallelism, and missing OpenTelemetry context propagation. Additionally, the test case result query filters by `created_at_ms` as a proxy for run identity, which is semantically incorrect — `test_suite_run_id` is the proper filter.

## What Changes

- **Simplify `InProcessMetricEvaluationExecutor`**: Replace nested executor model with a single `Context.taskWrapping()` executor. Iterate test case results sequentially; dispatch each TSMD evaluation to the shared executor as a separate virtual thread. This mirrors `InProcessEvaluationExecutor`'s proven pattern.
- **Fix test case result filtering**: Replace `createdAtMs`-based filtering with a `runId` `FilterCondition` (using existing `ANALYTICS_RESULTS` whitelist entry). Remove `createdAtMs` field from `MetricEvaluationContext`.
- **Remove `computedAtMs` from `MetricEvaluationContext`**: Use `System.currentTimeMillis()` at the point of use instead of carrying a pre-computed timestamp through context. Keep `Clock` injection in `TestSuiteEvaluationJob` for the field that needs it.
- **Add OpenTelemetry tracing to `MetricEvaluationWorker`**: Create a span per TSMD evaluation (matching `EvaluationWorker`'s pattern) with attributes for TSMD name, provider ID, run ID, and result ID. This enables distributed tracing of metric provider calls.

## Capabilities

### New Capabilities

_None — this is a simplification/refactoring of existing implementation._

### Modified Capabilities

_None — no spec-level behavior changes. The metric evaluation feature produces identical results; only the internal execution model and query filter are changing._

## Impact

- **Code**: `InProcessMetricEvaluationExecutor`, `MetricEvaluationContext`, `TestSuiteEvaluationJob`, `MetricEvaluationWorker`
- **APIs**: No API changes
- **Data/Migration**: No schema changes — `test_suite_run_id` column and index already exist on `test_case_run_results`
- **Security/Permissions**: No impact
- **Risks**: Low — sequential result iteration may be slightly slower than parallel, but TSMD-level parallelism (the actual bottleneck) is preserved. The concurrency model becomes simpler and more predictable.
