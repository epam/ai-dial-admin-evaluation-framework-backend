## Context

The evaluation framework backend has a fully functional test suite run lifecycle (create, cancel, delete, SSE status streaming) backed by a mock evaluation job that sleeps and generates fake results. The mock job infrastructure (`TestSuiteEvaluationJob`, `MockResultsGenerator`, `MockResultsBatchWriter`, `MockRequestBodyBuilder`, `MockResponseBodyBuilder`) lives in `service.domain.job` and runs in-process via `@Async("testSuiteRunExecutor")`.

The system already supports:
- Request body resolution from templates + bindings + test case data (`MockRequestBodyBuilder`)
- Response column extraction via JSONata (`ResponseColumnExtractor`)
- Batch result writing to analytics DB (`MockResultsBatchWriter` → `TestCaseRunResultRepository.saveAll`)
- DIAL Core deployment invocation (`DialCoreDeploymentInvoker` in `client.dialcore`)
- Token propagation to async threads (`TokenPropagationHelper`)
- Cancellation via `Thread.interrupt()` with thread tracking map
- Concurrent run limits (global + per-suite)

The design docs (`docs/design/infrastructure-architecture.md`) envision K8s Jobs for production evaluation, with the current in-process approach as a stepping stone.

### Current Data Flow
```
POST /runs → PENDING → afterCommit → @Async TestSuiteEvaluationJob
                                        ├── sleep(random)
                                        ├── MockResultsGenerator.generateAndSave
                                        │   ├── read test cases (paged)
                                        │   ├── MockRequestBodyBuilder.buildRequestBody
                                        │   ├── MockResponseBodyBuilder.buildResponseBody
                                        │   ├── ResponseColumnExtractor.extract
                                        │   └── batch write to analytics DB
                                        └── update status → COMPLETED/FAILED
```

### Target Data Flow
```
POST /runs → PENDING → afterCommit → @Async TestSuiteEvaluationJob
                                        ├── InProcessEvaluationExecutor.execute
                                        │   ├── read test cases (paged)
                                        │   ├── for each case × numberOfRuns:
                                        │   │   ├── resolve request (template + bindings)
                                        │   │   ├── call endpoint (DialCoreDeploymentInvoker)
                                        │   │   │   ├── non-streaming: single HTTP call
                                        │   │   │   └── streaming: accumulate SSE chunks
                                        │   │   ├── capture timing (duration, TTFT, TTLT)
                                        │   │   ├── extract response columns (JSONata)
                                        │   │   └── buffer result
                                        │   ├── flush batch to analytics DB
                                        │   └── report progress (SSE)
                                        └── update status → COMPLETED/FAILED/CANCELLED
```

## Goals / Non-Goals

**Goals:**
- Replace mock evaluation job with real HTTP call execution against target deployments
- Support both streaming (SSE) and non-streaming HTTP responses
- Provide configurable execution settings (concurrency, timeout, retry, rate limiting) per run
- Design clean interface boundary (`EvaluationExecutor`) for future K8s Job extraction
- Handle graceful cancellation with configurable grace period
- Write results in configurable batches during execution (not just at end)
- Capture streaming-specific timing metrics (TTFT, TTLT)

**Non-Goals:**
- K8s Job submission (post-v1 — interface designed now, implementation later)
- Service account / token refresh for long-running evaluations (v1 uses user JWT as-is)
- Object storage / blob offloading for large payloads (v1: DB with size limits)
- Metrics calculation pipeline (separate future change)
- Deployment-specific auto-detected throughput limits (v1: user-configured rate limit only)
- Resume-after-restart (v1: orphaned runs stay FAILED; manual re-trigger)

## Decisions

### D1: Hybrid Execution Model — In-Process with Extraction Interface

**Decision:** Implement evaluation execution in-process using the existing `@Async` + `ThreadPoolTaskExecutor` infrastructure. Define an `EvaluationExecutor` interface that both in-process and future K8s implementations can satisfy.

**Why over pure K8s:** Not all deployments run on K8s. For small setups, an all-in-one deployment with built-in evaluation is simpler. For large setups, the same interface can dispatch to K8s Jobs.

**Why over pure in-process:** Without the interface, extracting to K8s later would require significant refactoring. The interface costs almost nothing now and preserves the option.

**Interface sketch:**
```java
public interface EvaluationExecutor {
    void execute(EvaluationContext context);
}
```
Where `EvaluationContext` carries: runId, suiteId, execution settings, cancellation signal, progress callback, result sink.

### D2: Intra-Run Concurrency via Semaphore + Virtual Threads

**Decision:** Use Java 21 virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`) bounded by a `Semaphore(concurrencyLevel)` for parallel test case execution within a single run. Default `concurrencyLevel = 1` (sequential).

**Why virtual threads over thread pool:** Virtual threads are lightweight (millions possible), don't require pool sizing tuning, and are ideal for I/O-bound workloads (HTTP calls). Java 21 is already required by the project.

**Why semaphore over fixed pool:** A semaphore provides exact concurrency control regardless of the underlying executor. Combined with virtual threads, we get both bounded concurrency and efficient resource usage.

**Alternative considered:** `ThreadPoolExecutor` with fixed size — heavier, requires sizing, not as natural for I/O-bound tasks.

### D3: Streaming Response Handling — Auto-Detect + Two-Mode Accumulator

**Decision:** Auto-detect streaming responses from `Content-Type: text/event-stream` response header. Use a two-mode accumulator: try OpenAI assembly first, fall back to JSON array of all events for custom SSE. Capture streaming timing: `timeToFirstTokenMs` and `timeToLastTokenMs`.

**Timing unification:** TTFT and TTLT are always populated (never null):
- **Streaming:** TTFT = time to first content delta, TTLT = time to last content delta (before `[DONE]`), `execDurationMs` = total elapsed including `[DONE]` and cleanup. Relationship: TTFT <= TTLT <= execDurationMs.
- **Non-streaming:** TTFT = TTLT = `execDurationMs` (the entire response arrives at once — first token and last token are the same event). This eliminates null checks in analytics queries and simplifies aggregation across streaming/non-streaming results.

**Why auto-detect:** The request template may or may not include `"stream": true`. The response header is the reliable indicator. No user configuration needed.

**Two-mode accumulator:**
1. **OpenAI mode** — when SSE events follow `{"choices":[{"delta":{"content":"..."}}]}` format, extract content deltas, concatenate, and assemble a complete non-streaming response body (`message.content` instead of `delta.content`). Terminated by `data: [DONE]`.
2. **Raw events mode (fallback)** — when SSE events don't match OpenAI format, store ALL events as a JSON array in `responseBody`. Each `data:` payload is parsed as JSON if valid, or stored as a string if not. Example: `[{"text":"chunk1"},{"text":"chunk2"}]` or `["Hello","world"]`.

**Why JSON array fallback (not raw SSE log or failure):** JSONata can extract from arrays (`$[-1].result` for last event, `$[event='done'].answer`, `$.text` for all text values). Users configure `responseColumns` expressions knowing the array structure. This handles arbitrary SSE formats without losing data.

**Array extraction prerequisite:** The current `DashjoinJsonataEvaluationService.evaluate()` parses input as `Map<String, Object>`, which rejects top-level arrays. This SHALL be changed to parse as generic `Object` so that JSONata naturally operates on both objects and arrays. This is a one-line change in the evaluator (parse to `Object` instead of `Map`).

**Why not both (assembled + raw):** Doubles storage with marginal benefit. If raw SSE needed later, it can be added as an opt-in feature.

**Streaming detection requires a request factory change** — the current `SimpleClientHttpRequestFactory` (backed by `HttpURLConnection`) buffers the entire response via `readAllBytes()`. See D12.

### D4: Run Configuration Grouping — Nested Optional DTOs

**Decision:** Extend `RunConfigDto` with optional nested objects: `execution` (ExecutionSettingsDto), `retry` (RetryPolicyDto). All new fields optional with system defaults from `application.yml`.

```
RunConfigDto:
  numberOfRuns: Integer (required, existing)
  testRunName: String (optional, existing)
  execution: ExecutionSettingsDto (optional)
    concurrencyLevel: Integer (default: 1)
    requestTimeoutMs: Long (default: 30000)
    rateLimitRps: Double (optional, null = no limit)
  retry: RetryPolicyDto (optional)
    maxRetries: Integer (default: 0)
    retryDelayMs: Long (default: 1000)
    retryBackoffMultiplier: Double (default: 2.0)
```

**Why nested:** Groups related settings, keeps the top-level simple, makes it clear which settings belong together. Minimal payload for basic usage.

**Why all optional:** 90% of users just set `numberOfRuns`. Power users opt into execution/retry tuning. No breaking changes.

**Validation:**
- `concurrencyLevel`: min 1, max configurable (e.g., 50)
- `requestTimeoutMs`: min 1000, max configurable (e.g., 600000 = 10 min)
- `rateLimitRps`: if provided, min 0.1
- `maxRetries`: min 0, max configurable (e.g., 10)
- `retryDelayMs`: min 100, max configurable
- `retryBackoffMultiplier`: min 1.0, max 10.0

**Computed retry delay cap:** The exponential backoff formula `retryDelayMs * retryBackoffMultiplier^(attemptIndex-1)` is capped at `max-retry-delay-ms` (default 60000ms) per individual retry. This property serves dual purpose: (1) validation ceiling for user-provided `retryDelayMs` base value, and (2) cap on computed delay. Without this cap, multiplier=3 + 10 retries → attempt 10 delay would be ~5.5 hours.

### D5: Error Handling — Continue on Failure, Record Everything

**Decision:** If a single test case call fails (after retries exhausted), record the result with `ExecutionStatus.FAILED/ERROR/TIMEOUT` and continue to the next test case. The run only fails (FAILED status) on infrastructure-level errors (unrecoverable).

**Error classification:**

| Category | HTTP Status | ExecutionStatus | Action |
|----------|-------------|-----------------|--------|
| Successful call | 2xx | SUCCESS | Store response |
| Target error | 4xx (except 401/403/429) | FAILED | Store response + status code |
| Auth failure | 401/403 | ERROR | Store response + status code, continue (JWT may be expired; not retried) |
| Timeout | — | TIMEOUT | Store partial/null response |
| Network error | — | ERROR | Store error message |
| Rate limited | 429 | Retry or FAILED | Retry if configured, then FAILED |
| Response truncated | any | ERROR | Store truncated body + truncation warning |
| Request resolution error | — | ERROR | Store resolution error message as responseBody (JSON error envelope), continue |

**Why continue:** A partially-completed evaluation is far more useful than no results. Users can filter results by `executionStatus` to analyze failures separately.

### D6: Batch Result Writing — Configurable System Default

**Decision:** Write results to analytics DB in batches. Batch size is a system-level config property (`test-suite-run.execution.result-batch-size`, default 100). Not user-configurable per run in v1 (simplifies API).

**Why not per-run:** The batch size is a performance tuning knob, not a user-facing feature. System admin can tune based on DB capacity. Exposing it to users adds API surface without clear benefit.

**Flush triggers:**
1. Batch buffer reaches configured size → flush
2. All test cases processed → flush remaining
3. Cancellation → flush accumulated results before marking cancelled

**On flush failure:** Set cancellation signal → stop dispatching new calls → drain in-flight (grace period) → mark run FAILED. This prevents workers from continuing to execute and buffer results that can never be persisted.

### D7: Cancellation — Stop + Drain + Abort

**Decision:** Three-phase cancellation:
1. **Stop dispatching**: No new test case calls are submitted
2. **Drain**: Wait up to `cancellationGracePeriodMs` (configurable, default 30000) for in-flight calls to complete
3. **Abort**: After grace period, interrupt remaining in-flight calls

Already-written partial results are kept (not deleted). The run is marked CANCELLED.

**Why keep partial results:** Results already written to analytics are committed. Deleting them on cancellation adds complexity and loses useful data. The `status = CANCELLED` on the run clearly indicates incompleteness.

### D8: Response Size Limits — Configurable with Truncation

**Decision:** Configurable max response body size (`test-suite-run.execution.max-response-size-bytes`, default 5MB). Responses exceeding the limit are truncated, `executionStatus` is set to `ERROR`, and a warning is recorded.

**JSONB safety:** Since `response_body` is a JSONB column, truncation MUST produce valid JSON. The truncated response is stored as a JSON string (the raw text wrapped in quotes). Structured JSON assembly (e.g., OpenAI response format) is abandoned in favor of the simpler string representation when truncation occurs. This guarantees the INSERT never fails due to invalid JSONB.

**Why ERROR status on truncation:** A truncated response is incomplete data — marking it as ERROR signals that the result is unreliable. The truncation warning in `extractionWarnings` provides details (original vs truncated size). Users can filter by `executionStatus = ERROR` to identify affected results.

**Why not store reference:** Object storage is a post-v1 concern. For v1, DB storage with a reasonable limit (5MB default) covers most LLM evaluation scenarios.

### D9: Request Resolution — Use ResolvedRequestService, Delete MockRequestBodyBuilder

**Decision:** The `EvaluationWorker` SHALL use `ResolvedRequestService` directly for full request resolution (URL, headers, query params, body). `MockRequestBodyBuilder` SHALL be **deleted** (not renamed) — it only resolves the body string and duplicates template substitution logic already in `ResolvedRequestService`.

**Why delete (not rename):** `MockRequestBodyBuilder` resolves the **body string only** using its own placeholder substitution logic. It does NOT wrap `ResolvedRequestService` and does NOT resolve URL, headers, or query params. The worker needs the full request, which `ResolvedRequestService.resolveRequest()` already provides via `ResolvedRequestDto` (url, headers, queryParams, body, warnings).

**No fallback-to-data:** Suites without a request template are marked `isValid = false` by validation. The executor only runs against valid suites, so the worker can always rely on `ResolvedRequestService` producing a valid resolved request. No runtime fallback is needed.

**Why ResolvedRequestService:** Already implements full template + bindings + data resolution with per-case overrides, placeholder substitution, and validation warnings. No need to duplicate or wrap.

### D10: DIAL Core URL Construction — Reuse DialCoreUrlBuilder

**Decision:** Reuse the existing `DialCoreUrlBuilder` (from try-it-out) to construct the full URL from `deploymentRef.id` + `endpointRef.relativeUrlPattern`. This handles standard paths (`/chat/completions` → `openai/deployments/{id}/chat/completions`) and custom paths.

### D11: Progress Reporting — Batch-Level SSE Updates

**Decision:** Report progress via existing SSE infrastructure at batch-flush boundaries using a new `progress` event type alongside the existing `status-update` events.

**New SSE event type: `progress`**
```
event: progress
data: {
  "runId": "uuid",
  "testSuiteId": "uuid",
  "completedCases": 200,
  "totalCases": 1000,
  "timestamp": 1708876543210
}
```

**Integration with existing infrastructure:**
- Add `notifyProgress(UUID runId, UUID testSuiteId, int completedCases, int totalCases)` method to `TestSuiteRunSseService`
- Reuse existing `SseEmitterWrapper` filtering logic (runIds, testSuiteIds, statuses) — progress events are filtered the same way as status-update events
- Progress events are emitted by `ResultBatchWriter` after each successful batch flush
- `completedCases` = cumulative count of results written to analytics DB so far
- `totalCases` = `numberOfTestCases * numberOfRuns` (snapshot from run creation)

**Event flow for a typical run:**
```
connected  → {"connectionId": "..."}
status-update → {"status": "RUNNING", ...}
progress   → {"completedCases": 100, "totalCases": 1000, ...}
progress   → {"completedCases": 200, "totalCases": 1000, ...}
...
progress   → {"completedCases": 1000, "totalCases": 1000, ...}
status-update → {"status": "COMPLETED", ...}
```

**Why batch-level (not per-call):** Per-call SSE events for 1000s of calls would be noisy. Batch-level (every 100 results) gives meaningful progress without overwhelming clients.

**Why new event type (not extending SseStatusEventDto):** Status updates and progress updates have different semantics and payloads. Separate event types let clients handle them independently. The existing `status-update` contract is unchanged.

### D12: HTTP Client Factory — Switch to JdkClientHttpRequestFactory

**Decision:** Switch `DialCoreDeploymentInvokerConfiguration` from `SimpleClientHttpRequestFactory` (backed by `HttpURLConnection`) to `JdkClientHttpRequestFactory` (backed by `java.net.http.HttpClient`).

**Why:** `SimpleClientHttpRequestFactory` buffers the entire response via `readAllBytes()` before returning. It **cannot** support streaming SSE response reading. `JdkClientHttpRequestFactory` supports chunked/streaming reading, per-request timeouts, and is already proven in this project's test configuration (`spring.http.client.factory=jdk`).

**Scope:** This changes the request factory for the deployment invoker (try-it-out + evaluation). The `DialCoreClient` (models/applications listing) can stay on `SimpleClientHttpRequestFactory` since it doesn't need streaming.

**Streaming timeout note:** JDK HttpClient's per-request `timeout()` covers header reception, NOT stream body consumption. For streaming responses, `requestTimeoutMs` is enforced as total elapsed time (header reception + stream consumption) by the `EvaluationWorker`/`StreamingResponseAccumulator`. The accumulator accepts a `deadlineMs` and checks it after each event. For stalled streams (connection open, no data), the accumulator wraps the blocking read in a timeout-aware mechanism (e.g., `CompletableFuture.supplyAsync().get(remainingTimeout)` or socket read timeout) to enforce the deadline even when `InputStream.read()` blocks.

**Alternative considered:** `HttpComponentsClientHttpRequestFactory` (Apache HttpClient 5) — adds a new dependency, no advantage over JDK built-in. `WebClient` (reactive) — doesn't fit the blocking architecture.

### D13: Extend DialCoreDeploymentInvoker for Streaming

**Decision:** Add an `invokeWithStreaming()` method to the existing `DialCoreDeploymentInvoker` class. This method returns a `DeploymentInvocationResult` that includes streaming detection and an `InputStream` for SSE consumption. The existing `invoke()` method remains unchanged for backward compatibility.

**Why extend (not new class):** Try-it-out will eventually need the same streaming support (proxy SSE to browser) and trace ID injection. One class with shared URL building, auth, and base URL config avoids duplication. The caller decides what to do with the stream (eval: accumulate, try-it-out: proxy).

**New method signature (consistent with existing `invoke()` parameter types):**
```java
DeploymentInvocationResult invokeWithStreaming(HttpMethod method, String path,
    HttpHeaders headers, MultiValueMap<String, String> queryParams,
    Object body, Duration timeout);
```

**Note:** Uses the same parameter types as existing `invoke()` (`HttpHeaders`, `MultiValueMap`, relative `path`) plus `Duration timeout` for per-call timeouts. The `Authorization` header is set automatically by the interceptor — not from user-provided headers.

**`DeploymentInvocationResult` record (implements `AutoCloseable`):**
```java
record DeploymentInvocationResult(
    int statusCode,
    boolean streaming,           // true if Content-Type is text/event-stream
    Object body,                 // non-streaming: parsed JSON body (same as existing DeploymentInvocationResponse.parsedBody)
    InputStream eventStream,     // streaming: raw SSE InputStream for caller to consume
    HttpHeaders responseHeaders  // response headers (Spring HttpHeaders for consistency)
) implements AutoCloseable {
    @Override public void close() throws Exception {
        if (eventStream != null) eventStream.close();
    }
}
```

**Relationship with existing `DeploymentInvocationResponse`:** The existing `invoke()` returns `DeploymentInvocationResponse(statusCode, parsedBody)`. The new `DeploymentInvocationResult` extends this pattern with streaming awareness. The existing `invoke()` method and `DeploymentInvocationResponse` remain unchanged for backward compatibility.

**Per-call timeout:** The new method accepts `Duration timeout`, allowing the evaluation worker to set per-call timeout from `RunConfigDto.execution.requestTimeoutMs`. JDK `HttpClient` supports per-request timeout natively.

**Trace ID:** The caller adds `X-Correlation-Id` to the `headers` map before calling. The invoker is header-agnostic — it sends whatever it receives.

### D14: Header Blacklist — Dual Validation (Save-time + Call-time)

**Decision:** Maintain a configurable blacklist of system-managed headers that users cannot set via `requestTemplate.headers`. Validate at **both** suite save/update time (hard validation → `isValid = false` + `validationWarning`) and at call execution time (soft filter → skip + log).

**Blacklisted headers (default):**
- `Authorization` — set automatically via token propagation
- `Host` — set by HTTP client
- `Content-Length` — set by HTTP client
- `Transfer-Encoding` — managed by client
- `Connection` — managed by client
- `X-Correlation-Id` — set by evaluation worker (trace ID)

**Save-time validation:** Added to the existing `SuiteValidationService` pipeline. If a blacklisted header is found in `requestTemplate.headers`, the suite is marked `isValid = false` with a `validationWarning` describing which header is blacklisted. This prevents users from creating suites that will fail at execution time.

**Call-time validation:** At execution time, blacklisted headers are silently skipped and a warning is logged. This covers edge cases where the blacklist was updated after suite creation.

**Configuration:** `test-suite-run.execution.header-blacklist` as a list in `application.yml`. Extensible by admin without code changes.

## Component Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                     service.domain.job                            │
│                                                                  │
│  ┌──────────────────────┐    ┌─────────────────────────────────┐│
│  │ TestSuiteEvaluation  │    │ EvaluationExecutor (interface)  ││
│  │ Job (orchestrator)   │───▶│                                 ││
│  │ - @Async entry point │    │ void execute(EvaluationContext)  ││
│  │ - status management  │    └────────────┬────────────────────┘│
│  │ - SSE notifications  │                 │                     │
│  └──────────────────────┘    ┌────────────┴────────────────────┐│
│                              │ InProcessEvaluationExecutor     ││
│  ┌──────────────────────┐    │ - reads test cases (paged)      ││
│  │ EvaluationContext     │    │ - manages worker concurrency    ││
│  │ - runId, suiteId     │    │ - dispatches EvaluationWorker   ││
│  │ - executionSettings  │    │ - collects results              ││
│  │ - cancellationSignal │    │ - flushes batches               ││
│  │ - resultSink         │    └────────────┬────────────────────┘│
│  └──────────────────────┘                 │                     │
│                              ┌────────────┴────────────────────┐│
│                              │ EvaluationWorker                ││
│                              │ - resolves request              ││
│                              │ - calls endpoint                ││
│                              │ - handles streaming             ││
│                              │ - extracts columns              ││
│                              │ - builds TestCaseRunResult      ││
│                              └─────────────────────────────────┘│
│                                                                  │
│  ┌──────────────────────┐    ┌─────────────────────────────────┐│
│  │ StreamingResponse    │    │ ResultBatchWriter               ││
│  │ Accumulator          │    │ - buffers results               ││
│  │ - SSE chunk parsing  │    │ - flushes on batch size         ││
│  │ - response assembly  │    │ - @Transactional(analytics)     ││
│  │ - timing capture     │    │ - flush on cancel/complete      ││
│  └──────────────────────┘    └─────────────────────────────────┘│
└──────────────────────────────────────────────────────────────────┘
```

### Reused Components (no changes needed)
- `ResolvedRequestService` — full request resolution (URL, headers, query params, body)
- `DialCoreUrlBuilder` — URL construction
- `ResponseColumnExtractor` — JSONata extraction
- `TokenPropagationHelper` — JWT propagation to worker threads
- `TestSuiteRunSseService` — SSE status notifications
- `TestCaseRunResultRepository.saveAll()` — analytics batch write
- `TestCaseRepository.findAllEnabledAndValid()` — test case pagination

### New Components
| Component | Package | Purpose |
|-----------|---------|---------|
| `EvaluationExecutor` | `service.domain.job` | Interface for execution strategies |
| `InProcessEvaluationExecutor` | `service.domain.job` | In-process implementation with virtual threads |
| `EvaluationWorker` | `service.domain.job` | Single test case execution logic |
| `EvaluationContext` | `service.domain.job` | Immutable context carrier for a run |
| `StreamingResponseAccumulator` | `service.domain.job` | SSE chunk accumulation (two-mode: OpenAI + JSON array fallback) + timing |
| `ResultBatchWriter` | `service.domain.job` | Replaces `MockResultsBatchWriter` |
| `DeploymentInvocationResult` | `client.dialcore` | Record carrying HTTP response + streaming detection + InputStream |
| `ExecutionSettingsDto` | `service.domain.dto` | Concurrency/timeout/rate-limit settings |
| `RetryPolicyDto` | `service.domain.dto` | Retry configuration |
| `ExecutionSettingsValidator` | `service.domain.job` | Validates settings against system maximums |
| `EvaluationRunProperties` | `configuration.properties` | System defaults + maximums for execution settings |

### Modified Components
| Component | Change |
|-----------|--------|
| `RunConfigDto` | Add `execution` and `retry` optional fields |
| `TestSuiteEvaluationJob` | Replace mock sleep with `evaluationExecutor.execute()` |
| `TestSuiteRunProperties` | Add execution defaults section (or separate `EvaluationRunProperties`) |
| `TestCaseRunResult` | Add `timeToFirstTokenMs`, `timeToLastTokenMs` fields (nullable) |
| `TestCaseRunResultRowMapper` | Map new timing fields |
| `TestSuiteRunService` | Pass execution settings from RunConfig to job context |
| `DialCoreDeploymentInvoker` | Add `invokeWithStreaming()` method returning `DeploymentInvocationResult` with streaming detection + InputStream |
| `DialCoreDeploymentInvokerConfiguration` | Switch from `SimpleClientHttpRequestFactory` to `JdkClientHttpRequestFactory` for streaming support |
| `SuiteValidationService` | Add header blacklist validation at suite save/update — mark suite `isValid = false` with `validationWarning` when blacklisted headers found |

### Removed Components (replaced by real implementation)
| Component | Replacement |
|-----------|-------------|
| `MockResultsGenerator` | `InProcessEvaluationExecutor` |
| `MockResultsBatchWriter` | `ResultBatchWriter` |
| `MockResponseBodyBuilder` | Real HTTP responses from deployment |
| Mock job config properties (`mock-job.*`) | Real execution config properties |

**Note:** `MockRequestBodyBuilder` is **deleted** — it only resolves the body string and duplicates logic already in `ResolvedRequestService`. The worker uses `ResolvedRequestService` directly for full request resolution. Suites without a request template are prevented at validation time (`isValid = false`), so the worker always has a valid resolved request.

## Database Changes

### Analytics Migration: Add streaming timing fields
```sql
-- V1.3__AddStreamingTimingToTestCaseRunResults.sql
ALTER TABLE test_case_run_results
    ADD COLUMN time_to_first_token_ms BIGINT,
    ADD COLUMN time_to_last_token_ms BIGINT;
```

These columns are nullable in the DB schema for backward compatibility with pre-existing rows, but new results always populate both fields (non-streaming: TTFT = TTLT = duration; streaming: measured from first/last content delta). No index needed — they're per-result metadata, not query filters.

### No Meta Migration Needed
`RunConfigDto` is stored as JSONB in `test_suite_runs.run_config`. Adding `execution` and `retry` fields requires no schema change — JSONB is self-describing.

## Configuration Properties

New properties under `test-suite-run.execution`:
```yaml
test-suite-run:
  execution:
    default-concurrency-level: 1
    max-concurrency-level: 50
    default-request-timeout-ms: 30000
    max-request-timeout-ms: 600000
    default-rate-limit-rps: null   # null = no limit
    result-batch-size: 100
    max-response-size-bytes: 5242880  # 5MB
    cancellation-grace-period-ms: 30000
    header-blacklist:
      - Authorization
      - Host
      - Content-Length
      - Transfer-Encoding
      - Connection
      - X-Correlation-Id
  retry:
    default-max-retries: 0
    max-max-retries: 10
    default-retry-delay-ms: 1000
    max-retry-delay-ms: 60000          # dual role: (1) validation ceiling for base retryDelayMs, (2) cap on computed exponential delay
    default-retry-backoff-multiplier: 2.0
    max-retry-backoff-multiplier: 10.0
```

## Risks / Trade-offs

| Risk | Mitigation |
|------|------------|
| **User JWT expires during long evaluation** | v1 accepts auth failures. Results written before expiry are preserved. Post-v1: service account / token refresh. |
| **Target endpoint overload** | User-configurable `rateLimitRps` and `concurrencyLevel`. System admin can set max values. Default is sequential (safest). |
| **Large response bodies fill analytics DB** | Configurable `max-response-size-bytes` with truncation. Default 5MB is generous for LLM text responses. |
| **In-process execution shares JVM with API server** | Existing `ThreadPoolTaskExecutor` bounds total concurrent jobs. Virtual threads within a job are lightweight but HTTP connections consume memory. Monitored via existing health/metrics. |
| **Streaming response format assumptions** | Two-mode accumulator: try OpenAI assembly first, fall back to JSON array of all `data:` events. Handles both OpenAI and custom SSE formats. |
| **Restart kills running evaluations** | Accepted for v1. Startup reconciliation marks orphaned runs as FAILED. Results written before crash are preserved in analytics DB. |
| **Virtual threads + synchronized code** | Spring's `@Transactional` uses ThreadLocal which works with virtual threads. `Semaphore` is virtual-thread-safe. Avoid `synchronized` blocks in worker code — use `ReentrantLock` if needed. |

## Open Questions (all resolved)

| # | Question | Resolution |
|---|----------|------------|
| 1 | Should `MockRequestBodyBuilder` be renamed to `RequestBodyResolver`? | **Resolved (D9):** No — `MockRequestBodyBuilder` is deleted. Worker uses `ResolvedRequestService` directly for full request resolution. Fallback-to-data logic lives in the worker. |
| 2 | Should we add a `responseContentType` field to `TestCaseRunResult`? | **Deferred:** Can be added later without migration (JSONB stores mixed types) |
| 3 | K8s Job container image and submission details | **Post-v1:** Interface designed now (D1), implementation later |
| 4 | Service-to-service auth for K8s Jobs calling backend API | **Post-v1** |
| 5 | Header blacklist for user-provided request headers | **Resolved (D14):** Dual validation — save-time (`isValid=false` + `validationWarning`) and call-time (skip + log). Configurable blacklist in `application.yml` |
| 6 | How to handle non-OpenAI streaming formats | **Resolved (D3):** Two-mode accumulator — OpenAI assembly first, JSON array fallback for custom SSE |
| 7 | Should `DialCoreDeploymentInvoker` be extended or new class created? | **Resolved (D13):** Extend existing class with `invokeWithStreaming()` method. Shared by eval + future try-it-out |
