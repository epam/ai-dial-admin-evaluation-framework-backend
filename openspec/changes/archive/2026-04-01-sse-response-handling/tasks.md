## 1. SSE Event Parser — Data Carriers and Component

- [x] 1.1 Create `SseEvent` record in `service.domain.job` package — fields: `String event`, `Object data` (done: record compiles, used by parser)
- [x] 1.2 Create `SseParseResult` record in `service.domain.job` package — fields: `List<SseEvent> events`, `ExecutionStatus status`, `String truncationWarning` (done: record compiles, used by parser)
- [x] 1.3 Create `SseEventParser` component in `service.domain.job` package — injectable with `ObjectMapper` and `Clock`; implements `parse(InputStream, long deadlineMs, long maxBytes)` returning `SseParseResult`. Handles: named event types (defaults to `"message"`), multi-line data joining, JSON parsing of data payloads, `[DONE]` termination, comment/id/retry line skipping, event type reset per blank-line boundary, deadline and size limit enforcement, IOException handling (done: all spec scenarios for SSE event parser satisfied)
- [x] 1.4 Unit tests for `SseEventParser` — cover: named events with JSON data, unnamed events (default "message"), non-JSON data, multi-line data, `[DONE]` termination, deadline timeout, size limit truncation, comment lines ignored, event type reset, stream read error, empty stream, event type without data line, empty data payload, whitespace-only data, single event exceeding maxBytes, comments interspersed with event fields, consecutive blank lines, CRLF line endings (done: all spec scenarios have corresponding test cases, tests pass)

## 2. Refactor StreamingResponseAccumulator

- [x] 2.1 Refactor `StreamingResponseAccumulator` constructor to accept `SseEventParser` in addition to `ObjectMapper`; delegate SSE parsing to `SseEventParser.parse()` instead of internal line-by-line reading (done: accumulator uses parser, compiles)
- [x] 2.2 Update OpenAI mode detection — use parsed `SseEvent` list: OpenAI if first event has `choices[]` in data AND event type is `"message"`; any named event type forces structured SSE mode (done: detection logic uses parsed events)
- [x] 2.3 Implement structured SSE mode — assemble `responseBody` as `{"events": [{"event": "<type>", "data": <payload>}, ...]}` envelope using Jackson `ObjectNode`/`ArrayNode` for non-OpenAI streams (done: envelope format matches spec)
- [x] 2.4 Preserve OpenAI mode behavior — delta content concatenation and non-streaming response assembly unchanged (done: existing OpenAI tests still pass)
- [x] 2.5 Update `EvaluationWorker` to pass injected `SseEventParser` to `StreamingResponseAccumulator` constructor (done: worker compiles, injects parser)
- [x] 2.6 Unit tests for refactored `StreamingResponseAccumulator` — cover: OpenAI mode (unchanged behavior), structured SSE mode (envelope format), named event type preservation, mixed event types, size limit in structured mode, OpenAI detection with named events forces structured mode, empty SSE stream produces empty envelope, all-non-JSON-data structured mode (done: tests pass)

## 3. TryItOut SSE Support

- [x] 3.1 Create `SseEventDto` in `service.domain.dto` — fields: `String event`, `Object data` (done: DTO compiles)
- [x] 3.2 Extend `TryItOutCoreResponseDto` — add `Boolean streaming` and `List<SseEventDto> events` fields with `@JsonInclude(NON_NULL)` (done: DTO compiles, non-SSE responses unchanged)
- [x] 3.3 Refactor `TryItOutService.invokeAndBuildResponse()` — switch from `deploymentInvoker.invoke()` to `deploymentInvoker.invokeWithStreaming()` with try-with-resources; handle non-SSE responses same as before, handle SSE responses by parsing with `SseEventParser` and building response with `streaming=true`, `events` list, and `body` as `{"events": [...]}` envelope (done: both paths work, SSE events parsed and returned)
- [x] 3.4 Inject `SseEventParser`, `Clock`, and `EvaluationRunProperties` (for max-response-size-bytes) into `TryItOutService` (done: dependencies injected)
- [x] 3.5 Apply deadline enforcement for TryItOut SSE — use `clock.millis() + readTimeoutMs` from existing try-out timeout config (done: deadline passed to parser)
- [x] 3.6 Apply size limit enforcement for TryItOut SSE — use `max-response-size-bytes` from `EvaluationRunProperties` (done: size limit passed to parser)
- [x] 3.7 Unit tests for `TryItOutService` SSE handling — cover: non-SSE invocation unchanged, SSE invocation returns streaming=true with events and envelope body, resource cleanup, SSE stream read error returns partial events, empty SSE stream returns empty list, SSE timeout returns partial response (done: tests pass)

## 4. Existing Test Updates and Verification

- [x] 4.1 Update existing `StreamingResponseAccumulator` tests — adapt assertions for new constructor signature and verify backward compatibility of OpenAI mode (done: existing tests adapted and pass)
- [x] 4.2 Update existing `TryItOutService` tests — adapt for `invokeWithStreaming()` usage instead of `invoke()` (done: existing tests adapted and pass)
- [x] 4.3 Run full build `./gradlew clean build` — verify checkstyle, all unit and functional tests pass (done: build green)

## 5. Specs and Documentation

- [x] 5.1 Update `openspec/specs/README.md` per Spec Index Maintenance Policy — add `sse-event-parsing` spec entry, and update summaries for `try-it-out` and `eval-execution-engine` entries to reflect SSE structured response handling (done: index reflects new spec and affected spec summaries are updated)
- [x] 5.2 Update OpenAPI examples for TryItOut response to show SSE response shape with `streaming` and `events` fields (done: examples added/updated)
- [x] 5.3 Update openspec/specs/response-columns/spec.md to document that non-OpenAI SSE response bodies use {"events": [...]} envelope shape for JSONata extraction — add example expressions (done: spec updated with SSE envelope examples)
- [x] 5.4 Document migration path for existing JSONata expressions — add a note in proposal or design explaining that the response body format changes from flat `$[0].field` to `events[0].data.field` for non-OpenAI SSE responses, and provide migration guidance for updating existing extraction expressions (done: migration impact documented with before/after examples)
