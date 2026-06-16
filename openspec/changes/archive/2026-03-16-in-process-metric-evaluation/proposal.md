## Summary

Add an in-process metric evaluation phase to the test suite run lifecycle. After the deployment evaluation phase produces TestCaseRunResults, the system automatically evaluates configured metrics by calling metric provider `/evaluate` endpoints, and stores the results as EvalSummary records.

## Goals

- Automate metric evaluation as part of the test suite run — no external service needed to compute metrics
- Reuse TSMD input/config bindings to resolve metric evaluation requests per test case
- Capture RunMetricSnapshots before evaluation for reproducibility
- Write EvalSummary and RunMetricSnapshot records via service-layer client wrappers reusing existing batch write services
- Support provider-bounded concurrency with configurable semaphore limits
- Support cancellation (same AtomicBoolean pattern as deployment evaluation)
- Support configurable retry on `/evaluate` failures

## Non-goals

- Separate re-evaluation API endpoint (future scope — would introduce its own status tracking)
- Sophisticated completion semantics (e.g., partial success at run level)
- Rate limiting for metric evaluation calls (semaphore-based concurrency is sufficient for now)

## Current State

The test suite run lifecycle currently has one execution phase:
1. `InProcessEvaluationExecutor` dispatches `EvaluationWorker` calls per test case → produces `TestCaseRunResult` records

EvalSummary and RunMetricSnapshot records are written by external services via batch POST APIs (`/api/evaluations/summaries/batch`, `/api/evaluations/snapshots/batch`). There is no in-process metric computation.

## Proposed Change

### Phase 2: Metric Evaluation (chained after deployment evaluation)

After Phase 1 completes (and if not cancelled), `TestSuiteEvaluationJob` chains a new metric evaluation phase:

1. **Setup**: Load all aggregated TSMDs for the suite (new `findAllAggregatedByTestSuiteId` repository method). Group by provider ID. Generate `computationId` + `computedAtMs`. Batch-write `RunMetricSnapshots`.

2. **Evaluation**: Iterate `TestCaseRunResults` (cursor-paginated by runId). For each result:
   - If non-SUCCESS status → write EvalSummary with propagated status, empty metrics
   - If SUCCESS → evaluate all TSMDs concurrently (provider-semaphore-bounded virtual threads):
     - Resolve bindings: TestCase → `result.testCaseData`, Response → `result.extractedColumns`, Constant → literal value. Missing columns resolve to `null`.
     - Build `EvaluationRequest` (`metric_name` from declaration name, `config` from config bindings, `input` from input bindings)
     - Call `POST /evaluate` on the appropriate metric provider (with configurable retry)
     - Merge outputs across all TSMDs into single `metricValues` + `metricInfos` maps
     - Build and batch-write `EvalSummary`

3. **Status**: COMPLETED means both phases ran for all test cases. Individual metric failures are captured per-EvalSummary (`executionStatus = FAILED` if any metric errored for that test case).

### Output Mapping (EvaluationResponse → EvalSummary)

- `type: "value"` with no details → `metricValues[tsmdName][field] = number`
- `type: "value"` with details → `metricValues[tsmdName][field] = number`, `metricInfos[tsmdName][field] = details`
- `type: "error"` → `metricValues[tsmdName][field] = null`, `metricInfos[tsmdName][field] = { "error": message }`

### Concurrency Model

Each metric provider gets its own semaphore (configurable). For a given test case result, all TSMDs across all providers evaluate concurrently within their respective semaphore bounds. Cross-result parallelism is also supported — the semaphore naturally throttles the total concurrent `/evaluate` calls per provider.

## What Changes

- **Extend MetricProviderClient** with `evaluate(providerId, EvaluationRequest)` method calling `POST /evaluate`
- **New client DTOs**: `EvaluationRequestDto`, `EvaluationResponseDto`, `MetricOutputFieldDto`, `MetricErrorDto`
- **New `MetricEvaluationExecutor` interface + `InProcessMetricEvaluationExecutor`**: interface for metric evaluation strategies with in-process implementation that orchestrates RunMetricSnapshot capture, paginated result iteration, concurrent metric evaluation dispatch, and EvalSummary batch writing
- **New `MetricEvaluationWorker`**: evaluates a single TSMD against a single test case result (binding resolution + provider call + retry)
- **New `BindingResolver`**: resolves TSMD config/input bindings against test case data and extracted columns
- **New `EvalSummaryBatchWriteClient`**: service-layer wrapper that converts internal models to batch write DTOs and delegates to `EvalSummaryService.batchCreate()`, respecting existing batch size limits
- **New `RunMetricSnapshotBatchWriteClient`**: service-layer wrapper that converts internal models to batch write DTOs and delegates to `RunMetricSnapshotService.batchCreate()`
- **New `MetricEvaluationContext`**: carries computation state (computationId, TSMDs, provider semaphores, cancellation signal, etc.)
- **Modify `TestSuiteEvaluationJob`**: chain metric evaluation after deployment evaluation
- **New repository method**: `findAllAggregatedByTestSuiteId(UUID testSuiteId)` on `TestSuiteMetricDefinitionRepository`
- **New `MetricEvaluationProperties`**: configurable concurrency per provider, retry policy, batch size
- **If no TSMDs** configured for the suite → skip metric evaluation phase entirely, proceed to COMPLETED

## Capabilities

### New Capabilities

- `metric-evaluation`: In-process metric evaluation phase — binding resolution, provider `/evaluate` invocation, EvalSummary assembly, provider-bounded concurrency, retry, cancellation.

### Modified Capabilities

- `test-suite-runs`: Run lifecycle now includes a metric evaluation phase after deployment evaluation. COMPLETED status semantics extended to cover both phases.
- `metrics-storage`: EvalSummary and RunMetricSnapshot records now also written internally by the metric evaluation job via service-layer client wrappers that delegate to the existing batch write services.

## Impact

### Code

| Component | Type | Package |
|---|---|---|
| `MetricEvaluationExecutor` | New interface | `service.domain.job` |
| `InProcessMetricEvaluationExecutor` | New class | `service.domain.job` |
| `MetricEvaluationWorker` | New class | `service.domain.job` |
| `BindingResolver` | New class | `service.domain.job` |
| `EvalSummaryBatchWriteClient` | New class | `service.domain.job` |
| `RunMetricSnapshotBatchWriteClient` | New class | `service.domain.job` |
| `MetricEvaluationContext` | New class | `service.domain.job` |
| `MetricProviderClient` | Modified | `client.metricprovider` |
| `EvaluationRequestDto` | New DTO | `client.metricprovider.dto` |
| `EvaluationResponseDto` | New DTO | `client.metricprovider.dto` |
| `MetricOutputFieldDto` | New DTO | `client.metricprovider.dto` |
| `MetricErrorDto` | New DTO | `client.metricprovider.dto` |
| `TestSuiteEvaluationJob` | Modified | `service.domain.job` |
| `TestSuiteMetricDefinitionRepository` | Modified (new method) | `data.db.repository` |
| `PostgresTestSuiteMetricDefinitionRepository` | Modified (new method) | `data.db.repository` |
| `MetricEvaluationProperties` | New class | `configuration.properties` |

### Configuration

New properties under `metric-evaluation.*`:
- `metric-evaluation.default-concurrency-per-provider` — max concurrent `/evaluate` calls per provider (default: 5)
- `metric-evaluation.retry.max-retries` — max retries for `/evaluate` calls
- `metric-evaluation.retry.retry-delay-ms` — initial retry delay
- `metric-evaluation.retry.retry-backoff-multiplier` — backoff multiplier
- `metric-evaluation.batch-size` — EvalSummary batch write size

`docs/configuration.md` must be updated.

### API

No new REST endpoints. No breaking changes. The metric evaluation phase is transparent to API consumers — they trigger a test suite run as before, and EvalSummary records appear automatically when TSMDs are configured.

### Database / Migrations

No schema changes required. Existing `test_case_eval_summaries`, `run_metric_snapshots` tables are reused. No new Flyway migration needed.

## Risks

- **Provider availability**: If a metric provider is down, metric eval fails for affected metrics. Retry mitigates transient issues. Per-EvalSummary error capture prevents total run failure.
- **Performance**: Large test suites with many TSMDs multiply the number of `/evaluate` calls. Provider-bounded semaphores prevent overwhelming providers. Cursor pagination prevents OOM on result iteration.
- **Binding resolution edge cases**: Missing columns in test case data or extracted columns resolve to `null`. Provider behavior on `null` inputs is provider-dependent.

## Rollout

Feature is automatically active when TSMDs are configured for a test suite. No feature flag needed — suites without TSMDs skip the metric evaluation phase entirely.

## Test Plan

- Functional tests for the full flow: create suite → add TSMDs → run → verify EvalSummary records
- Unit tests for `BindingResolver` (all source types, missing columns, null handling)
- Unit tests for output mapping (value, value+details, error)
- Unit tests for `MetricEvaluationWorker` retry behavior
- Functional test for cancellation during metric evaluation phase
- Functional test for non-SUCCESS results propagating to EvalSummary
