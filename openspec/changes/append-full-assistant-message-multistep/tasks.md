## 1. Executor change

- [ ] 1.1 In `MultiStepConversationExecutor`, replace `extractAssistantContent(String)→String` with `extractAssistantMessage(String)→JsonNode`: return `root.path("choices").path(0).path("message")` only when it `isObject()`, else `null` (also `null` on blank body / parse failure, logging the exception as the last SLF4J arg).
- [ ] 1.2 Rewrite the per-turn append block (currently lines ~189-202): call `extractAssistantMessage(...)`; when it returns `null` keep the existing `log.warn` + `finalStatus = ERROR; break;` (no-message-object abort); otherwise `history.add(assistantMessage)` with the raw `JsonNode`/`ObjectNode`. Delete the `LinkedHashMap` role/content reconstruction.
- [ ] 1.3 Update the class javadoc: the assistant reply is now the full `choices[0].message` appended verbatim (nulls preserved), and the abort condition is "2xx with no `message` object" rather than "no extractable `content`".
- [ ] 1.4 Run `./gradlew spotlessApply`.

## 2. Tests

- [ ] 2.1 Add a `MultiStepConversationExecutorTest` case where a turn's mock `choices[0].message` carries extra fields (`tool_calls` and/or `refusal`) and a `content: null` case; assert the next turn's resent request body `messages` history contains that assistant message verbatim — extra fields present and explicit `content: null` preserved.
- [ ] 2.2 Add/confirm a `MultiStepConversationExecutorTest` case where a 2xx response has no `choices[0].message` object (missing `choices` / empty array / non-object `message`) → `executionStatus = ERROR`, partial history kept, no extraction for that turn.
- [ ] 2.3 Confirm existing multi-turn unit tests still pass unchanged (mock message already `{role, content}` → verbatim equals reconstructed).
- [ ] 2.4 Run `./gradlew test --tests "com.epam.aidial.evaluation.service.domain.job.MultiStepConversationExecutorTest"` and the functional suite `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$MultiStepConversationRunTests"`.

## 3. Docs & spec sync

- [ ] 3.1 Update the AGENTS.md multi-step inline convention: change the "hardcoded `choices[0].message.content` extraction" phrasing for the appended reply to "full `choices[0].message` appended verbatim; abort only when no message object". Leave the response-column extraction wording unchanged.
- [ ] 3.2 At archive time, sync the delta spec into `openspec/specs/multi-step-conversation/spec.md` via the `/opsx:archive` sync step (intelligent merge, not a copy).
