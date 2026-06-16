# Metric Evaluation

## Purpose
This spec defines the in-process metric evaluation engine — a second execution phase within the test suite run lifecycle that evaluates configured metrics against test case run results by calling metric provider `/evaluate` endpoints, and writes the results as EvalSummary records.

Status: **Implemented**

## Key Terms
- **MetricEvaluationExecutor**: Interface for metric evaluation execution strategies (analogous to `EvaluationExecutor` for deployment evaluation). Supports both in-process execution and future K8s Job delegation.
- **InProcessMetricEvaluationExecutor**: In-process implementation of `MetricEvaluationExecutor`. Orchestrates paginated result iteration, concurrent metric evaluation dispatch, RunMetricSnapshot capture, and EvalSummary batch writing.
- **MetricEvaluationWorker**: Evaluates a single TSMD against a single test case result — resolves bindings, calls provider `/evaluate`, handles retry.
- **BindingResolver**: Resolves TSMD config/input bindings against test case data and extracted columns from a TestCaseRunResult.
- **EvalSummaryBatchWriteClient**: Service-layer wrapper that converts internal EvalSummary models to batch write DTOs and delegates to `EvalSummaryService.batchCreate()`, chunking items to respect the existing batch size limit.
- **RunMetricSnapshotBatchWriteClient**: Service-layer wrapper that converts internal RunMetricSnapshot models to batch write DTOs and delegates to `RunMetricSnapshotService.batchCreate()`.
- **MetricEvaluationContext**: Immutable context carrier for a metric evaluation run — carries computationId, aggregated TSMDs grouped by provider, semaphores, cancellation signal, retry config.

## Requirements

### Requirement: Aggregated TSMD bulk loading
The repository SHALL support loading aggregated TSMDs for a test suite. Two variants SHALL exist:
- `findAllAggregatedByTestSuiteId(testSuiteId)` — loads ALL TSMDs regardless of `is_enabled` / `is_valid` state (used by revalidation and the aggregated-definition endpoint)
- `findAllEnabledAndValidAggregatedByTestSuiteId(testSuiteId)` — loads only TSMDs where `is_enabled = true AND is_valid = true` (used by the metric evaluation phase)
Status: **Implemented**

#### Scenario: findAllAggregatedByTestSuiteId — all TSMDs
- **WHEN** `findAllAggregatedByTestSuiteId(testSuiteId)` is called
- **THEN** it SHALL execute a 3-table JOIN (test_suite_metric_definitions + metric_declarations + metric_declaration_versions) and return `List<AggregatedMetricDefinition>` with all fields populated including `declarationProviderId` and `metricDeclarationName` — regardless of `is_enabled` or `is_valid`

#### Scenario: findAllEnabledAndValidAggregatedByTestSuiteId — filtered
- **WHEN** `findAllEnabledAndValidAggregatedByTestSuiteId(testSuiteId)` is called
- **THEN** it SHALL return only TSMDs where `is_enabled = true AND is_valid = true`

#### Scenario: No TSMDs for suite
- **WHEN** the test suite has no TSMDs
- **THEN** both methods SHALL return an empty list

#### Scenario: Disabled TSMD excluded from evaluation load
- **WHEN** a TSMD has `is_enabled = false` and `is_valid = true`
- **THEN** `findAllEnabledAndValidAggregatedByTestSuiteId` SHALL NOT include it in the result

#### Scenario: Invalid TSMD excluded from evaluation load
- **WHEN** a TSMD has `is_enabled = true` and `is_valid = false`
- **THEN** `findAllEnabledAndValidAggregatedByTestSuiteId` SHALL NOT include it in the result

### Requirement: Metric evaluation executor orchestration
`MetricEvaluationExecutor` is an interface; `InProcessMetricEvaluationExecutor` is the in-process implementation (mirroring the `EvaluationExecutor` / `InProcessEvaluationExecutor` pattern for deployment evaluation). The implementation SHALL capture RunMetricSnapshots, iterate all `TestCaseRunResult` records for the run using cursor-based pagination, dispatch metric evaluations concurrently per provider, assemble EvalSummary records, and batch-write them to the analytics DB. The set of TSMDs loaded into `MetricEvaluationContext` SHALL be limited to those that are both enabled and valid (`is_enabled = true AND is_valid = true`).
Status: **Implemented**

#### Scenario: Successful metric evaluation for all test cases
- **WHEN** the metric evaluation phase starts for a completed run with TSMDs configured
- **THEN** the executor SHALL iterate all TestCaseRunResults for the run, evaluate all enabled+valid TSMDs for each SUCCESS result, merge outputs into EvalSummary records, and batch-write them to the analytics DB

#### Scenario: No TSMDs configured for suite
- **WHEN** the metric evaluation phase starts and the suite has no TSMDs
- **THEN** the executor SHALL skip metric evaluation entirely and return without writing any records

#### Scenario: All TSMDs disabled or invalid
- **WHEN** the suite has TSMDs but all are either `is_enabled = false` or `is_valid = false`
- **THEN** the executor SHALL skip metric evaluation entirely (empty TSMD list in context) and return without writing any records

#### Scenario: Cursor-paginated result iteration
- **WHEN** the executor iterates TestCaseRunResults
- **THEN** it SHALL use cursor-based pagination (filtering by runId) to avoid loading all results into memory

#### Scenario: Cross-result parallelism
- **WHEN** multiple test case results are being processed
- **THEN** the executor SHALL dispatch metric evaluations across results concurrently — the provider semaphore controls the total concurrent `/evaluate` calls per provider

### Requirement: Provider-bounded concurrency
Each metric provider SHALL have its own semaphore controlling the maximum number of concurrent `/evaluate` calls. The semaphore limit SHALL be configurable.
Status: **Implemented**

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
Status: **Implemented**

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
The `BindingResolver` SHALL resolve TSMD config and input bindings against test case data and extracted columns from a `TestCaseRunResult`, producing `Map<String, Object>` for config and input. Resolution SHALL fail fast when a binding references a column that does not exist in the data map.
Status: **Implemented**

#### Scenario: TestCase binding source
- **WHEN** a binding has `source: { $type: "TestCase", columnName: "expected" }` and the test case data contains `{"expected": "A planet"}`
- **THEN** the resolver SHALL produce `{"expected": "A planet"}` for that binding's property

#### Scenario: Response binding source
- **WHEN** a binding has `source: { $type: "Response", columnName: "model_answer" }` and the extracted columns contain `{"model_answer": "Earth is the third planet"}`
- **THEN** the resolver SHALL produce the value `"Earth is the third planet"` for that binding's property

#### Scenario: Constant binding source
- **WHEN** a binding has `source: { $type: "Constant", value: "gemini-2.5-flash-lite" }`
- **THEN** the resolver SHALL produce the literal value `"gemini-2.5-flash-lite"` for that binding's property

#### Scenario: Missing column in test case data fails fast
- **WHEN** a binding has `source: { $type: "TestCase", columnName: "score" }` and the test case `data` map does NOT contain the key `"score"` (i.e. `data.containsKey("score")` is false)
- **THEN** the resolver SHALL throw `IllegalArgumentException` with a message identifying the missing column and source type

#### Scenario: Missing column in extracted columns fails fast
- **WHEN** a binding has `source: { $type: "Response", columnName: "model_answer" }` and the extracted columns map does NOT contain the key `"model_answer"`
- **THEN** the resolver SHALL throw `IllegalArgumentException` with a message identifying the missing column and source type

#### Scenario: Present column with null value resolves to null
- **WHEN** a binding references a column that IS present in the data map (i.e. `data.containsKey(columnName)` is true) but its value is `null`
- **THEN** the resolver SHALL produce `null` for that binding's property (no exception thrown)

#### Scenario: Multiple bindings merged into single map
- **WHEN** input bindings contain `[{property: "actual", source: Response/model_answer}, {property: "ground_truth", source: TestCase/expected}]`
- **THEN** the resolver SHALL produce `{"actual": <value>, "ground_truth": <value>}`

### Requirement: EvaluationRequest construction
The system SHALL build an `EvaluationRequest` from resolved bindings and the metric declaration name.
Status: **Implemented**

#### Scenario: Request structure
- **WHEN** a metric evaluation request is constructed for TSMD "Accuracy" (declaration name: "exact_match") with resolved config `{}` and input `{"actual": "test", "ground_truth": "test"}`
- **THEN** the request SHALL be `{"metric_name": "exact_match", "config": {}, "input": {"actual": "test", "ground_truth": "test"}}`

#### Scenario: metric_name from declaration
- **WHEN** the request is built
- **THEN** `metric_name` SHALL be taken from `AggregatedMetricDefinition.metricDeclarationName` (the provider's metric name), NOT from the TSMD's display name

### Requirement: Output mapping to metricValues and metricInfos
The system SHALL map `EvaluationResponse` output fields to EvalSummary's `metricValues` and `metricInfos` JSONB columns, keyed by TSMD name. Output field names SHALL always come from the metric's actual output schema, never from synthetic placeholder keys.
Status: **Implemented**

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
- **WHEN** the `/evaluate` call for a TSMD fails with a transport error (HTTP 500, timeout, all retries exhausted) and the TSMD's output schema has field names `["recall", "precision", "f1", "mrr"]`
- **THEN** `metricValues[tsmdName]` SHALL contain `{"recall": null, "precision": null, "f1": null, "mrr": null}` (all output fields set to null) and `metricInfos[tsmdName]` SHALL contain `{"recall": {"error": "<exception message>"}, "precision": {"error": "<exception message>"}, "f1": {"error": "<exception message>"}, "mrr": {"error": "<exception message>"}}` (error entry per output field)

#### Scenario: Transport failure with empty output schema (fallback)
- **WHEN** the `/evaluate` call for a TSMD fails with a transport error AND the TSMD's output schema has no extractable field names (null, malformed, or empty properties)
- **THEN** `metricValues[tsmdName]` SHALL be an empty object `{}` and `metricInfos[tsmdName]` SHALL contain `{"error": "<exception message>"}`. The system SHALL log a WARN indicating the TSMD has an invalid output schema.

> **Clarifying note**: This fallback is the sole remaining exception to the per-field error format in `metricInfos`. It uses `{"error": "message"}` directly under the TSMD name (without a field-name wrapper) because no output field names are available. This case is defense-in-depth only — output schema validation (see tsmd-validation spec) prevents TSMDs with invalid schemas from entering evaluation under normal operation.

#### Scenario: Multiple TSMDs merged into single EvalSummary
- **WHEN** a test case has TSMDs "Accuracy" and "RAG Quality" both evaluated
- **THEN** `metricValues` SHALL contain keys for both TSMD names: `{"Accuracy": {...}, "RAG Quality": {...}}`

### Requirement: Output schema field extraction
The system SHALL provide an injectable `OutputSchemaFieldExtractor` component (in `service.domain`) that extracts output field names from a metric's output schema JSON string. This component SHALL be used by both the metric evaluation executor (to resolve field names for transport failure mapping) and the TSMD validation service (to validate output schema structure).
Status: **Implemented**

#### Scenario: Valid output schema with multiple fields
- **WHEN** `extractFieldNames()` is called with an output schema containing `{"properties": {"recall": {...}, "precision": {...}, "f1": {...}}}`
- **THEN** the method SHALL return `["recall", "precision", "f1"]`

#### Scenario: Valid output schema with single field
- **WHEN** `extractFieldNames()` is called with an output schema containing `{"properties": {"exact_match": {...}}}`
- **THEN** the method SHALL return `["exact_match"]`

#### Scenario: Null or blank schema string
- **WHEN** `extractFieldNames()` is called with a null or blank string
- **THEN** the method SHALL return an empty list

#### Scenario: Schema without properties key
- **WHEN** `extractFieldNames()` is called with a JSON string that has no `"properties"` key or where `"properties"` is not an object
- **THEN** the method SHALL return an empty list

#### Scenario: Malformed JSON schema
- **WHEN** `extractFieldNames()` is called with invalid JSON
- **THEN** the method SHALL log a WARN and return an empty list (graceful degradation)

### Requirement: Typed TSMD evaluation result carrier
The system SHALL replace the untyped `Map<String, Object>` (where values are `EvaluationResponseDto | Exception`) with a sealed interface `TsmdEvaluationResult` in `service.domain.job`. Both variants SHALL carry `outputFieldNames` (`List<String>`) extracted from the TSMD's output schema.
Status: **Implemented**

#### Scenario: Sealed interface with two variants
- **WHEN** a TSMD evaluation completes
- **THEN** the result SHALL be represented as either `TsmdEvaluationResult.Success(EvaluationResponseDto response, List<String> outputFieldNames)` or `TsmdEvaluationResult.Failure(Exception error, List<String> outputFieldNames)`

#### Scenario: Output field names extracted before evaluation dispatch
- **WHEN** the metric evaluation executor starts execution
- **THEN** it SHALL extract output field names for each TSMD using `OutputSchemaFieldExtractor` before dispatching async evaluations, and include them in every `TsmdEvaluationResult` (both success and failure)

#### Scenario: MetricOutputMapper consumes typed results
- **WHEN** `MetricOutputMapper.buildMetricValues()` and `buildMetricInfos()` are called
- **THEN** they SHALL accept `Map<String, TsmdEvaluationResult>` and use pattern matching on the sealed type (no `instanceof Object` checks)

#### Scenario: checkForErrors uses typed results
- **WHEN** `checkForErrors()` determines whether any TSMD evaluation failed
- **THEN** it SHALL accept `Map<String, TsmdEvaluationResult>` and check for `Failure` instances or `Success` instances containing error-type metric outputs

#### Scenario: TSMD with empty field names (defense-in-depth)
- **WHEN** a TSMD's output schema yields an empty field name list (should not happen after validation)
- **THEN** the output mapper SHALL produce an empty object `{}` in `metricValues` for that TSMD and record the error only in `metricInfos`

#### Scenario: Timeout fallback produces Failure with field names
- **WHEN** a TSMD evaluation times out and no result was recorded
- **THEN** the executor SHALL record a `Failure` with a `RuntimeException` and the pre-extracted output field names for that TSMD

### Requirement: EvalSummary assembly from TestCaseRunResult
The system SHALL build one EvalSummary per TestCaseRunResult, copying context fields from the result and adding computed metric values.
Status: **Implemented**

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
Status: **Implemented**

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
- **THEN** the executor SHALL set the cancellation signal, stop dispatching new evaluations, and log the error; `executor.shutdownNow()` will interrupt in-flight threads immediately via the `finally` block (no grace-period drain — metric evaluation is append-only)

### Requirement: RunMetricSnapshot writing via service-layer client
The `RunMetricSnapshotBatchWriteClient` SHALL convert internal RunMetricSnapshot models to the existing `RunMetricSnapshotBatchWriteRequestDto` and delegate to `RunMetricSnapshotService.batchCreate()`.
Status: **Implemented**

#### Scenario: Snapshot write delegates to existing service
- **WHEN** RunMetricSnapshots are written before metric evaluation starts
- **THEN** the `RunMetricSnapshotBatchWriteClient` SHALL convert models to `RunMetricSnapshotBatchWriteRequestDto` and call `RunMetricSnapshotService.batchCreate()`

### Requirement: RunMetricSnapshot capture before evaluation
The `InProcessMetricEvaluationExecutor` SHALL capture RunMetricSnapshots as its first action before dispatching any `/evaluate` calls. It uses the `computationId` and `computedAtMs` from the `MetricEvaluationContext` (generated by the job during context construction) to batch-write one `RunMetricSnapshot` per TSMD capturing the metric configuration at evaluation time.
Status: **Implemented**

#### Scenario: Snapshot fields populated from aggregated TSMD
- **WHEN** RunMetricSnapshots are created
- **THEN** each snapshot SHALL contain: `id` (new UUID), `computationId` (shared across all snapshots), `testSuiteRunId`, `tsmdId` = TSMD.id, `tsmdName` = TSMD.name, `metricDeclarationId`, `metricDeclarationVersionId`, `configBindings` = TSMD.configBindings, `inputBindings` = TSMD.inputBindings, `outputSchema` = TSMD.versionOutputSchema, `computedAtMs`

#### Scenario: Snapshots written before evaluation starts
- **WHEN** the metric evaluation executor starts execution
- **THEN** RunMetricSnapshots SHALL be written to the analytics DB BEFORE any `/evaluate` calls are made

### Requirement: MetricProviderClient evaluate method
The `MetricProviderClient` SHALL support calling `POST /evaluate` on metric providers.
Status: **Implemented**

#### Scenario: Successful evaluation call
- **WHEN** `MetricProviderClient.evaluate(providerId, request)` is called
- **THEN** it SHALL use the provider's RestClient (from `MetricProviderRestClientFactory`) to POST to `/evaluate` with the request body and return the parsed `EvaluationResponseDto`

#### Scenario: Provider not configured
- **WHEN** `evaluate()` is called with a providerId that has no configured RestClient
- **THEN** it SHALL throw `IllegalArgumentException`

### Requirement: Cancellation with hard shutdown during metric evaluation

Status: Implemented

The metric evaluation phase SHALL support cancellation via the same `AtomicBoolean` signal used by the deployment evaluation phase. The grace-period drain semantics are removed; Phase 2 uses hard shutdown (immediate thread interrupt) because metric evaluation is append-only and results can be regenerated.

#### Scenario: Cancellation stops new dispatches
- **WHEN** cancellation is signaled during metric evaluation
- **THEN** the executor SHALL stop dispatching new metric evaluation tasks

#### Scenario: Executor shutdown on any exit path
- **WHEN** the executor's `execute()` method exits (whether by normal completion, cancellation, or exception)
- **THEN** the executor SHALL call `executor.shutdownNow()` in the `finally` block unconditionally to release thread resources and interrupt any lingering in-flight threads (no grace-period drain — metric evaluation is append-only and results can be regenerated)

#### Scenario: Partial results preserved
- **WHEN** metric evaluation is cancelled
- **THEN** all EvalSummary records written before cancellation SHALL be preserved

### Requirement: Per-result timeout for TSMD evaluations

Status: Implemented

The metric evaluation executor SHALL enforce a per-result timeout: the maximum wall time to wait for all TSMD futures on a single `TestCaseRunResult` before marking timed-out TSMDs as FAILED.

This is analogous to `requestTimeoutMs` in deployment evaluation (Phase 1). It is a separate concern from cancellation — it fires in normal operation when a metric provider call is slow, not only when the run is being cancelled.

#### Scenario: All TSMDs complete within timeout
- **WHEN** all TSMD futures for a result complete within `metric-evaluation.per-result-timeout-ms`
- **THEN** the EvalSummary is assembled from actual TSMD responses without interruption

#### Scenario: Slow TSMD exceeds per-result timeout
- **WHEN** one or more TSMD futures for a result do not complete within `metric-evaluation.per-result-timeout-ms`
- **THEN** the executor SHALL cancel the remaining futures, record timed-out TSMDs as errors in the EvalSummary, and continue to the next result

#### Scenario: Per-result timeout default aligned with HTTP read timeout
- **WHEN** the application starts without explicit `metric-evaluation.per-result-timeout-ms` configuration
- **THEN** it SHALL default to 150 000 ms — matching the metric provider HTTP read timeout so the HTTP layer resolves stuck calls before the future timeout fires

### Requirement: Configurable retry for metric evaluation
The metric evaluation worker SHALL support configurable retry with exponential backoff for `/evaluate` calls.
Status: **Implemented**

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
Status: **Implemented**

#### Scenario: All properties with defaults
- **WHEN** the application starts
- **THEN** it SHALL read: `metric-evaluation.default-concurrency-per-provider` (default: 5), `metric-evaluation.batch-size` (default: 100), `metric-evaluation.per-result-timeout-ms` (default: 150000, configurable via env var `METRIC_EVAL_PER_RESULT_TIMEOUT_MS`), `metric-evaluation.retry.max-retries` (default: 0), `metric-evaluation.retry.retry-delay-ms` (default: 1000), `metric-evaluation.retry.retry-backoff-multiplier` (default: 2.0), `metric-evaluation.retry.max-retry-delay-ms` (default: 60000)

#### Scenario: cancellation-grace-period-ms is removed
- **WHEN** a deployment YAML contains `metric-evaluation.cancellation-grace-period-ms`
- **THEN** Spring Boot SHALL log an unknown-property warning but SHALL NOT fail startup; the property has no effect and operators should migrate to `metric-evaluation.per-result-timeout-ms`

#### Scenario: Per-result timeout configurable via environment variable
- **WHEN** `METRIC_EVAL_PER_RESULT_TIMEOUT_MS` is set in the environment
- **THEN** `metric-evaluation.per-result-timeout-ms` SHALL use that value

### Requirement: End-to-end functional test for full run lifecycle with metric evaluation
A single e2e functional test SHALL verify the complete run lifecycle: deployment evaluation (Phase 1) → metric evaluation (Phase 2), covering success and failure paths within one test flow. The test SHALL live in `TestSuiteRunFunctionalTests` (existing class already has `@MockitoBean` for `DialCoreDeploymentInvoker` and `MetricProviderClient`). Mock setup logic SHALL be extracted into a dedicated helper class to keep the test readable.
Status: **Implemented**

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
