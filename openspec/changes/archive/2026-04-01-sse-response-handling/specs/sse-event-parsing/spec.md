## ADDED Requirements

### Requirement: SSE event parser component
The system SHALL provide an injectable `SseEventParser` component in `service.domain.job` package that parses an SSE `InputStream` into a list of structured `SseEvent` records.

`SseEventParser` SHALL accept `ObjectMapper` and `Clock` as constructor dependencies. The `parse` method SHALL accept `InputStream`, `deadlineMs` (long), and `maxBytes` (long) parameters and return an `SseParseResult`.

`SseParseResult` SHALL be a record containing:
- `events` (`List<SseEvent>`) — parsed events in order of receipt
- `status` (`ExecutionStatus`) — `SUCCESS`, `TIMEOUT`, or `ERROR`
- `truncationWarning` (`String`, nullable) — message when size limit exceeded

`SseEvent` SHALL be a record containing:
- `event` (`String`) — the event type name
- `data` (`Object`) — parsed `JsonNode` if data is valid JSON, raw `String` otherwise

**Status:** Planned

#### Scenario: Parse named SSE events with JSON data
- **WHEN** SSE stream contains `event: process_rules\ndata: {"status":"FAILED"}\n\n`
- **THEN** parser SHALL produce `SseEvent(event="process_rules", data=JsonNode{"status":"FAILED"})`

#### Scenario: Parse unnamed SSE events (default type)
- **WHEN** SSE stream contains `data: {"result":"hello"}\n\n` with no preceding `event:` line
- **THEN** parser SHALL produce `SseEvent(event="message", data=JsonNode{"result":"hello"})` — defaulting to `"message"` per SSE spec

#### Scenario: Parse non-JSON data payload
- **WHEN** SSE stream contains `data: plain text content\n\n`
- **THEN** parser SHALL produce `SseEvent(event="message", data="plain text content")` with data as raw String

#### Scenario: Multi-line data fields
- **WHEN** SSE stream contains consecutive `data:` lines before blank line separator: `data: line1\ndata: line2\n\n`
- **THEN** parser SHALL join data lines with `\n` separator (`"line1\nline2"`) before attempting JSON parse

#### Scenario: Stream termination on DONE sentinel
- **WHEN** SSE stream contains `data: [DONE]\n\n`
- **THEN** parser SHALL stop processing and NOT include a `[DONE]` event in results

#### Scenario: Deadline exceeded during parsing
- **WHEN** `clock.millis()` exceeds `deadlineMs` while reading events
- **THEN** parser SHALL stop reading, set `status = TIMEOUT`, and return events accumulated so far

#### Scenario: Size limit exceeded during parsing
- **WHEN** accumulated event data bytes exceed `maxBytes`
- **THEN** parser SHALL stop reading, set `status = ERROR`, set `truncationWarning` with accumulated byte count and limit, and return events accumulated so far

#### Scenario: SSE comment lines ignored
- **WHEN** SSE stream contains lines starting with `:` (colon)
- **THEN** parser SHALL ignore those lines (SSE comments per spec)

#### Scenario: SSE id and retry fields ignored
- **WHEN** SSE stream contains `id:` or `retry:` lines
- **THEN** parser SHALL ignore those lines

#### Scenario: Event type resets per event
- **WHEN** SSE stream contains `event: typeA\ndata: {}\n\ndata: {}\n\n`
- **THEN** first event SHALL have `event="typeA"`, second event SHALL have `event="message"` (type resets after each blank-line-delimited event)

#### Scenario: Stream read error
- **WHEN** `IOException` occurs while reading the stream
- **THEN** parser SHALL set `status = ERROR`, log at WARN level with exception, and return events accumulated so far

#### Scenario: Empty stream (no events)
- **WHEN** SSE stream is empty (immediate EOF, no data lines)
- **THEN** parser SHALL return `SseParseResult(events=[], status=SUCCESS, truncationWarning=null)` — empty list, no error

#### Scenario: Event type set without data line
- **WHEN** SSE stream contains `event: typeA\n\n` with no `data:` line before the blank-line boundary
- **THEN** parser SHALL NOT emit an event — per SSE spec, an event dispatch requires at least one `data:` line. The event type resets to `"message"` after the blank line.

#### Scenario: Data line with empty payload
- **WHEN** SSE stream contains `data:\n\n` (data field with no value after colon)
- **THEN** parser SHALL emit `SseEvent(event="message", data="")` — empty string data, not discarded

#### Scenario: Data line with only whitespace payload
- **WHEN** SSE stream contains `data:   \n\n` (data value is whitespace only)
- **THEN** parser SHALL emit `SseEvent(event="message", data="   ")` — whitespace preserved as raw string (not valid JSON, not trimmed)

#### Scenario: Single event exceeds maxBytes
- **WHEN** the first event's data payload alone exceeds `maxBytes`
- **THEN** parser SHALL NOT include the oversized event in results, set `status = ERROR`, set `truncationWarning`, and return an empty events list (the event was not yet committed when the limit was detected)

#### Scenario: Comment lines interspersed with event fields
- **WHEN** SSE stream contains `event: typeA\n: this is a comment\ndata: {"x":1}\n\n`
- **THEN** parser SHALL ignore the comment line, preserve the event type `typeA` across the comment, and emit `SseEvent(event="typeA", data=JsonNode{"x":1})`

#### Scenario: Consecutive blank lines between events
- **WHEN** SSE stream contains two blank lines between events (e.g., `data: a\n\n\ndata: b\n\n`)
- **THEN** parser SHALL treat extra blank lines as no-ops — only one event dispatch per data-bearing event block. The extra blank line does NOT produce an additional empty event.

#### Scenario: CRLF line endings
- **WHEN** SSE stream uses `\r\n` (CRLF) line endings instead of `\n` (LF)
- **THEN** parser SHALL handle CRLF identically to LF — `BufferedReader.readLine()` handles both

#### Scenario: Stream never terminates (no DONE, no EOF)
- **WHEN** SSE stream keeps sending events without `[DONE]` and without EOF
- **THEN** parser SHALL rely on `deadlineMs` enforcement to stop reading and return events accumulated so far with `status = TIMEOUT`

### Requirement: Byte counting for size limit enforcement
The system SHALL count accumulated bytes by measuring the UTF-8 byte length of each event's raw data string (before JSON parsing). The byte count SHALL include all `data:` payloads across all events.

**Status:** Planned

#### Scenario: Byte counting per event
- **WHEN** event data payload is `{"result":"hello"}` (18 bytes UTF-8)
- **THEN** parser SHALL add 18 to accumulated byte counter

#### Scenario: Byte counting with multi-line data
- **WHEN** event has multi-line data `line1\nline2` (joined, 11 bytes)
- **THEN** parser SHALL count the joined string's byte length
