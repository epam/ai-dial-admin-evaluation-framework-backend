## Why

`SseEventParser` currently stops reading an SSE stream at a fixed absolute wall-clock deadline (`clock.millis()` plus a per-path timeout — `requestTimeoutMs` on the eval path, `read-timeout-ms` on the try-it-out path). A legitimately long-running stream that stays alive by emitting periodic keep-alives (SSE comment lines or named `heartbeat` events) is killed once total elapsed time crosses that deadline, even though it is actively producing data. The desired behavior is an **idle (inactivity) timeout**: the deadline is recomputed from the configured timeout on every line read, so an active stream never spuriously times out, while a stalled one still stops promptly. An absolute max-total cap remains as a safety net against a server that heartbeats forever.

## What Changes

- **BREAKING (internal API):** `SseEventParser.parse(InputStream, long deadlineMs, long maxBytes)` becomes `parse(InputStream, long idleTimeoutMs, long maxTotalDurationMs, long maxBytes)`. Callers now pass **timeout durations**, not a precomputed epoch-ms deadline; the parser derives all deadlines internally from the injected `Clock`. This is an internal component contract — no public REST API surface changes.
- **Idle-timeout semantics:** the parser maintains an idle deadline of `clock.millis() + idleTimeoutMs` that is reset on **every line read** (data, event, heartbeat, comment, id, retry). If a line arrives after the idle deadline has already passed, parsing stops with `ExecutionStatus.TIMEOUT`.
- **Absolute max-total cap:** independently of the idle deadline, the parser enforces `startMs + maxTotalDurationMs`. Crossing it stops parsing with `ExecutionStatus.TIMEOUT`, bounding total stream duration even under continuous heartbeats. This cap is driven by a single **dedicated, standalone configuration property** (see below) — NOT derived from the per-path request/read timeouts.
- **Heartbeat handling cleaned up:** detecting a `:`-comment line MUST NOT mutate `currentEventType` (the WIP behavior leaks `"heartbeat"` onto the next emitted data event). Comment lines remain ignored as content (per SSE spec) but still reset the idle deadline like any other line. Named `event: heartbeat` events continue to be emitted into the result.
- **Callers updated:** `EvaluationWorker` and `StreamingResponseAccumulator` (eval path) and `TryItOutService` (try-it-out path) pass their existing per-path timeout as `idleTimeoutMs`, and the shared dedicated cap as `maxTotalDurationMs`, instead of computing a deadline.
- **New configuration — one dedicated global property:** a single max-total-duration property `sse-event-processing.max-total-duration-ms` (new top-level group, distinct from the existing `test-suite-run.sse.*` which configures the unrelated run-progress SSE emitter) that serves as the global hard cap for BOTH streaming paths. It is intentionally decoupled from the commonly-used `requestTimeoutMs` (eval) and `dial.components.core.try-out.read-timeout-ms` (try-it-out) so it can be set to a high value to allow long heartbeat-kept-alive streams without changing those shared timeouts. Those existing properties continue to provide the per-path **idle** timeout. Default set in `application.yml`; `docs/configuration.md` updated with the required six-column row.

## Capabilities

### New Capabilities

_None._ This change modifies existing behavior; it introduces no new capability spec.

### Modified Capabilities

- `sse-event-parsing`: The "Deadline enforcement" requirement is replaced by idle-timeout + max-total-cap enforcement; `parse(...)` signature changes to accept timeout durations; heartbeat/comment handling is clarified (comments do not alter event type; every line resets the idle deadline).
- `try-it-out`: The "Try-it-out SSE timeout enforcement" requirement changes from a fixed `clock.millis() + readTimeoutMs` deadline to passing the read timeout as the idle timeout plus the shared dedicated max-total-duration cap.

(Adding the new property's row to `docs/configuration.md` complies with the existing `configuration-docs` spec — that spec's requirements do not change, so it is handled as an implementation task, not a spec delta.)

## Impact

- **Code:** `service.domain.job.SseEventParser`, `StreamingResponseAccumulator`, `EvaluationWorker`, `service.domain.TryItOutService`. The global cap is a system-wide property injected directly into the two callers — it is NOT threaded through the per-run `EvaluationContext`.
- **Configuration:** one new dedicated `SseEventProcessingProperties` class (`@ConfigurationProperties(prefix = "sse-event-processing")`, shared by both streaming paths); `application.yml` default; `docs/configuration.md`. No changes to the existing `requestTimeoutMs` / `try-out.read-timeout-ms` properties — they keep their current meaning as the per-path idle timeout.
- **Tests:** `SseEventParserTest`, `StreamingResponseAccumulatorTest`, `TryItOutServiceTest` updated to the new signature and idle/cap semantics (existing deadline-based assertions migrated).
- **Docs/specs:** `sse-event-parsing`, `try-it-out`, `configuration-docs` spec deltas; `docs/configuration.md`.
- No DB schema changes, no Flyway migration, no security impact.
