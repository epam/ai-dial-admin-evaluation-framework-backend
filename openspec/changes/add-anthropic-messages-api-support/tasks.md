## 1. DialCoreUrlBuilder routing

- [x] 1.1 In `evaluation-runner-core/.../service/DialCoreUrlBuilder.java`, add `ANTHROPIC_MESSAGES_URL` (`/anthropic/v1/messages`, DIAL Core's real, deployment-less route) constant, and a branch in `buildUrl` that returns the URL unchanged (deployment ID not spliced in) when `resolvedUrl` equals it, checked after `OPENAI_STANDARD_PATHS` and before the generic passthrough fallback. (Note: an earlier draft of this task used a `/messages` shorthand translated to the real path via a second constant; dropped before implementation because the real frontend/suite authors write the full literal path directly, and there is no per-deployment suffix — unlike `/chat/completions` — to abbreviate.)
- [x] 1.2 In `src/test/java/com/epam/aidial/evaluation/service/domain/DialCoreUrlBuilderTest.java`, add cases: `/anthropic/v1/messages` routes to itself unchanged; a near-miss path (e.g. `/anthropic/v1/messages/count_tokens`) still falls through to the generic `/v1/deployments/{id}/route{path}` passthrough.
- [x] 1.3 Run `./gradlew test --tests "com.epam.aidial.evaluation.service.domain.DialCoreUrlBuilderTest"` and confirm all cases pass.

## 2. StreamingResponseAccumulator Anthropic mode

- [x] 2.1 In `evaluation-runner-core/.../job/StreamingResponseAccumulator.java`, add `isAnthropicMode(events)` (first event named `message_start` with data containing a `message` field), checked in `assemble()` after `isOpenAiMode`.
- [x] 2.2 Add new package-private helper `evaluation-runner-core/.../job/AnthropicContentAccumulator.java` (mirroring `CustomContentAccumulator`): seeds content blocks by index from `content_block_start`, appends `delta.text`/`delta.partial_json` from `content_block_delta`, and produces the final `content` array (text/tool_use reconstructed, other types passed through unchanged, JSON-parse-failure fallback to raw string for `tool_use.input`).
- [x] 2.3 In `StreamingResponseAccumulator`, add `assembleAnthropicResponse(events, parseStatus)`: deep-copies the base `message` object from `message_start`, feeds block-start/delta events to `AnthropicContentAccumulator`, merges `message_delta`'s `delta`/`usage` into the base message, treats `content_block_stop`/`message_stop`/`ping` as no-ops, and on truncation stores the concatenation of accumulated `text` blocks (ascending index order) as a plain JSON string with `executionStatus = ERROR`.
- [x] 2.4 Wire the three-way dispatch in `assemble()`: `isOpenAiMode` → `assembleOpenAiResponse`; else `isAnthropicMode` → `assembleAnthropicResponse`; else `assembleStructuredSseResponse`.
- [x] 2.5 In `evaluation-runner-core/src/test/java/com/epam/aidial/evaluation/runner/job/StreamingResponseAccumulatorTest.java`, add an `@Nested AnthropicMode` class covering: single text block across multiple deltas; `message_delta` stop_reason/usage merge; `tool_use` block with valid JSON reassembly; multiple blocks assembled in index order; truncation mid-stream (plain JSON string, `ERROR` status); a named-`message_start`-without-`message` stream falling back to Structured SSE mode.
- [x] 2.6 Run `./gradlew :evaluation-runner-core:test --tests "com.epam.aidial.evaluation.runner.job.StreamingResponseAccumulatorTest"` and confirm all cases pass.

## 3. Documentation

- [x] 3.1 Add `docs/patterns/anthropic-messages-api.md` documenting the two-seam design, the `model`-in-body requirement, and the explicit non-goals (no gating via `interfaces`/`InterfaceType`, no `count_tokens`, no `thinking` block reconstruction).
- [x] 3.2 Update `AGENTS.md` per AGENTS.md Maintenance guidelines: add a row to the "Unique Patterns" table linking to `docs/patterns/anthropic-messages-api.md` (done: table row added, no other section needs changes for this feature-following-existing-pattern work).
- [x] 3.3 Confirm `openspec/changes/add-anthropic-messages-api-support/specs/eval-execution-engine/spec.md` accurately reflects the final implementation (done: delta already written during planning; revise only if implementation diverges from the design).

## 4. Verification

- [x] 4.1 Run `./gradlew spotlessApply`.
- [x] 4.2 Run `./gradlew build` (full build: compiles both modules, `checkstyleMain`/`checkstyleTest`, `LayeredArchitectureTest`, `spotlessCheck`) and confirm no regressions.
- [ ] 4.3 Manually verify via the "Try it out" flow (`TryItOutService`) against a real DIAL Core instance with an Anthropic-backed deployment: `endpointRef.relativeUrlPattern = "/anthropic/v1/messages"` plus a request body containing `"model": "<deploymentId>"`, confirming the end-to-end path (URL construction → DIAL Core → streaming or non-streaming response → JSONata response-column extraction).

## 5. Suite validation: model/deploymentRef consistency

- [x] 5.1 In `evaluation-runner-core/.../service/DialCoreUrlBuilder.java`, `ANTHROPIC_MESSAGES_URL` (added in task 1.1) is already `public static final`, so it can be reused as the single source of truth for the `/anthropic/v1/messages` literal.
- [x] 5.2 In `src/main/java/com/epam/aidial/evaluation/service/domain/SuiteValidationService.java`, add a `deploymentRef` parameter to the private `validateRequest` method and pass `dto.getDeploymentRef()` from both call sites in `validateDeploymentSuite` (request #0 and each `additionalRequests[i]`). Add a new soft-validation block after the existing content-type-mismatch block: gated on `endpoint.getRelativeUrlPattern().equals(DialCoreUrlBuilder.ANTHROPIC_MESSAGES_URL)`, extract a literal `model` value from `template.getBody()` when it is a `JsonRequestBodyDto` with non-null `getContent()`, skip when `model` is absent/non-String/a `${{...}}` placeholder (`templateVariableExtractor.isPlaceholder`), and on a literal mismatch against `deploymentRef.getId()` add a warning via the existing `warning`/`warningPath` helpers at `$.requestTemplate.body` or `$.additionalRequests[i].requestTemplate.body`, using `ValidationWarningCode.TYPE`.
- [x] 5.3 In `src/test/java/com/epam/aidial/evaluation/service/domain/SuiteValidationServiceTest.java`, add cases: literal `model` matches `deploymentRef.id` (no warning); mismatch on request #0 (warning at `$.requestTemplate.body`); mismatch on `additionalRequests[1]` (warning at the indexed path); `model` is a `${{...}}` placeholder (skipped); body uses `jsonataContent` (skipped); non-`/anthropic/v1/messages` endpoint with a literal `model` unrelated to `deploymentRef.id` (not checked, no warning).
- [x] 5.4 Run `./gradlew test --tests "com.epam.aidial.evaluation.service.domain.SuiteValidationServiceTest"` and confirm all cases pass.
- [x] 5.5 Add or extend a functional test (e.g. `SuiteValidationBindingFunctionalTests` or a small dedicated class) asserting the warning surfaces through the suite create/update API for an `/anthropic/v1/messages` suite with a mismatched literal `model`. Run the corresponding functional test suite and confirm it passes.

