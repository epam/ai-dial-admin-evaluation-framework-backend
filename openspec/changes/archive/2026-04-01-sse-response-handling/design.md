## Context

DIAL app routes (non-standard endpoints) can return SSE responses with arbitrary, app-specific event structures. Currently:

1. **TryItOut** uses `DialCoreDeploymentInvoker.invoke()` (non-streaming) — raw SSE text is returned as an opaque string in `response.body`.
2. **Evaluation** uses `invokeWithStreaming()` + `StreamingResponseAccumulator`, which has two modes:
   - **OpenAI mode**: detects `choices[].delta.content`, concatenates, assembles standard response. Works well.
   - **Raw mode**: collects `data:` payloads into a JSON array, but **silently discards `event:` type names**. The stored `responseBody` is a bare array `[payload1, payload2, ...]` with no event type information.

Different apps return different SSE structures (named events, unnamed events, various payload shapes). The solution must be app-agnostic — relying only on the SSE wire format.

### Current code paths

```
TryItOut:  TryItOutService → invoker.invoke() → reads all bytes → parses JSON or returns raw string
                                                  (SSE text is NOT valid JSON → returned as opaque string)

Evaluation: EvaluationWorker → invoker.invokeWithStreaming() → if streaming:
                                  StreamingResponseAccumulator.accumulate(eventStream)
                                    → reads line-by-line, only processes "data: " lines
                                    → ignores "event: " lines entirely
                                    → OpenAI mode or raw JSON array mode
```

## Goals / Non-Goals

**Goals:**
- Parse SSE streams into structured JSON that preserves event type names
- Provide a universal `{"events": [...]}` envelope usable by JSONata extraction
- Enable TryItOut to return parsed SSE events for frontend debugging
- Keep OpenAI mode unchanged (delta collapse is the expected behavior for OpenAI streams)
- Same response shape in both TryItOut and Evaluation paths for consistency

**Non-Goals:**
- Per-suite SSE configuration or shaping strategies — JSONata is expressive enough
- Auto-parsing stringified JSON within event data fields (e.g., `"body": "{...}"`) — this is app-specific; users handle it with JSONata or we address it separately
- Changing how non-SSE responses are handled — those paths remain untouched
- WebSocket or other streaming protocols — SSE only

## Decisions

### D1: New `SseEventParser` component — injectable, not static

**Decision**: Create `SseEventParser` as an injectable `@Component` in `service.domain.job` package. It takes `ObjectMapper` as a dependency (for JSON parsing of data payloads).

**Why not a static utility?** The class needs `ObjectMapper` for JSON parsing. Making it a component follows project conventions (specialized components as injectable classes) and enables independent unit testing with controlled `ObjectMapper`.

**Signature:**
```java
@Component
public class SseEventParser {
    // Parse SSE stream into structured events
    public SseParseResult parse(InputStream stream, long deadlineMs, long maxBytes);
}
```

**`SseParseResult`** (record):
```java
public record SseParseResult(
    List<SseEvent> events,
    ExecutionStatus status,      // SUCCESS, TIMEOUT, ERROR
    String truncationWarning     // nullable
) {}
```

**`SseEvent`** (record):
```java
public record SseEvent(
    String event,   // event type name, defaults to "message" per SSE spec
    Object data     // parsed JsonNode if valid JSON, raw String if not
) {}
```

**Why over refactoring the accumulator directly?** Separation of concerns — parsing SSE wire format is distinct from deciding what to do with the parsed events (OpenAI collapse vs envelope). The accumulator becomes a consumer of parsed events.

### D2: OpenAI mode detection — first event heuristic preserved

**Decision**: Keep the existing detection: if the first event's data has a `choices[]` array AND the event type is `"message"` (i.e., no named `event:` line), treat the stream as OpenAI format.

**Why?** Named events (`event: process_rules`) are a strong signal of structured SSE. OpenAI streams never use named events. This gives clean auto-detection:
- `event: X` present → structured SSE mode (always)
- No `event:` + `choices[]` in data → OpenAI mode
- No `event:` + no `choices[]` → structured SSE mode (fallback)

**Alternative considered**: Explicit per-suite config to select mode. Rejected because auto-detection works reliably and avoids config burden.

### D3: Structured SSE envelope — `{"events": [...]}`

**Decision**: Non-OpenAI SSE responses are stored as:
```json
{
  "events": [
    {"event": "process_entities", "data": {"entities": [...]}},
    {"event": "process_rules",    "data": {"evaluated_rule": {...}}},
    {"event": "success",          "data": {"token_usages": [...]}}
  ]
}
```

**Why an object envelope, not a bare array?**
- JSONata expressions are cleaner: `events[event="success"].data.token_usages` vs `$[???].token_usages` (no event type to filter by)
- Extensible — we can add metadata later (e.g., `eventCount`) without breaking existing expressions
- Consistent with how MCP responses use an envelope structure

**Why not include convenience fields like `lastEvent` or `eventsByType`?**
- JSONata handles these trivially: `events[-1].data`, `events[event="X"]`
- Adding redundant structure increases storage size for no benefit
- YAGNI — if needed later, it's additive

### D4: Refactor `StreamingResponseAccumulator` to use `SseEventParser`

**Decision**: Refactor the accumulator to delegate SSE parsing to `SseEventParser`. The accumulator becomes:

```
SseEventParser.parse(stream, deadline, maxBytes)
    → SseParseResult { events, status, warning }

StreamingResponseAccumulator:
    if (isOpenAiMode(events)):
        → collapse deltas (existing logic)
        → assemble standard OpenAI response
    else:
        → wrap in {"events": [...]} envelope
        → serialize to responseBody string
```

The accumulator retains ownership of:
- OpenAI mode detection and delta collapse
- Response body assembly (serialization to string)
- Execution status resolution

**Constructor change**: Accumulator now takes `SseEventParser` + `ObjectMapper` instead of just `ObjectMapper`. Since `SseEventParser` is a Spring component and the accumulator is manually constructed in `EvaluationWorker`, the worker injects `SseEventParser` and passes it to the accumulator constructor.

### D5: TryItOut switches to `invokeWithStreaming()`

**Decision**: `TryItOutService.invokeAndBuildResponse()` switches from `deploymentInvoker.invoke()` to `deploymentInvoker.invokeWithStreaming()`.

- **Non-SSE response**: same as before — `result.body()` is the parsed JSON, stored in `response.body`. Per the existing `invokeWithStreaming()` contract (eval-execution-engine spec), non-SSE responses have `streaming=false`, `body` populated with parsed JSON, and `eventStream=null` — no InputStream management needed.
- **SSE response**: use `SseEventParser` to parse events, then:
  - `response.body` = `{"events": [...]}` (the shaped document JSONata would operate on)
  - `response.streaming` = `true`
  - `response.events` = list of `SseEventDto` objects for frontend debugging

**DTO changes:**
```java
@Data @Builder
public class TryItOutCoreResponseDto {
    private int statusCode;
    private Object body;
    // New fields:
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean streaming;          // true for SSE, null for non-SSE
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<SseEventDto> events;   // parsed SSE events for debugging, null for non-SSE
}
```

`SseEventDto` (new DTO):
```java
@Data @Builder
public class SseEventDto {
    private String event;   // event type name
    private Object data;    // parsed JSON or raw string
}
```

**Why `events` separate from `body`?** `body` is the JSONata-compatible document (object envelope). `events` is the raw event list for frontend rendering. They have the same data but different structure — `body.events[i]` has `{event, data}` while `events[i]` is `SseEventDto`. In practice they're equivalent, but keeping `body` as the "JSONata target" and `events` as "debug view" is clearer for frontend consumers.

**Alternative considered**: Only return `body` and let the frontend extract events from `body.events`. Viable, but slightly worse UX — the frontend would need to know the envelope structure. The explicit `events` field is self-documenting.

### D6: Timeout enforcement — Clock injection in parser

**Decision**: `SseEventParser` receives `Clock` via constructor injection (Spring component). Uses `clock.millis()` for deadline checking instead of `System.currentTimeMillis()`. This fixes the existing `System.currentTimeMillis()` usage in the accumulator (line 60) that violates project conventions.

### D7: SSE wire format compliance

**Decision**: The parser handles these SSE spec details:
- `event:` line sets the event type for the next `data:` payload. Resets to `"message"` after each blank-line-delimited event.
- Multiple consecutive `data:` lines are joined with `\n` before JSON parsing (per SSE spec).
- `id:` lines are ignored (not relevant for our use case).
- `retry:` lines are ignored.
- Lines starting with `:` are comments — ignored.
- `data: [DONE]` terminates the stream (OpenAI convention, not SSE spec — preserved for compatibility).
- Blank line signals end of an event (triggers event emission).

## Risks / Trade-offs

**[Breaking change: stored response format]** → Existing non-OpenAI SSE response bodies change from `[...]` to `{"events": [...]}`. Existing JSONata expressions like `$[0].field` break.
→ **Mitigation**: This only affects suites targeting non-OpenAI SSE endpoints. These are currently uncommon (the feature was barely usable). Document migration in release notes: `$[i].field` → `events[i].data.field`.

**[TryItOut timeout handling]** → `invokeWithStreaming()` returns a raw InputStream for SSE. The HTTP read timeout controls connection-level timeout, but a stalled stream (server sends events very slowly) could block the TryItOut request thread.
→ **Mitigation**: Apply the same deadline pattern used by `EvaluationWorker` — check `clock.millis()` per event. Use the existing `dial.components.core.try-out.read-timeout-ms` as the deadline for TryItOut SSE accumulation.

**[Memory for large SSE streams in TryItOut]** → Unlike evaluation (which has `maxResponseSizeBytes`), TryItOut currently has no response size limit. A pathological SSE stream could OOM.
→ **Mitigation**: Apply the same `max-response-size-bytes` limit used by evaluation. Truncate with error status if exceeded.

**[Non-JSON data payloads]** → If an SSE event's data is not valid JSON, it's stored as a raw string in the `data` field. JSONata can still access it as a string but cannot navigate into it.
→ **Accepted**: This is correct behavior. The parser is faithful to what was received. Users can file follow-up requests if JSON-within-string parsing is needed.

## Migration Guide for Existing JSONata Expressions

This change introduces a **breaking format change** for non-OpenAI SSE response bodies stored in the analytics DB. The stored `response_body` shape changes as follows.

### Before (old format)

Non-OpenAI SSE responses were stored as a bare JSON array of raw `data:` payloads, with event type names silently discarded:

```json
[
  {"status": "processing"},
  {"output": "Paris", "confidence": 0.95},
  "non-json-payload"
]
```

Example JSONata expressions (old):
```
$[0].status           → "processing"
$[1].output           → "Paris"
$[1].confidence       → 0.95
$count($)             → 3
```

### After (new format)

Non-OpenAI SSE responses are stored as a `{"events": [...]}` envelope. Each element preserves the event type name:

```json
{
  "events": [
    { "event": "message", "data": { "status": "processing" } },
    { "event": "result",  "data": { "output": "Paris", "confidence": 0.95 } },
    { "event": "message", "data": "non-json-payload" }
  ]
}
```

Example JSONata expressions (new):
```
events[0].data.status           → "processing"
events[1].data.output           → "Paris"
events[1].data.confidence       → 0.95
$count(events)                  → 3
events[event='result'][0].data.output  → "Paris"  (filter by event type)
events[-1].data                 → last event's data
```

### Migration steps for affected suites

1. **Identify affected suites**: Suites with non-OpenAI SSE deployments (i.e., deployments that return named or generic SSE events, not `choices[].delta.content` OpenAI format).
2. **Update response column expressions**: Change path prefix from `$[i].field` to `events[i].data.field`.
3. **Re-run evaluations**: Historical results in `eval_summaries.response_body` retain the old format; only new runs produce the new envelope. Re-run affected suites to regenerate results under the new format.

### OpenAI streams — no change

Suites targeting OpenAI-compatible deployments (chat completions with `choices[].delta.content`) are **unaffected**. The OpenAI assembled format (`choices[0].message.content`, `usage.total_tokens`, etc.) is unchanged.

## Open Questions

None — design decisions are fully resolved from the exploration phase.
