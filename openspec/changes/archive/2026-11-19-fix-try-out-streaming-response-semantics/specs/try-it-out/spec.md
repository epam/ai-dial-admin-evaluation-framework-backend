## MODIFIED Requirements

### Requirement: TryItOutResponseDto structure

Status: **Implemented**

The response SHALL be an envelope containing the resolved request, upstream response, timing information and invocation trace ID. Top-level invocation fields SHALL reflect the last executed invocation. For a plan with more than one invocation (multiple turns, multiple requests, or both), `history` SHALL contain one entry per executed invocation in request-major, turn-minor order, including the last and any failing invocation. Each entry SHALL have its own `resolvedRequest`, `response`, `durationMs`, `traceId` and `grafanaTraceUrl`. A single-request single-turn plan, including a multi-turn case collapsed by absence of per-turn bindings, SHALL omit `history`. A multi-request plan SHALL retain history even when each request has only one turn.

The nested response SHALL contain:
- `statusCode` (integer): the upstream HTTP status.
- `body` (nullable JSON value): the existing upstream body for non-SSE HTTP responses, raw string when that body is not JSON, or the evaluation-equivalent assembled document for SSE responses. An assembled SSE JSON string SHALL be exposed as a string value, not JSON-encoded text. Assembled SSE object, array, scalar and explicit JSON null values SHALL retain their JSON types. Non-SSE responses SHALL retain existing null omission: a Java-null body is omitted and null-valued object/map properties are omitted by response serialization.
- `streaming` (nullable boolean): `true` for HTTP SSE responses, omitted for non-SSE responses.
- `events` (nullable array): parsed SSE events in receipt order for HTTP SSE responses, including an empty array when no events were dispatched; omitted for non-SSE responses. Each event SHALL carry its event type and data as parsed JSON when valid, or a raw string otherwise. This array SHALL NOT claim to be byte-verbatim SSE transport data.
- `streamingStatus`: the existing non-success stream status, omitted for successful/non-SSE responses.
- `truncationWarning`: the existing truncation reason when present, omitted otherwise.

SSE `body` SHALL NOT be an unconditional event envelope. When available, it SHALL be the same assembled JSON document that evaluation uses for the same events and stream status, including provider-specific success documents and existing partial/fallback results. When assembly produces no document, it SHALL retain the parsed events in a diagnostic events-envelope body while preserving the assembly error status. SSE event diagnostics SHALL remain available in `events` independently of that assembled body. This contract SHALL apply to both variables and test-case endpoints and both DEPLOYMENT and ROUTE suites.

#### Scenario: Response structure (non-SSE)
- **WHEN** the system returns a try-it-out response for a non-SSE invocation
- **THEN** it SHALL include the resolved request (URL, query parameters, headers and body), nested response with upstream status and parsed JSON or raw-string body, elapsed duration in milliseconds and the invocation trace ID when tracing is enabled
- **AND** `streaming` and `events` SHALL be omitted
- **AND** duration SHALL retain its existing measurement boundary through the HTTP invocation return, excluding subsequent SSE parsing

#### Scenario: Response structure (SSE)
- **WHEN** DIAL Core returns `Content-Type: text/event-stream` to either try-it-out endpoint
- **THEN** `response.streaming` SHALL be `true`
- **AND** `response.events` SHALL contain parsed events in receipt order
- **AND** `response.body` SHALL contain the evaluation-equivalent assembled JSON value, not JSON-encoded text or an unconditional events envelope

#### Scenario: DIAL Core returns error status
- **WHEN** DIAL Core returns a 4xx or 5xx status code
- **THEN** the try-it-out endpoint SHALL still return HTTP 200 with the actual upstream status in `response.statusCode`
- **AND** `response.body` SHALL contain the upstream non-SSE body or evaluation-equivalent assembled SSE document, according to the response content type
- **AND** the trace ID SHALL be populated when the invocation span is valid

#### Scenario: Response body is valid JSON (non-SSE)
- **WHEN** DIAL Core returns a non-SSE body containing valid JSON
- **THEN** `response.body` SHALL retain the existing parsed JSON response behaviour for objects, arrays, strings, numbers and booleans
- **AND** a root JSON null SHALL retain the existing omitted-body behaviour, and null-valued object/map properties SHALL remain omitted by response serialization

#### Scenario: Response body is not JSON (non-SSE)
- **WHEN** DIAL Core returns a non-SSE body that is not valid JSON, such as HTML or plain text
- **THEN** `response.body` SHALL be the raw response string

#### Scenario: Resolved request with multipart body
- **WHEN** the suite uses a `multipart/form-data` request template
- **THEN** the resolved request body SHALL show the resolved text parts and DIAL file references

#### Scenario: Resolved request with URL-encoded body
- **WHEN** the suite uses an `application/x-www-form-urlencoded` request template
- **THEN** the resolved request body SHALL show the resolved key/value entries

#### Scenario: Multi-invocation plan includes executed history
- **WHEN** a try-it-out plan contains multiple turns, multiple requests, or both
- **THEN** `history` SHALL contain one entry per executed invocation in execution order, including a failing first invocation when later calls are skipped
- **AND** each entry SHALL carry its own `resolvedRequest`, `response`, `durationMs`, `traceId` and `grafanaTraceUrl`
- **AND** the final entry's `resolvedRequest`, `response`, `durationMs`, `traceId` and `grafanaTraceUrl` SHALL match their top-level counterparts
- **AND** fail-fast SHALL retain only the executed prefix, with the failing invocation last
- **AND** each SSE history response SHALL contain that invocation's assembled body and its own parsed events separately

#### Scenario: Single-request single-turn response omits history
- **WHEN** a single-request suite plans exactly one turn, including a multi-turn case collapsed by absence of a per-turn binding
- **THEN** `history` SHALL be omitted

#### Scenario: Recognized streaming providers expose assembled responses
- **WHEN** an HTTP try-out receives a successful chat-completions, Responses API or Anthropic Messages stream recognized by evaluation
- **THEN** `response.body` SHALL equal evaluation's assembled document for that stream
- **AND** chat-completions extensions, including `custom_content` and `custom_fields`, SHALL appear according to evaluation's existing merge semantics
- **AND** the original parsed event payloads SHALL remain in `response.events`

#### Scenario: Structured SSE fallback retains its actual assembled envelope
- **WHEN** evaluation does not recognize a stream's provider mode
- **THEN** `response.body` SHALL retain evaluation's structured events-envelope fallback
- **AND** `response.events` SHALL also contain the parsed event list; this fallback SHALL NOT force recognized provider bodies into envelopes

#### Scenario: Response-column extraction agrees with the exposed body
- **WHEN** a successful SSE try-out performs response-column extraction
- **THEN** extraction SHALL operate on the same assembled JSON document exposed in `response.body`
- **AND** existing extracted columns, warnings and frame propagation across turns/requests SHALL remain unchanged

#### Scenario: MCP response fields remain unchanged
- **WHEN** either try-it-out endpoint completes an MCP tool invocation
- **THEN** `response.body` SHALL remain the parsed final tool-result document
- **AND** `streaming` and `events` SHALL remain omitted, regardless of the SDK's underlying transport

### Requirement: Try-it-out invocation uses streaming-aware invoker

Status: **Implemented**

HTTP try-out invocations for DEPLOYMENT and ROUTE suites SHALL distinguish non-SSE bodies from SSE streams. The system SHALL consume an SSE stream once and expose both its evaluation-equivalent assembled body and parsed event list. Existing resource cleanup SHALL be preserved.

#### Scenario: Non-SSE invocation (behavior preserved)
- **WHEN** an HTTP try-out receives a response whose Content-Type is not `text/event-stream`
- **THEN** the nested response SHALL contain the upstream status and existing parsed body, with `streaming` and `events` omitted

#### Scenario: SSE invocation
- **WHEN** an HTTP try-out receives `Content-Type: text/event-stream`
- **THEN** the system SHALL parse the stream once and assemble the same parsed events using evaluation-equivalent response semantics
- **AND** return `streaming=true`, parsed events in `events` and the parsed assembled document in `body`
- **AND** it SHALL NOT reread the consumed stream to construct either view

#### Scenario: SSE invocation resource cleanup
- **WHEN** try-it-out processes an SSE response
- **THEN** the event stream SHALL be closed after processing, including partial/error processing

### Requirement: Try-it-out SSE parsing error handling

Status: **Implemented**

When SSE parsing encounters a read error, try-it-out SHALL return the dispatched event prefix and evaluation-equivalent partial/fallback body rather than propagating the read failure as HTTP 500. Existing timeout, status, truncation and fail-fast semantics SHALL remain unchanged.

#### Scenario: SSE stream read error
- **WHEN** a read error such as a connection reset occurs during SSE parsing
- **THEN** the response SHALL contain `streaming=true`, `streamingStatus=ERROR` and dispatched events in `events`
- **AND** `body` SHALL be the assembled result evaluation produces from those events and the error status

#### Scenario: Empty SSE stream
- **WHEN** DIAL Core returns `Content-Type: text/event-stream` with immediate EOF
- **THEN** `response.streaming` SHALL be `true`, `response.events` SHALL be an empty array and `response.body` SHALL be `{"events":[]}`, matching evaluation's empty structured-stream fallback

#### Scenario: SSE timeout returns partial response
- **WHEN** an SSE stream exceeds the idle timeout or absolute max-total cap
- **THEN** the response SHALL contain the dispatched event prefix in `events`, `streaming=true`, `streamingStatus=TIMEOUT` and evaluation's assembled partial/fallback result in `body`
- **AND** parser deadlines SHALL retain their existing completed-line-read boundary; transport behaviour governs a stalled read

#### Scenario: Recognized interrupted stream exposes plain partial text
- **WHEN** a recognized provider stream contains accumulated text and ends with a non-success parsing status
- **THEN** `response.body` SHALL contain evaluation's partial text as a JSON string value, without an additional layer of JSON quoting
- **AND** the dispatched events SHALL remain available in `response.events`
- **AND** the invocation SHALL remain failed: extraction SHALL be skipped and later chain invocations SHALL not run

#### Scenario: Response size limit keeps diagnostics and assembled prefix
- **WHEN** the SSE response exceeds the configured response-size limit
- **THEN** `events` SHALL contain only events dispatched before the overflow
- **AND** `streamingStatus=ERROR` and the existing truncation warning SHALL be retained
- **AND** `body` SHALL equal evaluation's assembled result from that same dispatched prefix and status

#### Scenario: Missing assembled output retains event diagnostics in body
- **WHEN** an HTTP SSE invocation cannot produce an assembled document and reports an assembly error
- **THEN** `response.body` SHALL fall back to `{"events":[...]}` containing the already-parsed events, including an empty array when no events were dispatched
- **AND** `response.events` SHALL contain the same parsed event list
- **AND** the error status, skipped extraction and fail-fast semantics SHALL remain unchanged
- **AND** a valid assembled JSON null or empty partial string SHALL NOT be replaced by this fallback

### Requirement: OpenAPI documentation

Status: **Implemented**

Both try-it-out endpoints SHALL expose OpenAPI descriptions, request/response schemas, examples and error responses that describe the assembled-body/parsed-events contract.

#### Scenario: Swagger UI shows endpoints
- **WHEN** a user navigates to Swagger UI
- **THEN** both try-it-out endpoints SHALL be visible under their appropriate tags with descriptions and response schemas

#### Scenario: SSE examples separate body and events
- **WHEN** a client reads either endpoint's SSE response example in `/v3/api-docs`
- **THEN** the example SHALL show a recognized provider's assembled document in `response.body` and its parsed stream chunks in `response.events`
- **AND** response schema descriptions SHALL explain partial-string and structured-envelope fallback bodies without claiming every SSE body is an events envelope

## Implementation Notes

- Shared preview assembly and diagnostic fallback: `src/main/java/com/epam/aidial/evaluation/service/domain/TryItOutService.java`; wire field descriptions: `src/main/java/com/epam/aidial/evaluation/service/domain/dto/TryItOutCoreResponseDto.java`.
- Provider assembly is reused unchanged from `evaluation-runner-core/src/main/java/com/epam/aidial/evaluation/runner/job/StreamingResponseAccumulator.java`.
- Behaviour tests: `TryItOutServiceTest`, `TryItOutFunctionalTests`, `McpTryItOutFunctionalTests`; API schema/example attachment: `OpenApiExamplesFunctionalTests`.
