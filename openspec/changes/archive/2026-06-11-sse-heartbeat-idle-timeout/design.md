## Context

`SseEventParser.parse(InputStream, long deadlineMs, long maxBytes)` (`service.domain.job`) currently enforces a single **absolute wall-clock deadline**: before processing each line it checks `clock.millis() > deadlineMs` and stops with `ExecutionStatus.TIMEOUT`. The deadline is computed by the callers:

- `EvaluationWorker.invokeSingle` → `deadlineMs = callStartMs + context.getRequestTimeoutMs()`, passed via `StreamingResponseAccumulator`.
- `TryItOutService.buildSseResponse` → `deadlineMs = startMs + dialCoreProperties.getTryOut().getReadTimeoutMs()`.

This kills a healthy long-running stream that emits periodic keep-alives once total elapsed time crosses the deadline, even while it is actively producing data. SSE servers keep idle connections alive with **comment lines** (`:` prefix, e.g. `: keep-alive`) and sometimes named `event: heartbeat` events; the parser should treat the *arrival of any line* as proof of liveness.

Constraints: Java 25 / Spring Boot, JDBC-only (not relevant here), `Clock` must be injected (no `System.currentTimeMillis()`), `@LogExecution` on Spring components, config defaults live in `application.yml` (not Java field initializers), every new config property gets a `docs/configuration.md` row. The parser is a shared singleton `@Component` consumed by two callers.

## Goals / Non-Goals

**Goals:**
- Replace the fixed absolute deadline with an **idle (inactivity) timeout**: every line read resets the deadline to `clock.millis() + idleTimeoutMs`.
- Add an independent **absolute max-total-duration cap** so a server that heartbeats forever still terminates; driven by a single dedicated, globally-shared configuration property.
- Change `parse(...)` so callers pass **timeout durations**, not a precomputed deadline; the parser owns all `clock`-based deadline math.
- Remove the WIP `currentEventType = "heartbeat"` mutation on comment lines (it leaks the type onto the next emitted data event).
- Migrate the existing unit/service tests to the new signature and semantics.

**Non-Goals:**
- No change to the public REST API surface, DTOs, or DB schema.
- No new socket/HTTP-layer read timeout — the existing client-level read timeouts are unchanged (see Risks for the blocking-read interaction).
- No change to size-limit (`maxBytes`) or `IOException` handling.
- No change to `[DONE]` handling, multi-line `data:` joining, or event dispatch rules.
- No suppression of named `heartbeat` events — they keep flowing into the result.

## Decisions

### Decision 1: Idle timeout reset on every line, plus an independent hard cap

`parse(...)` computes two deadlines from the injected `Clock` at entry:

```
final long startMs       = clock.millis();
final long hardDeadlineMs = startMs + maxTotalDurationMs;   // absolute cap
long idleDeadlineMs       = startMs + idleTimeoutMs;        // resets per line
```

Inside the read loop, the check runs **before** processing each line and the idle deadline is reset **after** the check:

```
while ((line = reader.readLine()) != null) {
    final long now = clock.millis();
    if (now > hardDeadlineMs || now > idleDeadlineMs) {
        return new SseParseResult(events, ExecutionStatus.TIMEOUT, null);
    }
    idleDeadlineMs = now + idleTimeoutMs;   // any line proves liveness
    ... existing line dispatch ...
}
```

Both bounds yield `ExecutionStatus.TIMEOUT` (same status as today — no new enum value). The idle reset applies to *every* line: `data:`, `event:`, comments, `id:`/`retry:`, and blank lines. Heartbeats need no special-casing — they reset the idle deadline simply by being lines that arrived.

**Why over alternatives:**
- *Keep absolute-only deadline (status quo):* rejected — defeats the purpose; long live streams die.
- *Reset only on heartbeat lines:* rejected (user decision) — a stream emitting only content with no keep-alives would still time out; classifying "what is a heartbeat" is brittle. "Any line resets" subsumes heartbeats and is simpler.
- *Idle-only, no hard cap:* rejected (user decision) — an endlessly-heartbeating or looping server could stream forever; only `maxBytes` would bound it, and a stream of tiny comment lines accumulates no data bytes, so `maxBytes` would never trigger. The hard cap is the safety net.

### Decision 2: Dedicated, globally-shared `sse-event-processing.max-total-duration-ms`

The hard cap is a single new property in a dedicated `@ConfigurationProperties(prefix = "sse-event-processing")` class `SseEventProcessingProperties` (package `configuration.properties`), field `Long maxTotalDurationMs` (`@NotNull @Min(1000)`). The `application.yml` default is **`3600000` ms (1 hour)** — set high so it acts as a true global ceiling, not a working timeout (confirmed as the intended default).

The two callers inject `SseEventProcessingProperties` directly and pass `getMaxTotalDurationMs()` as `maxTotalDurationMs`. The **idle** timeout keeps its current source per path:
- eval: `context.getRequestTimeoutMs()` (per-run, user-configurable),
- try-it-out: `dialCoreProperties.getTryOut().getReadTimeoutMs()`.

**Why a dedicated group over reusing existing properties:**
- The cap is global and shared by both paths; nesting it under `test-suite-run.execution.*` (eval-only) or `dial.components.core.try-out.*` (try-it-out-only) would be semantically wrong for the other path.
- The user requirement: a separate knob that can be set high without perturbing the commonly-used `requestTimeoutMs` / `read-timeout-ms` (which also govern non-streaming behavior).
- The existing `test-suite-run.sse.*` group is the unrelated **run-progress SSE emitter** (`TestSuiteRunSseService`); reusing it would conflate two distinct concerns. A new top-level group avoids the collision.

### Decision 3: New `parse(...)` signature and caller wiring

```
SseParseResult parse(InputStream stream, long idleTimeoutMs, long maxTotalDurationMs, long maxBytes)
```

- `EvaluationWorker.invokeSingle`: drop `deadlineMs`; build `new StreamingResponseAccumulator(sseEventParser, objectMapper, context.getRequestTimeoutMs(), maxTotalDurationMs, context.getMaxResponseSizeBytes())`.
- `StreamingResponseAccumulator`: replace the `long deadlineMs` field with `long idleTimeoutMs` + `long maxTotalDurationMs`; forward all three to `parse(...)`.
- `TryItOutService.buildSseResponse`: pass `tryOut.getReadTimeoutMs()`, `sseEventProcessingProperties.getMaxTotalDurationMs()`, `maxBytes`.

This is an internal contract change only; both callers are updated in the same change.

### Decision 4: Revert heartbeat/comment type mutation

Restore comment handling to "ignored", collapsing the WIP branches back to:

```
} else if (line.startsWith(":") || line.startsWith("id:") || line.startsWith("retry:")) {
    // Comment, id, and retry fields are ignored per SSE spec
}
```

Remove the `HEARTBEAT_EVENT_TYPE` constant. Named `event: heartbeat` events are unaffected — they travel the existing `event:` branch and emit normally. This keeps the `sse-event-parsing` wire-format rules intact and fixes the type-leak bug where a mid-block comment would stamp the next data event as `"heartbeat"`.

## Risks / Trade-offs

- **Blocking read vs. clock check** → The `clock`-based idle/hard checks only run *between* lines; if the socket stalls mid-line with no bytes, `BufferedReader.readLine()` blocks. This is unchanged from today's behavior (the existing absolute-deadline check has the same property) and is backstopped by the HTTP client read timeouts. Documented, not a regression. Mitigation: the underlying DIAL Core / try-out client read timeouts unblock a truly dead socket.
- **Idle timeout slightly more permissive than before** → A stream that trickles one line just under the idle interval indefinitely is now bounded only by the hard cap, not the old absolute request timeout. Mitigation: the hard cap (default 1h) bounds total duration; operators tune it. Size limit (`maxBytes`) still bounds memory for data-bearing streams.
- **New required config property** → Missing/invalid value fails fast at startup (`@NotNull @Min`). Mitigation: ship a safe default in `application.yml`; document the row in `docs/configuration.md`.
- **Test churn** → `SseEventParserTest` constructs `parse(...)` with `FAR_FUTURE_DEADLINE`; the new signature needs `idleTimeoutMs` + `maxTotalDurationMs` arguments and the timeout scenarios must use a `Clock` that advances *between* successive `clock.millis()` calls. Mitigation: introduce a deterministic advancing test `Clock` — a `Clock` subclass backed by a mutable `AtomicLong` the test increments between line reads (NOT `Clock.fixed`/`Clock.offset`, which return a constant per call and so cannot cross a deadline mid-parse); convert "deadline already exceeded" to "idle/hard timeout exceeded".

## Migration Plan

1. Add `SseEventProcessingProperties` + `application.yml` default + `docs/configuration.md` row.
2. Change `SseEventParser.parse(...)` signature and internal deadline logic; revert comment handling; remove `HEARTBEAT_EVENT_TYPE`.
3. Update `StreamingResponseAccumulator` fields/constructor and `EvaluationWorker` call site.
4. Update `TryItOutService` to inject the new properties and pass timeouts.
5. Update tests (`SseEventParserTest`, `StreamingResponseAccumulatorTest`, `TryItOutServiceTest`) and the `sse-event-parsing` / `try-it-out` specs.

No data migration, no Flyway change. Rollback = revert the change; the property is additive and unused after revert.
