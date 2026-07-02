## Why

In multi-turn runs, `MultiStepConversationExecutor` re-sends the full `messages` history each turn, but it reconstructs each assistant turn as a stripped `{role:"assistant", content:<string>}` from the hardcoded `choices[0].message.content` path. Everything else the model returned — `tool_calls`, `refusal`, `name`, `reasoning_content`, structured/array content, provider-specific fields — is discarded, so the resent history does not match what the model actually said and any tool-calling or non-plain-text conversation breaks on the next turn. Appending the full assistant message is both more correct and simpler (no reconstruction).

## What Changes

- Append the entire `choices[0].message` object **verbatim** to the running conversation history, instead of rebuilding a two-field `{role, content}` map. The `message`-level object only (not the `choices[0]` wrapper, whose `finish_reason`/`index`/`logprobs` are invalid inside a `messages` array).
- Change the per-turn abort gate: abort the conversation with `ERROR` only when `choices[0].message` is **absent or not a JSON object** (missing `choices`, empty array, or no `message`). A present message object **without a string `content`** (e.g. a tool-call-only turn) is now **valid** and appended as-is.
- Store the appended assistant message as the raw Jackson `JsonNode`/`ObjectNode` (not a hand-built `Map`) — the simplest verbatim path, no field copying. Note: the response body is serialized with the shared `NON_NULL` `ObjectMapper` before the message is read, so a reply whose `content` is JSON `null` is appended with `content` absent (functionally equivalent for resend); preserving a literal `content: null` is out of scope (it would require a larger, non-simpler change to response parsing).
- Out of scope (untouched): response-column extraction and the per-step `extractedColumns` array, `responseBody` = last raw response, retries, and fail-fast semantics. Response-column extraction will be revised later under its own separate spec change.

No API, DB schema, config, or OpenAPI changes — this is internal executor behavior only.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `multi-step-conversation`: the "Assistant reply extraction (hardcoded OpenAI path, non-streaming)" requirement changes — the appended history turn is the **full `choices[0].message`** (not a reconstructed `{role, content}`), and the abort condition becomes "no `message` object returned" rather than "no extractable `content` string". The per-step turn-loop and fail-fast requirements are reworded to match (append the full assistant message; abort on a missing message object).

## Impact

- **Code:** `service/domain/job/MultiStepConversationExecutor.java` only — replace `extractAssistantContent(String)→String` with `extractAssistantMessage(String)→JsonNode` (returns the `ObjectNode`, or `null` when absent/not-an-object); rewrite the append block; update the class javadoc.
- **Tests:** `service/domain/job/MultiStepConversationExecutorTest.java` — add a turn whose mock `choices[0].message` carries extra fields (`tool_calls`/`refusal`) and no string `content`, asserting the resent request body's history contains the message verbatim (extra fields preserved) and that the turn is not aborted; rework the former "no extractable content" abort test into a "no `message` object → ERROR, partial history" test. The `roleOf` test helper must accept a `JsonNode` (appended assistant messages are now `ObjectNode`s, not `Map`s). Existing turn tests are otherwise unaffected. Functional `MultiStepConversationRunFunctionalTests` needs no change.
- **Docs:** AGENTS.md multi-step inline convention — update the "hardcoded `choices[0].message.content` extraction" phrasing for the appended reply to "full `choices[0].message` appended verbatim; abort only when no message object". No `docs/database-schema.md` or `docs/configuration.md` change.
- **APIs / DB / dependencies:** none.
