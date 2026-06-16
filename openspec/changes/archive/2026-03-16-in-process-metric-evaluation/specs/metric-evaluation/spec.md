# Metric Evaluation

## Purpose
This spec defines the in-process metric evaluation engine — a second execution phase within the test suite run lifecycle that evaluates configured metrics against test case run results by calling metric provider `/evaluate` endpoints, and writes the results as EvalSummary records.

Status: **Planned**

## Key Terms
- **MetricEvaluationExecutor**: Interface for metric evaluation execution strategies (analogous to `EvaluationExecutor` for deployment evaluation). Supports both in-process execution and future K8s Job delegation.
- **InProcessMetricEvaluationExecutor**: In-process implementation of `MetricEvaluationExecutor`. Orchestrates paginated result iteration, concurrent metric evaluation dispatch, RunMetricSnapshot capture, and EvalSummary batch writing.
- **MetricEvaluationWorker**: Evaluates a single TSMD against a single test case result — resolves bindings, calls provider `/evaluate`, handles retry.
- **BindingResolver**: Resolves TSMD config/input bindings against test case data and extracted columns from a TestCaseRunResult.
- **EvalSummaryBatchWriteClient**: Service-layer wrapper that converts internal EvalSummary models to batch write DTOs and delegates to `EvalSummaryService.batchCreate()`, chunking items to respect the existing batch size limit.
- **RunMetricSnapshotBatchWriteClient**: Service-layer wrapper that converts internal RunMetricSnapshot models to batch write DTOs and delegates to `RunMetricSnapshotService.batchCreate()`.
- **MetricEvaluationContext**: Immutable context carrier for a metric evaluation run — carries computationId, aggregated TSMDs grouped by provider, semaphores, cancellation signal, retry config.

## ADDED Requirements

### Requirement: Metric evaluation executor orchestration
`MetricEvaluationExecutor` is an interface; `InProcessMetricEvaluationExecutor` is the in-process implementation (mirroring the `EvaluationExecutor` / `InProcessEvaluationExecutor` pattern for deployment evaluation). The implementation SHALL capture RunMetricSnapshots, iterate all `TestCaseRunResult` records for the run using cursor-based pagination, dispatch metric evaluations concurrently per provider, assemble EvalSummary records, and batch-write them to the analytics DB.
Status: **Planned**

#### Scenario: Successful metric evaluation for all test cases
- **WHEN** the metric evaluation phase starts for a completed run with TSMDs configured
- **THEN** the executor SHALL iterate all TestCaseRunResults for the run, evaluate all TSMDs for each SUCCESS result, merge outputs into EvalSummary records, and batch-write them to the analytics DB

#### Scenario: No TSMDs configured for suite
- **WHEN** the metric evaluation phase starts and the suite has no TSMDs
- **THEN** the executor SHALL skip metric evaluation entirely and return without writing any records

#### Scenario: Cursor-paginated result iteration
- **WHEN** the executor iterates TestCaseRunResults
- **THEN** it SHALL use cursor-based pagination (filtering by runId) to avoid loading all results into memory

#### Scenario: Cross-result parallelism
- **WHEN** multiple test case results are being processed
- **THEN** the executor SHALL dispatch metric evaluations across results concurrently — the provider semaphore controls the total concurrent `/evaluate` calls per provider

### Requirement: Provider-bounded concurrency
Each metric provider SHALL have its own semaphore controlling the maximum number of concurrent `/evaluate` calls. The semaphore limit SHALL be configurable.
Status: **Planned**

#### Scenario: Semaphore per provider
- **WHEN** the metric evaluation phase starts with TSMDs from providers "dial" and "custom"
- **THEN** the executor SHALL create a separate semaphore for each provider, each initialized to the configured concurrency limit

#### Scenario: Semaphore acquired before /evaluate call
- **WHEN** a MetricEvaluationWorker is about to call `/evaluate`
- **THEN** it SHALL acquire the provider's semaphore before making the HTTP call and release it in a `finally` block

#### Scenario: Configurable concurrency
- **WHEN** the application starts
- **THEN** `metric-evaluation.default-concurrency-per-provider` SHALL control the semaphore limit (default: 5)

### Requirement: Single metric evaluation (worker)
The `MetricEvaluationWorker` SHALL evaluate a single TSMD against a single TestCaseRunResult by resolving bindings, building an `EvaluationRequest`, calling the provider's `/evaluate` endpoint, and returning the `EvaluationResponse`.
Status: **Planned**

#### Scenario: Successful metric evaluation
- **WHEN** a worker evaluates TSMD "Accuracy" (metric: exact_match) for a test case result
- **THEN** the worker SHALL resolve bindings, build `EvaluationRequest` with `metric_name` from the metric declaration's name, `config` from resolved config bindings, `input` from resolved input bindings, call `POST /evaluate` on the provider, and return the `EvaluationResponse`

#### Scenario: Provider call failure with retry
- **WHEN** the `/evaluate` call fails with a retryable condition (timeout, 5xx, 429) and retry is configured
- **THEN** the worker SHALL retry up to `maxRetries` times with exponential backoff: `delay = min(retryDelayMs * retryBackoffMultiplier^(attemptIndex - 1), maxRetryDelayMs)`

#### Scenario: Non-retryable failure
- **WHEN** the `/evaluate` call fails with a non-retryable condition (4xx except 429)
- **THEN** the worker SHALL NOT retry and SHALL throw an exception with the error details

#### Scenario: All retries exhausted
- **WHEN** all retry attempts fail
- **THEN** the worker SHALL throw an exception with the error details from the last attempt

#### Scenario: Retry respects cancellation
- **WHEN** the run is cancelled while a retry backoff is in progress
- **THEN** the worker SHALL abort the retry and throw an exception with the last known error

#### Scenario: Transport failure propagation to executor
- **WHEN** the worker throws an exception (transport failure, all retries exhausted)
- **THEN** the executor SHALL catch the per-TSMD exception via `CompletableFuture` error handling and map it to error entries in metricValues (null) and metricInfos (error message)

### Requirement: Binding resolution
The `BindingResolver` SHALL resolve TSMD config and input bindings against test case data and extracted columns from a `TestCaseRunResult`, producing `Map<String, Object>` for config and input.
Status: **Planned**

#### Scenario: TestCase binding source
- **WHEN** a binding has `source: { $type: "TestCase", columnName: "expected" }` and the test case data contains `{"expected": "A planet"}`
- **THEN** the resolver SHALL produce `{"expected": "A planet"}` for that binding's property

#### Scenario: Response binding source
- **WHEN** a binding has `source: { $type: "Response", columnName: "model_answer" }` and the extracted columns contain `{"model_answer": "Earth is the third planet"}`
- **THEN** the resolver SHALL produce the value `"Earth is the third planet"` for that binding's property

#### Scenario: Constant binding source
- **WHEN** a binding has `source: { $type: "Constant", value: "gemini-2.5-flash-lite" }`
- **THEN** the resolver SHALL produce the literal value `"gemini-2.5-flash-lite"` for that binding's property

#### Scenario: Missing column resolves to null
- **WHEN** a binding references a column name that does not exist in the test case data or extracted columns
- **THEN** the resolver SHALL produce `null` for that binding's property

#### Scenario: Multiple bindings merged into single map
- **WHEN** input bindings contain `[{property: "actual", source: Response/model_answer}, {property: "ground_truth", source: TestCase/expected}]`
- **THEN** the resolver SHALL produce `{"actual": <value>, "ground_truth": <value>}`

### Requirement: EvaluationRequest construction
The system SHALL build an `EvaluationRequest` from resolved bindings and the metric declaration name.
Status: **Planned**

#### Scenario: Request structure
- **WHEN** a metric evaluation request is constructed for TSMD "Accuracy" (declaration name: "exact_match") with resolved config `{}` and input `{"actual": "test", "ground_truth": "test"}`
- **THEN** the request SHALL be `{"metric_name": "exact_match", "config": {}, "input": {"actual": "test", "ground_truth": "test"}}`

#### Scenario: metric_name from declaration
- **WHEN** the request is built
- **THEN** `metric_name` SHALL be taken from `AggregatedMetricDefinition.metricDeclarationName` (the provider's metric name), NOT from the TSMD's display name

### Requirement: Output mapping to metricValues and metricInfos
The system SHALL map `EvaluationResponse` output fields to EvalSummary's `metricValues` and `metricInfos` JSONB columns, keyed by TSMD name.
Status: **Planned**

#### Scenario: Value output without details
- **WHEN** an output field has `{type: "value", value: 1}` with no details
- **THEN** `metricValues[tsmdName][fieldName]` SHALL be `1` and no entry SHALL be added to metricInfos for that field

#### Scenario: Value output with details
- **WHEN** an output field has `{type: "value", value: 0.8, details: {"reason": "..."}}`
- **THEN** `metricValues[tsmdName][fieldName]` SHALL be `0.8` and `metricInfos[tsmdName][fieldName]` SHALL be `{"reason": "..."}`

#### Scenario: Error output
- **WHEN** an output field has `{type: "error", message: "Invalid pattern"}`
- **THEN** `metricValues[tsmdName][fieldName]` SHALL be `null` (explicit JSON null, preserved via ObjectNode.putNull) and `metricInfos[tsmdName][fieldName]` SHALL be `{"error": "Invalid pattern"}`

#### Scenario: Transport failure for a TSMD
- **WHEN** the `/evaluate` call for a TSMD fails with a transport error (HTTP 500, timeout, all retries exhausted)
- **THEN** `metricValues[tsmdName]` SHALL contain `{"error": null}` (a placeholder key with null value) and `metricInfos[tsmdName]` SHALL contain `{"error": "<exception message>"}`

#### Scenario: Multiple TSMDs merged into single EvalSummary
- **WHEN** a test case has TSMDs "Accuracy" and "RAG Quality" both evaluated
- **THEN** `metricValues` SHALL contain keys for both TSMD names: `{"Accuracy": {...}, "RAG Quality": {...}}`

### Requirement: EvalSummary assembly from TestCaseRunResult
The system SHALL build one EvalSummary per TestCaseRunResult, copying context fields from the result and adding computed metric values.
Status: **Planned**

#### Scenario: Field mapping from result to summary
- **WHEN** an EvalSummary is built for a TestCaseRunResult
- **THEN** the batch write envelope SHALL carry `testSuiteId`, `testSuiteRunId`, `computationId`, and `computedAtMs` from the MetricEvaluationContext. Each item SHALL carry: `testCaseRunResultId` = result.id, `testCaseId`, `testCaseName`, `runIndex`, `testCaseData`, `extractedColumns`, `execDurationMs`, `responseStatusCode` from result. The `createdAtMs` is derived by the service from the run's creation timestamp (not set per-item).

#### Scenario: Non-SUCCESS result propagation
- **WHEN** a TestCaseRunResult has `executionStatus != SUCCESS`
- **THEN** the EvalSummary SHALL have `executionStatus` propagated from the result, `metricValues = {}`, `metricInfos = null` — no metric evaluation SHALL be attempted

#### Scenario: Metric error determines executionStatus
- **WHEN** all metrics evaluate successfully (no `type: "error"` outputs)
- **THEN** the EvalSummary SHALL have `executionStatus = SUCCESS`

#### Scenario: Any metric error or transport failure fails the summary
- **WHEN** at least one metric output field has `type: "error"` OR at least one TSMD evaluation fails with a transport error (worker exception)
- **THEN** the EvalSummary SHALL have `executionStatus = FAILED`

### Requirement: EvalSummary batch writing via service-layer client
The `EvalSummaryBatchWriteClient` SHALL convert internal EvalSummary models to the existing `EvalSummaryBatchWriteRequestDto` and delegate to `EvalSummaryService.batchCreate()`. The executor SHALL buffer EvalSummary records and flush them through the client at configurable thresholds.
Status: **Planned**

#### Scenario: Batch flush on size
- **WHEN** the buffer reaches `metric-evaluation.batch-size` (default: 100) records
- **THEN** the executor SHALL flush the buffer via `EvalSummaryBatchWriteClient`, which converts models to DTOs and calls `EvalSummaryService.batchCreate()`

#### Scenario: Chunking to respect existing batch size limit
- **WHEN** the number of items to write exceeds the existing `analytics.eval-summaries.batch.max-items` limit
- **THEN** the client SHALL chunk items into multiple `batchCreate()` calls, each within the limit

#### Scenario: Final flush on completion
- **WHEN** all test cases have been processed
- **THEN** the executor SHALL flush any remaining buffered records via the client

#### Scenario: Flush on cancellation
- **WHEN** the run is cancelled during metric evaluation
- **THEN** the executor SHALL flush all accumulated records via the client before returning

#### Scenario: Batch write failure
- **WHEN** a batch write via the service fails
- **THEN** the executor SHALL set the cancellation signal, stop dispatching new evaluations, drain in-flight calls (up to grace period), and log the error

### Requirement: RunMetricSnapshot writing via service-layer client
The `RunMetricSnapshotBatchWriteClient` SHALL convert internal RunMetricSnapshot models to the existing `RunMetricSnapshotBatchWriteRequestDto` and delegate to `RunMetricSnapshotService.batchCreate()`.
Status: **Planned**

#### Scenario: Snapshot write delegates to existing service
- **WHEN** RunMetricSnapshots are written before metric evaluation starts
- **THEN** the `RunMetricSnapshotBatchWriteClient` SHALL convert models to `RunMetricSnapshotBatchWriteRequestDto` and call `RunMetricSnapshotService.batchCreate()`

### Requirement: RunMetricSnapshot capture before evaluation
The `InProcessMetricEvaluationExecutor` SHALL capture RunMetricSnapshots as its first action before dispatching any `/evaluate` calls. It uses the `computationId` and `computedAtMs` from the `MetricEvaluationContext` (generated by the job during context construction) to batch-write one `RunMetricSnapshot` per TSMD capturing the metric configuration at evaluation time.
Status: **Planned**

#### Scenario: Snapshot fields populated from aggregated TSMD
- **WHEN** RunMetricSnapshots are created
- **THEN** each snapshot SHALL contain: `id` (new UUID), `computationId` (shared across all snapshots), `testSuiteRunId`, `tsmdId` = TSMD.id, `tsmdName` = TSMD.name, `metricDeclarationId`, `metricDeclarationVersionId`, `configBindings` = TSMD.configBindings, `inputBindings` = TSMD.inputBindings, `outputSchema` = TSMD.versionOutputSchema, `computedAtMs`

#### Scenario: Snapshots written before evaluation starts
- **WHEN** the metric evaluation executor starts execution
- **THEN** RunMetricSnapshots SHALL be written to the analytics DB BEFORE any `/evaluate` calls are made

### Requirement: MetricProviderClient evaluate method
The `MetricProviderClient` SHALL support calling `POST /evaluate` on metric providers.
Status: **Planned**

#### Scenario: Successful evaluation call
- **WHEN** `MetricProviderClient.evaluate(providerId, request)` is called
- **THEN** it SHALL use the provider's RestClient (from `MetricProviderRestClientFactory`) to POST to `/evaluate` with the request body and return the parsed `EvaluationResponseDto`

#### Scenario: Provider not configured
- **WHEN** `evaluate()` is called with a providerId that has no configured RestClient
- **THEN** it SHALL throw `IllegalArgumentException`

### Requirement: Aggregated TSMD bulk loading
The repository SHALL support loading all aggregated TSMDs for a test suite in a single query.
Status: **Planned**

#### Scenario: findAllAggregatedByTestSuiteId
- **WHEN** `findAllAggregatedByTestSuiteId(testSuiteId)` is called
- **THEN** it SHALL execute a 3-table JOIN (test_suite_metric_definitions + metric_declarations + metric_declaration_versions) and return `List<AggregatedMetricDefinition>` with all fields populated including `declarationProviderId` and `metricDeclarationName`

#### Scenario: No TSMDs for suite
- **WHEN** the test suite has no TSMDs
- **THEN** it SHALL return an empty list

### Requirement: Graceful cancellation during metric evaluation
The metric evaluation phase SHALL support cancellation via the same `AtomicBoolean` signal used by the deployment evaluation phase.
Status: **Planned**

#### Scenario: Cancellation stops new dispatches
- **WHEN** cancellation is signaled during metric evaluation
- **THEN** the executor SHALL stop dispatching new metric evaluation tasks

#### Scenario: Grace period for in-flight calls
- **WHEN** cancellation is signaled and there are in-flight `/evaluate` calls
- **THEN** the executor SHALL wait up to `metric-evaluation.cancellation-grace-period-ms` (default: 30000) for in-flight calls to complete

#### Scenario: Partial results preserved
- **WHEN** metric evaluation is cancelled
- **THEN** all EvalSummary records written before cancellation SHALL be preserved

### Requirement: Configurable retry for metric evaluation
The metric evaluation worker SHALL support configurable retry with exponential backoff for `/evaluate` calls.
Status: **Planned**

#### Scenario: Retry configuration properties
- **WHEN** the application starts
- **THEN** it SHALL read `metric-evaluation.retry.max-retries` (default: 0), `metric-evaluation.retry.retry-delay-ms` (default: 1000), `metric-evaluation.retry.retry-backoff-multiplier` (default: 2.0), `metric-evaluation.retry.max-retry-delay-ms` (default: 60000)

#### Scenario: Retryable conditions
- **WHEN** the `/evaluate` call fails with timeout, HTTP 429, or HTTP 5xx
- **THEN** the worker SHALL retry according to the configured policy

#### Scenario: Non-retryable conditions
- **WHEN** the `/evaluate` call fails with HTTP 4xx (except 429)
- **THEN** the worker SHALL NOT retry

### Requirement: Configuration properties for metric evaluation
The system SHALL expose configurable properties under the `metric-evaluation` prefix.
Status: **Planned**

#### Scenario: All properties with defaults
- **WHEN** the application starts
- **THEN** it SHALL read: `metric-evaluation.default-concurrency-per-provider` (default: 5), `metric-evaluation.batch-size` (default: 100), `metric-evaluation.cancellation-grace-period-ms` (default: 30000), `metric-evaluation.retry.max-retries` (default: 0), `metric-evaluation.retry.retry-delay-ms` (default: 1000), `metric-evaluation.retry.retry-backoff-multiplier` (default: 2.0), `metric-evaluation.retry.max-retry-delay-ms` (default: 60000)

### Requirement: End-to-end functional test for full run lifecycle with metric evaluation
A single e2e functional test SHALL verify the complete run lifecycle: deployment evaluation (Phase 1) → metric evaluation (Phase 2), covering success and failure paths within one test flow. The test SHALL live in `TestSuiteRunFunctionalTests` (existing class already has `@MockitoBean` for `DialCoreDeploymentInvoker` and `MetricProviderClient`). Mock setup logic SHALL be extracted into a dedicated helper class to keep the test readable.
Status: **Planned**

#### Scenario: Full two-phase run with mixed success/failure test cases
- **WHEN** a test suite is configured with 2-3 test cases, TSMDs with bindings, and a mock deployment that returns SUCCESS for some test cases and FAILED/TIMEOUT for at least one test case, and the metric provider `/evaluate` mock returns valid `EvaluationResponse` objects
- **THEN** the run SHALL reach COMPLETED status after both phases execute

#### Scenario: 1:1 correspondence between TestCaseRunResults and EvalSummaries
- **WHEN** the run completes
- **THEN** the number of EvalSummary records SHALL equal the number of TestCaseRunResult records for the run, with each EvalSummary referencing its corresponding TestCaseRunResult via `testCaseRunResultId`

#### Scenario: Non-SUCCESS test case result propagates to EvalSummary
- **WHEN** a TestCaseRunResult has `executionStatus != SUCCESS` (e.g., TIMEOUT or FAILED)
- **THEN** the corresponding EvalSummary SHALL have the same non-SUCCESS `executionStatus`, `metricValues = {}`, and `metricInfos = null`. No `/evaluate` call SHALL have been made for that test case.

#### Scenario: SUCCESS test case result has metric values populated
- **WHEN** a TestCaseRunResult has `executionStatus = SUCCESS` and the metric provider returns valid outputs
- **THEN** the corresponding EvalSummary SHALL have `executionStatus = SUCCESS`, `metricValues` populated with the TSMD name as key and output field names/values as nested map, and `metricInfos` populated for outputs that include details

#### Scenario: RunMetricSnapshots captured correctly
- **WHEN** the run completes
- **THEN** RunMetricSnapshot records SHALL exist for each TSMD, all sharing the same `computationId`, with `tsmdName`, `metricDeclarationId`, `metricDeclarationVersionId`, `configBindings`, `inputBindings`, and `outputSchema` matching the TSMD configuration at evaluation time

#### Scenario: Mock setup isolated in helper class
- **WHEN** the test is implemented
- **THEN** mock configuration for `DialCoreDeploymentInvoker` (deployment responses per test case) and `MetricProviderClient.evaluate()` (metric responses) SHALL be encapsulated in a dedicated helper class (e.g., `MetricEvaluationTestHelper`), keeping the test method focused on assertions

## Implementation Notes
- Executor interface: `com.epam.aidial.evaluation.service.domain.job.MetricEvaluationExecutor`
- In-process executor: `com.epam.aidial.evaluation.service.domain.job.InProcessMetricEvaluationExecutor`
- Worker: `com.epam.aidial.evaluation.service.domain.job.MetricEvaluationWorker`
- Binding resolver: `com.epam.aidial.evaluation.service.domain.job.BindingResolver`
- EvalSummary client: `com.epam.aidial.evaluation.service.domain.job.EvalSummaryBatchWriteClient`
- RunMetricSnapshot client: `com.epam.aidial.evaluation.service.domain.job.RunMetricSnapshotBatchWriteClient`
- Context: `com.epam.aidial.evaluation.service.domain.job.MetricEvaluationContext`
- Client DTOs: `EvaluationRequestDto`, `EvaluationResponseDto`, `MetricOutputFieldDto`, `MetricErrorDto` in `client.metricprovider.dto`
- Config: `com.epam.aidial.evaluation.configuration.properties.MetricEvaluationProperties`
