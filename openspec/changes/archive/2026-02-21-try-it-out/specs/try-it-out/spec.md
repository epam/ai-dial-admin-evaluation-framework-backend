## ADDED Requirements

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
The system SHALL validate the test suite configuration before invoking the DIAL Core deployment. The request SHALL be rejected if preconditions are not met.

#### Scenario: Missing deployment reference
- **WHEN** the test suite has `deploymentRef` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that deployment reference is required

#### Scenario: Missing request template
- **WHEN** the test suite has `requestTemplate` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that request template is required

#### Scenario: Missing endpoint reference
- **WHEN** the test suite has `endpointRef` as null or `endpointRef.method` is null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that endpoint reference with HTTP method is required

#### Scenario: Null resolved URL
- **WHEN** the resolved URL is null after template resolution (e.g., `urlTemplate` was null in the request template)
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that a resolved URL is required for invocation

#### Scenario: Unresolvable required template variables
- **WHEN** template resolution produces warnings with `REQUIRED` code (unbound variables without defaults)
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`
- **AND** the error message SHALL list the unresolved variable names (e.g., "Unresolved required template variables: [prompt, model]")
- **AND** the `resolvedRequest` (including all warnings) SHALL be included in the error response details field so the client can see exactly which variables failed and why

---

### Requirement: TryItOutResponseDto structure
The response SHALL be an envelope containing the resolved request, the DIAL Core response, and timing information.

#### Scenario: Response structure
- **WHEN** system returns a try-it-out response
- **THEN** `TryItOutResponseDto` SHALL include:
  - `resolvedRequest` (`ResolvedRequestDto`) — the resolved URL, query params, headers, and body that were sent to DIAL Core
  - `response` (`TryItOutCoreResponseDto`) — the deployment's response containing `statusCode` (int) and `body` (Object, nullable — parsed JSON or raw string)
  - `durationMs` (Long) — wall-clock time for the DIAL Core invocation in milliseconds

#### Scenario: DIAL Core returns error status
- **WHEN** DIAL Core returns 4xx or 5xx status code
- **THEN** the try-it-out endpoint SHALL still return HTTP 200
- **AND** the `response.statusCode` SHALL contain the actual upstream status code
- **AND** the `response.body` SHALL contain the upstream response body

#### Scenario: Response body is valid JSON
- **WHEN** DIAL Core returns a response body that is valid JSON
- **THEN** `response.body` SHALL be the parsed JSON value (object, array, string, number, boolean, or null)

#### Scenario: Response body is not JSON
- **WHEN** DIAL Core returns a response body that is not valid JSON (e.g., HTML error page, plain text)
- **THEN** `response.body` SHALL be the raw response string

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
