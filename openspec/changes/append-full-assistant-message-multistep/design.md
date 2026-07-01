## Context

`MultiStepConversationExecutor.execute(...)` drives a multi-turn chat-completions conversation for one test case, re-sending the full `messages` history each turn. After each successful turn (`MultiStepConversationExecutor.java:189-202`) it currently:

1. reads the string at `choices[0].message.content` via `extractAssistantContent(String)→String`;
2. if that string is absent, treats the turn as "2xx with no extractable assistant reply", sets `ERROR`, and aborts (fail-fast, partial history kept);
3. otherwise builds a fresh `LinkedHashMap` `{role:"assistant", content:<string>}` and appends it to the `history` list.

The `history` list is `List<Object>`; template user messages are parsed `Map`s, and the whole history is serialized once per turn via the shared `objectMapper.writeValueAsString`. That `ObjectMapper` is configured `NON_NULL`.

Reconstructing the assistant turn drops `tool_calls`, `refusal`, `name`, `reasoning_content`, structured/array `content`, and any provider-specific fields, so the resent history diverges from the model's actual output.

## Goals / Non-Goals

**Goals:**
- Append the model's assistant turn to history exactly as returned (`choices[0].message` verbatim), so subsequent turns see the true prior context (including tool calls).
- Support tool-call-only turns where `content` is `null`, preserving that explicit null on resend.
- Keep the change minimal and localized to `MultiStepConversationExecutor` (one helper + the append block + javadoc).

**Non-Goals:**
- Changing response-column extraction, the per-step `extractedColumns` array, `responseBody`, retries, or fail-fast semantics (a separate future spec change owns response-column extraction).
- Any API, DB, config, or OpenAPI change.
- The single-step executor (it does not build conversation history).
- Special multimodal handling (array/object `content` already passes through verbatim once we stop reconstructing).

## Decisions

**1. Append `choices[0].message` verbatim (message-level, not `choices[0]`).** The `choices[0]` wrapper carries `finish_reason`/`index`/`logprobs`, which are not valid inside a `messages` array and would pollute resent history. We append only the `message` object.

**2. New abort gate = "no message object".** Replace `extractAssistantContent(String)→String` with `extractAssistantMessage(String)→JsonNode` returning `root.path("choices").path(0).path("message")` **only if `isObject()`**, else `null` (also `null` on blank body / parse failure, logging the exception as the last SLF4J arg). The append block aborts with `ERROR` (keeping the existing `log.warn` + `break`, partial history retained) only when the helper returns `null`. A message object with `content: null` is valid and appended.

**3. Store the raw `ObjectNode` in `history`.** Add the returned `JsonNode` directly to the `List<Object>` history rather than converting to a `Map`. Rationale: a `JsonNode` serializes faithfully — a `NullNode` (`content: null`) is written as JSON `null`, whereas a `Map` with a `null` value would be silently stripped by the `NON_NULL` `ObjectMapper` (per AGENTS.md). Mixing `JsonNode` and `Map` entries in the same list serializes cleanly through the single `writeValueAsString`. This is also the simplest code path (no field copying).

**4. Blast radius.** Only the `extractAssistant*` helper, the append block (lines ~189-202), and the class javadoc change. Response-column extraction (`responseColumnExtractor.extract(responseColumns, outcome.responseBody())`, lines 205-207) is untouched — it still evaluates the user-configured column expressions against the raw response.

## Risks / Trade-offs

- **More permissive gate:** turns that previously aborted (no string `content`) now succeed when a message object is present. This is intended (required for tool calls) and matches the new spec; the extreme case (a `message` object with neither `content` nor `tool_calls`) is accepted as a valid empty turn rather than an error — acceptable, and simpler than adding a minimum-content rule.
- **Heterogeneous history list** (`JsonNode` + `Map`): benign — both serialize through the same `ObjectMapper`; no consumer reads history back as typed objects (it is only serialized into the request body).
- **Downstream tolerance of `content: null`:** some providers may be strict about an assistant message with `content: null` and no `tool_calls`. This mirrors what the model itself returned, so it is faithful; strictness is a provider/data concern, not an executor bug.
