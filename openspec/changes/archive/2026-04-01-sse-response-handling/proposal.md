## Why

DIAL app routes (non-standard endpoints) can return SSE (Server-Sent Events) responses with app-specific event structures. The current system has two problems:

1. **TryItOut uses `invoke()` (non-streaming)** — raw SSE text is returned as an opaque string, completely unusable for debugging or JSONata testing.
2. **Evaluation's `StreamingResponseAccumulator` discards SSE event type names** (`event:` lines are silently ignored) — the raw mode produces a bare JSON array of `data:` payloads with no way to filter by event type. Additionally, apps that return stringified JSON in event fields (e.g., `"body": "{...}"`) produce string values that JSONata cannot navigate into.

Different apps have different SSE structures — the solution must be app-agnostic, relying only on the SSE wire format (RFC).

## What Changes

- **New generic `SseEventParser`** — parses SSE `InputStream` into structured `(event, data)` pairs. Preserves named event types (defaults to `"message"` per SSE spec when absent). Parses `data:` payloads as JSON when valid, keeps raw string otherwise. Handles multi-line `data:` fields, `[DONE]` termination, deadline and size limits.
- **Refactored `StreamingResponseAccumulator`** — uses `SseEventParser` internally. OpenAI mode (auto-detected: first event has `choices[]` + type is `"message"`) unchanged — still collapses deltas. Non-OpenAI mode now produces `{"events": [{event, data}, ...]}` envelope instead of a bare array, preserving event type names.
- **`TryItOutService` SSE support** — switches from `invoke()` to `invokeWithStreaming()` for HTTP/DEPLOYMENT suites. When response is SSE, returns parsed events in `TryItOutCoreResponseDto` so the frontend can render individual events for debugging.
- **`TryItOutCoreResponseDto` extended** — adds `streaming` boolean and `events` list field for SSE responses. Non-SSE responses unchanged.
- **`DeploymentInvocationResponse` extended or new DTO** — the non-streaming `invoke()` method result may need to carry streaming info, OR TryItOut switches to using `invokeWithStreaming()` directly.
- **Stored `response_body` format change** — for non-OpenAI SSE, analytics DB stores `{"events": [...]}` object instead of `[...]` bare array. Existing JSONata expressions written against the old format would need updating. **BREAKING** for any existing response column expressions targeting raw SSE array format.

## Capabilities

### New Capabilities
- `sse-event-parsing`: Generic SSE wire format parser that converts `InputStream` into structured event objects with type preservation and JSON payload parsing. Shared by both TryItOut and Evaluation paths.

### Modified Capabilities
- `eval-execution-engine`: Streaming accumulator refactored to use new SSE parser; non-OpenAI mode output format changes from bare array to `{"events": [...]}` envelope with event type names preserved.
- `try-it-out`: Switches to streaming-capable invocation for HTTP suites; response DTO extended with SSE event list for debugging.

## Impact

- **Code**: New `SseEventParser` class in `service.domain.job` (or `service.domain`). Refactored `StreamingResponseAccumulator`. Modified `TryItOutService`, `TryItOutCoreResponseDto`.
- **API**: TryItOut response shape extended (additive — new `streaming` and `events` fields). Non-SSE responses unchanged.
- **Storage**: `response_body` in `test_case_run_results` changes format for non-OpenAI SSE from `[...]` to `{"events": [...]}`. No DB schema migration needed (column is text/JSONB string).
- **Breaking**: Existing JSONata response column expressions written for the bare array SSE format (e.g., `$[0].field`) must be updated to `events[0].data.field`. This only affects suites targeting non-OpenAI SSE endpoints — standard OpenAI and non-SSE responses are unaffected.
- **No new dependencies**: Uses existing Spring `RestClient` streaming support and Jackson for JSON parsing.
- **No config changes**: Existing timeout and size limit properties are reused.
- **OpenAPI**: TryItOut response examples need updating to show SSE response shape.
