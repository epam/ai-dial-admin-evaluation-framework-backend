## Context

Users can configure test suites with request templates, input bindings, and deployment references, but there's no way to validate this configuration against a real DIAL Core deployment without triggering a full evaluation run (which is mock-only today).

The existing `ResolvedRequestService` already performs full template resolution (URL, query params, headers, body) with type-preserving placeholder substitution, and there is infrastructure for JWT token propagation to DIAL Core. What's missing is:
1. An HTTP client method to **invoke** deployment endpoints (vs. the existing metadata-only GET calls)
2. Controller endpoints to orchestrate the "resolve → invoke → proxy" flow

## Goals / Non-Goals

**Goals:**
- Allow users to send a single resolved request to a DIAL Core deployment and see the actual response
- Support two modes: test-case-based (uses existing data + bindings) and variables-based (direct constant values)
- Reuse existing `ResolvedRequestService` for template resolution
- Keep the DIAL Core invocation component cleanly separated from the metadata client

**Non-Goals:**
- Streaming/SSE response proxying (synchronous only for V1)
- Storing try-it-out results in the database
- Rate limiting or cost tracking
- Response schema auto-detection (future feature that builds on this)

## Decisions

### Decision 1: Two endpoints over a single union endpoint

**Choice:** Separate endpoints for the two modes.

```
POST /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out
POST /api/v1/test-suites/{testSuiteId}/try-it-out
```

**Rationale:** The test-case-based endpoint naturally belongs under the test case resource (consistent with existing `/resolved-request` pattern). Each endpoint gets a clean, focused DTO — no XOR validation on a union type. The service layer shares the core logic through private methods.

**Alternative considered:** Single endpoint with `testCaseId` OR `variables` in body — rejected because it produces awkward validation and unclear OpenAPI documentation.

### Decision 2: Separate `DialCoreDeploymentInvoker` component

**Choice:** Create `DialCoreDeploymentInvoker` in `client.dialcore` package with its own `RestClient` bean, separate from the existing `DialCoreClient`.

**Rationale:**
- `DialCoreClient` fetches deployment metadata: GET-only, retries, 30s timeout
- `DialCoreDeploymentInvoker` invokes deployment endpoints: any HTTP method, no retries (idempotency not guaranteed), 120s timeout
- Different non-functional requirements → different client configuration
- Single Responsibility: metadata client stays focused

**Alternative considered:** Adding an `invoke()` method to existing `DialCoreClient` — rejected because timeout and retry requirements differ fundamentally.

**Layering:** The invoker lives in `client.dialcore` and must NOT depend on `service.*` types (consistent with existing `DialCoreClient` which imports zero service-layer classes). Therefore:
- The invoker's `invoke()` method accepts Spring/JDK types: `HttpHeaders` for headers and `MultiValueMap<String, String>` for query params — not `KeyValueTemplateDto` (which is a service-layer DTO in `service.domain.dto`).
- The invoker returns its own `DeploymentInvocationResponse` record (in `client.dialcore`), not a service-layer DTO. The service maps it to `TryItOutCoreResponseDto`.
- The service layer (`TryItOutService`) is responsible for converting `List<KeyValueTemplateDto>` from `ResolvedRequestDto` into `HttpHeaders`/`MultiValueMap<String, String>` before calling the invoker.

This preserves the dependency direction: `service → client`, not `client → service`.

**Interceptor sharing:** The authorization token interceptor (reading from `AuthorizationTokenHolder`) is shared between both RestClient beans. Extract from the private method in `DialCoreClientConfiguration` to a package-private static method reusable by `DialCoreDeploymentInvokerConfiguration` (both in `client.dialcore`).

### Decision 3: URL prefix routing via known OpenAI-standard paths

**Choice:** Maintain a `Set<String>` of known OpenAI-standard paths in `DialCoreUrlBuilder`. If the resolved URL matches any entry → use `/openai/deployments/{deploymentId}` prefix; otherwise → use `/v1/deployments/{deploymentId}/route` prefix (custom application routes).

Known standard paths (V1): `/chat/completions`, `/embeddings`

Full URL construction:
```
Standard:  {coreBaseUrl}/openai/deployments/{deploymentId}{resolvedUrl}
Custom:    {coreBaseUrl}/v1/deployments/{deploymentId}/route{resolvedUrl}
```

**Rationale:** DIAL Core proxies the OpenAI-compatible API. Both `/chat/completions` and `/embeddings` are common endpoints users will target. Using a `Set` instead of a single equality check is the same complexity but covers known standard endpoints and is trivially extensible (add a string to the set). Custom routes follow DIAL Core's documented pattern. The comparison is centralized in `DialCoreUrlBuilder`.

**Alternative considered:** Deriving from deployment type or `operationId` — rejected as over-engineering for V1. Field-based routing (storing prefix in test suite) would require schema changes.

### Decision 4: Reuse existing `ResolvedRequestService` methods without visibility changes

**Choice:** Two reuse strategies by endpoint mode, no visibility change needed:

- **Test-case path:** Delegate to the existing `resolveRequest(UUID testSuiteId, UUID testCaseId)` (already `public`, `@Transactional(value = "metaTransactionManager", readOnly = true)`). This method already handles suite/test-case loading, JSONB deserialization via `JsonbMapper`, effective template/bindings determination, and resolution. Since `TryItOutService` itself has no `@Transactional`, the transaction is scoped to the `resolveRequest()` call only — the DB connection is released before the DIAL Core invocation begins.
- **Variables path:** Call the package-private `resolve(RequestTemplateDto, List<InputBindingDto>, Map<String, Object>)` directly. Both classes are in `service.domain`, so package-private access is sufficient — no visibility change required.

**Rationale:** The test-case path avoids duplicating ~20 lines of loading/deserialization logic (suite lookup, test-case lookup, ownership check, `JsonbMapper` calls, effective template/bindings fallback). The `@Transactional` scoping concern resolves naturally: Spring creates a transaction for `resolveRequest()` and commits it on return, before the long-running DIAL Core call.

For the variables-based mode, convert `Map<String, Object> variables` to `List<InputBindingDto>` with all `constantValue` bindings, then call `resolve()` with the suite's template and empty data map.

### Decision 5: Error proxying (V1)

**Choice:** Proxy DIAL Core response status + body as-is in the structured response. The try-it-out endpoint always returns HTTP 200 to the client, with the upstream status code inside the response body.

**Rationale:** The try-it-out response is an envelope containing both the resolved request (for debugging) and the DIAL Core response. A 4xx from DIAL Core doesn't mean the try-it-out request was invalid — it means the deployment rejected the payload. The client needs to see the actual upstream response to debug their configuration.

**Exception:** Infrastructure failures (DIAL Core unreachable, timeout) bubble up as 502/504 using the existing `UPSTREAM_ERROR`/`UPSTREAM_TIMEOUT` error codes.

### Decision 6: Separate try-it-out timeout configuration

**Choice:** Add `dial.components.core.try-out.read-timeout-ms` (default: 120000) for the invoker's `RestClient`.

**Rationale:** LLM inference latency (30-120s+) is much longer than metadata API calls (< 1s). Using the metadata client's 30s timeout would cause false timeouts. A separate config allows independent tuning.

## Risks / Trade-offs

**[Cost exposure]** → Each try-it-out call consumes real LLM tokens via the user's own JWT permissions. No server-side mitigation in V1 — rate limiting is a non-goal. The UI should show a confirmation dialog before sending.

**[URL routing heuristic]** → The known-paths set (`/chat/completions`, `/embeddings`) does not cover all possible OpenAI-standard endpoints (e.g., `/images/generations`, `/audio/transcriptions`). → Mitigated by centralizing the set in `DialCoreUrlBuilder` — adding a new standard path is a one-line change.

**[Resolution warnings treated as errors]** → If `ResolvedRequestService` produces `REQUIRED`-code warnings, the try-it-out service will reject the request. This prevents sending malformed payloads to DIAL Core, but may be surprising if users expect "best-effort" behavior. → Users can check `/resolved-request` preview first to understand what's missing.

**[No retry on invocation]** → Unlike metadata calls, deployment invocations are not retried. If a transient failure occurs, the user must manually retry. → Acceptable for V1 "try it out" UX.

**[Double suite loading in test-case path]** → The test-case path loads the suite twice: once in `TryItOutService` for precondition validation (deploymentRef/endpointRef/requestTemplate not null), once inside `ResolvedRequestService.resolveRequest()`. This avoids modifying `ResolvedRequestService` and provides clear 400 errors before entering the resolution flow. A theoretical TOCTOU race exists (suite modified between reads), but is acceptable for a synchronous user-initiated action.

**[Null variable values]** → When a user sends `{ "variables": { "myVar": null } }`, the null entry is skipped during conversion to `InputBindingDto`. This means the variable falls through to its template default or produces a REQUIRED warning. This is intentional: `null` means "not provided", not "set to null". Users who want to send a JSON null value should use the test-case path with actual test case data.
