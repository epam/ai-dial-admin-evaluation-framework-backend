## Why

The current evaluation job is a mock placeholder that sleeps for a random duration and generates fake results. To deliver real evaluation functionality, we need an execution engine that actually calls target deployment endpoints per test case, captures real responses (including streaming SSE), and writes genuine `TestCaseRunResult` rows. This is the core capability the product was designed around — everything else (suite authoring, case management, result analytics) is infrastructure supporting this.

## What Changes

- **Replace mock evaluation job** with a real in-process execution engine that resolves request bodies (template + bindings + data), calls target endpoints via DIAL Core, and captures responses with timing metrics
- **Handle streaming SSE responses** from LLM deployments (accumulate chunks, measure TTFT/TTLT, reconstruct assembled response)
- **Extend `RunConfigDto`** with grouped execution settings: concurrency control, per-call timeout, retry policy, rate limiting — all optional with sensible defaults
- **Add configurable concurrency within a run** (sequential by default, configurable parallelism via semaphore/bounded executor)
- **Extract response columns (JSONata) during execution** — per result, inline with response capture
- **Batch-write results to analytics DB** with configurable batch size (system-level default, optional per-run override)
- **Graceful cancellation** with configurable grace period — stop dispatching new calls, wait for in-flight to complete or timeout, then abort
- **Design execution engine interface** (`EvaluationExecutor`) for future extraction to K8s Jobs without changing the orchestration layer

## Capabilities

### New Capabilities
- `eval-execution-engine`: In-process evaluation execution engine — worker lifecycle, concurrency control, HTTP call dispatch, streaming response accumulation, error handling, retry, rate limiting, cancellation, batch result writing, progress reporting

### Modified Capabilities
- `test-suite-runs`: Extended `RunConfigDto` with `execution`, `retry`, and response handling settings; new validation rules for execution config; adjusted run creation flow to pass execution settings to the engine
- `analytics-eval-results`: Potential new fields on `TestCaseRunResult` for streaming timing metrics (time-to-first-token, time-to-last-token); response size limits; binary/large payload handling strategy

## Impact

- **Code**: New `service.domain.job` components replacing mock implementations; new execution engine classes; extended DTOs; possible new Flyway migration (analytics) for timing fields
- **API**: `POST /api/v1/test-suites/{testSuiteId}/runs` request body extended (backward-compatible — new fields are optional with defaults)
- **Dependencies**: Reuses existing `DialCoreDeploymentInvoker` for endpoint calls; `TokenPropagationHelper` for JWT propagation to worker threads; `ResponseColumnExtractor` for JSONata extraction
- **Configuration**: New `application.yml` properties for execution defaults (concurrency, timeout, retry, batch size, grace period, response size limits)
- **Systems**: DIAL Core deployments will receive real traffic from evaluation runs; need to consider load impact

## Open Questions (all resolved)

| # | Question | Resolution |
|---|----------|------------|
| 1 | **Streaming detection** — auto-detect from `Content-Type: text/event-stream`, or user-configured per suite? | **Resolved:** Auto-detect from response `Content-Type` header. No user configuration needed. (Design D3) |
| 2 | **Assembled response format** — store assembled response as-if non-streaming, or preserve SSE event log? | **Resolved:** Two-mode accumulator — OpenAI format: assemble into non-streaming response body. Non-OpenAI: store as JSON array of all `data:` events. (Design D3) |
| 3 | **Streaming timing fields** — add `timeToFirstTokenMs` / `timeToLastTokenMs` to `TestCaseRunResult`? | **Resolved:** Yes — analytics migration V1.3, nullable BIGINT columns. (Analytics spec) |
| 4 | **Response size limits** — what default max? What happens when exceeded? | **Resolved:** 5MB default, truncate with warning. `executionStatus = ERROR` (truncated response is incomplete data). (Design D8) |
| 5 | **Token expiration** — user JWT will expire during long-running evaluations. Accept failures for v1? | **Resolved:** v1 accepts auth failures. Results before expiry preserved. Post-v1: service account / refresh. |
| 6 | **Resume after restart** — re-enqueue runs based on already-written results? Or just mark failed? | **Deferred (post-v1):** Mark as FAILED on restart. Results preserved. |
| 7 | **K8s Job extraction** — when and how to extract execution to K8s Jobs? | **Deferred (post-v1):** Interface designed now (`EvaluationExecutor`), K8s implementation later. (Design D1) |
| 8 | **Header blacklist** — which system headers should be blocked from user-provided headers? | **Resolved:** Dual validation — save-time (`isValid=false` + warning via `SuiteValidationService`) and call-time (skip + log). Configurable blacklist: `[Authorization, Host, Content-Length, Transfer-Encoding, Connection, X-Correlation-Id]`. (Design D14) |
| 9 | **Deployment throughput limits** — should we respect per-deployment rate limits? | **Deferred (post-v1):** v1 uses user-configured `rateLimitRps` only. |
| 10 | **Non-OpenAI streaming formats** — are all targets OpenAI-compatible SSE? | **Resolved:** Two-mode accumulator handles both. Custom SSE stored as JSON array for JSONata extraction. (Design D3) |
