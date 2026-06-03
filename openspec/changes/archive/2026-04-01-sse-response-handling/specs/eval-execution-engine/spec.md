## MODIFIED Requirements

### Requirement: Streaming response accumulation
`StreamingResponseAccumulator` parses SSE event streams, accumulates content, and assembles complete response bodies. It operates in two modes:

1. **OpenAI mode** — auto-detected when the first event has `choices[]` array in data AND event type is `"message"` (no named `event:` line). Extracts `choices[0].delta.content` from each chunk, concatenates, and assembles a complete non-streaming chat-completions response.
2. **Structured SSE mode** — for all other SSE streams. Preserves event type names and wraps parsed events in a `{"events": [...]}` envelope.

The accumulator SHALL delegate SSE wire format parsing to `SseEventParser`. It SHALL accept `SseEventParser` as a constructor parameter (in addition to `ObjectMapper`).

The accumulator SHALL use `Clock` (via `SseEventParser`) for deadline checking instead of `System.currentTimeMillis()`.

**Status:** Planned

#### Scenario: OpenAI chat-completions SSE format
- **WHEN** response stream contains events with no named `event:` type and data in format `{"choices":[{"delta":{"content":"..."}}]}`
- **THEN** accumulator SHALL extract content deltas from each chunk, concatenate them, and assemble a complete response in non-streaming chat-completions format (with `message.content` instead of `delta.content`)

#### Scenario: Stream termination
- **WHEN** stream contains `data: [DONE]`
- **THEN** accumulator SHALL finalize the assembled response

#### Scenario: Stream error mid-accumulation
- **WHEN** SSE stream is interrupted (connection drop, timeout) after receiving partial data
- **THEN** accumulator SHALL set `executionStatus = ERROR` and store whatever was accumulated as `responseBody`

#### Scenario: Non-OpenAI streaming format fallback
- **WHEN** response has `Content-Type: text/event-stream` but events do NOT follow OpenAI format (either named `event:` types are present, or data lacks `choices[].delta.content` structure)
- **THEN** accumulator SHALL produce `responseBody` as a JSON object with `events` array: `{"events": [{"event": "<type>", "data": <payload>}, ...]}`. Each event's `event` field SHALL contain the SSE event type name (defaulting to `"message"` when absent). Each event's `data` field SHALL contain parsed JSON if the data payload is valid JSON, or a raw string if not.

#### Scenario: OpenAI mode detection with named events
- **WHEN** SSE stream has named `event:` types (e.g., `event: process_rules`)
- **THEN** accumulator SHALL use structured SSE mode regardless of data payload structure — named events are never treated as OpenAI format

#### Scenario: JSON array fallback enables JSONata extraction
- **WHEN** non-OpenAI SSE response is stored as `{"events": [...]}` envelope
- **THEN** JSONata `responseColumns` expressions SHALL be able to filter by event type (e.g., `events[event="process_rules"].data.evaluated_rule.status`), access last event (e.g., `events[-1].data`), or iterate all events (e.g., `events.data.result`). `DashjoinJsonataEvaluationService` MUST accept generic JSON input (supporting both JSON objects AND arrays at top level).

#### Scenario: Empty SSE stream produces empty envelope
- **WHEN** SSE stream has `Content-Type: text/event-stream` but contains no events (immediate EOF or only comments/empty lines)
- **THEN** accumulator SHALL produce `responseBody` as `{"events": []}` (empty envelope) with `executionStatus = SUCCESS`

#### Scenario: All events have non-JSON data in structured mode
- **WHEN** non-OpenAI SSE stream contains events where all data payloads are plain text (not valid JSON)
- **THEN** accumulator SHALL produce `{"events": [{"event": "<type>", "data": "<raw string>"}, ...]}` — each event's `data` is a JSON string value, not a parsed object

### Requirement: Response size limiting
Streaming responses SHALL be limited by configurable max response size. Size tracking and truncation are handled by `SseEventParser` (which tracks accumulated bytes during parsing).

When accumulated content exceeds `max-response-size-bytes`:
- Accumulator SHALL stop processing
- Store response as the best available representation of accumulated data (OpenAI: truncated content string; structured SSE: envelope with events accumulated so far)
- Record truncation warning
- Set `executionStatus = ERROR`

**Status:** Planned

#### Scenario: Size limit during OpenAI streaming
- **WHEN** accumulated delta content exceeds `max-response-size-bytes`
- **THEN** accumulator SHALL stop, store truncated content as JSON string, set `executionStatus = ERROR`, record truncation warning

#### Scenario: Size limit during structured SSE streaming
- **WHEN** accumulated event data bytes exceed `max-response-size-bytes`
- **THEN** accumulator SHALL stop, store `{"events": [...]}` envelope with events accumulated before limit, set `executionStatus = ERROR`, record truncation warning

### Requirement: Streaming-aware deployment invocation
`DialCoreDeploymentInvoker` SHALL expose `invokeWithStreaming()` method that returns `DeploymentInvocationResult` with streaming detection and raw `InputStream` access for SSE consumption. This requirement is unchanged — existing behavior preserved.

**Status:** Planned

#### Scenario: invokeWithStreaming returns non-streaming result
- **WHEN** endpoint returns response with `Content-Type` that is NOT `text/event-stream`
- **THEN** `DeploymentInvocationResult.streaming` SHALL be `false`, `body` SHALL contain parsed response, `eventStream` SHALL be `null`

#### Scenario: invokeWithStreaming returns streaming result
- **WHEN** endpoint returns response with `Content-Type: text/event-stream`
- **THEN** `DeploymentInvocationResult.streaming` SHALL be `true`, `eventStream` SHALL contain raw SSE `InputStream`, `body` SHALL be `null`

#### Scenario: DeploymentInvocationResult resource lifecycle
- **WHEN** worker receives `DeploymentInvocationResult` with `streaming = true`
- **THEN** worker SHALL use result in try-with-resources block; `close()` SHALL close underlying `eventStream`

### Requirement: Endpoint invocation (streaming SSE)
When resolved request is sent and response `Content-Type` is `text/event-stream`, worker SHALL accumulate SSE events via refactored `StreamingResponseAccumulator` (which delegates to `SseEventParser`), assemble them into complete response body (OpenAI format or structured `{"events": [...]}` envelope), and record assembled response.

**Status:** Planned

#### Scenario: Endpoint invocation (streaming SSE)
- **WHEN** resolved request is sent to deployment and response Content-Type is `text/event-stream`
- **THEN** worker SHALL accumulate SSE chunks via `StreamingResponseAccumulator`, assemble into response body, and record assembled response with status and timing

### Requirement: HTTP client factory for streaming support
`DialCoreDeploymentInvokerConfiguration` SHALL use `JdkClientHttpRequestFactory` (backed by `java.net.http.HttpClient`) with HTTP/1.1 protocol version pinned. This requirement is unchanged.

**Status:** Planned

#### Scenario: JdkClientHttpRequestFactory used for deployment invoker
- **WHEN** deployment invoker `RestClient` bean is created
- **THEN** SHALL use `JdkClientHttpRequestFactory` as request factory, enabling chunked/streaming response reading

#### Scenario: HTTP/1.1 protocol version pinned
- **WHEN** `JdkClientHttpRequestFactory` is configured
- **THEN** underlying `HttpClient` SHALL be built with `HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)`
