## ADDED Requirements

### Requirement: Evaluation executor interface
The system SHALL define an `EvaluationExecutor` interface with a single `execute(EvaluationContext)` method. The `EvaluationContext` SHALL carry: `runId`, `testSuiteId`, execution settings (concurrency, timeout, retry, rate limit), a cancellation signal, a progress callback, and a result sink. This interface enables swapping in-process execution with K8s Job submission without changing orchestration code.

#### Scenario: In-process executor is the default
- **WHEN** the application starts with default configuration
- **THEN** the `InProcessEvaluationExecutor` bean SHALL be the active `EvaluationExecutor` implementation

#### Scenario: Executor receives fully populated context
- **WHEN** `TestSuiteEvaluationJob` dispatches a run
- **THEN** it SHALL construct an `EvaluationContext` from the run's `RunConfigDto` (with system defaults for omitted fields) and pass it to the executor

### Requirement: In-process evaluation execution
The `InProcessEvaluationExecutor` SHALL read enabled and valid test cases from the suite in pages, dispatch execution tasks (one per test case per run index) bounded by the configured concurrency level, collect results, and flush them to analytics DB in batches.

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
- **THEN** those test cases SHALL NOT be dispatched for execution

#### Scenario: Test cases read in pages
- **WHEN** the suite has more enabled+valid test cases than fit in a single page
- **THEN** the executor SHALL read test cases using paginated repository queries (reusing existing `findAllEnabledAndValid` with pagination)

### Requirement: Single test case evaluation (worker)
The `EvaluationWorker` SHALL resolve the request body, call the target deployment endpoint, capture the response (including streaming), extract response columns, and build a `TestCaseRunResult`.

#### Scenario: Full request resolution
- **WHEN** a test case is dispatched for execution
- **THEN** the worker SHALL resolve the full request (URL, headers, query params, body) using `ResolvedRequestService.resolveRequest()` (template + bindings + test case data, with per-case overrides). Suites without a request template are prevented at validation time (`isValid = false`), so the worker can always rely on a valid resolved request.

#### Scenario: Endpoint invocation (non-streaming)
- **WHEN** the resolved request is sent and the response `Content-Type` is NOT `text/event-stream`
- **THEN** the worker SHALL capture the full response body, HTTP status code, and timing (exec start, exec complete, duration). `timeToFirstTokenMs` and `timeToLastTokenMs` SHALL both be set to `execDurationMs` (the entire response arrives at once — first token and last token are the same event)

#### Scenario: Endpoint invocation (streaming SSE)
- **WHEN** the resolved request is sent and the response `Content-Type` is `text/event-stream`
- **THEN** the worker SHALL accumulate SSE chunks via `StreamingResponseAccumulator`, assemble them into a complete response body (OpenAI chat-completions format), capture `timeToFirstTokenMs` and `timeToLastTokenMs`, and record the assembled response

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

#### Scenario: Trace ID generation
- **WHEN** a test case call is made
- **THEN** the worker SHALL generate a unique `traceId` (UUID) per call and propagate it as `X-Correlation-Id` header to the deployment

### Requirement: Streaming response accumulation
The `StreamingResponseAccumulator` SHALL parse OpenAI-compatible SSE event streams, accumulate content deltas, assemble a complete response body, and capture streaming timing metrics.

#### Scenario: OpenAI chat-completions SSE format
- **WHEN** the response stream contains events in format `data: {"choices":[{"delta":{"content":"..."}}]}\n\n`
- **THEN** the accumulator SHALL extract content deltas from each chunk, concatenate them, and assemble a complete response in the non-streaming chat-completions format (with `message.content` instead of `delta.content`)

#### Scenario: Stream termination
- **WHEN** the stream contains `data: [DONE]`
- **THEN** the accumulator SHALL finalize the assembled response. Note: `timeToLastTokenMs` is already recorded at the last content delta (see "Time to last token" scenario) — the `[DONE]` marker only signals finalization, not a timing event.

#### Scenario: Time to first token
- **WHEN** the first SSE event with content delta is received
- **THEN** the accumulator SHALL record `timeToFirstTokenMs` as the elapsed time since the request was sent

#### Scenario: Time to last token
- **WHEN** the last SSE event with content delta is received (before `[DONE]`)
- **THEN** the accumulator SHALL record `timeToLastTokenMs` as the elapsed time since the request was sent

#### Scenario: Stream error mid-accumulation
- **WHEN** the SSE stream is interrupted (connection drop, timeout) after receiving partial data
- **THEN** the accumulator SHALL set `executionStatus = ERROR`, store whatever was accumulated as `responseBody`, and record `timeToLastTokenMs` at the point of failure

#### Scenario: Non-OpenAI streaming format fallback
- **WHEN** the response has `Content-Type: text/event-stream` but events do not follow OpenAI format (no `choices[].delta.content` structure)
- **THEN** the accumulator SHALL store ALL `data:` payloads as a JSON array in `responseBody`. Each payload SHALL be parsed as JSON if valid, or stored as a JSON string if not. Example: `[{"text":"chunk1"},{"text":"chunk2"}]` or `["Hello","world"]`. `timeToFirstTokenMs`/`timeToLastTokenMs` SHALL be based on first/last data events.

#### Scenario: JSON array fallback enables JSONata extraction
- **WHEN** a non-OpenAI SSE response is stored as a JSON array
- **THEN** JSONata `responseColumns` expressions SHALL be able to extract from the array (e.g., `$[-1].result` for last event, `$[event='done'].answer`, `$.text` for all text values). This requires `DashjoinJsonataEvaluationService` to accept generic JSON input (not just objects) — the evaluator SHALL parse the response body as `Object` (supporting both JSON objects and arrays at the top level).

### Requirement: Response size limiting
The system SHALL enforce a configurable maximum response body size. Responses exceeding the limit SHALL be truncated with a warning recorded. Because `response_body` is a JSONB column, truncation MUST produce valid JSON — raw byte-level truncation is NOT allowed.

#### Scenario: Response within limit
- **WHEN** the response body is within `max-response-size-bytes` (default 5MB)
- **THEN** the full response body SHALL be stored

#### Scenario: Response exceeds limit (non-streaming)
- **WHEN** the non-streaming response body exceeds `max-response-size-bytes`
- **THEN** the worker SHALL store the response body as a JSON string containing the raw response text truncated at the byte limit (e.g., `"<truncated text>"`). This guarantees JSONB validity since a JSON string is always valid JSONB. `extractionWarnings` SHALL include a truncation warning with the original and truncated sizes. The `executionStatus` SHALL be set to `ERROR` (truncated response is incomplete data). Note: JSONata extraction on truncated content may produce partial/incorrect results — this is expected and communicated via the truncation warning.

#### Scenario: Streaming response exceeds limit
- **WHEN** accumulated SSE content exceeds `max-response-size-bytes` during streaming
- **THEN** the accumulator SHALL stop accumulating, close the stream, and store the response as a JSON string of the accumulated content truncated at the limit (guaranteeing JSONB validity). A truncation warning SHALL be recorded. The `executionStatus` SHALL be set to `ERROR`. For OpenAI mode, the assembled structured response is abandoned in favor of the truncated string — partial JSON object assembly would produce invalid JSONB.

### Requirement: Retry policy execution
When a `RetryPolicyDto` is configured with `maxRetries > 0`, the worker SHALL retry failed calls according to the policy.

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
When `rateLimitRps` is configured, the executor SHALL limit the rate of outgoing HTTP calls to the specified requests-per-second across all concurrent workers in the same run.

#### Scenario: No rate limit (default)
- **WHEN** `rateLimitRps` is null
- **THEN** the executor SHALL NOT apply rate limiting — calls are dispatched as fast as concurrency allows

#### Scenario: Rate limit applied
- **WHEN** `rateLimitRps` is configured (e.g., 5.0)
- **THEN** the executor SHALL ensure no more than `rateLimitRps` HTTP calls are initiated per second across all workers, using a token bucket or similar algorithm

#### Scenario: Rate limit interacts with concurrency
- **WHEN** `concurrencyLevel = 10` and `rateLimitRps = 2.0`
- **THEN** even though 10 workers are available, new calls SHALL be throttled to 2 per second (most workers will be idle waiting for rate limit tokens)

#### Scenario: Retries acquire rate limit tokens
- **WHEN** a worker retries a failed call and `rateLimitRps` is configured
- **THEN** the retry attempt SHALL acquire a rate limit token before making the HTTP call, same as a first attempt. This prevents retry storms from bypassing the rate limiter after a burst of failures (e.g., 429 responses).

### Requirement: Batch result writing
The executor SHALL buffer completed `TestCaseRunResult` records and flush them to the analytics database in configurable batches.

#### Scenario: Batch flush on size
- **WHEN** the result buffer reaches `result-batch-size` (system config, default 100)
- **THEN** the executor SHALL flush the buffer to the analytics DB via `TestCaseRunResultRepository.saveAll()` in an analytics transaction

#### Scenario: Final flush on completion
- **WHEN** all test cases have been executed (run completes)
- **THEN** the executor SHALL flush any remaining buffered results

#### Scenario: Flush on cancellation
- **WHEN** the run is cancelled and in-flight calls complete (or are aborted)
- **THEN** the executor SHALL flush all accumulated results before marking the run as CANCELLED

#### Scenario: Batch write failure
- **WHEN** a batch write to analytics DB fails
- **THEN** the executor SHALL set the cancellation signal, stop dispatching new calls, drain in-flight calls (up to grace period), log the error, and mark the run as FAILED with error category `INTERNAL` and code `ANALYTICS_WRITE_FAILED`. This prevents workers from continuing to execute and buffer results that can never be persisted.

### Requirement: Graceful cancellation
When a run is cancelled, the executor SHALL stop dispatching new calls, wait for in-flight calls to complete (up to a grace period), then abort remaining calls.

#### Scenario: Cancellation stops new dispatches
- **WHEN** cancellation is signaled
- **THEN** the executor SHALL immediately stop submitting new test case calls to the worker pool

#### Scenario: Grace period for in-flight calls
- **WHEN** cancellation is signaled and there are in-flight HTTP calls
- **THEN** the executor SHALL wait up to `cancellationGracePeriodMs` (system config, default 30000ms) for in-flight calls to complete naturally

#### Scenario: Abort after grace period
- **WHEN** the grace period expires and in-flight calls are still running
- **THEN** the executor SHALL interrupt/abort the remaining calls

#### Scenario: Partial results preserved
- **WHEN** a run is cancelled at any point
- **THEN** all results written to analytics DB before cancellation SHALL be preserved (not deleted)

### Requirement: Progress reporting
The executor SHALL report execution progress at batch-flush boundaries via the existing SSE infrastructure.

#### Scenario: Progress event on batch flush
- **WHEN** a batch of results is flushed to analytics DB
- **THEN** the executor SHALL emit a progress notification via `TestSuiteRunSseService` containing: `completedCases` (number of results written so far), `totalCases` (total expected: numberOfTestCases * numberOfRuns)

#### Scenario: No progress events for empty batches
- **WHEN** a batch flush contains zero results (e.g., all calls pending)
- **THEN** no progress event SHALL be emitted

### Requirement: JWT token propagation to workers
The executor SHALL propagate the initiating user's JWT token to all worker threads for DIAL Core deployment calls.

#### Scenario: Token available in workers
- **WHEN** workers make HTTP calls to DIAL Core deployments
- **THEN** the user's JWT SHALL be available via `AuthorizationTokenHolder.getToken()` in the worker thread, propagated via `TokenPropagationHelper`

#### Scenario: Token captured before async dispatch
- **WHEN** the run is dispatched to the `@Async` executor
- **THEN** the token SHALL be captured in the calling thread (before `CompletableFuture.supplyAsync`) and propagated to the async thread and all worker threads spawned from it

### Requirement: Execution configuration system defaults and validation
The system SHALL define default and maximum values for all execution settings via `application.yml` properties. Per-run values in `RunConfigDto` SHALL be validated against these system maximums.

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

#### Scenario: Template headers included
- **WHEN** the request template defines headers (e.g., `Content-Type: application/json`)
- **THEN** the worker SHALL include all resolved headers in the HTTP request, **except** headers on the system blacklist (see header blacklist requirement)

#### Scenario: Blacklisted headers filtered at call time
- **WHEN** the resolved request template includes headers that are on the system header blacklist (e.g., `Authorization`, `Host`, `Content-Length`, `Transfer-Encoding`, `Connection`, `X-Correlation-Id`)
- **THEN** the worker SHALL silently skip those headers and log a warning. The blacklist comparison SHALL be **case-insensitive** (HTTP headers are case-insensitive per RFC 7230 — e.g., `authorization`, `Authorization`, and `AUTHORIZATION` all match the `Authorization` blacklist entry). The blacklist is configurable via `test-suite-run.execution.header-blacklist`.

#### Scenario: Template query params included
- **WHEN** the request template defines query parameters
- **THEN** the worker SHALL append all resolved query parameters to the request URL

#### Scenario: Authorization header from JWT
- **WHEN** the worker makes a deployment call
- **THEN** the `Authorization: Bearer <jwt>` header SHALL be set automatically via the token propagation mechanism (not from user-provided headers)

### Requirement: Mock job replacement
The `TestSuiteEvaluationJob` SHALL delegate to `EvaluationExecutor` instead of performing mock sleep and fake result generation. All mock-specific components (`MockResultsGenerator`, `MockResponseBodyBuilder`, `MockResultsBatchWriter`, `MockRequestBodyBuilder`) SHALL be removed. The worker uses `ResolvedRequestService` directly for full request resolution; the fallback-to-data logic (when no template exists) lives in the worker.

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

### Requirement: HTTP client factory for streaming support
The `DialCoreDeploymentInvokerConfiguration` SHALL use `JdkClientHttpRequestFactory` (backed by `java.net.http.HttpClient`) instead of `SimpleClientHttpRequestFactory` to support streaming SSE response reading.

#### Scenario: JdkClientHttpRequestFactory used for deployment invoker
- **WHEN** the deployment invoker `RestClient` bean is created
- **THEN** it SHALL use `JdkClientHttpRequestFactory` as the request factory, enabling chunked/streaming response reading and per-request timeouts

#### Scenario: DialCoreClient unaffected
- **WHEN** the `DialCoreClient` (models/applications listing) bean is created
- **THEN** it MAY continue using `SimpleClientHttpRequestFactory` since it does not need streaming support

### Requirement: Streaming-aware deployment invocation
The `DialCoreDeploymentInvoker` SHALL expose an `invokeWithStreaming()` method that returns a `DeploymentInvocationResult` with streaming detection and raw `InputStream` access for SSE consumption.

#### Scenario: invokeWithStreaming returns non-streaming result
- **WHEN** the endpoint returns a response with `Content-Type` that is NOT `text/event-stream`
- **THEN** `DeploymentInvocationResult.streaming` SHALL be `false`, `body` SHALL contain the parsed response, and `eventStream` SHALL be `null`

#### Scenario: invokeWithStreaming returns streaming result
- **WHEN** the endpoint returns a response with `Content-Type: text/event-stream`
- **THEN** `DeploymentInvocationResult.streaming` SHALL be `true`, `eventStream` SHALL contain the raw SSE `InputStream` for the caller to consume, and `body` SHALL be `null`

#### Scenario: Per-call timeout (header reception)
- **WHEN** `invokeWithStreaming()` is called with a `Duration timeout` parameter
- **THEN** the HTTP call SHALL use that timeout for header reception (JDK HttpClient supports per-request timeout natively). Note: this timeout covers time until response headers are received, NOT the time to fully consume a streaming body.

#### Scenario: Streaming consumption timeout
- **WHEN** the worker consumes a streaming response via `StreamingResponseAccumulator`
- **THEN** the worker SHALL enforce `requestTimeoutMs` as the **total elapsed time** from request start (including both header reception and stream consumption). If the total elapsed time exceeds `requestTimeoutMs`, the worker SHALL close the stream, set `executionStatus = TIMEOUT`, and store whatever was accumulated up to that point. This prevents slow-dripping SSE streams from occupying worker threads indefinitely. The `StreamingResponseAccumulator` SHALL accept a `deadlineMs` (absolute timestamp) and check it after each SSE event is read. To handle **stalled streams** (connection open but no data arriving), the accumulator SHALL wrap the blocking `InputStream` read in a mechanism that respects the deadline — e.g., running the accumulation inside `CompletableFuture.supplyAsync().get(remainingTimeout)` or setting a socket-level read timeout on the stream. This ensures the deadline is enforced even when `InputStream.read()` blocks indefinitely between events.

#### Scenario: DeploymentInvocationResult structure
- **WHEN** a deployment invocation completes
- **THEN** the result SHALL contain: `statusCode` (int), `streaming` (boolean), `body` (Object, nullable — parsed JSON for non-streaming), `eventStream` (InputStream, nullable — raw SSE for streaming), `responseHeaders` (HttpHeaders — response headers). This extends the existing `DeploymentInvocationResponse(statusCode, parsedBody)` pattern with streaming awareness.

#### Scenario: DeploymentInvocationResult resource lifecycle
- **WHEN** the worker receives a `DeploymentInvocationResult` with `streaming = true`
- **THEN** the worker SHALL use `DeploymentInvocationResult` in a try-with-resources block (the result implements `AutoCloseable`). The `close()` method SHALL close the underlying `eventStream`, releasing the HTTP connection back to the pool. This prevents connection leaks when the worker or accumulator throws before fully consuming the stream.

#### Scenario: Method signature consistent with existing invoke()
- **WHEN** `invokeWithStreaming()` is defined
- **THEN** it SHALL use the same parameter types as the existing `invoke()` method: `HttpMethod method`, `String path` (relative), `HttpHeaders headers`, `MultiValueMap<String, String> queryParams`, `Object body`, plus `Duration timeout` for per-call timeout

### Requirement: Request resolution error handling
When the worker fails to resolve the request for a test case (e.g., binding variable missing, template parsing error), the worker SHALL record the failure and continue to the next test case.

#### Scenario: Resolution failure recorded as ERROR
- **WHEN** `ResolvedRequestService.resolveRequest()` throws an exception for a specific test case
- **THEN** the worker SHALL set `executionStatus = ERROR`, store the error message in `responseBody` as a JSON error envelope, set `responseStatusCode = null`, and continue to the next test case (consistent with "continue on failure" policy)
