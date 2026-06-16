## MODIFIED Requirements

### Requirement: TryItOutResponseDto structure
Response is an envelope containing resolved request, DIAL Core response, timing information, and OTel trace ID.

`TryItOutResponseDto` SHALL include:
- `resolvedRequest` (`ResolvedRequestDto`) — resolved URL, query params, headers, and body
- `response` (`TryItOutCoreResponseDto`) — deployment's response (see below)
- `durationMs` (Long) — wall-clock time for DIAL Core invocation in milliseconds
- `traceId` (String, nullable) — 32-char hex OTel trace ID; present when Micrometer Tracing active

`TryItOutCoreResponseDto` SHALL include:
- `statusCode` (int) — HTTP status code from DIAL Core
- `body` (Object, nullable) — parsed JSON response body, or `{"events": [...]}` envelope for SSE responses
- `streaming` (Boolean, nullable) — `true` when response was SSE, `null` (omitted from JSON) for non-SSE responses. Uses `@JsonInclude(NON_NULL)`.
- `events` (List of `SseEventDto`, nullable) — parsed SSE events for frontend debugging, `null` (omitted from JSON) for non-SSE responses. Uses `@JsonInclude(NON_NULL)`.

`SseEventDto` SHALL be a DTO with:
- `event` (String) — SSE event type name (e.g., `"process_rules"`, `"message"`)
- `data` (Object) — parsed JSON payload or raw string

**Status:** Planned

#### Scenario: Response structure (non-SSE)
- **WHEN** DIAL Core returns a non-SSE response (Content-Type is NOT `text/event-stream`)
- **THEN** `response.body` SHALL be parsed JSON value or raw string, `response.streaming` SHALL be `null` (omitted), `response.events` SHALL be `null` (omitted)

#### Scenario: Response structure (SSE)
- **WHEN** DIAL Core returns an SSE response (Content-Type is `text/event-stream`)
- **THEN** `response.streaming` SHALL be `true`, `response.events` SHALL contain the list of parsed `SseEventDto` objects in order of receipt, `response.body` SHALL be the `{"events": [...]}` envelope (the document JSONata would operate on)

#### Scenario: Response body is valid JSON (non-SSE)
- **WHEN** DIAL Core returns response body that is valid JSON
- **THEN** `response.body` SHALL be parsed JSON value (object, array, string, number, boolean, or null)

#### Scenario: Response body is not JSON (non-SSE)
- **WHEN** DIAL Core returns response body that is not valid JSON (e.g., HTML error page, plain text)
- **THEN** `response.body` SHALL be raw response string

### Requirement: Try-it-out invocation uses streaming-aware invoker
Try-it-out service SHALL use `DialCoreDeploymentInvoker.invokeWithStreaming()` for HTTP/DEPLOYMENT suite invocations instead of `invoke()`. This enables SSE response handling.

For non-SSE responses, behavior is unchanged — `DeploymentInvocationResult.body()` provides the parsed response. For SSE responses, the service SHALL parse events using `SseEventParser` and build the structured response.

**Status:** Planned

#### Scenario: Non-SSE invocation (behavior preserved)
- **WHEN** try-it-out invokes deployment and response Content-Type is NOT `text/event-stream`
- **THEN** system SHALL read parsed body from `DeploymentInvocationResult.body()`, build `TryItOutCoreResponseDto` with `statusCode` and `body`, with `streaming` and `events` as `null`

#### Scenario: SSE invocation
- **WHEN** try-it-out invokes deployment and response Content-Type is `text/event-stream`
- **THEN** system SHALL parse SSE stream via `SseEventParser`, build `TryItOutCoreResponseDto` with `streaming=true`, `events` containing parsed `SseEventDto` list, and `body` containing `{"events": [...]}` envelope

#### Scenario: SSE invocation resource cleanup
- **WHEN** try-it-out processes an SSE response
- **THEN** system SHALL use `DeploymentInvocationResult` in try-with-resources to ensure `eventStream` is closed

## ADDED Requirements

### Requirement: Try-it-out SSE timeout enforcement
When processing SSE responses, try-it-out SHALL enforce a deadline to prevent indefinite blocking on stalled streams.

The deadline SHALL be `clock.millis() + readTimeoutMs` where `readTimeoutMs` is the existing `dial.components.core.try-out.read-timeout-ms` configuration.

**Status:** Planned

#### Scenario: SSE stream exceeds timeout
- **WHEN** SSE stream is still active after deadline expires
- **THEN** system SHALL stop reading, return partial events accumulated so far, and include `streaming=true` in response

### Requirement: Try-it-out SSE size limit enforcement
When processing SSE responses, try-it-out SHALL enforce the same `max-response-size-bytes` limit used by evaluation to prevent OOM on pathological streams.

**Status:** Planned

#### Scenario: SSE stream exceeds size limit
- **WHEN** accumulated SSE event data exceeds `max-response-size-bytes`
- **THEN** system SHALL stop reading and return partial events accumulated so far

### Requirement: Try-it-out SSE parsing error handling
When SSE parsing encounters an error (IOException, malformed stream), try-it-out SHALL return a partial or error response rather than propagating the exception as an HTTP 500.

**Status:** Planned

#### Scenario: SSE stream read error
- **WHEN** an `IOException` occurs while parsing the SSE stream (e.g., connection reset mid-stream)
- **THEN** system SHALL return partial events accumulated before the error, set `streaming=true`, and include whatever events were successfully parsed. The `body` SHALL be the `{"events": [...]}` envelope of the partial events.

#### Clarification: Truncated SSE parsing consistency
- **WHEN** SSE parsing is truncated for any reason (timeout, size limit, or read error)
- **THEN** the `events` field SHALL contain the partial list of successfully parsed events up to the point of truncation, and `body` SHALL contain the `{"events": [...]}` envelope built from that same partial list. Both fields always reflect the same set of partial data.

#### Scenario: Empty SSE stream
- **WHEN** DIAL Core returns `Content-Type: text/event-stream` but the stream body is empty (immediate EOF)
- **THEN** `response.streaming` SHALL be `true`, `response.events` SHALL be an empty list, `response.body` SHALL be `{"events": []}`

#### Scenario: SSE timeout returns partial response
- **WHEN** SSE stream exceeds the deadline during try-it-out
- **THEN** system SHALL return partial events accumulated so far with `streaming=true`, and `body` as the envelope of partial events

### Requirement: Try-it-out invocation uses pluggable serializer
Try-it-out service SHALL use `RequestBodySerializerRegistry` to serialize resolved body before invoking DIAL Core deployment. This requirement is unchanged.

**Status:** Planned

#### Scenario: JSON body invocation (current behavior preserved)
- **WHEN** resolved body is `ResolvedJsonBodyDto`
- **THEN** system SHALL serialize as JSON and invoke DIAL Core with `Content-Type: application/json`

#### Scenario: Multipart body invocation
- **WHEN** resolved body is `ResolvedMultipartBodyDto`
- **THEN** system SHALL build multipart request and invoke DIAL Core with `Content-Type: multipart/form-data`

#### Scenario: URL-encoded body invocation
- **WHEN** resolved body is `ResolvedUrlEncodedBodyDto`
- **THEN** system SHALL build URL-encoded form body and invoke DIAL Core with `Content-Type: application/x-www-form-urlencoded`
