## 1. Database & Configuration Foundation

- [x] 1.1 Create analytics Flyway migration `V1.3__AddStreamingTimingToTestCaseRunResults.sql` — add `time_to_first_token_ms BIGINT` and `time_to_last_token_ms BIGINT` nullable columns to `test_case_run_results`
- [x] 1.2 Update `TestCaseRunResult` model with `timeToFirstTokenMs` and `timeToLastTokenMs` fields (Long, nullable)
- [x] 1.3 Update `TestCaseRunResultRowMapper` to map the new timing columns
- [x] 1.4 Update `TestCaseRunResultRepository` / `PostgresTestCaseRunResultRepository` — include new columns in INSERT and SELECT queries
- [x] 1.5 Update `docs/database-schema.md` with new columns

## 2. DTOs & Configuration Properties

- [x] 2.1 Create `ExecutionSettingsDto` — `concurrencyLevel` (Integer), `requestTimeoutMs` (Long), `rateLimitRps` (Double); all nullable with validation annotations
- [x] 2.2 Create `RetryPolicyDto` — `maxRetries` (Integer), `retryDelayMs` (Long), `retryBackoffMultiplier` (Double); all nullable with validation annotations
- [x] 2.3 Extend `RunConfigDto` — add `execution` (ExecutionSettingsDto, optional) and `retry` (RetryPolicyDto, optional) fields
- [x] 2.4 Create `EvaluationRunProperties` (`@ConfigurationProperties`) — execution defaults/maxes and retry defaults/maxes under `test-suite-run.execution.*` and `test-suite-run.retry.*`
- [x] 2.5 Add execution and retry default values (including `header-blacklist`) to `application.yml` and test `application.yml`
- [x] 2.6 Create `ExecutionSettingsValidator` component — validates per-run values against system maximums from `EvaluationRunProperties`
- [x] 2.7 Integrate `ExecutionSettingsValidator` into `TestSuiteRunService.createRun()` — validate execution/retry settings before persisting
- [x] 2.8 Update analytics DTOs — add `timeToFirstTokenMs` and `timeToLastTokenMs` to `ExecutionInfoDto` (batch write request + read response)
- [x] 2.9 Update `TestCaseRunResultMapper` (MapStruct) to map new timing fields
- [x] 2.10 Update `docs/configuration.md` with new execution properties

## 3. Analytics Filter Extension

- [x] 3.1 Add `executionInfo.timeToFirstTokenMs` and `executionInfo.timeToLastTokenMs` to `FilterWhitelists.ANALYTICS_RESULTS` (type: LONG, operators: gt/gte/lt/lte/eq)
- [x] 3.2 Update `WhereBuilder` if needed for new filter fields mapping
- [x] 3.3 Update `OpenApiQueryParamCustomizer` registration for new filter fields (if list endpoint is registered)

## 4. Execution Engine Core

- [x] 4.1 Create `EvaluationContext` record/class — runId, suiteId, testSuiteRun, executionSettings (merged with defaults), retryPolicy (merged with defaults), cancellation signal (AtomicBoolean), token (String)
- [x] 4.2 Create `EvaluationExecutor` interface — `void execute(EvaluationContext context)`
- [x] 4.3 Create `ResultBatchWriter` component — thread-safe result buffer, flushes to `TestCaseRunResultRepository.saveAll()` in analytics transaction at batch size threshold; replaces `MockResultsBatchWriter`
- [x] 4.4 Create `EvaluationWorker` component — single test case execution: resolve request (via `ResolvedRequestService`, no fallback-to-data — suites without template are invalid) → call endpoint → capture response/timing (TTFT/TTLT always populated: non-streaming sets TTFT=TTLT=duration) → extract columns → build `TestCaseRunResult`
- [x] 4.5 Create `InProcessEvaluationExecutor` — reads test cases in pages, dispatches `EvaluationWorker` tasks via virtual thread executor bounded by semaphore, collects results into `ResultBatchWriter`, handles completion/failure/cancellation

## 5. HTTP Client & Streaming Response Handling

- [x] 5.1 Switch `DialCoreDeploymentInvokerConfiguration` from `SimpleClientHttpRequestFactory` to `JdkClientHttpRequestFactory` — enables streaming SSE response reading and per-request timeouts
- [x] 5.2 Create `DeploymentInvocationResult` record in `client.dialcore` — `statusCode` (int), `streaming` (boolean), `body` (Object, nullable), `eventStream` (InputStream, nullable), `responseHeaders` (HttpHeaders). Extends the pattern of existing `DeploymentInvocationResponse(statusCode, parsedBody)`.
- [x] 5.3 Add `invokeWithStreaming()` method to `DialCoreDeploymentInvoker` — same parameter types as existing `invoke()` (`HttpMethod`, `String path`, `HttpHeaders`, `MultiValueMap<String,String>`, `Object body`) plus `Duration timeout`; returns `DeploymentInvocationResult` with streaming auto-detection from response `Content-Type`
- [x] 5.4 Create `StreamingResponseAccumulator` — two-mode accumulator: (1) OpenAI mode — parse `choices[].delta.content`, concatenate, assemble non-streaming response body; (2) JSON array fallback — store all `data:` payloads as a JSON array. Captures TTFT/TTLT timing in both modes. Accepts a `deadlineMs` parameter and checks total elapsed time periodically — returns TIMEOUT if `requestTimeoutMs` exceeded during stream consumption.
- [x] 5.5 Integrate streaming detection in `EvaluationWorker` — use `invokeWithStreaming()`, auto-detect from `DeploymentInvocationResult.streaming`, delegate to `StreamingResponseAccumulator` when streaming

## 6. Retry & Rate Limiting

- [x] 6.1 Implement retry logic in `EvaluationWorker` — exponential backoff with computed delay capped at `max-retry-delay-ms` (formula: `min(retryDelayMs * multiplier^(attempt-1), maxRetryDelayMs)`), retryable conditions (timeout, network error, 429, 5xx), non-retryable (4xx except 429; 401/403 → ERROR status, not retried), respects cancellation during backoff sleep
- [x] 6.2 Implement rate limiting in `InProcessEvaluationExecutor` — token bucket or similar, shared across all workers within a run, configured from `rateLimitRps`

## 7. Response Size Limiting

- [x] 7.1 Implement response body truncation in `EvaluationWorker` — check against `max-response-size-bytes` (default 5MB), truncate if exceeded, store as JSON string (guarantees JSONB validity), set `executionStatus = ERROR`, add truncation warning to `extractionWarnings`
- [x] 7.2 Implement streaming accumulation size limit in `StreamingResponseAccumulator` — stop accumulating and close stream when limit reached, set `executionStatus = ERROR`

## 7b. Response Column Extraction for Array Responses

- [x] 7b.1 Update `DashjoinJsonataEvaluationService.evaluate()` to accept generic JSON (not just `Map<String, Object>`) — parse as `Object` instead of `MAP_TYPE` so that top-level JSON arrays (`[{...},{...}]`) are supported. JSONata natively handles array expressions (`$[0].text`, `$[-1].result`, `$.text`)
- [x] 7b.2 Update `ResponseColumnExtractor` if needed to handle both object and array response bodies
- [x] 7b.3 Add unit tests for array-format extraction — verify JSONata expressions like `$[0].text`, `$[-1].result`, `$.text` work on array top-level JSON (raw SSE fallback format)

## 8. Cancellation

- [x] 8.1 Implement cancellation signal propagation — `EvaluationContext.cancellationSignal` (AtomicBoolean) checked by executor before dispatching new tasks
- [x] 8.2 Implement grace period drain in `InProcessEvaluationExecutor` — wait up to `cancellationGracePeriodMs` for in-flight calls, then abort
- [x] 8.3 Flush accumulated results on cancellation — `ResultBatchWriter.flush()` before marking run as CANCELLED

## 9. Job Orchestration Integration

- [x] 9.1 Refactor `TestSuiteEvaluationJob` — replace mock sleep/failure with `evaluationExecutor.execute(context)` delegation; construct `EvaluationContext` from run config + system defaults
- [x] 9.2 Update `TestSuiteRunService.createRun()` — capture user JWT token before async dispatch for propagation to workers
- [x] 9.3 Integrate progress reporting — add `notifyProgress(runId, testSuiteId, completedCases, totalCases)` to `TestSuiteRunSseService`; emit new `progress` SSE event type (`{"runId", "testSuiteId", "completedCases", "totalCases", "timestamp"}`) from `ResultBatchWriter` after each successful batch flush; reuse existing `SseEmitterWrapper` filtering (runIds, testSuiteIds, statuses)
- [x] 9.4 Remove mock components — delete `MockResultsGenerator`, `MockResultsBatchWriter`, `MockResponseBodyBuilder`, `MockRequestBodyBuilder`. Worker uses `ResolvedRequestService.resolveRequest()` directly for full request resolution. No fallback-to-data logic — suites without request template are prevented at validation time.
- [x] 9.5 Remove `test-suite-run.mock-job.*` properties from `application.yml` and `TestSuiteRunProperties`

## 9b. Header Blacklist Validation

- [x] 9b.1 Add `header-blacklist` property to `EvaluationRunProperties` — list of header names (default: `[Authorization, Host, Content-Length, Transfer-Encoding, Connection, X-Correlation-Id]`)
- [x] 9b.2 Integrate header blacklist validation into `SuiteValidationService` — check `requestTemplate.headers` at suite save/update; mark `isValid = false` with `validationWarning` when blacklisted headers found
- [x] 9b.3 Add call-time header filtering in `EvaluationWorker` — silently skip blacklisted headers from resolved request, log warning

## 10. OpenAPI & Examples

- [x] 10.1 Update `RunConfigDto` OpenAPI schema annotations — add `@Schema` for `execution` and `retry` fields with examples
- [x] 10.2 Update OpenAPI example JSON files for `POST /test-suites/{id}/runs` — minimal (just numberOfRuns) and full (with execution + retry)
- [x] 10.3 Update OpenAPI example JSON for run response — include `execution` and `retry` in `runConfig`
- [x] 10.4 Add `@Schema` for `timeToFirstTokenMs` and `timeToLastTokenMs` in analytics result DTOs

## 11. Testing

- [x] 11.1 Unit tests for `ExecutionSettingsValidator` — valid/invalid concurrency, timeout, retry, rate limit values against system max
- [x] 11.2 Unit tests for `StreamingResponseAccumulator` — OpenAI SSE format parsing, content accumulation, TTFT/TTLT capture, mid-stream error, non-OpenAI fallback (JSON array storage)
- [x] 11.3 Unit tests for `EvaluationWorker` — request resolution, non-streaming call, streaming call, timeout, network error, HTTP error, column extraction, response truncation, retry logic
- [x] 11.4 Unit tests for `ResultBatchWriter` — batch threshold flush, final flush, cancellation flush, write failure handling
- [x] 11.5 Unit tests for `InProcessEvaluationExecutor` — sequential execution, parallel execution, cancellation, rate limiting, error handling (continue-on-failure)
- [x] 11.6 Functional tests — create run with execution settings, validate settings persisted in runConfig JSONB, validate settings rejected when exceeding system max
- [x] 11.7 Functional tests — analytics results with streaming timing fields (batch write + read with TTFT/TTLT, filter by timing)
- [x] 11.8 Integration test — end-to-end run with mock HTTP server (WireMock or similar): create suite + cases, trigger run with execution settings, verify results written with real HTTP responses
- [x] 11.9 Update existing test suite run tests — adjust for removed mock-job properties, verify new execution properties
- [x] 11.10 Unit tests for `DialCoreDeploymentInvoker.invokeWithStreaming()` — non-streaming response, streaming SSE response, timeout, per-call timeout propagation
- [x] 11.11 Functional tests for header blacklist validation — suite with blacklisted headers marked invalid with warning, suite without blacklisted headers passes

## 12. Documentation

- [x] 12.1 ~~Update `docs/database-schema.md` — new analytics columns~~ (duplicate of 1.5, skip)
- [x] 12.2 Update `docs/configuration.md` — new execution and retry properties
- [x] 12.3 Update `openspec/specs/README.md` — add `eval-execution-engine` spec entry, update `runner-and-jobs` and `test-suite-runs` status
