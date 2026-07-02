## 1. Executor change

- [x] 1.1 In `MultiStepConversationExecutor`, replace `extractAssistantContent(String)→String` with `extractAssistantMessage(String)→JsonNode`: return `root.path("choices").path(0).path("message")` only when it `isObject()`, else `null` (also `null` on blank body / parse failure, logging the exception as the last SLF4J arg).
- [x] 1.2 Rewrite the per-turn append block: call `extractAssistantMessage(...)`; when it returns `null` keep the existing `log.warn` + `finalStatus = ERROR; break;` (no-message-object abort); otherwise `history.add(assistantMessage)` with the raw `JsonNode`/`ObjectNode`. Deleted the `LinkedHashMap` role/content reconstruction.
- [x] 1.3 Updated the class javadoc: the assistant reply is now the full `choices[0].message` appended verbatim; the abort condition is "2xx with no `message` object". (Note corrected during impl: `content: null` is stripped by the shared `NON_NULL` serialization of the response body before extraction, so it is appended as absent `content` — see design Decision 3.)
- [x] 1.4 Ran `./gradlew spotlessApply` — BUILD SUCCESSFUL.

## 2. Tests

- [x] 2.1 Added `appendsFullAssistantMessageVerbatim` — turn 0's `choices[0].message` carries `tool_calls` + `refusal` and no string `content`; asserts the turn is NOT aborted (SUCCESS) and turn 1's resent history entry preserves those extra fields verbatim (asserted as a `JsonNode`). Updated the `roleOf` helper to accept a `JsonNode`.
- [x] 2.2 Reworked the former "no extractable content" case into `failFastOnNoMessageObject` — a 2xx response whose `choices[0]` has no `message` object → `executionStatus = ERROR`, empty `extractedColumns` array.
- [x] 2.3 Remaining multi-turn unit tests pass unchanged (happy-path, fail-fast, broadcast, turn-count guards).
- [x] 2.4 Ran the unit class and `PostgresFunctionalTests$MultiStepConversationRunTests` — both BUILD SUCCESSFUL.

## 3. Docs & spec sync

- [x] 3.1 Updated the AGENTS.md multi-step inline convention (line 146): the assistant reply appended to history is the full `choices[0].message` verbatim; abort only when no message object; noted the `NON_NULL`/`content: null` interaction. Response-column extraction wording left unchanged.
- [x] 3.2 At archive time, sync the delta spec into `openspec/specs/multi-step-conversation/spec.md` via the `/opsx:archive` sync step (intelligent merge, not a copy).
