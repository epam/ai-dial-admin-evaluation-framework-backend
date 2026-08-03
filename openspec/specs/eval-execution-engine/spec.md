# Eval Execution Engine

## Purpose
This spec defines the in-process evaluation execution engine that replaces the mock evaluation job. The engine resolves requests from templates, calls target deployment endpoints via DIAL Core, handles both streaming (SSE) and non-streaming HTTP responses, extracts response columns via JSONata, tracks retry attempts, and writes results to the analytics DB in batches. Supports configurable concurrency, retry policies, rate limiting (via Bucket4j), response size limits, and graceful cancellation.

Status: **Implemented**

## Key Terms
- **EvaluationExecutor**: Interface for execution strategies (`execute(EvaluationContext)`). Currently implemented by `InProcessEvaluationExecutor`; designed for future K8s Job extraction.
- **EvaluationContext**: Immutable context carrier for a run — carries runId, suiteId, execution settings, retry policy, cancellation signal, and JWT token.
- **EvaluationWorker**: Single test case execution logic — resolves request, calls endpoint, captures response, tracks retries, extracts columns, builds `TestCaseRunResult`.
- **StreamingResponseAccumulator**: Two-mode SSE accumulator — OpenAI chat-completions format assembly or `{"events":[...]}` envelope for custom SSE formats. Delegates SSE wire format parsing to `SseEventParser`.
- **ResultBatchWriter**: Thread-safe result buffer that flushes to analytics DB at configurable batch size thresholds, with SSE progress reporting.

## Requirements

### Requirement: Evaluation executor interface
The system SHALL define an `EvaluationExecutor` interface with a single `execute(EvaluationContext)` method. The `EvaluationContext` SHALL carry: `runId`, `testSuiteId`, execution settings (concurrency, timeout, retry, rate limit), a cancellation signal, a progress callback, and a result sink. This interface enables swapping in-process execution with K8s Job submission without changing orchestration code.
Status: **Implemented**

#### Scenario: In-process executor is the default
- **WHEN** the application starts with default configuration
- **THEN** the `InProcessEvaluationExecutor` bean SHALL be the active `EvaluationExecutor` implementation

#### Scenario: Executor receives fully populated context
- **WHEN** `TestSuiteEvaluationJob` dispatches a run
- **THEN** it SHALL construct an `EvaluationContext` from the run's `RunConfigDto` (with system defaults for omitted fields) and pass it to the executor. Context construction and cancellation signal registration SHALL occur before async dispatch to prevent race conditions.

### Requirement: In-process evaluation execution
The `InProcessEvaluationExecutor` SHALL read test inputs from the `test_case_run_inputs` table (populated at snapshot phase) in pages, dispatch execution tasks (one per test case per run index) bounded by the configured concurrency level, collect results, and flush them to analytics DB in batches. For legacy runs without a snapshot (no `test_case_run_inputs` rows), it SHALL fall back to reading live test cases from the suite.
Status: **Implemented**

#### Scenario: Snapshot path — pages from inputs table
- **WHEN** `test_case_run_inputs` rows exist for the run (snapshot phase committed)
- **THEN** the executor SHALL page through `testCaseRunInputRepository.findByRunId()` instead of `testCaseRepository.findEnabledValidByTestSuiteId()`. The `testCaseRepository` SHALL NOT be called.

#### Scenario: Legacy path — falls back to live test cases
- **WHEN** `test_case_run_inputs` rows do NOT exist for the run (legacy run without snapshot)
- **THEN** the executor SHALL fall back to paging through live test cases from `testCaseRepository.findEnabledValidByTestSuiteId()`, wrapping each `TestCase` as a `TestCaseRunInput` struct for uniform worker interface.

#### Scenario: Sequential execution (default)
- **WHEN** `concurrencyLevel` is 1 (default)
- **THEN** the executor SHALL process test case calls one at a time, in order (page by page, case by case, run index by run index)

#### Scenario: Parallel execution
- **WHEN** `concurrencyLevel` is greater than 1 (e.g., 10)
- **THEN** the executor SHALL process up to `concurrencyLevel` test case calls concurrently using a semaphore-bounded virtual thread executor

#### Scenario: All enabled and valid test cases are executed
- **WHEN** the executor runs for a suite with N enabled+valid test cases and `numberOfRuns = M`
- **THEN** the executor SHALL dispatch exactly N * M evaluation tasks (one per case per run index 0..M-1)

#### Scenario: Disabled and invalid test cases are skipped
- **WHEN** the suite contains test cases with `enabled = false` or `isValid = false`
- **THEN** those test cases SHALL NOT be dispatched for execution (they are excluded at snapshot phase)

#### Scenario: Test cases read in pages
- **WHEN** the suite has more enabled+valid test cases than fit in a single page
- **THEN** the executor SHALL read inputs using paginated queries

### Requirement: Single test case evaluation (worker)
The `EvaluationWorker` SHALL accept a `TestCaseRunInput` (carrying frozen test case data and optional overrides) plus an `EvaluationContext` (carrying snapshot fields). It SHALL resolve the request body using snapshot fields from context + test case data + per-case overrides (no DB read), call the target deployment endpoint, capture the response (including streaming), extract response columns, and build a `TestCaseRunResult`. Resolution SHALL be performed by injecting `RequestResolver` (from `evaluation-runner-core`) — not `ResolvedRequestService` (which remains in the EF backend and carries DB dependencies for the Try-It-Out path). `EvaluationWorker` itself now resides in the shared module, under package `com.epam.aidial.evaluation.runner.job`.
Status: **Implemented**

#### Scenario: Snapshot-based request resolution via RequestResolver
- **WHEN** a test case is dispatched for execution
- **THEN** the worker SHALL resolve the full request using `requestResolver.resolve(effectiveTemplate, effectiveBindings, testCaseData)` where:
  - `effectiveTemplate` = `input.getRequestTemplateOverride()` if non-null, else `context.getSnapshotRequestTemplate()`
  - `effectiveBindings` = `input.getInputBindingsOverride()` if non-null, else `context.getSnapshotInputBindings()`
  - `testCaseData` = deserialized from `input.getTestCaseData()`
  - `deploymentRef` and `endpointRef` are read from `context.getSnapshotDeploymentRef()` and `context.getSnapshotEndpointRef()`
  - The worker SHALL NOT call `resolveRequest(suiteId, tcId)` (no DB reads during execution) and SHALL NOT call `ResolvedRequestService.resolve(...)` — that method no longer exists; the equivalent logic lives on the injected `RequestResolver`

#### Scenario: EvaluationWorker injects RequestResolver, not ResolvedRequestService
- **WHEN** the shared module's `EvaluationWorker` bean is created
- **THEN** its constructor SHALL accept `RequestResolver` (from `runner.service`) as the resolution dependency — not `ResolvedRequestService` (which is an EF backend class)

#### Scenario: Package paths corrected for evaluation-runner-core move
- **WHEN** locating the classes referenced by this spec's "Implementation Notes" section (baseline `eval-execution-engine/spec.md`)
- **THEN** the following corrected fully-qualified names apply (superseding the baseline's now-stale paths):
  - `EvaluationWorker`: `com.epam.aidial.evaluation.runner.job.EvaluationWorker` (was `com.epam.aidial.evaluation.service.domain.job.EvaluationWorker`)
  - `EvaluationContext`: `com.epam.aidial.evaluation.runner.job.EvaluationContext` (was `com.epam.aidial.evaluation.service.domain.job.EvaluationContext`)
  - `StreamingResponseAccumulator`: `com.epam.aidial.evaluation.runner.job.StreamingResponseAccumulator` (was `com.epam.aidial.evaluation.service.domain.job.StreamingResponseAccumulator`)
  - `TestCaseRunResultFactory`: `com.epam.aidial.evaluation.runner.job.TestCaseRunResultFactory` (was `com.epam.aidial.evaluation.service.domain.job.TestCaseRunResultFactory`)
  - `ResultBatchWriter`: the name now denotes an interface at `com.epam.aidial.evaluation.runner.job.ResultBatchWriter` (`addResults(List<TestCaseRunResult>)`, `flush()`). The baseline's concrete `com.epam.aidial.evaluation.service.domain.job.ResultBatchWriter` class and its `ResultBatchWriterTransactional` collaborator no longer exist; they are replaced by `com.epam.aidial.evaluation.service.domain.job.PostgresResultBatchWriterFactory` (Spring bean, creates one writer per run) and `com.epam.aidial.evaluation.service.domain.job.PostgresResultBatchWriter` (plain per-run instance, not a Spring bean), both in the EF backend, implementing the shared interface
  - `EvaluationRunProperties`: `com.epam.aidial.evaluation.runner.config.properties.EvaluationRunProperties` (was `com.epam.aidial.evaluation.configuration.properties.testsuite.EvaluationRunProperties`)
  - `DeploymentInvocationResult`: `com.epam.aidial.evaluation.runner.client.dialcore.DeploymentInvocationResult` (was `com.epam.aidial.evaluation.client.dialcore.DeploymentInvocationResult`)
  - `EvaluationExecutor`, `InProcessEvaluationExecutor`, `ExecutionSettingsValidator` remain unmoved, in `com.epam.aidial.evaluation.service.domain.job` (EF backend) — these classes have DB dependencies and were not part of the shared-module extraction

### Requirement: Multi-turn dispatch and per-turn result emission
The worker that executes one run input SHALL return a list of results. When the input carries `multi_turn_data`, execution is delegated to the multi-turn turn loop, which emits one result per executed turn; otherwise the existing single-turn path is used and returns a single result. MCP inputs are unchanged. Each result carries `turn_index` and `total_turns` (single-turn = `0/1`).
Status: **Implemented**

#### Scenario: Multi-turn input yields per-turn results
- **WHEN** a run input has `multi_turn_data`
- **THEN** execution runs the turn loop and returns one `TestCaseRunResult` per executed turn

#### Scenario: Single-turn input is unchanged
- **WHEN** a run input has no `multi_turn_data`
- **THEN** the existing single-turn path runs and returns exactly one result with `turn_index=0, total_turns=1`

### Requirement: One concurrency permit per test-case run
The execution unit SHALL be the whole test-case run: turns of one multi-turn case run sequentially under a single concurrency permit, and progress is counted one unit per test-case run regardless of how many turn rows it writes.
Status: **Implemented**

#### Scenario: Progress counts test-case runs, not turns
- **WHEN** a multi-turn case writes N turn rows
- **THEN** run progress advances by one unit for that case, and the runnable-case count treats the multi-turn case as one unit

### Requirement: Snapshot phase
Before transitioning a run to RUNNING, the system SHALL execute a snapshot phase that freezes the suite configuration and test case data.
Status: **Implemented**

#### Scenario: Snapshot phase runs before RUNNING state
- **WHEN** a run is dispatched
- **THEN** the snapshot phase SHALL complete (committing `suite_snapshot` and `test_case_run_inputs` rows) before `status` is set to RUNNING

#### Scenario: Snapshot phase builds SuiteSnapshotDto
- **WHEN** the snapshot phase executes
- **THEN** `SuiteSnapshotBuilder.build(liveSuite)` SHALL produce a `SuiteSnapshotDto` with `snapshotVersion = "1"`, `suiteType`, and all execution-relevant fields. DEPLOYMENT suites: `deploymentRef`, `endpointRef`, `requestTemplate`, `inputBindings`, `responseColumns`, `testCaseSchema`. MCP_TOOL suites: `mcpDeploymentRef`, `toolRef`, `argumentTemplate`, `inputBindings`, `responseColumns`, `testCaseSchema`.

#### Scenario: Snapshot phase inserts test case inputs
- **WHEN** the snapshot phase executes
- **THEN** all enabled+valid test cases SHALL be read in pages and inserted as rows in `test_case_run_inputs`, preserving `testCaseId`, `testCaseName`, `testCaseData`, and per-case overrides (`requestTemplateOverride`, `inputBindingsOverride`)

#### Scenario: Snapshot failure marks run FAILED
- **WHEN** the snapshot phase throws an exception
- **THEN** the run SHALL be marked FAILED with an appropriate error code (`SNAPSHOT_FAILED`, `SNAPSHOT_SERIALIZATION_CONFLICT`, or `SNAPSHOT_SUITE_MISSING`)

#### Scenario: Snapshot serialization conflict retried
- **WHEN** the snapshot phase transaction fails with PostgreSQL `40001` (serialization failure)
- **THEN** the system SHALL retry up to 2 times; each retry deletes prior `test_case_run_inputs` for idempotency; on final failure the run is marked FAILED with `SNAPSHOT_SERIALIZATION_CONFLICT`

#### Scenario: Inconsistent snapshot guard
- **WHEN** run transitions to RUNNING and exactly one of `suite_snapshot` / `test_case_run_inputs` rows is present
- **THEN** the run SHALL be immediately marked FAILED with `SNAPSHOT_STATE_INCONSISTENT`

#### Scenario: Legacy run — synthesized snapshot
- **WHEN** a legacy run (created before snapshot feature; `suite_snapshot = null`) is executed
- **THEN** `TestSuiteEvaluationJob.buildContext()` SHALL call `SuiteSnapshotBuilder.build(liveSuite)` to synthesize a transient snapshot; if the live suite no longer exists, the run SHALL fail with `SNAPSHOT_SUITE_MISSING`

#### Scenario: Endpoint invocation (non-streaming)
- **WHEN** the resolved request is sent and the response `Content-Type` is NOT `text/event-stream`
- **THEN** the worker SHALL capture the full response body, HTTP status code, and timing (exec start, exec complete, duration)

#### Scenario: Endpoint invocation (streaming SSE)
- **WHEN** the resolved request is sent and the response `Content-Type` is `text/event-stream`
- **THEN** the worker SHALL accumulate SSE chunks via `StreamingResponseAccumulator`, assemble them into a complete response body (OpenAI chat-completions format), and record the assembled response

#### Scenario: Request timeout
- **WHEN** the endpoint does not respond within `requestTimeoutMs`
- **THEN** the worker SHALL abort the call, set `executionStatus = TIMEOUT`, record `responseBody = null`, `responseStatusCode = null`, and the elapsed time as `execDurationMs`

#### Scenario: Network error
- **WHEN** the endpoint call fails with a network-level error (connection refused, DNS failure, etc.)
- **THEN** the worker SHALL set `executionStatus = ERROR`, store the error message in `responseBody` as a JSON error envelope, and set `responseStatusCode = null`

#### Scenario: HTTP error from target (4xx/5xx)
- **WHEN** the endpoint returns an HTTP 4xx or 5xx status
- **THEN** the worker SHALL set `executionStatus = FAILED`, store the response body and status code as-is, and proceed (not retry unless retry is configured)

#### Scenario: Response column extraction
- **WHEN** a response body is captured (streaming or non-streaming)
- **THEN** the worker SHALL apply the suite's `responseColumns` definitions via `ResponseColumnExtractor`, storing extracted values in `extractedColumns` and any extraction failures in `extractionWarnings`

#### Scenario: Request body stored in results
- **WHEN** the worker builds a `TestCaseRunResult`
- **THEN** the worker SHALL serialize and store the resolved request body in `requestBody` (JSONB). The serialization SHALL use the existing `serializeBody()` method. `requestBody` SHALL be null only when request resolution itself fails (ERROR status before HTTP call).

#### Scenario: Retry count tracked in results
- **WHEN** the worker completes execution of a test case (with or without retries)
- **THEN** the result SHALL include `retryCount` set to the number of retry attempts made (0 if no retries occurred, N if N retries were attempted before the final outcome)

#### Scenario: Log details populated on retries
- **WHEN** the worker completes execution with `retryCount > 0`
- **THEN** the result SHALL include `logDetails` containing a structured log of retry attempts: `{"retryAttempts": [{"attemptIndex": 1, "statusCode": <int|null>, "errorType": "<HTTP_ERROR|TIMEOUT|NETWORK_ERROR>", "durationMs": <long>}, ...]}`. Each entry represents one failed attempt before the final result.

#### Scenario: Log details null when no retries
- **WHEN** the worker completes execution with `retryCount = 0`
- **THEN** `logDetails` SHALL be null (not an empty object or empty array)

### Requirement: Test case execution produces a traceable result
Each test case execution SHALL create an OpenTelemetry child span. The span's trace ID (32 lowercase hex characters) SHALL be stored as `TestCaseRunResult.traceId` and used as the correlation handle for the DIAL Core invocation. The span SHALL cover the entire execution including retries.
Status: **Implemented**

#### Scenario: traceId stored in result is OTel trace ID
- **WHEN** a test case executes successfully or fails
- **THEN** `TestCaseRunResult.traceId` SHALL be the 32-char lowercase hex OTel trace ID from the test case execution span (previously was a UUID with dashes)

#### Scenario: traceId stable across retries
- **WHEN** a test case execution is retried
- **THEN** `TestCaseRunResult.traceId` SHALL remain the same (the span wraps all retry attempts)

#### Scenario: Execution span attributes
- **WHEN** the test case execution span is created
- **THEN** it SHALL have span name `eval.testcase.execute`
- **AND** it SHALL have tags for `testcase.id` (UUID string) and `run.index` (int)

### Requirement: Streaming response accumulation
`StreamingResponseAccumulator` parses SSE event streams and assembles complete response bodies. It delegates SSE wire format parsing to `SseEventParser` and operates in two modes:

1. **OpenAI mode** — auto-detected when the first event has no named `event:` type (type is `"message"`) AND its data contains a `choices[]` array. Extracts `choices[0].delta.content` from each chunk, concatenates, and assembles a complete non-streaming chat-completions response.
2. **Structured SSE mode** — for all other streams. Wraps parsed events in a `{"events": [{"event": "<type>", "data": <payload>}, ...]}` envelope. Named events force this mode regardless of data payload structure.

Status: **Implemented**

#### Scenario: OpenAI chat-completions SSE format
- **WHEN** response stream contains events with no named `event:` type and data in format `{"choices":[{"delta":{"content":"..."}}]}`
- **THEN** the accumulator SHALL extract content deltas from each chunk, concatenate them, and assemble a complete response in non-streaming chat-completions format (with `message.content` instead of `delta.content`)

#### Scenario: Stream termination
- **WHEN** the stream contains `data: [DONE]`
- **THEN** the accumulator SHALL finalize the assembled response

#### Scenario: Stream error mid-accumulation
- **WHEN** the SSE stream is interrupted (connection drop, timeout) after receiving partial data
- **THEN** the accumulator SHALL set `executionStatus = ERROR` and store whatever was accumulated as `responseBody`

#### Scenario: Non-OpenAI streaming format fallback
- **WHEN** the response has `Content-Type: text/event-stream` but events do NOT follow OpenAI format (either named `event:` types are present, or data lacks `choices[].delta.content` structure)
- **THEN** the accumulator SHALL produce `responseBody` as a JSON object with `events` array: `{"events": [{"event": "<type>", "data": <payload>}, ...]}`. Each event's `event` field SHALL contain the SSE event type name (defaulting to `"message"` when absent). Each event's `data` field SHALL contain parsed JSON if the data payload is valid JSON, or a raw string if not.

#### Scenario: OpenAI mode detection with named events
- **WHEN** SSE stream has named `event:` types (e.g., `event: process_rules`)
- **THEN** accumulator SHALL use structured SSE mode regardless of data payload structure — named events are never treated as OpenAI format

#### Scenario: JSON array fallback enables JSONata extraction
- **WHEN** non-OpenAI SSE response is stored as `{"events": [...]}` envelope
- **THEN** JSONata `responseColumns` expressions SHALL be able to filter by event type (e.g., `events[event="process_rules"].data.evaluated_rule.status`), access last event (e.g., `events[-1].data`), or iterate all events (e.g., `events.data.result`). `DashjoinJsonataEvaluationService` MUST accept generic JSON input (supporting both JSON objects AND arrays at top level).

#### Scenario: Empty SSE stream produces empty envelope
- **WHEN** SSE stream has `Content-Type: text/event-stream` but contains no events (immediate EOF or only comments/empty lines)
- **THEN** accumulator SHALL produce `responseBody` as `{"events": []}` (empty envelope) with `executionStatus = SUCCESS`

#### Scenario: All events have non-JSON data in structured mode
- **WHEN** non-OpenAI SSE stream contains events where all data payloads are plain text (not valid JSON)
- **THEN** accumulator SHALL produce `{"events": [{"event": "<type>", "data": "<raw string>"}, ...]}` — each event's `data` is a JSON string value, not a parsed object

### Requirement: Response size limiting
The system SHALL enforce a configurable maximum response body size. Responses exceeding the limit SHALL be truncated with a warning recorded. Because `response_body` is a JSONB column, truncation MUST produce valid JSON — raw byte-level truncation is NOT allowed.
Status: **Implemented**

#### Scenario: Response within limit
- **WHEN** the response body is within `max-response-size-bytes` (default 5MB)
- **THEN** the full response body SHALL be stored

#### Scenario: Response exceeds limit (non-streaming)
- **WHEN** the non-streaming response body exceeds `max-response-size-bytes`
- **THEN** the worker SHALL store the response body as a JSON string containing the raw response text truncated at the byte limit (e.g., `"<truncated text>"`). This guarantees JSONB validity since a JSON string is always valid JSONB. `extractionWarnings` SHALL include a truncation warning with the original and truncated sizes. The `executionStatus` SHALL be set to `ERROR` (truncated response is incomplete data). Note: JSONata extraction on truncated content may produce partial/incorrect results — this is expected and communicated via the truncation warning.

#### Scenario: Streaming response exceeds limit
- **WHEN** accumulated SSE event data bytes exceed `max-response-size-bytes` during streaming (size tracked by `SseEventParser`)
- **THEN** the accumulator SHALL stop accumulating and set `executionStatus = ERROR`. For OpenAI mode: store the accumulated content as a truncated JSON string. For structured SSE mode: store the `{"events": [...]}` envelope with events accumulated before the limit was hit. A truncation warning SHALL be recorded in both cases.

### Requirement: Retry policy execution
When a `RetryPolicyDto` is configured with `maxRetries > 0`, the worker SHALL retry failed calls according to the policy.
Status: **Implemented**

#### Scenario: No retry configured (default)
- **WHEN** `retry` is null or `maxRetries = 0`
- **THEN** the worker SHALL NOT retry failed calls — a single failure is recorded immediately

#### Scenario: Retry on retryable failure
- **WHEN** a call fails with a retryable condition (timeout, network error, HTTP 429, HTTP 5xx) and `maxRetries > 0`
- **THEN** the worker SHALL retry up to `maxRetries` times with exponential backoff: delay = `min(retryDelayMs * retryBackoffMultiplier^(attemptIndex - 1), maxRetryDelayMs)` where `maxRetryDelayMs` is the system-configured cap on computed retry delay (default 60000ms from `test-suite-run.retry.max-retry-delay-ms`). This cap prevents exponential growth from producing unreasonable delays (e.g., multiplier=3, 10 retries → uncapped attempt 10 would be 5.5 hours).

#### Scenario: Non-retryable failure
- **WHEN** a call fails with HTTP 4xx (except 429 and 401/403)
- **THEN** the worker SHALL NOT retry — the result is recorded immediately with `executionStatus = FAILED`

#### Scenario: Authentication failure (401/403)
- **WHEN** a call fails with HTTP 401 or 403
- **THEN** the worker SHALL set `executionStatus = ERROR` (not FAILED — this is an infrastructure/auth issue, not a target endpoint error), store the response body and status code, and NOT retry (the JWT is likely expired and retries won't help). The run continues executing remaining cases per the continue-on-failure policy.

#### Scenario: All retries exhausted
- **WHEN** all retry attempts fail
- **THEN** the worker SHALL record the result from the last attempt (preserving the final HTTP status, response body, and execution timing from the last attempt)

#### Scenario: Retry respects cancellation
- **WHEN** the run is cancelled while a retry is pending (backoff sleep)
- **THEN** the worker SHALL abort the retry, record the last known result, and mark the in-flight result accordingly

### Requirement: Rate limiting
When `rateLimitRps` is configured, the executor SHALL limit the rate of outgoing HTTP calls to the specified requests-per-second across all concurrent workers in the same run, using Bucket4j token bucket algorithm.
Status: **Implemented**

#### Scenario: No rate limit (default)
- **WHEN** `rateLimitRps` is null
- **THEN** the executor SHALL NOT apply rate limiting — calls are dispatched as fast as concurrency allows

#### Scenario: Rate limit applied via Bucket4j
- **WHEN** `rateLimitRps` is configured (e.g., 5.0)
- **THEN** the executor SHALL create a Bucket4j `Bucket` with `Bandwidth.builder().capacity(tokens).refillGreedy(tokens, Duration.ofSeconds(1)).build()` (Bucket4j 8.x API) matching the configured RPS, and workers SHALL call `bucket.asBlocking().consume(1)` before each HTTP call

#### Scenario: Rate limit interacts with concurrency
- **WHEN** `concurrencyLevel = 10` and `rateLimitRps = 2.0`
- **THEN** even though 10 workers are available, new calls SHALL be throttled to 2 per second (most workers will be idle waiting for rate limit tokens)

#### Scenario: Retries acquire rate limit tokens
- **WHEN** a worker retries a failed call and `rateLimitRps` is configured
- **THEN** the retry attempt SHALL acquire a rate limit token before making the HTTP call, same as a first attempt. This prevents retry storms from bypassing the rate limiter after a burst of failures (e.g., 429 responses).

### Requirement: Batch result writing
The executor SHALL buffer completed `TestCaseRunResult` records and flush them to the analytics database in configurable batches. The executor SHALL perform exactly one final flush at the end of execution, after all virtual threads have terminated (or been interrupted via `shutdownNow()`) — eliminating the race where late-arriving worker writes land in a buffer already drained by the final flush.
Status: **Implemented**

#### Scenario: Batch flush on size
- **WHEN** the result buffer reaches `result-batch-size` (system config, default 100)
- **THEN** the executor SHALL flush the buffer to the analytics DB via `TestCaseRunResultRepository.saveAll()` in an analytics transaction

#### Scenario: Final flush on completion
- **WHEN** all test cases have been executed (run completes)
- **THEN** the executor SHALL flush any remaining buffered results — exactly once, AFTER worker shutdown has completed

#### Scenario: Flush on cancellation
- **WHEN** the run is cancelled and in-flight calls complete (or are interrupted)
- **THEN** the single final `flush(buffer)` at step (5) of the shutdown ordering window (see "No flush during shutdown ordering window" below) SHALL persist all accumulated results — real rows from completed workers plus any synthetic ERROR rows from worker exceptions that surfaced during the cancel window — before `execute()` returns to `TestSuiteEvaluationJob.executeRunAsync`, which then marks the run CANCELLED. There is NO separate cancellation-specific flush; cancellation reuses the same final-flush invocation as normal completion.

#### Scenario: No flush during shutdown ordering window
- **WHEN** the executor reaches its `finally` block
- **THEN** the order SHALL be: (1) `executor.shutdown()` (no new tasks), (2) wait for futures bounded by grace if cancelled or unbounded otherwise, (3) `executor.shutdownNow()` only if cancelled and futures still incomplete, (4) WARN log with unfinished count if cancelled, (5) single final `flush(buffer)`. Flushing before step (5) is forbidden — it re-introduces the race where a late `addResult` lands in a buffer that was already drained.

#### Scenario: Batch write failure
- **WHEN** a batch write to analytics DB fails
- **THEN** the executor SHALL set the cancellation signal, stop dispatching new calls, drain in-flight calls (up to grace period), log the error, and mark the run as FAILED with error category `INTERNAL` and code `ANALYTICS_WRITE_FAILED`. This prevents workers from continuing to execute and buffer results that can never be persisted.

### Requirement: Graceful cancellation
When a run is cancelled, the executor SHALL stop dispatching new calls, wait for in-flight calls to complete (up to a grace period bounded by `cancellationGracePeriodMs`), then abort remaining calls. The cancellation signal SHALL be registered before async dispatch to prevent race conditions. The `cancellationGracePeriodMs` value SHALL apply ONLY when `cancellationSignal == true`; it SHALL NOT be used as an overall evaluation timeout. A run that takes longer than the grace period without being cancelled SHALL continue to completion. The executor SHALL NOT synthesize result rows for cases interrupted by post-grace shutdown — see "Diagnostic logging for unfinished cases on cancel".
Status: **Implemented**

#### Scenario: Cancellation signal registered before async dispatch
- **WHEN** `TestSuiteRunService` triggers an evaluation run
- **THEN** `TestSuiteEvaluationJob.registerCancellationSignal(runId)` SHALL be called synchronously BEFORE calling the `@Async executeRunAsync(runId, token)` method. The `@Async` annotation means the entire `executeRunAsync()` body runs in the executor thread, so signal registration must happen in the caller's thread. `executeRunAsync()` SHALL retrieve the pre-registered signal from the `activeCancellationSignals` map. This prevents `interruptRun(runId)` from silently losing the cancellation if called before the async thread starts. If `executeRunAsync()` dispatch fails (exception, executor rejection), the caller SHALL clean up the registered signal to prevent map leaks.

#### Scenario: Cancellation stops new dispatches
- **WHEN** cancellation is signaled
- **THEN** the executor SHALL immediately stop submitting new test case calls to the worker pool

#### Scenario: Grace period applied only after cancellation
- **WHEN** cancellation is signaled and there are in-flight HTTP calls
- **THEN** the executor SHALL call `executor.shutdown()` (no new tasks accepted) and wait on `CompletableFuture.allOf(futures).get(cancellationGracePeriodMs, MILLISECONDS)` for in-flight workers to drain

#### Scenario: Long-running uncancelled run does NOT time out
- **WHEN** a run executes for longer than `cancellationGracePeriodMs` and `cancellationSignal` is NEVER set
- **THEN** the executor SHALL wait for all dispatched futures via unbounded `CompletableFuture.allOf(futures).join()` (no timeout). The run SHALL NOT be aborted simply because total wall-clock execution exceeded the grace-period value. Per-call wall-clock bounds remain the responsibility of `requestTimeoutMs` per test case.

#### Scenario: Abort after grace period
- **WHEN** the grace period expires and in-flight calls are still running
- **THEN** the executor SHALL call `executor.shutdownNow()` to interrupt remaining virtual threads
- **AND** the executor SHALL log the unfinished count once at WARN level (see "Diagnostic logging for unfinished cases on cancel")
- **AND** the executor SHALL NOT synthesize result rows for the unfinished cases — they remain absent from `test_case_run_results`

#### Scenario: Partial results preserved
- **WHEN** a run is cancelled at any point
- **THEN** all results that completed and were written to analytics DB before cancellation SHALL be preserved (not deleted)

#### Scenario: Cancellation signal cleanup
- **WHEN** the async run task completes (success, failure, or cancellation)
- **THEN** the cancellation signal SHALL be removed from the `cancellationSignals` map in a `finally`/`whenComplete` block

### Requirement: Synthetic ERROR result for worker exception
When a test case worker throws an unexpected exception (an exception that escapes the worker's own internal handling and reaches the executor's per-task `catch (Exception e)`), the executor SHALL emit a synthetic `TestCaseRunResult` with `executionStatus = ERROR` to the result buffer in best-effort fashion. This makes per-case worker bugs visible in the analytics surface — without it, a buggy worker silently drops cases. The synthesis is best-effort: if appending the synthetic row to the buffer itself fails, the executor SHALL log the secondary failure and continue without further fallback. The executor SHALL NOT chain additional retry / fallback layers beyond the single best-effort attempt — the JVM may be in any state, and the next safe action is to keep going so other cases can still finish.
Status: **Implemented**

#### Scenario: Worker throws unexpected exception
- **WHEN** the runnable inside `InProcessEvaluationExecutor` catches an exception escaping `evaluationWorker.execute(...)`
- **THEN** the executor SHALL build a synthetic `TestCaseRunResult` via `TestCaseRunResultFactory.errorResult(...)` with `executionStatus = ERROR`, `responseBody` set to a JSON error envelope `{"error":{"type":"<exception class name>","message":"<exception message>","origin":"executor"}}`, `responseStatusCode = null`, `execStartedAtMs = execCompletedAtMs = clock.millis()`, `execDurationMs = 0`, `retryCount = 0`, `logDetails = null`
- **AND** the executor SHALL pass that result to `resultBatchWriter.addResult(buffer, synthetic)` so it is persisted alongside other results

#### Scenario: Per-case error does not fail the run
- **WHEN** one or more synthetic `ERROR` rows are produced for a run because workers threw unexpected exceptions
- **THEN** the run SHALL still reach `COMPLETED` status (per-case errors do NOT mark the whole run FAILED)

#### Scenario: TestCaseRunResultFactory must not throw
- **WHEN** the executor invokes `TestCaseRunResultFactory.errorResult(...)`
- **THEN** the factory SHALL build the synthetic row from the input, the run index, the caught exception, and the current clock millis via fixed-shape construction — no template resolution, no JSON parsing of test case data, no DB access — guaranteeing it cannot throw and double-drop the case

#### Scenario: Buffer append failure is logged, not retried
- **WHEN** the inner `resultBatchWriter.addResult(buffer, synthetic)` call itself throws (e.g., the buffer's downstream batch flush triggered by reaching `result-batch-size` threshold fails)
- **THEN** the executor SHALL log the secondary failure at `ERROR` level (with the exception as last SLF4J argument) and continue. It SHALL NOT raise, SHALL NOT retry, and SHALL NOT make a second synthesis attempt.

#### Scenario: Broad catch is intentional
- **WHEN** static analysis or code review questions the broad `catch (Exception e)` in the worker runnable
- **THEN** the catch IS intentional and documented as the deliberate exception to AGENTS.md's "catch specific exceptions" rule. The contract of this catch is "any unexpected runtime failure escaping the worker" — narrowing it would re-introduce the silent-drop bug that this requirement exists to prevent. The catch SHALL remain `catch (Exception e)`.

### Requirement: Diagnostic logging for unfinished cases on cancel
When the post-grace cancellation path executes `executor.shutdownNow()`, the executor SHALL emit a single WARN log line naming the count of dispatched test case tasks whose futures had not completed at that point. This count is the authoritative diagnostic signal for "how many cases were interrupted by cancellation." The executor SHALL NOT synthesize result rows for these cases — absence of rows in `test_case_run_results`, combined with the run's `status = CANCELLED`, IS the signal.
Status: **Implemented**

#### Scenario: Unfinished count logged on cancel
- **WHEN** cancellation is signaled, the grace period elapses, and the executor calls `shutdownNow()`
- **THEN** the executor SHALL compute `unfinishedCount = futures.stream().filter(f -> !f.isDone()).count()` and emit one log line at WARN level: `"Run {runId} cancelled with {unfinishedCount} test case(s) interrupted before completion"`

#### Scenario: No synthetic rows for unfinished cases
- **WHEN** the cancellation grace period expires with unfinished workers
- **THEN** the executor SHALL NOT iterate the futures list to write synthetic `CANCELLED`, `INTERRUPTED`, or any other status row for those cases. The cases simply do not appear in `test_case_run_results`.

#### Scenario: Absence + run status carries the signal
- **WHEN** an operator inspects a run with `status = CANCELLED` and finds `count(test_case_run_results WHERE run_id = X)` < `numberOfTestCases × numberOfRuns`
- **THEN** the missing rows correspond to test cases that were either never dispatched (cancellation observed in dispatch loop) or interrupted by post-grace shutdown. The run's `status = CANCELLED` and the WARN log line are the authoritative explanation; no per-case row is required to convey this.

### Requirement: Catastrophic executor failures are rethrown
If an exception escapes the dispatch loop itself (e.g., `findByRunId` throws because the meta DB connection died, an OOM in path-resolution code), the executor SHALL best-effort flush the buffer and re-throw the original exception so `TestSuiteEvaluationJob.executeRunAsync` marks the run `FAILED` via its existing outer catch. The current code swallows such exceptions — that behaviour is removed.
Status: **Implemented**

#### Scenario: Dispatch-loop exception rethrown
- **WHEN** an exception escapes the dispatch loop (e.g., from `testCaseRunInputRepository.findByRunId`)
- **THEN** the executor's `catch (Exception e)` SHALL log the failure with the exception as last SLF4J argument, attempt one final `resultBatchWriter.flush(buffer)` inside a `try/catch` that logs and continues on failure, and then **re-throw** the original exception (unwrapped, no new exception class introduced)

#### Scenario: Run marked FAILED by outer catch
- **WHEN** the executor rethrows a catastrophic failure
- **THEN** `TestSuiteEvaluationJob.executeRunAsync`'s existing `catch (Exception e)` SHALL log it and call `repository.updateToFailed(runId, e.getMessage(), errorDetails, now, now)` with `code = "UNEXPECTED_ERROR"` and `category = INTERNAL` — preserving the existing error path

### Requirement: Progress reporting
The executor SHALL report execution progress at batch-flush boundaries via the existing SSE infrastructure.
Status: **Implemented**

#### Scenario: Progress event on batch flush
- **WHEN** a batch of results is flushed to analytics DB
- **THEN** the executor SHALL emit a progress notification via `TestSuiteRunSseService` containing: `completedCases` (number of results written so far), `totalCases` (total expected: numberOfTestCases * numberOfRuns)

#### Scenario: No progress events for empty batches
- **WHEN** a batch flush contains zero results (e.g., all calls pending)
- **THEN** no progress event SHALL be emitted

### Requirement: JWT token propagation to workers
The executor SHALL propagate the initiating user's JWT token to all worker threads for DIAL Core deployment calls.
Status: **Implemented**

#### Scenario: Token available in workers
- **WHEN** workers make HTTP calls to DIAL Core deployments
- **THEN** the user's JWT SHALL be available via `AuthorizationTokenHolder.getToken()` in the worker thread, propagated via `TokenPropagationHelper`

#### Scenario: Token captured before async dispatch
- **WHEN** the run is dispatched to the `@Async` executor
- **THEN** the token SHALL be captured in the calling thread (before `CompletableFuture.supplyAsync`) and propagated to the async thread and all worker threads spawned from it

### Requirement: Execution configuration system defaults and validation
The system SHALL define default and maximum values for all execution settings via `application.yml` properties. Per-run values in `RunConfigDto` SHALL be validated against these system maximums.
Status: **Implemented**

#### Scenario: System defaults applied when execution settings omitted
- **WHEN** `RunConfigDto.execution` is null
- **THEN** the executor SHALL use system defaults: `concurrencyLevel = 1`, `requestTimeoutMs = 30000`, `rateLimitRps = null`

#### Scenario: System defaults applied when retry omitted
- **WHEN** `RunConfigDto.retry` is null
- **THEN** the executor SHALL use system defaults: `maxRetries = 0` (no retry)

#### Scenario: Per-run value within system maximum
- **WHEN** `concurrencyLevel = 20` and system max is 50
- **THEN** the value SHALL be accepted

#### Scenario: Per-run value exceeds system maximum
- **WHEN** `concurrencyLevel = 100` and system max is 50
- **THEN** the service SHALL reject the run creation with HTTP 400 and error code `VALIDATION_ERROR`

#### Scenario: Configurable system properties
- **WHEN** the application starts
- **THEN** it SHALL read execution configuration from `test-suite-run.execution.*` and `test-suite-run.retry.*` property namespaces

### Requirement: URL and method resolution from endpoint contract
The worker SHALL construct the full request URL and HTTP method from the suite's `deploymentRef` and `endpointRef`, using `DialCoreUrlBuilder` for URL construction.
Status: **Implemented**

#### Scenario: Standard OpenAI endpoint
- **WHEN** `endpointRef.relativeUrlPattern` is `/chat/completions` and `deploymentRef.id` is `gpt-4`
- **THEN** the worker SHALL construct URL as `{dialCoreBaseUrl}/openai/deployments/gpt-4/chat/completions`

#### Scenario: Custom endpoint
- **WHEN** `endpointRef.relativeUrlPattern` is `/custom/predict`
- **THEN** the worker SHALL construct URL as `{dialCoreBaseUrl}/v1/deployments/{id}/route/custom/predict`

#### Scenario: HTTP method from endpointRef
- **WHEN** `endpointRef.method` is `POST`
- **THEN** the worker SHALL use POST as the HTTP method for the deployment call

### Requirement: Request headers and query parameters
The worker SHALL include resolved headers and query parameters from the request template in the deployment call.
Status: **Implemented**

#### Scenario: Template headers included
- **WHEN** the request template defines headers (e.g., `Content-Type: application/json`)
- **THEN** the worker SHALL include all resolved headers in the HTTP request, **except** headers on the system blacklist (see header blacklist requirement)

#### Scenario: Blacklisted headers filtered at call time
- **WHEN** the resolved request template includes headers that are on the system header blacklist (e.g., `Authorization`, `Host`, `Content-Length`, `Transfer-Encoding`, `Connection`, `traceparent`, `tracestate`)
- **THEN** the worker SHALL silently skip those headers and log a warning. The blacklist comparison SHALL be **case-insensitive** (HTTP headers are case-insensitive per RFC 7230 — e.g., `authorization`, `Authorization`, and `AUTHORIZATION` all match the `Authorization` blacklist entry). The blacklist is configurable via `test-suite-run.execution.header-blacklist`.

#### Scenario: Template query params included
- **WHEN** the request template defines query parameters
- **THEN** the worker SHALL append all resolved query parameters to the request URL

#### Scenario: Authorization header from JWT
- **WHEN** the worker makes a deployment call
- **THEN** the `Authorization: Bearer <jwt>` header SHALL be set automatically via the token propagation mechanism (not from user-provided headers)

### Requirement: Mock job replacement
The `TestSuiteEvaluationJob` SHALL delegate to `EvaluationExecutor` instead of performing mock sleep and fake result generation. All mock-specific components (`MockResultsGenerator`, `MockResponseBodyBuilder`, `MockResultsBatchWriter`, `MockRequestBodyBuilder`) SHALL be removed. The worker uses `ResolvedRequestService` directly for full request resolution. Custom utility methods (`resolveInt`, `resolveLong`, `resolveDouble`) SHALL be replaced with `ObjectUtils.defaultIfNull` from Apache Commons Lang.
Status: **Implemented**

#### Scenario: Job delegates to executor
- **WHEN** `TestSuiteEvaluationJob.executeRunAsync(runId)` is called
- **THEN** it SHALL construct an `EvaluationContext` from the run's configuration and call `evaluationExecutor.execute(context)`

#### Scenario: Mock components removed
- **WHEN** the real executor is implemented
- **THEN** `MockResultsGenerator`, `MockResultsBatchWriter`, `MockResponseBodyBuilder`, and `MockRequestBodyBuilder` SHALL be removed from the codebase

#### Scenario: Request resolution via ResolvedRequestService
- **WHEN** the worker resolves a test case request
- **THEN** it SHALL use `ResolvedRequestService.resolveRequest()` for full request resolution (URL, headers, query params, body). `MockRequestBodyBuilder` (which only resolves the body string) is deleted. Suites without a request template are prevented at validation time (`isValid = false`), so no runtime fallback is needed.

#### Scenario: Mock job configuration properties removed
- **WHEN** the real executor is implemented
- **THEN** `test-suite-run.mock-job.*` properties SHALL be removed and replaced by `test-suite-run.execution.*` properties

#### Scenario: Utility methods use established libraries
- **WHEN** the job resolves configuration values with defaults
- **THEN** it SHALL use `ObjectUtils.defaultIfNull(value, default)` from Apache Commons Lang instead of custom `resolveInt`/`resolveLong`/`resolveDouble` static methods

### Requirement: HTTP client factory for streaming support
The `DialCoreDeploymentInvokerConfiguration` SHALL use `JdkClientHttpRequestFactory` (backed by `java.net.http.HttpClient`) with HTTP/1.1 protocol version pinned, instead of relying on default HTTP/2 ALPN negotiation.
Status: **Implemented**

#### Scenario: JdkClientHttpRequestFactory used for deployment invoker
- **WHEN** the deployment invoker `RestClient` bean is created
- **THEN** it SHALL use `JdkClientHttpRequestFactory` as the request factory, enabling chunked/streaming response reading

#### Scenario: HTTP/1.1 protocol version pinned
- **WHEN** the `JdkClientHttpRequestFactory` is configured
- **THEN** the underlying `HttpClient` SHALL be built with `HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)` to avoid HTTP 505 errors from servers that do not support HTTP/2

#### Scenario: DialCoreClient unaffected
- **WHEN** the `DialCoreClient` (models/applications listing) bean is created
- **THEN** it MAY continue using `SimpleClientHttpRequestFactory` since it does not need streaming support

### Requirement: Streaming-aware deployment invocation
The `DialCoreDeploymentInvoker` SHALL expose an `invokeWithStreaming()` method that returns a `DeploymentInvocationResult` with streaming detection and raw `InputStream` access for SSE consumption. The method SHALL NOT accept a `Duration timeout` parameter (timeout is enforced at the worker level).
Status: **Implemented**

#### Scenario: invokeWithStreaming returns non-streaming result
- **WHEN** the endpoint returns a response with `Content-Type` that is NOT `text/event-stream`
- **THEN** `DeploymentInvocationResult.streaming` SHALL be `false`, `body` SHALL contain the parsed response, and `eventStream` SHALL be `null`

#### Scenario: invokeWithStreaming returns streaming result
- **WHEN** the endpoint returns a response with `Content-Type: text/event-stream`
- **THEN** `DeploymentInvocationResult.streaming` SHALL be `true`, `eventStream` SHALL contain the raw SSE `InputStream` for the caller to consume, and `body` SHALL be `null`

#### Scenario: Streaming consumption timeout
- **WHEN** the worker consumes a streaming response via `StreamingResponseAccumulator`
- **THEN** the worker SHALL enforce `requestTimeoutMs` as the **total elapsed time** from request start (including both header reception and stream consumption). If the total elapsed time exceeds `requestTimeoutMs`, the worker SHALL close the stream, set `executionStatus = TIMEOUT`, and store whatever was accumulated up to that point. This prevents slow-dripping SSE streams from occupying worker threads indefinitely. The `StreamingResponseAccumulator` SHALL accept a `deadlineMs` (absolute timestamp) and check it after each SSE event is read. To handle **stalled streams** (connection open but no data arriving), the accumulator SHALL wrap the blocking `InputStream` read in a mechanism that respects the deadline — e.g., running the accumulation inside `CompletableFuture.supplyAsync().get(remainingTimeout)` or setting a socket-level read timeout on the stream. This ensures the deadline is enforced even when `InputStream.read()` blocks indefinitely between events.

#### Scenario: DeploymentInvocationResult structure
- **WHEN** a deployment invocation completes
- **THEN** the result SHALL contain: `statusCode` (int), `streaming` (boolean), `body` (Object, nullable — parsed JSON for non-streaming), `eventStream` (InputStream, nullable — raw SSE for streaming), `responseHeaders` (HttpHeaders — response headers). This extends the existing `DeploymentInvocationResponse(statusCode, parsedBody)` pattern with streaming awareness.

#### Scenario: DeploymentInvocationResult resource lifecycle
- **WHEN** the worker receives a `DeploymentInvocationResult` with `streaming = true`
- **THEN** the worker SHALL use `DeploymentInvocationResult` in a try-with-resources block (the result implements `AutoCloseable`). The `close()` method SHALL close the underlying `eventStream`, releasing the HTTP connection back to the pool. This prevents connection leaks when the worker or accumulator throws before fully consuming the stream.

#### Scenario: Method signature without timeout
- **WHEN** `invokeWithStreaming()` is defined
- **THEN** it SHALL use parameters: `HttpMethod method`, `String path` (relative), `HttpHeaders headers`, `MultiValueMap<String, String> queryParams`, `Object body`. The `Duration timeout` parameter is removed — timeout enforcement is the worker's responsibility via `CompletableFuture.get(remainingTimeout)`.

### Requirement: X-Correlation-Id removed from eval execution header blacklist
The `X-Correlation-Id` header SHALL be removed from the evaluation run header blacklist. It is no longer generated by the eval worker; `traceparent` and `tracestate` SHALL be added to the blacklist instead.
Status: **Implemented**

#### Scenario: traceparent stripped from template headers
- **WHEN** a test suite's request template includes a `traceparent` header
- **THEN** `InProcessEvaluationExecutor` / `EvaluationWorker` SHALL strip it (via the header blacklist)
- **AND** the correct `traceparent` SHALL be injected by the `RestClient` interceptor

#### Scenario: X-Correlation-Id from template is forwarded
- **WHEN** a test suite's request template includes an `X-Correlation-Id` header
- **AND** `X-Correlation-Id` is not in the header blacklist
- **THEN** the template-provided value SHALL be included in the DIAL Core request as-is

### Requirement: Request resolution error handling
When the worker fails to resolve the request for a test case (e.g., binding variable missing, template parsing error), the worker SHALL record the failure and continue to the next test case.
Status: **Implemented**

#### Scenario: Resolution failure recorded as ERROR
- **WHEN** `ResolvedRequestService.resolveRequest()` throws an exception for a specific test case
- **THEN** the worker SHALL set `executionStatus = ERROR`, store the error message in `responseBody` as a JSON error envelope, set `responseStatusCode = null`, and continue to the next test case (consistent with "continue on failure" policy)

### Requirement: MCP tool evaluation in EvaluationWorker

The `EvaluationWorker` SHALL support MCP_TOOL suites by branching on suite type at the beginning of test case execution. For MCP suites, the worker SHALL resolve arguments, invoke the MCP tool, serialize the response, and feed the result into the shared extraction and result-building pipeline.
Status: **Implemented**

#### Scenario: MCP suite detected
- **WHEN** the `EvaluationContext` references an MCP_TOOL suite
- **THEN** the worker SHALL use the MCP execution flow: `McpRequestResolver` → `McpToolInvoker` → `McpResponseSerializer` → `ResponseColumnExtractor`

#### Scenario: DEPLOYMENT suite unchanged
- **WHEN** the `EvaluationContext` references a DEPLOYMENT suite
- **THEN** the worker SHALL use the existing HTTP execution flow: `ResolvedRequestService` → `RequestBodySerializer` → `DialCoreDeploymentInvoker` → `StreamingResponseAccumulator` → `ResponseColumnExtractor`

### Requirement: MCP argument resolution in worker

The worker SHALL resolve MCP tool arguments from the suite's argument template and effective input bindings using `McpRequestResolver`.
Status: **Implemented**

#### Scenario: Full argument resolution with effective bindings
- **WHEN** an MCP test case is dispatched for execution
- **THEN** the worker SHALL determine effective bindings: test case `inputBindingsOverride` (if non-null) takes priority over suite-level `context.getInputBindings()` (same override semantics as HTTP suites)
- **AND** resolve tool arguments using `McpRequestResolver.resolve(argumentTemplate, effectiveBindings, testCaseData)` where `argumentTemplate` is pre-loaded from `EvaluationContext` and `testCaseData` is parsed from the current test case

#### Scenario: Argument resolution failure
- **WHEN** `McpRequestResolver` fails to resolve arguments for a test case
- **THEN** the worker SHALL set `executionStatus = ERROR`, store the error message in `responseBody`, and continue to the next test case

### Requirement: MCP tool invocation in worker

The worker SHALL invoke the MCP tool via `McpToolInvoker` and handle the various outcomes. The worker SHALL determine the MCP transport from `mcpDeploymentRef.transport` (defaulting to `McpTransport.STREAMABLE_HTTP` when null) and pass it to `McpToolInvoker.callTool()`.
Status: **Implemented**

#### Scenario: Successful tool call (isError = false)
- **WHEN** the MCP tool call completes with `isError = false`
- **THEN** the worker SHALL serialize the `CallToolResult` via `McpResponseSerializer`
- **AND** set `executionStatus = SUCCESS`
- **AND** store the serialized JSON in `responseBody`
- **AND** set `responseStatusCode = 200`

#### Scenario: Tool-level error (isError = true)
- **WHEN** the MCP tool call completes with `isError = true`
- **THEN** the worker SHALL serialize the `CallToolResult` via `McpResponseSerializer`
- **AND** set `executionStatus = FAILED`
- **AND** store the serialized JSON in `responseBody`
- **AND** set `responseStatusCode = 200` (transport succeeded, tool reported error)

#### Scenario: MCP transport timeout
- **WHEN** the MCP tool call times out (exceeds `requestTimeoutMs`)
- **THEN** the worker SHALL set `executionStatus = TIMEOUT`, `responseBody = null`, `responseStatusCode = null`

#### Scenario: MCP transport error
- **WHEN** the MCP tool call fails with a network-level error
- **THEN** the worker SHALL set `executionStatus = ERROR`, store the error message in `responseBody`

#### Scenario: JSON-RPC error from DIAL Core
- **WHEN** DIAL Core returns a JSON-RPC error (e.g., tool not found, invalid arguments)
- **THEN** the worker SHALL set `executionStatus = ERROR`, store the JSON-RPC error details in `responseBody`

### Requirement: MCP request body stored in results

For MCP test cases, the resolved tool arguments SHALL be stored in `requestBody`.
Status: **Implemented**

#### Scenario: MCP requestBody content
- **WHEN** the worker builds a `TestCaseRunResult` for an MCP test case
- **THEN** `requestBody` SHALL contain a JSON object with the resolved tool arguments (e.g., `{"query": "What is MCP?", "limit": 10}`)
- **AND** `requestBody` SHALL be null only when argument resolution itself fails

### Requirement: MCP retry policy

The existing retry policy SHALL apply to MCP tool calls with MCP-specific retryable conditions.
Status: **Implemented**

#### Scenario: Retry on MCP transport failure
- **WHEN** an MCP tool call fails with a transport-level error (timeout, connection failure) and `maxRetries > 0`
- **THEN** the worker SHALL retry up to `maxRetries` times with exponential backoff (same formula as HTTP retries)

#### Scenario: Retry on JSON-RPC server error
- **WHEN** an MCP tool call fails with a JSON-RPC server error (error code -32000 to -32099) and `maxRetries > 0`
- **THEN** the worker SHALL retry (server errors are transient, similar to HTTP 5xx)

#### Scenario: No retry on tool-level isError
- **WHEN** an MCP tool call returns `isError = true` (tool completed but reported an error)
- **THEN** the worker SHALL NOT retry (tool-level errors are semantic, not transient)

#### Scenario: No retry on JSON-RPC invalid params
- **WHEN** an MCP tool call fails with JSON-RPC `InvalidParams` error (-32602)
- **THEN** the worker SHALL NOT retry (client error, similar to HTTP 4xx)

### Requirement: MCP rate limiting

The existing Bucket4j rate limiting SHALL apply to MCP tool calls identically to HTTP calls.
Status: **Implemented**

#### Scenario: MCP calls throttled by rate limiter
- **WHEN** `rateLimitRps` is configured and the suite is MCP_TOOL
- **THEN** each MCP tool call (including retries) SHALL acquire a rate limit token before execution

### Requirement: MCP response column extraction

After serializing the MCP response to JSON, the worker SHALL apply the suite's `responseColumns` via `ResponseColumnExtractor` — identical to the HTTP path.
Status: **Implemented**

#### Scenario: JSONata on MCP response
- **WHEN** an MCP test case completes with a serialized response body
- **THEN** the worker SHALL evaluate all `responseColumns` JSONata expressions against the serialized JSON
- **AND** store results in `extractedColumns` and failures in `extractionWarnings`

#### Scenario: MCP-specific extraction paths
- **WHEN** response columns use MCP-specific JSONata paths (e.g., `$.isError`, `$.content[0].text`, `$.structuredContent.results`)
- **THEN** the extraction SHALL work correctly because the serialized JSON preserves the MCP envelope structure

### Requirement: EvaluationWorker resides in `evaluation-runner-core`
The `EvaluationWorker` class SHALL reside in the `evaluation-runner-core` module under package `com.epam.aidial.evaluation.runner.job`. The EF backend's `InProcessEvaluationExecutor` SHALL inject it from the shared module.

Status: **Implemented**

#### Scenario: EvaluationWorker is a shared-module bean
- **WHEN** the EF backend application context starts
- **THEN** the `EvaluationWorker` bean SHALL originate from `evaluation-runner-core` (via `EvaluationRunnerAutoConfiguration`) and be injectable into `InProcessEvaluationExecutor` without the EF backend declaring it as a bean

#### Scenario: EvaluationWorker has no EF backend import
- **WHEN** ArchUnit's `RunnerModuleConstraintsTest` is run in the shared module
- **THEN** `EvaluationWorker` SHALL have no import from `com.epam.aidial.evaluation` (the EF backend package)

### Requirement: TestCaseRunResult type used by EvaluationWorker comes from `evaluation-runner-core`
The `TestCaseRunResult` type produced by `EvaluationWorker.execute(...)` and consumed by `InProcessEvaluationExecutor` and the EF backend's `PostgresResultBatchWriter` (implementing the shared module's `ResultBatchWriter` interface) SHALL be `com.epam.aidial.evaluation.runner.model.TestCaseRunResult`. The EF backend's analytics `TestCaseRunResultRecordMapper` SHALL map this shared type to the jOOQ-generated `TestCaseRunResultsRecord`.

Status: **Implemented**

#### Scenario: EvaluationWorker returns shared TestCaseRunResult
- **WHEN** `EvaluationWorker.execute(input, context, runIndex, responseColumns, traceId, execStartedAtMs)` completes
- **THEN** it SHALL return `List<com.epam.aidial.evaluation.runner.model.TestCaseRunResult>`

#### Scenario: InProcessEvaluationExecutor consumes shared TestCaseRunResult
- **WHEN** `InProcessEvaluationExecutor` collects results from `EvaluationWorker` (via `TestCaseRunner`)
- **THEN** it SHALL pass them to a `com.epam.aidial.evaluation.runner.job.ResultBatchWriter` instance (created per-run by `PostgresResultBatchWriterFactory`) whose `addResults(List<TestCaseRunResult>)` parameter is `List<com.epam.aidial.evaluation.runner.model.TestCaseRunResult>`

#### Scenario: Analytics RecordMapper maps from shared model
- **WHEN** `TestCaseRunResultRecordMapper.from(TestCaseRunResult result)` is called in the EF backend
- **THEN** it SHALL produce a `TestCaseRunResultsRecord` by reading fields from `com.epam.aidial.evaluation.runner.model.TestCaseRunResult` — no data conversion, only field mapping

## Implementation Notes
- Executor interface: `com.epam.aidial.evaluation.service.domain.job.EvaluationExecutor` (unmoved — EF backend)
- In-process executor: `com.epam.aidial.evaluation.service.domain.job.InProcessEvaluationExecutor` (unmoved — EF backend)
- Worker: `com.epam.aidial.evaluation.runner.job.EvaluationWorker` (moved to `evaluation-runner-core`)
- Context: `com.epam.aidial.evaluation.runner.job.EvaluationContext` (moved to `evaluation-runner-core`)
- Streaming accumulator: `com.epam.aidial.evaluation.runner.job.StreamingResponseAccumulator` (moved to `evaluation-runner-core`)
- Result factory: `com.epam.aidial.evaluation.runner.job.TestCaseRunResultFactory` (moved to `evaluation-runner-core`)
- Batch writer interface: `com.epam.aidial.evaluation.runner.job.ResultBatchWriter` (`addResults(List<TestCaseRunResult>)`, `flush()` — moved to `evaluation-runner-core` as a DB-free interface)
- Postgres batch writer: `com.epam.aidial.evaluation.service.domain.job.PostgresResultBatchWriter` (plain per-run instance) and `com.epam.aidial.evaluation.service.domain.job.PostgresResultBatchWriterFactory` (Spring bean) — EF backend, implement the shared `ResultBatchWriter` interface; supersede the baseline's `ResultBatchWriter`/`ResultBatchWriterTransactional` classes, which no longer exist
- Settings validator: `com.epam.aidial.evaluation.service.domain.job.ExecutionSettingsValidator` (unmoved — EF backend)
- Config properties: `com.epam.aidial.evaluation.runner.config.properties.EvaluationRunProperties` (moved to `evaluation-runner-core`)
- Deployment invoker result: `com.epam.aidial.evaluation.runner.client.dialcore.DeploymentInvocationResult` (moved to `evaluation-runner-core`)
- Request resolution: `com.epam.aidial.evaluation.runner.service.RequestResolver` (`evaluation-runner-core`) — `TurnLoopExecutor` injects it directly; the EF backend's `com.epam.aidial.evaluation.service.domain.ResolvedRequestService` retains only the DB-backed Try-It-Out overload (`resolveRequest(UUID, UUID)`) and delegates to the injected `RequestResolver`
- Modified: `EvaluationWorker` — add suite type branching at `execute()` entry point. MCP retry logic reuses the existing `invokeWithRetries()` method by extracting the inner execution logic into a strategy function. MCP transport propagation: reads `mcpDeploymentRef.transport`, defaults to `STREAMABLE_HTTP` when null, passes to `McpToolInvoker.callTool()`.
- Modified: `EvaluationContext` — carries `suiteType` and deserialized MCP-specific references (`mcpDeploymentRef`, `toolRef`, `argumentTemplate`, `inputBindings`) loaded from the suite at run initialization time.
- MCP field loading chain: `TestSuiteEvaluationJob` deserializes MCP fields from the suite's JSONB strings and passes them into `EvaluationContext.builder()` as typed objects (conditionally for `MCP_TOOL` suites only). `inputBindings` is loaded alongside other MCP fields.
- MCP effective bindings: `EvaluationWorker.invokeMcpSingle()` determines effective bindings per test case — `testCase.inputBindingsOverride` (if non-null) takes priority over `context.getInputBindings()`. Effective bindings are passed to `McpRequestResolver.resolve()`.
- DTOs: `ExecutionSettingsDto`, `RetryPolicyDto` (in `service.domain.dto`)
