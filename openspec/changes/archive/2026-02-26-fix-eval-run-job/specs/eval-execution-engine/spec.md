## MODIFIED Requirements

### Requirement: Single test case evaluation (worker)
The `EvaluationWorker` SHALL resolve the request body, call the target deployment endpoint, capture the response (including streaming), extract response columns, and build a `TestCaseRunResult`. The worker SHALL track retry attempts and store the actual request body in results.
Status: **Implemented**

#### Scenario: Full request resolution
- **WHEN** a test case is dispatched for execution
- **THEN** the worker SHALL resolve the full request (URL, headers, query params, body) using `ResolvedRequestService.resolveRequest()` (template + bindings + test case data, with per-case overrides). Suites without a request template are prevented at validation time (`isValid = false`), so the worker can always rely on a valid resolved request.

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

#### Scenario: Trace ID generation
- **WHEN** a test case call is made
- **THEN** the worker SHALL generate a unique `traceId` (UUID) per call and propagate it as `X-Correlation-Id` header to the deployment

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

### Requirement: Streaming response accumulation
The `StreamingResponseAccumulator` SHALL parse OpenAI-compatible SSE event streams, accumulate content deltas, and assemble a complete response body. Streaming timing metrics (TTFT/TTLT) are removed.
Status: **Implemented**

#### Scenario: OpenAI chat-completions SSE format
- **WHEN** the response stream contains events in format `data: {"choices":[{"delta":{"content":"..."}}]}\n\n`
- **THEN** the accumulator SHALL extract content deltas from each chunk, concatenate them, and assemble a complete response in the non-streaming chat-completions format (with `message.content` instead of `delta.content`)

#### Scenario: Stream termination
- **WHEN** the stream contains `data: [DONE]`
- **THEN** the accumulator SHALL finalize the assembled response

#### Scenario: Stream error mid-accumulation
- **WHEN** the SSE stream is interrupted (connection drop, timeout) after receiving partial data
- **THEN** the accumulator SHALL set `executionStatus = ERROR` and store whatever was accumulated as `responseBody`

#### Scenario: Non-OpenAI streaming format fallback
- **WHEN** the response has `Content-Type: text/event-stream` but events do not follow OpenAI format (no `choices[].delta.content` structure)
- **THEN** the accumulator SHALL store ALL `data:` payloads as a JSON array in `responseBody`. Each payload SHALL be parsed as JSON if valid, or stored as a JSON string if not. Example: `[{"text":"chunk1"},{"text":"chunk2"}]` or `["Hello","world"]`.

#### Scenario: JSON array fallback enables JSONata extraction
- **WHEN** a non-OpenAI SSE response is stored as a JSON array
- **THEN** JSONata `responseColumns` expressions SHALL be able to extract from the array (e.g., `$[-1].result` for last event, `$[event='done'].answer`, `$.text` for all text values). This requires `DashjoinJsonataEvaluationService` to accept generic JSON input (not just objects) — the evaluator SHALL parse the response body as `Object` (supporting both JSON objects and arrays at the top level).

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

### Requirement: Evaluation executor interface
The system SHALL define an `EvaluationExecutor` interface with a single `execute(EvaluationContext)` method. The `EvaluationContext` SHALL carry: `runId`, `testSuiteId`, execution settings (concurrency, timeout, retry, rate limit), a cancellation signal, a progress callback, and a result sink. This interface enables swapping in-process execution with K8s Job submission without changing orchestration code.
Status: **Implemented**

#### Scenario: In-process executor is the default
- **WHEN** the application starts with default configuration
- **THEN** the `InProcessEvaluationExecutor` bean SHALL be the active `EvaluationExecutor` implementation

#### Scenario: Executor receives fully populated context
- **WHEN** `TestSuiteEvaluationJob` dispatches a run
- **THEN** it SHALL construct an `EvaluationContext` from the run's `RunConfigDto` (with system defaults for omitted fields) and pass it to the executor. Context construction and cancellation signal registration SHALL occur before async dispatch to prevent race conditions.

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

### Requirement: Graceful cancellation
When a run is cancelled, the executor SHALL stop dispatching new calls, wait for in-flight calls to complete (up to a grace period), then abort remaining calls. The cancellation signal SHALL be registered before async dispatch to prevent race conditions.
Status: **Implemented**

#### Scenario: Cancellation signal registered before async dispatch
- **WHEN** `TestSuiteRunService` triggers an evaluation run
- **THEN** `TestSuiteEvaluationJob.registerCancellationSignal(runId)` SHALL be called synchronously BEFORE calling the `@Async executeRunAsync(runId, token)` method. The `@Async` method body runs entirely in the executor thread, so signal registration must happen in the caller's thread. `executeRunAsync()` SHALL retrieve the pre-registered signal from the `activeCancellationSignals` map. This prevents `interruptRun(runId)` from silently losing the cancellation if called before the async thread starts. If `executeRunAsync()` dispatch fails (exception, executor rejection), the caller SHALL clean up the registered signal to prevent map leaks.

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

#### Scenario: Cancellation signal cleanup
- **WHEN** the async run task completes (success, failure, or cancellation)
- **THEN** the cancellation signal SHALL be removed from the `cancellationSignals` map in a `finally`/`whenComplete` block

## REMOVED Requirements

### Requirement: Streaming timing fields in TestCaseRunResult model
**Reason**: TTFT/TTLT fields add significant complexity (~25 files, ~50 lines) with limited practical value for users. The timing data is not surfaced in any current UI and complicates the codebase disproportionately.
**Migration**: Remove `executionInfo.timeToFirstTokenMs` and `executionInfo.timeToLastTokenMs` from API requests/responses. If timing analytics are needed in the future, they can be re-added with a simpler approach.

### Requirement: Filtering on streaming timing fields
**Reason**: Removed together with the underlying TTFT/TTLT data fields. Without the data, filters are meaningless.
**Migration**: Remove `executionInfo.timeToFirstTokenMs` and `executionInfo.timeToLastTokenMs` filter entries from `FilterWhitelists.ANALYTICS_RESULTS`.
