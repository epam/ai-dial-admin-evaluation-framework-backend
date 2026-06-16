## MODIFIED Requirements

### Requirement: Try-it-out SSE timeout enforcement
When processing SSE responses, try-it-out SHALL enforce an **idle (inactivity) timeout** plus an **absolute max-total cap** to prevent indefinite blocking on stalled or endlessly-heartbeating streams. The idle timeout SHALL be the existing `dial.components.core.try-out.read-timeout-ms` configuration, passed to `SseEventParser.parse(...)` as `idleTimeoutMs` (reset on every received line). The absolute cap SHALL be the dedicated `sse-event-processing.max-total-duration-ms` configuration, passed as `maxTotalDurationMs`. Try-it-out SHALL NOT compute an absolute `clock.millis() + readTimeoutMs` deadline itself.

Status: **Implemented**

#### Scenario: SSE stream idle longer than read timeout
- **WHEN** an SSE stream sends no line for longer than `dial.components.core.try-out.read-timeout-ms`
- **THEN** system SHALL stop reading, return partial events accumulated so far, and include `streaming=true` in response

#### Scenario: Active SSE stream is not killed by total elapsed time
- **WHEN** an SSE stream keeps sending lines (data or keep-alive heartbeats) with gaps shorter than the read timeout, for total elapsed time exceeding the read timeout but within `sse-event-processing.max-total-duration-ms`
- **THEN** system SHALL continue reading and SHALL NOT time out on the idle timeout

#### Scenario: SSE stream exceeds the absolute max-total cap
- **WHEN** an SSE stream stays active beyond `sse-event-processing.max-total-duration-ms` total elapsed time
- **THEN** system SHALL stop reading, return partial events accumulated so far, and include `streaming=true` in response

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
- **WHEN** an SSE stream stalls past the idle timeout, or stays active beyond the absolute max-total cap, during try-it-out
- **THEN** system SHALL return partial events accumulated so far with `streaming=true`, and `body` as the envelope of partial events
