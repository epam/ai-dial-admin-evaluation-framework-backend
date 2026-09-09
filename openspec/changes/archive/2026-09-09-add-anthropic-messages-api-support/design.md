## Context

The evaluation framework backend invokes deployments through DIAL Core via two protocol-agnostic seams: request bodies are fully user-authored JSON (`JsonRequestBodyDto.content`/`jsonataContent`), and response-column extraction is fully JSONata-driven. Only two narrow seams hardcode protocol-specific behavior today:

- `DialCoreUrlBuilder.buildUrl(deploymentId, resolvedUrl)` — the sole call site is `TurnLoopExecutor` (line ~352). It special-cases `/chat/completions` and `/embeddings` into `/openai/deployments/{id}{path}`; everything else falls through to a generic passthrough `/v1/deployments/{id}/route{path}`.
- `StreamingResponseAccumulator` — reassembles a complete response from a parsed SSE event list (`SseEventParser`/`SseEvent` already own all wire-format concerns: idle timeout, max-duration cap, size limit, `[DONE]` sentinel, default event type). Today it has two modes: OpenAI mode (unnamed `message` event + `choices[]` array) and Structured SSE mode (generic `{"events":[...]}"` passthrough envelope, used for everything that isn't OpenAI-shaped).

DIAL Core's own routing (verified against `epam/ai-dial-core`, `development` branch, `RouteTemplate.java` and `MessagesBaseController.java`) exposes the Anthropic Messages API at a **fixed, deployment-less** path — `^/+anthropic/v1/messages$` — architecturally different from the deployment-scoped `POST_DEPLOYMENT` route (`^/+openai/deployments/(?<id>.+?)/(completions|chat/completions|embeddings)$`). `MessagesBaseController` resolves the deployment to call from the `model` field of the JSON request body, mirroring Anthropic's native API contract; there is no deployment-scoped alias for it. This was independently corroborated against the public DIAL API docs (`dialx.ai/dial_api#tag/Anthropic/operation/createAnthropicMessage`).

DIAL Core deployment metadata does carry an `interfaces: List<InterfaceType>` field (already including an `ANTHROPIC_MESSAGES` value) on `DialCoreDeploymentDto`, surfaced through `GET /openai/models` and friends. This was considered and rejected as a discovery/gating signal — it does not reliably indicate which deployments actually work with the Messages shape, so this design does not read or depend on it anywhere.

## Goals / Non-Goals

**Goals:**
- Let a suite author invoke a Claude-style deployment through DIAL Core's Anthropic Messages API by declaring `endpointRef.relativeUrlPattern = "/anthropic/v1/messages"` on their suite (DIAL Core's real, deployment-less route — matched and passed through unchanged, not translated from a shorthand) — no new deployment-type/enum dispatch, no schema/DTO changes.
- Support both non-streaming and SSE-streaming Anthropic Messages responses, producing the same shape of assembled non-streaming JSON response object regardless of which transport DIAL Core actually used underneath — matching the existing guarantee `StreamingResponseAccumulator` already provides for OpenAI-shaped chat completions.
- Keep `DialCoreUrlBuilder` and `StreamingResponseAccumulator` as the only two touch points; no changes to `TurnLoopExecutor`, `RequestResolver`, or any request-body binding.

**Non-Goals:**
- Automatic detection of which deployments support the Anthropic Messages shape (via `interfaces`/`InterfaceType` or any other DIAL Core metadata). Recognition is 100% suite-author-declared via the URL path.
- Exposing the deployment ID as a JSONata binding to request-body resolution. The suite author must literally write `"model": "<deploymentId>"` in their request body, the same way they'd write any other vendor-specific literal today.
- The companion `/anthropic/v1/messages/count_tokens` endpoint.
- Reconstructing Anthropic `thinking`/`redacted_thinking` content blocks (only `text` and `tool_use` blocks get delta-reconstructed; any other block type is passed through unchanged from its `content_block_start` shape).
- Any new configuration property, Flyway migration, or public API/DTO change.

## Decisions

### 1. Recognition stays URL-path based inside `DialCoreUrlBuilder`

Add a fixed-path branch, checked after the existing `OPENAI_STANDARD_PATHS` check and before the generic passthrough fallback:

```java
public static final String ANTHROPIC_MESSAGES_URL = "/anthropic/v1/messages";

public String buildUrl(String deploymentId, String resolvedUrl) {
    if (OPENAI_STANDARD_PATHS.contains(resolvedUrl)) {
        return "/openai/deployments/" + deploymentId + resolvedUrl;
    }
    if (ANTHROPIC_MESSAGES_URL.equals(resolvedUrl)) {
        return resolvedUrl;
    }
    return "/v1/deployments/" + deploymentId + "/route" + resolvedUrl;
}
```

**Why URL-path dispatch, not a deployment-type enum**: this mirrors the existing `OPENAI_STANDARD_PATHS` mechanism exactly — one `Set`/fixed-string check per protocol shape, with the same method signature preserved. A deployment-type enum would require plumbing a new field through `endpointRef`/DTOs/mapper/validation and would still ultimately need to be suite-author-declared (since DIAL Core deployment metadata isn't a reliable signal — see Non-Goals), so it adds surface area without adding capability. `buildUrl`'s signature is unchanged; for this branch the `deploymentId` parameter is intentionally unused — this is a deliberate, narrow asymmetry (documented inline) rather than a signature change, since the one call site would otherwise need conditional logic duplicated outside this method. Unlike the OpenAI branch, this one is a match-and-return-unchanged passthrough, not a translation: `resolvedUrl` already equals the real DIAL Core path, because — unlike `/chat/completions`, which is a genuine suffix left over after DIAL Core splices in the deployment ID — the Anthropic route has no deployment ID in its path at all, so there's no suffix to shorthand. An earlier draft of this design invented a `/messages` shorthand by analogy to the OpenAI suffixes; that analogy doesn't hold, and the real frontend/suite authors write the literal full path, so the shorthand was dropped before implementation.

**Alternative considered**: introduce a `RelativeUrlPattern` enum or `DeploymentType` dispatch in `TurnLoopExecutor`. Rejected — it would touch a third file (`TurnLoopExecutor`) and duplicate the routing decision that `DialCoreUrlBuilder` already owns exclusively; it also doesn't remove the need for the suite author to declare `/anthropic/v1/messages` themselves.

### 2. `StreamingResponseAccumulator` gets a third assembly mode, checked after OpenAI-mode detection

Anthropic's Messages API streams **named** SSE events (`message_start`, `content_block_start`, `content_block_delta`, `content_block_stop`, `message_delta`, `message_stop`, `ping`). OpenAI-mode detection requires an *unnamed* first event (`event: message` is `SseEventParser`'s default type when no `event:` line is present), so the two detection paths cannot collide — verified against the existing test `accumulate_namedEventWithChoices_usesStructuredMode`, which already proves a named event is never treated as OpenAI-shaped regardless of payload.

```java
private boolean isAnthropicMode(List<SseEvent> events) {
    if (events.isEmpty()) {
        return false;
    }
    SseEvent first = events.get(0);
    if (!"message_start".equals(first.event())) {
        return false;
    }
    if (!(first.data() instanceof JsonNode node)) {
        return false;
    }
    return node.has("message");
}
```

`assemble()` becomes a three-way dispatch: `isOpenAiMode` → `assembleOpenAiResponse`; else `isAnthropicMode` → `assembleAnthropicResponse`; else `assembleStructuredSseResponse`. A stream with a named `message_start` event but no `message` field falls through to Structured SSE mode (graceful degradation to the existing generic envelope, not an error).

**Why detection order matters, why the shape check (`node.has("message")`) beyond the event name**: `message_start` alone is a weaker signal than the existing OpenAI check (which validates the `choices[]` shape, not just the event name), so requiring the nested `message` object keeps the same precision bar OpenAI mode already sets — a named event whose payload doesn't match the expected shape degrades gracefully rather than misassembling.

### 3. Per-block accumulation extracted into `AnthropicContentAccumulator`, mirroring `CustomContentAccumulator`

A new package-private, non-Spring helper class in `evaluation-runner-core/.../job/`, instantiated fresh per `assembleAnthropicResponse` call (same lifecycle as `CustomContentAccumulator`). It owns:
- Seeding a content block from `content_block_start.content_block`, keyed by array index.
- Appending `delta.text` (`text_delta`) or `delta.partial_json` (`input_json_delta`) from `content_block_delta` into the per-index accumulator.
- Producing the final `content` array in index order: `text` blocks become `{"type":"text","text": <accumulated>}`; `tool_use` blocks become `{"type":"tool_use","id":...,"name":...,"input": <parsed accumulated JSON>}`, falling back to the raw accumulated string if the JSON fails to parse (graceful degradation — the accumulated text is regenerable diagnostic data, not a data-integrity-critical write, matching the project's existing exception-handling convention split between fail-fast for persisted/serialized data and graceful degradation for regenerable in-memory data); any other block type is passed through unchanged from its `content_block_start` shape (no delta reconstruction, per Non-Goals).

**Why a separate class instead of inlining into `StreamingResponseAccumulator`**: this is the same call already made for `CustomContentAccumulator` — keeps `StreamingResponseAccumulator` from growing multiple unrelated per-mode accumulation algorithms inline, and makes the block-accumulation logic independently unit-testable.

`assembleAnthropicResponse` itself:
- Deep-copies the `message` object off `message_start` as the base response object (carries `id`, `type`, `role`, `model`, initial `usage`, initially-empty `content`).
- Feeds `content_block_start`/`content_block_delta` events to `AnthropicContentAccumulator`.
- On `message_delta`, merges `delta.stop_reason`/`delta.stop_sequence` into the base message and merges `usage` into the base message's `usage` object.
- Treats `content_block_stop`/`message_stop`/`ping` as no-ops.
- On success, sets the base message's `content` to the accumulator's final array and serializes the whole message object as `responseBody`.
- On truncation (`parseStatus != SUCCESS`), mirrors OpenAI mode's truncated-string convention: `responseBody` becomes a plain JSON string of the concatenation of all `text`-block accumulated text (in index order), not the full object, and `executionStatus` is set to `ERROR` — identical contract to the existing `accumulate_sizeLimitExceeded_truncatesAndSetsError` behavior for OpenAI mode.

### 4. No new bindings; one soft `model`/`deploymentRef` consistency check

The suite author must include a literal `"model"` field in their JSON request body (e.g. `"model": "claude-3-5-sonnet-v2"`) — confirmed that `TurnLoopExecutor` threads `deploymentId` only into `urlBuilder.buildUrl(...)`, never into `requestResolver.resolveForRun(...)`, so no binding exists today for request bodies to reference the deployment ID, and adding one remains out of scope (one-line literal the author already writes for any vendor's API).

After reviewing this design, one soft validation check is added on top: `SuiteValidationService.validateRequest` gains a new block, gated on `endpoint.getRelativeUrlPattern()` equal to `DialCoreUrlBuilder.ANTHROPIC_MESSAGES_URL` (`public static final` so this service reuses the single source of truth for the path literal rather than duplicating it), that compares a literal `model` value in the request body against the suite's `deploymentRef.id`:
- Only checked when the body is a literal `JsonRequestBodyDto.content` map (`instanceof JsonRequestBodyDto jsonBody` with non-null `getContent()`) — `jsonataContent` bodies are skipped entirely, since raw JSONata source can't be statically evaluated for a literal value at suite-save time.
- Skipped when the `model` key is absent, its value isn't a `String`, or it's a `${{...}}` placeholder (reusing `TemplateVariableExtractor.isPlaceholder`, already used elsewhere in this class to skip FILE-ref checks for dynamic values).
- `deploymentRef` is threaded into `validateRequest` as a new parameter, sourced once per suite in `validateDeploymentSuite` (`dto.getDeploymentRef()`) and passed identically to the request-#0 call and every `additionalRequests[i]` call — `RequestDefinitionDto` deliberately excludes `deploymentRef` (suite↔deployment is 1-to-1 across the whole chain), so one value applies uniformly.
- On a literal mismatch, a non-blocking warning is added at the existing per-request warning path (`$.requestTemplate.body` / `$.additionalRequests[i].requestTemplate.body`), reusing `ValidationWarningCode.TYPE` (no new enum value) — it never produces an HTTP 400, only affects `isValid`/`validationWarnings`, matching every other check already in this method (content-type mismatch, header blacklist).

**Why soft, not hard**: mirrors every other body-shape check in `validateRequest`; a suite author could legitimately want `deploymentRef` to differ from the literal `model` in some deliberate testing scenario (e.g. probing fallback/routing behavior) — a warning nudges toward the common-typo case without blocking a valid one.

**Why gated on `/anthropic/v1/messages` specifically, not applied to every JSON body**: OpenAI-shaped request bodies also commonly carry a literal `model` field whose value is unrelated to `deploymentRef.id` (DIAL Core resolves those deployments from the URL path, not the body) — an ungated check would produce false-positive warnings on existing, correct OpenAI-shaped suites.

## Risks / Trade-offs

- **[Suite author forgets or mistypes the `model` field]** → DIAL Core will reject the request itself (unknown/missing model), surfacing as a normal run-level HTTP error through the existing error-handling path; no new error handling needed here. Mitigated by pattern-doc/spec documentation of the requirement, and now also nudged at suite-save time by the soft `model`/`deploymentRef` check in Decision 4.
- **[A future Anthropic API change alters the `message_start`/`content_block_*` event shape]** → `isAnthropicMode`'s shape check (`node.has("message")`) means an incompatible future shape degrades to Structured SSE mode rather than crashing or misassembling; the raw envelope is still usable via JSONata even if the dedicated mode doesn't recognize it.
- **[`tool_use` `input_json_delta` chunks don't reassemble into valid JSON, e.g. a truncated stream mid-block]** → Falls back to the raw accumulated string for `input` rather than throwing, consistent with graceful-degradation-for-regenerable-data convention; the run itself isn't failed by this alone (though truncation already sets `ERROR` via the existing `parseStatus` mechanism).
- **[Scope creep temptation to also handle `count_tokens` or `thinking` blocks]** → Explicitly out of scope per proposal; can be added later following the same two-seam pattern without redesign, since both seams are narrowly extended (fixed-path branch, additional mode) rather than replaced.
- **[`jsonataContent`-authored `/anthropic/v1/messages` bodies aren't covered by the new soft check]** → Documented, deliberate limitation — the check is best-effort, not a completeness guarantee, consistent with the project's "fully opaque JSON body" philosophy for anything beyond a literal template.
- **[The soft warning could read as a false positive for a deliberate `deploymentRef`/`model` divergence]** → Mitigated by being non-blocking: the suite remains fully functional and runnable; `isValid`/`validationWarnings` only reflect a nudge, not an error.
