## MODIFIED Requirements

### Requirement: Deadline enforcement

The parser SHALL accept two timeout **durations** — `idleTimeoutMs` (inactivity timeout) and `maxTotalDurationMs` (absolute cap) — rather than a precomputed `deadlineMs`. The parser SHALL derive all deadlines internally from the injected `Clock`. The signature SHALL be `parse(InputStream stream, long idleTimeoutMs, long maxTotalDurationMs, long maxBytes)`.

At entry the parser SHALL capture `startMs = clock.millis()` and compute `hardDeadlineMs = startMs + maxTotalDurationMs`. It SHALL maintain an idle deadline initialized to `startMs + idleTimeoutMs`. Before processing each line read from the stream, the parser SHALL check the current time against both deadlines; if `clock.millis() > hardDeadlineMs` OR `clock.millis() > idleDeadlineMs`, parsing SHALL stop immediately and return `ExecutionStatus.TIMEOUT` with the events accumulated so far. After the check, for every line read (any line type — `data:`, `event:`, comment `:`, `id:`, `retry:`, or blank), the parser SHALL reset the idle deadline to `clock.millis() + idleTimeoutMs`. The hard deadline SHALL NOT be reset.

Status: **Implemented**

#### Scenario: Idle timeout exceeded between lines
- **WHEN** more than `idleTimeoutMs` elapses (per the injected `Clock`) between one line and the next line read
- **THEN** parser SHALL stop on the next line and return `SseParseResult(events=<events accumulated so far>, status=TIMEOUT, truncationWarning=null)`

#### Scenario: Idle deadline resets on every received line
- **WHEN** lines (including comment/heartbeat keep-alive lines) keep arriving with gaps each shorter than `idleTimeoutMs`, for a total elapsed time greater than `idleTimeoutMs`
- **THEN** parser SHALL NOT time out on the idle deadline — each received line resets it — and SHALL continue parsing until the stream ends, `[DONE]`, the size limit, or the absolute cap

#### Scenario: Absolute max-total cap exceeded under continuous activity
- **WHEN** lines keep arriving (so the idle deadline never expires) but total elapsed time since stream start exceeds `maxTotalDurationMs`
- **THEN** parser SHALL stop and return `SseParseResult(events=<events accumulated so far>, status=TIMEOUT, truncationWarning=null)`

#### Scenario: Caller passes durations, not a deadline
- **WHEN** a caller invokes `parse(stream, idleTimeoutMs, maxTotalDurationMs, maxBytes)`
- **THEN** the parser SHALL compute deadlines from `clock.millis()` internally; callers SHALL NOT pass an absolute epoch-millisecond deadline

### Requirement: SSE event parser component

The system SHALL provide an injectable `SseEventParser` component in `service.domain.job` package that parses an SSE `InputStream` into a list of structured `SseEvent` records.

Status: **Implemented**

#### Scenario: Parse named SSE events with JSON data
- **WHEN** SSE stream contains `event: process_rules\ndata: {"status":"FAILED"}\n\n`
- **THEN** parser SHALL produce `SseEvent(event="process_rules", data=JsonNode{"status":"FAILED"})`

#### Scenario: Parse unnamed SSE events (default type)
- **WHEN** SSE stream contains `data: {"result":"hello"}\n\n` with no preceding `event:` line
- **THEN** parser SHALL produce `SseEvent(event="message", data=JsonNode{"result":"hello"})`

#### Scenario: Parse non-JSON data payload
- **WHEN** SSE stream contains `data: plain text content\n\n`
- **THEN** parser SHALL produce `SseEvent(event="message", data="plain text content")` with data as raw String

#### Scenario: Join multi-line data fields
- **WHEN** SSE stream contains `data: line1\ndata: line2\n\n`
- **THEN** parser SHALL join with `\n` and parse as single payload: `data="line1\nline2"`

#### Scenario: [DONE] terminates the stream
- **WHEN** stream contains `data: {"a":1}\n\ndata: [DONE]\n\n`
- **THEN** parser SHALL emit one event for `{"a":1}` and stop; events after `[DONE]` are ignored

#### Scenario: Comment lines are ignored
- **WHEN** stream contains `:comment text\ndata: {"x":1}\n\n`
- **THEN** parser SHALL ignore the comment line (it does NOT change the current event type) and emit `SseEvent(event="message", data=JsonNode{"x":1})`

#### Scenario: id: and retry: lines are ignored
- **WHEN** stream contains `id: 42\nretry: 3000\ndata: {"ok":true}\n\n`
- **THEN** parser SHALL ignore `id:` and `retry:` and emit `SseEvent(event="message", data=JsonNode{"ok":true})`

#### Scenario: Named heartbeat events are emitted
- **WHEN** stream contains `event: heartbeat\ndata: {}\n\ndata: {"result":"hi"}\n\n`
- **THEN** parser SHALL emit `SseEvent(event="heartbeat", data=JsonNode{})` followed by `SseEvent(event="message", data=JsonNode{"result":"hi"})` — named heartbeat events are not suppressed

#### Scenario: Event type resets to "message" after blank line
- **WHEN** stream contains `event: custom\ndata: {"a":1}\n\ndata: {"b":2}\n\n`
- **THEN** first event has type `"custom"`, second event has type `"message"` (reset after blank line)

#### Scenario: Event type set without data line
- **WHEN** stream contains `event: typeA\n\ndata: {"next":1}\n\n`
- **THEN** parser SHALL NOT emit an event for the first block (no `data:` line present) — per SSE spec, an event dispatch requires at least one `data:` line. The event type resets to `"message"` after the blank line. Only the second event is emitted.

#### Scenario: Empty stream returns empty event list
- **WHEN** SSE stream is empty or contains only blank lines
- **THEN** parser SHALL return `SseParseResult(events=[], status=SUCCESS, truncationWarning=null)`

#### Scenario: Data line with empty payload
- **WHEN** stream contains `data:\n\n` (data field with no value after colon)
- **THEN** parser SHALL emit `SseEvent(event="message", data="")` — empty string, not discarded

#### Scenario: Data line with only whitespace payload
- **WHEN** stream contains `data:   \n\n` (data value is three spaces)
- **THEN** parser SHALL emit `SseEvent(event="message", data="  ")` — two spaces preserved after stripping one leading space (whitespace not trimmed, stored as raw string)

#### Scenario: Comment lines interspersed with event fields
- **WHEN** stream contains `event: typeA\n: this is a comment\ndata: {"x":1}\n\n`
- **THEN** parser SHALL ignore the comment line, preserve the event type `typeA`, and emit `SseEvent(event="typeA", data=JsonNode{"x":1})`

#### Scenario: Consecutive blank lines between events
- **WHEN** stream contains two blank lines between events (e.g., `data: a\n\n\ndata: b\n\n`)
- **THEN** parser SHALL treat extra blank lines as no-ops — only one event is emitted per data-bearing block; the extra blank line does NOT produce an additional empty event

#### Scenario: CRLF line endings
- **WHEN** SSE stream uses `\r\n` (CRLF) line endings instead of `\n` (LF)
- **THEN** parser SHALL handle CRLF identically to LF — `BufferedReader.readLine()` strips the `\r` automatically

#### Scenario: Stream never terminates (no DONE, no EOF)
- **WHEN** SSE stream keeps sending events without `[DONE]` and without EOF
- **THEN** parser SHALL rely on the idle timeout and the absolute max-total cap to stop reading and return the events accumulated so far with `status = TIMEOUT`
