# SSE Event Parsing

## Purpose

Defines the injectable `SseEventParser` component and its supporting data carriers (`SseEvent`, `SseParseResult`). The parser handles all SSE wire format compliance concerns in one place and is consumed by both the evaluation engine (`StreamingResponseAccumulator`) and the Try It Out path (`TryItOutService`).

Status: **Implemented**

## Key Terms

- **SseEventParser**: Injectable `@Component` in `service.domain.job` that parses an SSE `InputStream` into structured events. Takes `ObjectMapper` and `Clock` as constructor dependencies.
- **SseEvent**: Immutable record with `String event` (type name, defaults to `"message"`) and `Object data` (parsed `JsonNode` if valid JSON, raw `String` otherwise).
- **SseParseResult**: Immutable record with `List<SseEvent> events`, `ExecutionStatus status` (`SUCCESS`, `TIMEOUT`, `ERROR`), and `String truncationWarning` (nullable).

## Wire Format Rules (RFC SSE)

| Line prefix | Action |
|-------------|--------|
| `event:` | Set event type for the next event block (resets to `"message"` after each blank-line boundary) |
| `data:` | Accumulate data line (strip exactly one leading space; join multiple data: lines with `\n`) |
| `data: [DONE]` | Terminate stream immediately — no event emitted (OpenAI compatibility) |
| `:` (comment) | Ignored |
| `id:` | Ignored |
| `retry:` | Ignored |
| Blank line | Dispatch event if any `data:` lines accumulated; reset event type to `"message"` |

## Requirements

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
- **THEN** parser SHALL ignore the comment line and emit `SseEvent(event="message", data=JsonNode{"x":1})`

#### Scenario: id: and retry: lines are ignored
- **WHEN** stream contains `id: 42\nretry: 3000\ndata: {"ok":true}\n\n`
- **THEN** parser SHALL ignore `id:` and `retry:` and emit `SseEvent(event="message", data=JsonNode{"ok":true})`

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
- **THEN** parser SHALL rely on `deadlineMs` enforcement to stop reading and return events accumulated so far with `status = TIMEOUT`

### Requirement: Deadline enforcement

The parser SHALL check `clock.millis() > deadlineMs` before processing each line. When deadline is exceeded, parsing stops immediately and returns `ExecutionStatus.TIMEOUT`.

Status: **Implemented**

#### Scenario: Deadline exceeded mid-stream
- **WHEN** `clock.millis()` exceeds `deadlineMs` during parsing
- **THEN** parser SHALL return `SseParseResult(events=<events accumulated so far>, status=TIMEOUT, truncationWarning=null)`

### Requirement: Size limit enforcement

The parser SHALL track the total accumulated byte length of raw data payloads. When adding a new event would exceed `maxBytes`, parsing stops and returns `ExecutionStatus.ERROR` with a truncation warning.

Status: **Implemented**

#### Scenario: Size limit exceeded
- **WHEN** accumulated data bytes plus the current event's raw data would exceed `maxBytes`
- **THEN** parser SHALL return `SseParseResult(events=<events before overflow>, status=ERROR, truncationWarning="Response truncated: accumulated N bytes, limit M")`

#### Scenario: Single event whose payload alone exceeds maxBytes
- **WHEN** the very first event's payload exceeds `maxBytes`
- **THEN** `events` SHALL be empty and status SHALL be `ERROR`

#### Scenario: Byte counting per event
- **WHEN** event data payload is `{"result":"hello"}` (18 bytes UTF-8)
- **THEN** parser SHALL add 18 to the accumulated byte counter

#### Scenario: Byte counting with multi-line data
- **WHEN** event has multi-line data joined as `line1\nline2` (11 bytes)
- **THEN** parser SHALL count the joined string's UTF-8 byte length (11) toward the limit

### Requirement: IOException handling

If the underlying `InputStream` throws an `IOException` during reading, the parser SHALL log a warning and return `ExecutionStatus.ERROR` with the events accumulated before the error.

Status: **Implemented**

## Implementation Notes

- **Parsing algorithm**: `BufferedReader` line-by-line over `InputStreamReader(UTF-8)`. State: `currentEventType` (String), `dataLines` (List).
- **JSON parsing**: `objectMapper.readTree(rawData)`. If result is `null` or `MissingNode`, returns raw string (handles empty/whitespace-only payloads in Jackson 2.20+).
- **Empty/whitespace payloads**: Stored as raw String (empty string `""` for `data:`, or the value after stripping exactly one leading space — e.g., `data:   ` (3 spaces) → `"  "` (2 spaces)).
- **`[DONE]` detection**: Checked after stripping one leading space from `data:` value.
- **Clock**: Injected via constructor — project convention disallows `System.currentTimeMillis()`.
