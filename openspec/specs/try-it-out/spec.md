# Try It Out Spec

## Purpose

Endpoints for sending a single resolved request to a DIAL Core deployment or MCP tool call and proxying the response. Covers test-case-based and variables-based modes for both HTTP and MCP suites, URL routing, timeout configuration, error proxying, type-aware validation rules, and SSE streaming response handling (`streaming=true`, `events` list, `{"events":[...]}` body envelope).

Status: **Implemented**

## Requirements

### Requirement: Try it out with test case data
The system SHALL provide `POST /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out` to resolve the effective request template using the test case's data and effective bindings, send the resolved request to the DIAL Core deployment referenced by the test suite, and return the deployment's response along with the resolved request details.

#### Scenario: Successful try-it-out with test case
- **WHEN** authenticated user sends POST to `/api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out`
- **AND** the test suite has a valid `deploymentRef`, `requestTemplate`, and `endpointRef`
- **AND** the test case exists and belongs to the test suite
- **THEN** the system SHALL load the suite (for `deploymentRef`/`endpointRef` precondition validation via `JsonbMapper` deserialization), then delegate resolution to `ResolvedRequestService.resolveRequest(testSuiteId, testCaseId)` which handles test-case loading, effective template/bindings determination, and resolution within its own `@Transactional(readOnly=true)` scope. Note: this results in the suite being loaded twice (accepted trade-off for clear pre-validation errors without modifying `ResolvedRequestService`).
- **AND** send the resolved request to the DIAL Core deployment (after the transaction completes and the DB connection is released)
- **AND** return HTTP 200 with `TryItOutResponseDto` containing the resolved request, the deployment's response (status code + body), and execution duration in milliseconds

#### Scenario: Test case with template/bindings overrides
- **WHEN** the test case has `requestTemplateOverride` and/or `inputBindingsOverride`
- **THEN** the system SHALL use the overrides instead of suite-level template/bindings for resolution

#### Scenario: Test suite not found
- **WHEN** user sends try-it-out request with non-existent `testSuiteId`
- **THEN** the system SHALL return HTTP 404 with error code `NOT_FOUND`

#### Scenario: Test case not found
- **WHEN** user sends try-it-out request with non-existent `testCaseId` or a test case that does not belong to the test suite
- **THEN** the system SHALL return HTTP 404 with error code `NOT_FOUND`

---

### Requirement: Try it out with variables
The system SHALL provide `POST /api/v1/test-suites/{testSuiteId}/try-it-out` accepting a `variables` map (`Map<String, Object>`) in the request body. Each entry maps a template variable name to its constant value. The system SHALL resolve the suite's request template by treating each variable as a constant-value binding, send the resolved request to the DIAL Core deployment, and return the response.

#### Scenario: Successful try-it-out with variables
- **WHEN** authenticated user sends POST to `/api/v1/test-suites/{testSuiteId}/try-it-out` with body `{ "variables": { "prompt": "Hello", "model": "gpt-4" } }`
- **AND** the test suite has a valid `deploymentRef`, `requestTemplate`, and `endpointRef`
- **THEN** the system SHALL load the suite, deserialize JSONB fields via `JsonbMapper` (`deploymentRef` → `DeploymentReferenceDto`, `endpointRef` → `EndpointContractDto`, `requestTemplate` → `RequestTemplateDto`). The suite's `inputBindings` are NOT deserialized — they are fully replaced by the user-provided variables.
- **AND** convert the variables map to constant-value `InputBindingDto` entries (each map entry becomes an `InputBindingDto` with `templateVariable` = key and `constantValue` = value)
- **AND** resolve the suite's request template by calling package-private `ResolvedRequestService.resolve(template, convertedBindings, emptyMap)` (same `service.domain` package)
- **AND** send the resolved request to the DIAL Core deployment
- **AND** return HTTP 200 with `TryItOutResponseDto`

#### Scenario: Variables must not be null
- **WHEN** user sends try-it-out request with `variables` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Empty variables map is valid
- **WHEN** user sends try-it-out request with `variables` as an empty map `{}`
- **AND** the template has no `${{...}}` placeholders (fully static)
- **THEN** the system SHALL accept the request and proceed with resolution and invocation

#### Scenario: Variable with null value
- **WHEN** user sends try-it-out request with a variable mapped to null (e.g., `{ "variables": { "myVar": null } }`)
- **THEN** the system SHALL skip that entry when converting to `InputBindingDto` (treat it as if the variable was not provided)
- **AND** the template variable will fall through to its default value (if any) or produce a `REQUIRED` warning if no default exists

#### Scenario: Variable with blank key
- **WHEN** user sends try-it-out request with a blank key in the variables map (e.g., `{ "variables": { "": "value" } }`)
- **THEN** the system SHALL skip that entry when converting to `InputBindingDto` (a blank key cannot match any `${{var}}` placeholder)

#### Scenario: Test suite not found
- **WHEN** user sends try-it-out request with non-existent `testSuiteId`
- **THEN** the system SHALL return HTTP 404 with error code `NOT_FOUND`

---

### Requirement: Validation before invocation
The system SHALL validate the test suite configuration before invoking the deployment or MCP tool. The request SHALL be rejected if preconditions are not met. Validation SHALL be type-aware: DEPLOYMENT suites validate HTTP preconditions, MCP_TOOL suites validate MCP preconditions.

#### Scenario: Missing deployment reference (DEPLOYMENT suite)
- **WHEN** a DEPLOYMENT suite has `deploymentRef` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that deployment reference is required

#### Scenario: Missing request template (DEPLOYMENT suite)
- **WHEN** a DEPLOYMENT suite has `requestTemplate` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that request template is required

#### Scenario: Missing endpoint reference (DEPLOYMENT suite)
- **WHEN** a DEPLOYMENT suite has `endpointRef` as null or `endpointRef.method` is null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that endpoint reference with HTTP method is required

#### Scenario: Missing MCP deployment reference (MCP_TOOL suite)
- **WHEN** an MCP_TOOL suite has `mcpDeploymentRef` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that MCP deployment reference is required

#### Scenario: Missing tool reference (MCP_TOOL suite)
- **WHEN** an MCP_TOOL suite has `toolRef` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that tool reference is required

#### Scenario: Null resolved URL (DEPLOYMENT suite only)
- **WHEN** the resolved URL is null after template resolution
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

> **Note:** This scenario applies only to DEPLOYMENT suites. MCP_TOOL suites do not resolve URLs.

#### Scenario: Unresolvable required template variables
- **WHEN** template/argument resolution produces warnings with `REQUIRED` code
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`
- **AND** the error message SHALL list the unresolved variable names
- **AND** the `resolvedRequest` SHALL be included in the error response details field

> **Note:** This scenario applies to both suite types: DEPLOYMENT suites check URL template variables, MCP_TOOL suites check argument template variables.

---

### Requirement: Try it out with MCP tool call (test case)

The system SHALL support try-it-out for MCP_TOOL suites via `POST /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out`. For MCP suites, the system SHALL resolve tool arguments from the argument template using effective bindings and test case data, execute the MCP tool call via `McpToolInvoker`, and return the MCP response.

#### Scenario: Successful MCP try-it-out with test case
- **WHEN** authenticated user sends POST to `/api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out`
- **AND** the test suite has `suiteType = MCP_TOOL` with valid `mcpDeploymentRef`, `toolRef`, and `argumentTemplate`
- **THEN** the system SHALL determine effective bindings: test case `inputBindingsOverride` (if non-null) takes priority over suite-level `inputBindings` (same override semantics as HTTP suites)
- **AND** resolve tool arguments via `McpRequestResolver.resolve(argumentTemplate, effectiveBindings, testCaseData)`
- **AND** determine the MCP transport from `mcpDeploymentRef.transport` (defaulting to `STREAMABLE_HTTP` when null)
- **AND** execute `McpToolInvoker.callTool(mcpDeploymentRef.id, toolRef.name, resolvedArguments, token, transport)`
- **AND** serialize the `CallToolResult` via `McpResponseSerializer`
- **AND** return HTTP 200 with `TryItOutResponseDto` containing the resolved arguments, the serialized MCP response, and execution duration

#### Scenario: MCP suite missing mcpDeploymentRef
- **WHEN** the MCP_TOOL suite has `mcpDeploymentRef` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: MCP suite missing toolRef
- **WHEN** the MCP_TOOL suite has `toolRef` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: MCP tool call returns isError
- **WHEN** the MCP tool call returns `isError = true`
- **THEN** the try-it-out endpoint SHALL still return HTTP 200
- **AND** `response.body` SHALL contain the serialized MCP response with `isError: true`

#### Scenario: MCP tool call timeout
- **WHEN** the MCP tool call times out
- **THEN** the system SHALL return HTTP 504 with error code `UPSTREAM_TIMEOUT`

---

### Requirement: Try it out with MCP tool call (variables)

The system SHALL support try-it-out with variables for MCP_TOOL suites via `POST /api/v1/test-suites/{testSuiteId}/try-it-out`. Each variable entry maps an argument template variable name to its constant value.

#### Scenario: Successful MCP try-it-out with variables
- **WHEN** authenticated user sends POST to `/api/v1/test-suites/{testSuiteId}/try-it-out` with `{ "variables": { "search_query": "MCP protocol" } }`
- **AND** the test suite has `suiteType = MCP_TOOL`
- **THEN** the system SHALL convert the variables map to constant-value `InputBindingDto` entries via `convertVariablesToBindings()` (each map entry becomes a binding with `templateVariable` = key and `constantValue` = value — same conversion as HTTP try-it-out with variables)
- **AND** resolve tool arguments via `McpRequestResolver.resolveWithVariables(argumentTemplate, convertedBindings, variables)`
- **AND** execute the MCP tool call and return the response

---

### Requirement: MCP try-it-out response structure

The `TryItOutResponseDto` SHALL accommodate MCP responses alongside HTTP responses.

#### Scenario: MCP response in TryItOutResponseDto
- **WHEN** an MCP try-it-out completes
- **THEN** `resolvedRequest` SHALL contain the resolved arguments (as the "body" component — reuse `ResolvedJsonBodyDto` with the arguments map)
- **AND** `response.statusCode` SHALL be `200` when the tool call succeeds (even if `isError = true` — that's tool-level, not transport-level)
- **AND** `response.body` SHALL be the parsed JSON object from `McpResponseSerializer` output (content blocks + structuredContent + isError)

#### Scenario: MCP transport error response
- **WHEN** the MCP tool call fails at the transport level (connection refused, timeout)
- **THEN** the system SHALL return the standard error response (HTTP 502/504) — same pattern as HTTP try-it-out

---

### Requirement: TryItOutResponseDto structure
The response SHALL be an envelope containing the resolved request, the DIAL Core response, timing information, and the OTel trace ID of the invocation.

`TryItOutCoreResponseDto` SHALL include:
- `statusCode` (int) — HTTP status code from DIAL Core
- `body` (Object, nullable) — parsed JSON response body, or `{"events": [...]}` envelope for SSE responses
- `streaming` (Boolean, nullable) — `true` when response was SSE, `null` (omitted from JSON) for non-SSE responses. Uses `@JsonInclude(NON_NULL)`.
- `events` (List of `SseEventDto`, nullable) — parsed SSE events for frontend debugging, `null` (omitted from JSON) for non-SSE responses. Uses `@JsonInclude(NON_NULL)`.

`SseEventDto` SHALL be a DTO with:
- `event` (String) — SSE event type name (e.g., `"process_rules"`, `"message"`)
- `data` (Object) — parsed JSON payload if valid JSON, raw string otherwise

#### Scenario: Response structure (non-SSE)
- **WHEN** system returns a try-it-out response for a non-SSE invocation
- **THEN** `TryItOutResponseDto` SHALL include:
  - `resolvedRequest` (`ResolvedRequestDto`) — the resolved URL, query params, headers, and body
  - `response` (`TryItOutCoreResponseDto`) — `statusCode` (int), `body` (Object, nullable — parsed JSON or raw string), `streaming` = `null` (omitted), `events` = `null` (omitted)
  - `durationMs` (Long) — wall-clock time for the DIAL Core invocation in milliseconds
  - `traceId` (String, nullable) — the 32-char hex OTel trace ID; null when tracing disabled

#### Scenario: Response structure (SSE)
- **WHEN** DIAL Core returns an SSE response (Content-Type is `text/event-stream`)
- **THEN** `response.streaming` SHALL be `true`, `response.events` SHALL contain the list of parsed `SseEventDto` objects in order of receipt, `response.body` SHALL be the `{"events": [...]}` envelope (the document JSONata would operate on)

#### Scenario: DIAL Core returns error status
- **WHEN** DIAL Core returns 4xx or 5xx status code
- **THEN** the try-it-out endpoint SHALL still return HTTP 200
- **AND** the `response.statusCode` SHALL contain the actual upstream status code
- **AND** the `response.body` SHALL contain the upstream response body
- **AND** `traceId` SHALL still be populated

#### Scenario: Response body is valid JSON (non-SSE)
- **WHEN** DIAL Core returns a non-SSE response body that is valid JSON
- **THEN** `response.body` SHALL be the parsed JSON value (object, array, string, number, boolean, or null)

#### Scenario: Response body is not JSON (non-SSE)
- **WHEN** DIAL Core returns a non-SSE response body that is not valid JSON (e.g., HTML error page, plain text)
- **THEN** `response.body` SHALL be the raw response string

#### Scenario: Resolved request with multipart body
- **WHEN** the test suite uses a `multipart/form-data` request template
- **THEN** `resolvedRequest.body` SHALL be a `ResolvedMultipartBodyDto` showing the resolved form parts (text values and file blob UUIDs)

#### Scenario: Resolved request with URL-encoded body
- **WHEN** the test suite uses a `application/x-www-form-urlencoded` request template
- **THEN** `resolvedRequest.body` SHALL be a `ResolvedUrlEncodedBodyDto` showing the resolved `List<KeyValueTemplateDto>` entries

---

### Requirement: Try-it-out invocation uses streaming-aware invoker
The try-it-out service SHALL use `DialCoreDeploymentInvoker.invokeWithStreaming()` for HTTP/DEPLOYMENT suite invocations. For non-SSE responses, behavior is unchanged — `DeploymentInvocationResult.body()` provides the parsed response. For SSE responses, the service SHALL parse events using `SseEventParser` and build the structured response.

Status: **Implemented**

#### Scenario: Non-SSE invocation (behavior preserved)
- **WHEN** try-it-out invokes deployment and response Content-Type is NOT `text/event-stream`
- **THEN** system SHALL read parsed body from `DeploymentInvocationResult.body()`, build `TryItOutCoreResponseDto` with `statusCode` and `body`, with `streaming` and `events` as `null`

#### Scenario: SSE invocation
- **WHEN** try-it-out invokes deployment and response Content-Type is `text/event-stream`
- **THEN** system SHALL parse SSE stream via `SseEventParser`, build `TryItOutCoreResponseDto` with `streaming=true`, `events` containing parsed `SseEventDto` list, and `body` containing `{"events": [...]}` envelope

#### Scenario: SSE invocation resource cleanup
- **WHEN** try-it-out processes an SSE response
- **THEN** system SHALL use `DeploymentInvocationResult` in try-with-resources to ensure `eventStream` is closed

---

### Requirement: Try-it-out SSE timeout enforcement
When processing SSE responses, try-it-out SHALL enforce a deadline to prevent indefinite blocking on stalled streams. The deadline SHALL be `clock.millis() + readTimeoutMs` where `readTimeoutMs` is the existing `dial.components.core.try-out.read-timeout-ms` configuration.

Status: **Implemented**

#### Scenario: SSE stream exceeds timeout
- **WHEN** SSE stream is still active after deadline expires
- **THEN** system SHALL stop reading, return partial events accumulated so far, and include `streaming=true` in response

---

### Requirement: Try-it-out SSE size limit enforcement
When processing SSE responses, try-it-out SHALL enforce the same `max-response-size-bytes` limit used by evaluation to prevent OOM on pathological streams.

Status: **Implemented**

#### Scenario: SSE stream exceeds size limit
- **WHEN** accumulated SSE event data exceeds `max-response-size-bytes`
- **THEN** system SHALL stop reading and return partial events accumulated so far

---

### Requirement: Try-it-out SSE parsing error handling
When SSE parsing encounters an error (IOException, malformed stream), try-it-out SHALL return a partial or error response rather than propagating the exception as an HTTP 500.

Status: **Implemented**

#### Scenario: SSE stream read error
- **WHEN** an `IOException` occurs while parsing the SSE stream (e.g., connection reset mid-stream)
- **THEN** system SHALL return partial events accumulated before the error with `streaming=true` and `body` as the `{"events": [...]}` envelope of the partial events

#### Scenario: Empty SSE stream
- **WHEN** DIAL Core returns `Content-Type: text/event-stream` but the stream body is empty (immediate EOF)
- **THEN** `response.streaming` SHALL be `true`, `response.events` SHALL be an empty list, `response.body` SHALL be `{"events": []}`

#### Scenario: SSE timeout returns partial response
- **WHEN** SSE stream exceeds the deadline during try-it-out
- **THEN** system SHALL return partial events accumulated so far with `streaming=true`, and `body` as the envelope of partial events

---

### Requirement: Try-it-out invocation uses pluggable serializer
The try-it-out service SHALL use `RequestBodySerializerRegistry` to serialize the resolved body before invoking the DIAL Core deployment, instead of relying on the invoker's hardcoded JSON serialization.

#### Scenario: JSON body invocation (current behavior preserved)
- **WHEN** the resolved body is `ResolvedJsonBodyDto`
- **THEN** the system SHALL serialize as JSON and invoke DIAL Core with `Content-Type: application/json`

#### Scenario: Multipart body invocation
- **WHEN** the resolved body is `ResolvedMultipartBodyDto`
- **THEN** the system SHALL build a multipart request (reading file bytes from BlobStorage for file parts), and invoke DIAL Core with `Content-Type: multipart/form-data`

#### Scenario: URL-encoded body invocation
- **WHEN** the resolved body is `ResolvedUrlEncodedBodyDto`
- **THEN** the system SHALL build a URL-encoded form body and invoke DIAL Core with `Content-Type: application/x-www-form-urlencoded`

---

### Requirement: Try-it-out invocation span
Each try-it-out invocation SHALL create an OTel child span that wraps the DIAL Core HTTP call. The span's trace ID SHALL be returned in the response and propagated to DIAL Core via `traceparent`.
Status: **Implemented**

#### Scenario: Invocation span created
- **WHEN** `TryItOutService` calls `invokeAndBuildResponse()`
- **THEN** an OTel span named `try-it-out.invoke` SHALL be started as a child of the current HTTP request span
- **AND** the span SHALL be ended after the DIAL Core response is received (or on failure)

#### Scenario: traceId returned in response
- **WHEN** try-it-out completes (success or DIAL Core error)
- **THEN** `TryItOutResponseDto.traceId` SHALL contain the 32-char hex trace ID of the invocation span

#### Scenario: traceId null when tracing disabled
- **WHEN** Micrometer Tracing is disabled (`management.tracing.enabled=false`)
- **THEN** `TryItOutResponseDto.traceId` SHALL be null (Micrometer returns a no-op span; its trace ID is all-zeros, which SHALL be treated as absent and serialized as null via `@JsonInclude(NON_NULL)`)

---

### Requirement: TryItOutWithVariablesRequestDto structure
The request body for the suite-level try-it-out endpoint.

#### Scenario: Request structure
- **WHEN** client sends a try-it-out with variables request
- **THEN** `TryItOutWithVariablesRequestDto` SHALL include:
  - `variables` (`Map<String, Object>`, required, not null, may be empty) — template variable names mapped to their constant values. An empty map is valid when the template has no placeholders.

---

### Requirement: URL construction for DIAL Core invocation
The system SHALL construct the full DIAL Core URL by combining the base URL, a deployment prefix, and the resolved URL template. `DialCoreUrlBuilder` SHALL maintain a `Set<String>` of known OpenAI-standard paths to determine the routing prefix.

Known standard paths (V1): `/chat/completions`, `/embeddings`

#### Scenario: Standard chat completions endpoint
- **WHEN** the resolved URL from the template equals `/chat/completions`
- **THEN** the full URL SHALL be `{coreBaseUrl}/openai/deployments/{deploymentRef.id}/chat/completions`

#### Scenario: Standard embeddings endpoint
- **WHEN** the resolved URL from the template equals `/embeddings`
- **THEN** the full URL SHALL be `{coreBaseUrl}/openai/deployments/{deploymentRef.id}/embeddings`

#### Scenario: Custom application route
- **WHEN** the resolved URL from the template does NOT match any known OpenAI-standard path (e.g., `/my-custom-endpoint`)
- **THEN** the full URL SHALL be `{coreBaseUrl}/v1/deployments/{deploymentRef.id}/route{resolvedUrl}`

#### Scenario: HTTP method from endpoint reference
- **WHEN** constructing the DIAL Core request
- **THEN** the system SHALL use `endpointRef.method` as the HTTP method

---

### Requirement: Error handling for infrastructure failures
The system SHALL map DIAL Core connectivity and timeout failures to appropriate HTTP error responses.

#### Scenario: DIAL Core unreachable
- **WHEN** the DIAL Core deployment is unreachable (connection refused, DNS failure)
- **THEN** the system SHALL return HTTP 502 with error code `UPSTREAM_ERROR`

#### Scenario: DIAL Core timeout
- **WHEN** the DIAL Core deployment does not respond within the configured timeout
- **THEN** the system SHALL return HTTP 504 with error code `UPSTREAM_TIMEOUT`

---

### Requirement: Try-it-out timeout configuration
The system SHALL support a separate read timeout for try-it-out invocations, configurable via `dial.components.core.try-out.read-timeout-ms`.

#### Scenario: Default timeout
- **WHEN** no explicit try-it-out timeout is configured
- **THEN** the system SHALL use a default of 120000 milliseconds (120 seconds)

#### Scenario: Custom timeout
- **WHEN** `dial.components.core.try-out.read-timeout-ms` is set to a custom value
- **THEN** the system SHALL use the configured value for the try-it-out RestClient's read timeout

---

### Requirement: Request headers for DIAL Core invocation
The system SHALL include resolved template headers and the user's authorization token when invoking the DIAL Core deployment.

#### Scenario: Authorization token forwarded
- **WHEN** system invokes DIAL Core deployment
- **THEN** the user's JWT token from the incoming request SHALL be forwarded as `Authorization: Bearer` header

#### Scenario: Template headers included
- **WHEN** the resolved request has custom headers from the template (e.g., `X-Custom: value`)
- **THEN** the service layer SHALL convert `List<KeyValueTemplateDto>` from `ResolvedRequestDto` to `HttpHeaders` and pass them to the invoker (preserving duplicate header names)

#### Scenario: Query parameters included
- **WHEN** the resolved request has query parameters
- **THEN** the service layer SHALL convert `List<KeyValueTemplateDto>` from `ResolvedRequestDto` to `MultiValueMap<String, String>` and pass them to the invoker

---

### Requirement: OpenAPI documentation
The system SHALL expose OpenAPI annotations on both try-it-out endpoints with descriptions, request/response schemas, and error responses.

#### Scenario: Swagger UI shows endpoints
- **WHEN** user navigates to Swagger UI
- **THEN** both try-it-out endpoints are visible under appropriate tags with descriptions and response schemas

---

## Implementation Notes

- Modified: `TryItOutService` — branch by `suiteType`: HTTP flow (existing) or MCP flow (new)
- MCP test-case flow: loads suite-level `inputBindings`, determines effective bindings (test case `inputBindingsOverride` > suite bindings) → `McpRequestResolver.resolve(argumentTemplate, effectiveBindings, testCaseData)` → `McpToolInvoker.callTool(id, tool, args, token, transport)` → `McpResponseSerializer.serialize()` → build `TryItOutResponseDto`
- MCP variables flow: `convertVariablesToBindings(variables)` → `McpRequestResolver.resolveWithVariables(argumentTemplate, convertedBindings, variables)` → same invocation chain
- MCP transport propagation: `TryItOutService` reads `mcpDeploymentRef.transport`, defaults to `McpTransport.STREAMABLE_HTTP` when null, passes to `McpToolInvoker`
- Reuse `TryItOutResponseDto` structure — `resolvedRequest.body` contains arguments as JSON, `response.body` contains serialized MCP response
