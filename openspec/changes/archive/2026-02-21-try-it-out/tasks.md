## 1. Configuration & Properties

- [x] 1.1 Add `try-out.read-timeout-ms` to `DialCoreProperties` (nested `TryOut` class with `readTimeoutMs` field, validated `@Min(0)`)
- [x] 1.2 Add default value `dial.components.core.try-out.read-timeout-ms: 120000` in `application.yml`
- [x] 1.3 Update `docs/configuration.md` with the new property

## 2. Client Layer — DialCoreDeploymentInvoker

- [x] 2.1 Extract the authorization token interceptor from `DialCoreClientConfiguration` into a package-private static method (or shared utility within `client.dialcore`) so both RestClient beans can reuse it. Create `DialCoreDeploymentInvokerConfiguration` — new `@Bean("dialCoreTryOutRestClient")` RestClient with try-out read timeout, shared connect timeout, shared base URL, and the shared authorization token interceptor
- [x] 2.2 Create `DeploymentInvocationResponse` record in `client.dialcore` — `int statusCode` and `Object body` (nullable). This is the invoker's return type, kept in the client layer to avoid the client depending on service-layer DTOs
- [x] 2.3 Create `DialCoreDeploymentInvoker` component in `client.dialcore` — `invoke(HttpMethod method, String path, HttpHeaders headers, MultiValueMap<String, String> queryParams, Object body)` method returning `DeploymentInvocationResponse`. Headers use Spring `HttpHeaders` and query params use `MultiValueMap<String, String>` — both support duplicate keys natively. This keeps the client layer free of `service.*` imports (consistent with existing `DialCoreClient`). The service layer converts `List<KeyValueTemplateDto>` → `HttpHeaders`/`MultiValueMap` before calling. Inject `ObjectMapper` for response body parsing. **Content-Type:** set `Content-Type: application/json` on outgoing requests when a body is present. **Body handling:** only include a request body for POST, PUT, and PATCH methods; for GET/DELETE/HEAD/OPTIONS, ignore the `body` parameter. **Error handling:** catch `ResourceAccessException` and inspect the cause chain — if root cause is `SocketTimeoutException` → throw `DialCoreClientException(HttpStatus.GATEWAY_TIMEOUT, ...)` (maps to 504); otherwise (e.g., `ConnectException`, `UnknownHostException`) → throw `DialCoreClientException(HttpStatus.BAD_GATEWAY, ...)` (maps to 502). HTTP 4xx/5xx from Core → return as-is (no throw). **Response body parsing:** read response as raw `String`; attempt `objectMapper.readValue(body, Object.class)`; if parsing succeeds → use parsed object as `body`; if `JsonProcessingException` → use raw string as `body`
- [x] 2.4 Add `@LogExecution` annotation to `DialCoreDeploymentInvoker`

## 3. DTOs

- [x] 3.1 Create `TryItOutWithVariablesRequestDto` in `service.domain.dto` — `variables` field (`Map<String, Object>`, `@NotNull`; empty map is valid for fully static templates)
- [x] 3.2 Create `TryItOutCoreResponseDto` in `service.domain.dto` — `statusCode` (int) and `body` (Object, nullable). The service layer constructs this from `DeploymentInvocationResponse` (client-layer record) — a simple field-by-field mapping (no MapStruct needed)
- [x] 3.3 Create `TryItOutResponseDto` in `service.domain.dto` — `resolvedRequest` (ResolvedRequestDto), `response` (TryItOutCoreResponseDto), `durationMs` (Long)

## 4. Service Layer — TryItOutService

- [x] 4.1 Create `DialCoreUrlBuilder` component in `service.domain` with `@LogExecution` — `buildUrl(String deploymentId, String resolvedUrl)` method implementing URL prefix routing using a `Set<String>` of known OpenAI-standard paths (`/chat/completions`, `/embeddings`). If resolved URL matches a known path → `/openai/deployments/{id}{resolvedUrl}`; otherwise → `/v1/deployments/{id}/route{resolvedUrl}`. Add a comment noting the resolved URL comes from template variable substitution (not `endpointRef.relativeUrlPattern`), so a template like `/chat/${{action}}` resolving to `/chat/completions` is routed correctly.
- [x] 4.2 Create `TryItOutService` in `service.domain` with:
  - `tryWithTestCase(UUID testSuiteId, UUID testCaseId)`:
    1. Load suite via `TestSuiteRepository.findById()` (for `deploymentRef`/`endpointRef` validation — deserialize JSONB via `JsonbMapper.map(suite.getDeploymentRef())` → `DeploymentReferenceDto` and `JsonbMapper.map(suite.getEndpointRef())` → `EndpointContractDto`)
    2. Validate suite preconditions (deploymentRef, endpointRef, requestTemplate not null)
    3. Delegate resolution to existing `ResolvedRequestService.resolveRequest(testSuiteId, testCaseId)` — this handles test-case loading, JSONB deserialization, effective template/bindings determination, and resolution within its own `@Transactional(readOnly=true)` scope (DB connection released on return)
    4. Validate resolution result (resolved URL not null, no REQUIRED warnings)
    5. Build DIAL Core URL via `DialCoreUrlBuilder`, invoke via `DialCoreDeploymentInvoker`, measure duration, map `DeploymentInvocationResponse` → `TryItOutCoreResponseDto`, return `TryItOutResponseDto`
    - **Note (accepted trade-off):** The suite is loaded twice — once here for precondition validation, once inside `resolveRequest()`. This avoids modifying `ResolvedRequestService` and provides clear 400 errors for missing deploymentRef/endpointRef/requestTemplate before entering the resolution flow. The theoretical TOCTOU race (suite modified between the two reads) is acceptable for V1.
  - `tryWithVariables(UUID testSuiteId, Map<String, Object> variables)`:
    1. Load suite via `TestSuiteRepository.findById()`
    2. Deserialize `deploymentRef` → `DeploymentReferenceDto`, `endpointRef` → `EndpointContractDto`, `requestTemplate` → `RequestTemplateDto` via `JsonbMapper`. Do NOT deserialize `inputBindings` — they are fully replaced by the user-provided variables
    3. Validate suite preconditions
    4. Convert variables map to `List<InputBindingDto>` with `constantValue` entries — skip entries where the value is null (treat as if the variable was not provided, allowing template defaults to apply) and skip entries where the key is blank (a blank key cannot match any `${{var}}` placeholder)
    5. Call package-private `ResolvedRequestService.resolve(template, convertedBindings, emptyMap)` (same package, no visibility change needed)
    6. Validate resolution result, build URL, invoke, measure duration, map `DeploymentInvocationResponse` → `TryItOutCoreResponseDto`, return `TryItOutResponseDto`
  - Shared private helper to convert `List<KeyValueTemplateDto>` (from `ResolvedRequestDto`) to `HttpHeaders` and `MultiValueMap<String, String>` for the invoker call — preserves duplicate keys
  - Shared private method for: validation (deploymentRef not null, endpointRef.method not null, requestTemplate not null; post-resolution: resolved URL not null, no REQUIRED warnings), URL construction, invocation, and timing
  - **No `@Transactional`** on public methods — for the test-case path, `resolveRequest()` manages its own transaction; for the variables path, DB reads are simple repository calls outside a transaction; in both cases, the DIAL Core invocation (up to 120s) runs without holding a DB connection
- [x] 4.3 Add `@LogExecution` to `TryItOutService`

## 5. Web Layer — Controllers

- [x] 5.1 Create `TestCaseTryOutController` at `/api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out` — `POST` endpoint, injects `TryItOutService`, calls `tryWithTestCase()`, OpenAPI annotations (`@Tag`, `@Operation`, `@ApiResponse`), `@LogExecution` on controller class
- [x] 5.2 Create `TestSuiteTryOutController` at `/api/v1/test-suites/{testSuiteId}/try-it-out` — `POST` endpoint with `@Valid @RequestBody TryItOutWithVariablesRequestDto`, injects `TryItOutService`, calls `tryWithVariables()`, OpenAPI annotations, `@LogExecution` on controller class
- [x] 5.3 Add exception handling in `DefaultExceptionHandler` for any new exception types from the invoker (or verify existing `DialCoreClientException` handling covers 502/504 mapping)

## 6. Tests

- [x] 6.1 Unit test for `DialCoreUrlBuilder` — test `/chat/completions` → `/openai/deployments/{id}/chat/completions`, `/embeddings` → `/openai/deployments/{id}/embeddings`, and custom routes → `/v1/deployments/{id}/route{path}`
- [x] 6.2 Unit test for `DialCoreDeploymentInvoker` — use `MockRestServiceServer` (same pattern as existing `DialCoreClientTest`); test: successful JSON response is parsed (returns `DeploymentInvocationResponse`), non-JSON response returns raw string, HTTP 4xx/5xx from Core returns status+body as-is (no throw), connection failure (`ResourceAccessException` with `ConnectException` cause) throws `DialCoreClientException` with 502, read timeout (`ResourceAccessException` with `SocketTimeoutException` cause) throws `DialCoreClientException` with 504, body ignored for GET requests, Content-Type set to `application/json` for POST requests with body
- [x] 6.3 Unit test for `TryItOutService` — mock `ResolvedRequestService` (both `resolveRequest()` for test-case path and `resolve()` for variables path), `DialCoreDeploymentInvoker`, `TestSuiteRepository`, `JsonbMapper`; verify validation logic (missing deploymentRef, missing template, missing endpointRef, REQUIRED warnings → 400), URL construction, timing, `KeyValueTemplateDto` → `HttpHeaders`/`MultiValueMap` conversion, blank-key and null-value variable filtering
- [x] 6.4 Functional test for test-case try-it-out endpoint — create suite + test case with template/bindings, `@MockBean` the `DialCoreDeploymentInvoker` to return a canned response, verify resolved request + proxied response via HTTP
- [x] 6.5 Functional test for suite-level try-it-out with variables — create suite with template, send variables, `@MockBean` the `DialCoreDeploymentInvoker`, verify resolution + response via HTTP
- [x] 6.6 Functional tests for error scenarios — missing deploymentRef, missing template, non-existent suite/test-case (these don't need invoker mock); for timeout/connection scenarios use `@MockBean` invoker that throws `DialCoreClientException` with appropriate status codes

## 7. OpenAPI Examples & Documentation

- [x] 7.1 Add OpenAPI example JSON files for both try-it-out endpoints (request + response) under `src/main/resources/openapi/examples/`
- [x] 7.2 Update `openspec/specs/README.md` with new `try-it-out` capability entry
