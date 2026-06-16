## MODIFIED Requirements

### Requirement: TryItOutResponseDto structure
The response SHALL be an envelope containing the resolved request, the DIAL Core response, timing information, and the OTel trace ID of the invocation.

#### Scenario: Response structure
- **WHEN** system returns a try-it-out response
- **THEN** `TryItOutResponseDto` SHALL include:
  - `resolvedRequest` (`ResolvedRequestDto`) — the resolved URL, query params, headers, and body that were sent to DIAL Core
  - `response` (`TryItOutCoreResponseDto`) — the deployment's response containing `statusCode` (int) and `body` (Object, nullable — parsed JSON or raw string)
  - `durationMs` (Long) — wall-clock time for the DIAL Core invocation in milliseconds
  - `traceId` (String, nullable) — the 32-char hex OTel trace ID of the invocation span; present when Micrometer Tracing is active; null when tracing is disabled

#### Scenario: DIAL Core returns error status
- **WHEN** DIAL Core returns 4xx or 5xx status code
- **THEN** the try-it-out endpoint SHALL still return HTTP 200
- **AND** the `response.statusCode` SHALL contain the actual upstream status code
- **AND** the `response.body` SHALL contain the upstream response body
- **AND** `traceId` SHALL still be populated (the invocation span covers the DIAL Core call regardless of its status)

#### Scenario: Response body is valid JSON
- **WHEN** DIAL Core returns a response body that is valid JSON
- **THEN** `response.body` SHALL be the parsed JSON value (object, array, string, number, boolean, or null)

#### Scenario: Response body is not JSON
- **WHEN** DIAL Core returns a response body that is not valid JSON (e.g., HTML error page, plain text)
- **THEN** `response.body` SHALL be the raw response string

## ADDED Requirements

### Requirement: Try-it-out invocation span
Each try-it-out invocation SHALL create an OTel child span that wraps the DIAL Core HTTP call. The span's trace ID SHALL be returned in the response and propagated to DIAL Core via `traceparent`.

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
