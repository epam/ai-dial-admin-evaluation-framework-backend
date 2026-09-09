## MODIFIED Requirements

### Requirement: URL and method resolution from endpoint contract
The worker SHALL construct the full request URL and HTTP method from the suite's `deploymentRef` and `endpointRef`, using `DialCoreUrlBuilder` for URL construction.
Status: **Implemented**

#### Scenario: Standard OpenAI endpoint
- **WHEN** `endpointRef.relativeUrlPattern` is `/chat/completions` and `deploymentRef.id` is `gpt-4`
- **THEN** the worker SHALL construct URL as `{dialCoreBaseUrl}/openai/deployments/gpt-4/chat/completions`

#### Scenario: Custom endpoint
- **WHEN** `endpointRef.relativeUrlPattern` is `/custom/predict`
- **THEN** the worker SHALL construct URL as `{dialCoreBaseUrl}/v1/deployments/{id}/route/custom/predict`

#### Scenario: HTTP method from endpointRef
- **WHEN** `endpointRef.method` is `POST`
- **THEN** the worker SHALL use POST as the HTTP method for the deployment call

#### Scenario: Anthropic Messages endpoint
- **WHEN** `endpointRef.relativeUrlPattern` is `/anthropic/v1/messages`
- **THEN** the worker SHALL construct URL as `{dialCoreBaseUrl}/anthropic/v1/messages` (passed through unchanged — it is DIAL Core's real, deployment-less route), with the deployment ID NOT included anywhere in the URL. The suite author is responsible for including a `model` field identifying the deployment inside the JSON request body — DIAL Core resolves the deployment from that field, not from the URL. This mapping applies regardless of the value of `deploymentRef.id`.

### Requirement: Streaming response accumulation
`StreamingResponseAccumulator` parses SSE event streams and assembles complete response bodies. It delegates SSE wire format parsing to `SseEventParser` and operates in three modes:

1. **OpenAI mode** — auto-detected when the first event has no named `event:` type (type is `"message"`) AND its data contains a `choices[]` array. Extracts `choices[0].delta.content` from each chunk, concatenates, and assembles a complete non-streaming chat-completions response.
2. **Anthropic mode** — auto-detected when the first event's named `event:` type is `message_start` AND its data contains a `message` object. Reassembles `content_block_start`/`content_block_delta`/`message_delta` events into a complete Anthropic Messages response: a `content` array (text and tool_use blocks merged from deltas, other block types passed through unchanged) plus the base message's `stop_reason`/`stop_sequence`/`usage` merged from `message_delta`.
3. **Structured SSE mode** — for all other streams. Wraps parsed events in a `{"events": [{"event": "<type>", "data": <payload>}, ...]}` envelope. Named events force this mode — unless they match Anthropic mode's detection — regardless of data payload structure.

Status: **Implemented**

#### Scenario: OpenAI chat-completions SSE format
- **WHEN** response stream contains events with no named `event:` type and data in format `{"choices":[{"delta":{"content":"..."}}]}`
- **THEN** the accumulator SHALL extract content deltas from each chunk, concatenate them, and assemble a complete response in non-streaming chat-completions format (with `message.content` instead of `delta.content`)

#### Scenario: Stream termination
- **WHEN** the stream contains `data: [DONE]`
- **THEN** the accumulator SHALL finalize the assembled response

#### Scenario: Stream error mid-accumulation
- **WHEN** the SSE stream is interrupted (connection drop, timeout) after receiving partial data
- **THEN** the accumulator SHALL set `executionStatus = ERROR` and store whatever was accumulated as `responseBody`

#### Scenario: Non-OpenAI streaming format fallback
- **WHEN** the response has `Content-Type: text/event-stream` but events do NOT follow OpenAI format (either named `event:` types are present, or data lacks `choices[].delta.content` structure), AND the stream does NOT match Anthropic mode's detection (first event named `message_start` carrying a `message` object)
- **THEN** the accumulator SHALL produce `responseBody` as a JSON object with `events` array: `{"events": [{"event": "<type>", "data": <payload>}, ...]}`. Each event's `event` field SHALL contain the SSE event type name (defaulting to `"message"` when absent). Each event's `data` field SHALL contain parsed JSON if the data payload is valid JSON, or a raw string if not.

#### Scenario: OpenAI mode detection with named events
- **WHEN** SSE stream has named `event:` types (e.g., `event: process_rules`) that do not match Anthropic mode's `message_start`-with-`message` detection
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

#### Scenario: Anthropic Messages SSE format detected
- **WHEN** the response stream's first event has a named `event:` type of `message_start` and its data contains a `message` object
- **THEN** the accumulator SHALL use Anthropic mode

#### Scenario: Anthropic mode text block assembly
- **WHEN** an Anthropic-mode stream contains a `content_block_start` event with `content_block.type = "text"` at some index, followed by one or more `content_block_delta` events with `delta.type = "text_delta"` at that index
- **THEN** the accumulator SHALL concatenate the `delta.text` values in arrival order and produce a final `content` entry at that index: `{"type":"text","text": "<concatenated text>"}`

#### Scenario: Anthropic mode tool_use block assembly
- **WHEN** an Anthropic-mode stream contains a `content_block_start` event with `content_block.type = "tool_use"` at some index, followed by one or more `content_block_delta` events with `delta.type = "input_json_delta"` at that index
- **THEN** the accumulator SHALL concatenate the `delta.partial_json` fragments in arrival order, parse the concatenated result as JSON, and produce a final `content` entry at that index: `{"type":"tool_use","id":<id>,"name":<name>,"input": <parsed JSON>}`. If the concatenated fragments do not parse as valid JSON, `input` SHALL instead be the raw concatenated string.

#### Scenario: Anthropic mode multiple content blocks assembled in order
- **WHEN** an Anthropic-mode stream contains `content_block_start`/`content_block_delta` events for more than one content block index
- **THEN** the accumulator SHALL produce the final `content` array with entries ordered by ascending block index

#### Scenario: Anthropic mode message_delta merged
- **WHEN** an Anthropic-mode stream contains a `message_delta` event with a `delta` object (e.g., `stop_reason`, `stop_sequence`) and/or a `usage` object
- **THEN** the accumulator SHALL merge `delta.stop_reason`/`delta.stop_sequence` into the base message object (taken from the `message_start` event's `message`) and merge `usage` into that base message's `usage` object

#### Scenario: Anthropic mode ignores lifecycle-only events
- **WHEN** an Anthropic-mode stream contains `content_block_stop`, `message_stop`, or `ping` events
- **THEN** the accumulator SHALL treat them as no-ops for content reconstruction

#### Scenario: Anthropic mode non-text/tool_use block passthrough
- **WHEN** a `content_block_start` event carries a `content_block.type` other than `text` or `tool_use` (e.g., `thinking`)
- **THEN** the accumulator SHALL pass that block through unchanged, exactly as captured from `content_block_start`, into the final `content` array at its index — without reconstructing it from subsequent delta events

### Requirement: Response size limiting
The system SHALL enforce a configurable maximum response body size. Responses exceeding the limit SHALL be truncated with a warning recorded. Because `response_body` is a JSONB column, truncation MUST produce valid JSON — raw byte-level truncation is NOT allowed.
Status: **Implemented**

#### Scenario: Response within limit
- **WHEN** the response body is within `max-response-size-bytes` (default 5MB)
- **THEN** the full response body SHALL be stored

#### Scenario: Response exceeds limit (non-streaming)
- **WHEN** the non-streaming response body exceeds `max-response-size-bytes`
- **THEN** the worker SHALL store the response body as a JSON string containing the raw response text truncated at the byte limit (e.g., `"<truncated text>"`). This guarantees JSONB validity since a JSON string is always valid JSONB. `extractionWarnings` SHALL include a truncation warning with the original and truncated sizes. The `executionStatus` SHALL be set to `ERROR` (truncated response is incomplete data). Note: JSONata extraction on truncated content may produce partial/incorrect results — this is expected and communicated via the truncation warning.

#### Scenario: Streaming response exceeds limit
- **WHEN** accumulated SSE event data bytes exceed `max-response-size-bytes` during streaming (size tracked by `SseEventParser`)
- **THEN** the accumulator SHALL stop accumulating and set `executionStatus = ERROR`. For OpenAI mode: store the accumulated content as a truncated JSON string. For Anthropic mode: store the concatenation of all `text`-block accumulated text (in ascending block-index order) as a truncated JSON string. For structured SSE mode: store the `{"events": [...]}` envelope with events accumulated before the limit was hit. A truncation warning SHALL be recorded in all cases.

## Implementation Notes (addendum)
At sync/archive time, add the following line to the baseline spec's existing "## Implementation Notes" section (also update the "Key Terms" entry for `StreamingResponseAccumulator` from "Two-mode" to "Three-mode"):
- Anthropic content accumulator: `com.epam.aidial.evaluation.runner.job.AnthropicContentAccumulator` (package-private, `evaluation-runner-core`; mirrors `CustomContentAccumulator`'s per-run lifecycle)
