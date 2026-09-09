## Why

Today the execution engine can invoke deployments shaped like OpenAI chat completions/embeddings (via `DialCoreUrlBuilder`'s `/openai/deployments/{id}{path}` routing and `StreamingResponseAccumulator`'s OpenAI-mode SSE assembly), or any other endpoint via a generic passthrough. Suite authors have no first-class way to target a Claude-style deployment through DIAL Core's Anthropic Messages API — they'd fall into the generic passthrough route and get a raw `{"events":[...]}"` envelope back instead of a properly assembled non-streaming response, even though the request/response pipeline is otherwise fully protocol-agnostic (opaque JSON request bodies, JSONata-driven response extraction).

## What Changes

- `DialCoreUrlBuilder` recognizes DIAL Core's real, deployment-less Anthropic Messages route as a suite-author-declared path, `endpointRef.relativeUrlPattern = "/anthropic/v1/messages"`, and passes it through unchanged (there is no per-deployment suffix to abbreviate the way `/chat/completions` is) — unlike the existing OpenAI-shaped routes, the deployment ID is intentionally **not** spliced into the URL; DIAL Core instead resolves the deployment from the `model` field in the JSON request body (the suite author is responsible for setting it).
- `StreamingResponseAccumulator` gains a third mode, "Anthropic mode," alongside the existing OpenAI mode and Structured SSE mode. It is auto-detected from the SSE event shape (named `message_start` event carrying a `message` object) and reassembles a complete Anthropic Messages response — merging `content_block_start`/`content_block_delta`/`message_delta` events into a final `content` array (text and tool_use blocks) and top-level `usage`/`stop_reason` — matching what OpenAI mode already does for chat completions. Mode detection is unambiguous: Anthropic mode requires a *named* first event, OpenAI mode requires an *unnamed* one.
- A new package-private helper, `AnthropicContentAccumulator` (mirroring the existing `CustomContentAccumulator`), accumulates per-index content blocks during Anthropic-mode assembly.
- Recognition stays purely URL-path based, driven by the suite author's own `endpointRef` configuration — there is no automatic deployment-capability detection. DIAL Core deployment metadata (`interfaces`/`InterfaceType`, including the existing `ANTHROPIC_MESSAGES` value) is **not** used for gating or discovery; it does not reliably indicate which deployments actually support the Messages shape.
- New pattern doc `docs/patterns/anthropic-messages-api.md` plus a corresponding `AGENTS.md` "Unique Patterns" row, documenting the two-seam design, the `model`-in-body requirement, and explicit non-goals.
- `SuiteValidationService` gains a new soft validation check for `/anthropic/v1/messages`-shaped requests: when the JSON body's literal `model` field doesn't match the suite's configured `deploymentRef.id`, a non-blocking validation warning is added — mirrors the existing content-type/header-blacklist checks in `validateRequest`. Skipped when the body uses `jsonataContent`, when `model` is absent, or when `model` is a `${{...}}` placeholder, since none of those can be statically evaluated at suite-save time.

**Out of scope**: the companion `/anthropic/v1/messages/count_tokens` endpoint, and Anthropic `thinking`/`redacted_thinking` content blocks (both real DIAL Core/Anthropic features, but not required for basic invocation — can follow the same pattern later if needed).

## Capabilities

### New Capabilities

(none — this extends existing capabilities rather than introducing a new one)

### Modified Capabilities

- `eval-execution-engine`: adds Anthropic Messages API support to two existing requirements — "URL and method resolution from endpoint contract" gains a fixed-path mapping for `/anthropic/v1/messages`, and "Streaming response accumulation" gains a third assembly mode (Anthropic mode) alongside OpenAI mode and Structured SSE mode. "Response size limiting" gains truncation behavior for the new mode.
- `test-suites`: "Per-request soft validation with indexed warning paths" gains a soft check that a `/anthropic/v1/messages` request's literal body `model` field matches the suite's `deploymentRef.id`.

## Impact

- Code: `evaluation-runner-core/src/main/java/com/epam/aidial/evaluation/runner/service/DialCoreUrlBuilder.java` (routing; `ANTHROPIC_MESSAGES_URL` is `public static final` so `SuiteValidationService` can reuse it), `evaluation-runner-core/src/main/java/com/epam/aidial/evaluation/runner/job/StreamingResponseAccumulator.java` (assembly), new `evaluation-runner-core/src/main/java/com/epam/aidial/evaluation/runner/job/AnthropicContentAccumulator.java`, `src/main/java/com/epam/aidial/evaluation/service/domain/SuiteValidationService.java` (new soft validation block).
- Tests: `src/test/java/com/epam/aidial/evaluation/service/domain/DialCoreUrlBuilderTest.java`, `evaluation-runner-core/src/test/java/com/epam/aidial/evaluation/runner/job/StreamingResponseAccumulatorTest.java`, `src/test/java/com/epam/aidial/evaluation/service/domain/SuiteValidationServiceTest.java`.
- Docs: `openspec/specs/eval-execution-engine/spec.md` (delta), `openspec/specs/test-suites/spec.md` (delta), new `docs/patterns/anthropic-messages-api.md`, `docs/patterns/README.md` (new pattern row), `AGENTS.md` Unique Patterns table row.
- No API/DTO/DB changes — this is entirely inside the execution engine's existing seams plus one soft-validation addition; suite authors opt in purely by setting `endpointRef.relativeUrlPattern` and including a `model` field in their request body.
- No new dependencies, no config properties, no schema migrations.
