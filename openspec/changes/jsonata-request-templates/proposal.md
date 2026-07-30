## Why

The multi-turn executor (`service.domain.job.MultiTurnExecutor`) and the JSON request-body path it shares
with single-turn execution hardcode assumptions that only hold for OpenAI-shaped chat-completions:

- `MultiTurnExecutor.runTurn` requires the resolved JSON body to contain a top-level `messages` **array**
  (`RequestTemplateDto` → `JsonRequestBodyDto.content`) — any other request shape (e.g. the Responses API's
  `input` array, a single-string `prompt`, a tool-call envelope) fails the turn with an ERROR row.
- The assistant reply is read back from a hardcoded `choices[0].message` path
  (`extractAssistantMessage`) — no other response envelope is supported.
- `content.put("stream", false)` force-disables streaming on every turn, even though `test-cases`/
  `response-columns` already support SSE envelopes (`{"events": [...]}`) for single-turn suites via
  `SseEventParser`/`StreamingResponseAccumulator`.
- Turn count is purely `multiTurnData.length` with no way to express "resend history only if the template
  actually references per-turn data" — a multi-turn dataset bound to a suite with no per-turn binding still
  round-trips once per turn instead of collapsing to a single request.

This is a structural ceiling, not a bug: as long as "assemble the request" and "read the reply" are two
fixed Java code paths keyed to one wire format, the executor cannot support non-chat-completions
deployments (Responses API, custom agent endpoints, MCP-adjacent HTTP APIs) without another hardcoded
branch per API shape.

`com.dashjoin:jsonata:0.9.10` is already a runtime dependency (used today only for `response-columns`
extraction via `JsonataEvaluationService`/`DashjoinJsonataEvaluationService`) and its `Frame` API — carrying
named variable bindings into an evaluation — is unused. JSON is a syntactic subset of JSONata (a JSON
document evaluates to itself), so a request-template body can be **unconditionally** evaluated as a JSONata
expression: legacy Map-shaped bodies keep working unchanged (structural `${{}}` resolution → serialize →
JSONata-echo), while new String-content bodies gain full JSONata authoring power (conditionals,
`$append`, arbitrary object construction) to shape any request/response contract, with the previous turn's
extracted response columns fed in as named frame variables (e.g. `$history`) instead of a hardcoded
`messages` array.

## What Changes

- **Unconditional JSONata evaluation of JSON request bodies.** Every `application/json` request body —
  single-turn or multi-turn — is JSONata-evaluated before being sent. Plain JSON bodies are unaffected
  (JSON ⊂ JSONata; a literal object/array echoes itself), so this is additive for existing suites.
- **`JsonRequestBodyDto.content` becomes `Object`**, accepting either the legacy `Map<String, Object>`
  (structural `${{}}` resolution, then JSON-serialized and evaluated — one eval path, not a special case)
  or a `String` JSONata source (preprocessed for `${{}}` placeholders, then evaluated directly).
- **Placeholders resolve before JSONata evaluation, not after.** `${{var}}`/`${{var|type:default}}` keep
  their existing resolution semantics (typed value, default, binding priority); the resolved values are
  spliced into the body — as a JSON-serialized literal for a quoted-full-value placeholder, JSON-string-escaped
  for an embedded-in-literal placeholder, or JSON-serialized for a bare placeholder outside any string literal
  — before the combined text is parsed as JSONata source.
- **Unified turn loop for every DEPLOYMENT HTTP suite** (replacing the multi-turn-only loop): turn count
  `N` is `multiTurnData.length` when the resolved template's bindings reference at least one `perTurn=true`
  dataset field, else `N = 1`. Single-turn suites are the `N = 1` degenerate case (no behavior change,
  `turnIndex`/`totalTurns` stay `null`); a multi-turn dataset bound to a suite with no per-turn binding
  collapses to one request from the shared data instead of resending history once per turn.
- **Request-template frame**: each turn's JSONata evaluation gets a `Frame` with the **previous turn's
  reconciled extracted response columns bound directly as `$<columnName>`** (e.g. `$history` if a response
  column is named `history`) — turn 0 evaluates with those names unbound (`undefined`). This replaces the
  hardcoded history-array-of-messages accumulation with an author-controlled JSONata expression (typically
  `$append($history, [...])`).
- **Response-column extraction frame**: extraction gains `$request` (the parsed sent request body) and
  `$response` (the parsed response body) as frame variables, for both single-turn and MCP; the root
  evaluation document stays the raw response body so all existing expressions are untouched.
- **Streaming on every turn.** The hardcoded `stream: false` injection is removed; `StreamingResponseAccumulator`'s
  OpenAI-mode accumulation is extended to also assemble DIAL `choices[i].delta.custom_content` (scalar fields
  overwrite, `attachments`/`stages` arrays merge by index) onto the final `choices[0].message.custom_content`,
  so streamed custom-content responses reconcile the same way non-streaming ones already do.
- **New write-time 400s**: a String-content request body must be valid JSONata source; a response column
  name must not collide with a JSONata built-in function name or with the reserved `request`/`response`
  frame names.
- **Runtime contract unchanged in shape, widened in scope**: the evaluated body must resolve to a JSON
  object or the row is ERROR; a JSONata evaluation failure is an ERROR row; the fail-fast turn loop is
  unchanged.
- **New `JsonataProperties`** (`jsonata.evaluation-timeout-ms`, `jsonata.max-recursion-depth`) wired into
  `Frame.setRuntimeBounds` so a runaway or unbounded-recursion JSONata expression aborts instead of hanging
  a worker thread.

Untouched: the MCP tool-invocation path, `url`/`queryParams`/`headers` resolution (still `${{}}`-only, no
JSONata), `ConditionExpressionEvaluator`, and the MCP + multi-turn rejection guard at run creation.

**Implemented as part of this change:** WP1 (JSONata seam + properties), WP2 (body model + resolver
refactor), WP3 (reserved-name validation), WP4 (extraction frame), WP5 (streaming turn invocation), WP6
(unified turn loop + `EvaluationContext` schema), WP7 (DIAL custom-content streaming accumulation), WP8
(functional tests + doc/spec sync). WP0 (this bundle + the `JsonataFrameSpikeTest` pinning the `Frame` API
behaviors the rest of the plan depends on) is complete.

**Planned/Vision:** none — this bundle covers the full JSONata request-template rework; a future
Responses-API-specific example suite is out of scope here (the point of this change is that no
API-specific Java code is needed for it).

## Non-Goals

- No change to the MCP tool-invocation request/response path (`ArgumentTemplateDto`, `McpRequestResolver`).
- No JSONata evaluation of `urlTemplate`/`queryParams`/`headers` — these remain `${{}}`-only.
- No change to `ConditionExpressionEvaluator` or the conditional-metric-execution frame shape.
- No change to the MCP + multi-turn rejection guard (still hard-rejected at run creation).
- No re-evaluation of historical runs — the new behavior applies to newly created runs only.

## Current State

- `JsonRequestBodyDto.content` is `Map<String, Object>`; resolution is purely structural `${{}}` substitution
  (`ResolvedRequestService`), with type-preserving full-value replacement and string-concatenation embedded
  replacement — no JSONata involvement.
- `MultiTurnExecutor` hardcodes: turn count = `multiTurnData.length`; body must have a top-level `messages`
  array; assistant reply = `choices[0].message`; `stream: false` forced; history = raw message objects
  concatenated.
- `ResponseColumnExtractor`/`DashjoinJsonataEvaluationService` evaluate response-column JSONata expressions
  against the raw response body only — no frame, no `$request`/`$response`.
- `com.dashjoin.jsonata.Jsonata.Frame` (bind/lookup/`setRuntimeBounds`) has zero call sites in the codebase.

## API / Data / Security Impact

- **API contract (additive + one narrowing):** `RequestBodyDto` (`application/json` variant)'s `content`
  field changes from `Map<String, Object>` to `Object` (Map or String) in the OpenAPI schema — existing
  Map-shaped payloads remain valid; new String payloads are additive. `TestSuiteResponseDto`/
  `TestCaseRunResult` response shapes are unchanged. Two new HTTP 400 cases: invalid JSONata String body
  source; reserved response-column name.
- **No new endpoints.** No DB schema changes (no new migration) — `requestTemplate` and `responseColumns`
  remain JSONB on `test_suites`; `EvaluationContext` gains an in-memory (not persisted) `snapshotTestCaseSchema`
  field sourced from the existing `SuiteSnapshotDto`.
- **Security:** none — JSONata evaluation runs in-process against already-trusted suite/test-case data; the
  new `Frame.setRuntimeBounds` timeout/recursion cap is a hardening addition (protects worker threads from a
  malformed or adversarial JSONata expression), not a new attack surface.

## Risks

- **F1 — numeric fidelity.** dashjoin JSONata represents numbers as Java `double` internally; an explicit
  `1.0` echoes back as integral `1`, and a `long` above `2^53` loses precision on round-trip through
  evaluation. Pinned in `JsonataFrameSpikeTest`; documented as a known caveat in the `request-template` spec
  delta rather than worked around (no lossless numeric path exists through this library version).
- **F2 — null vs. undefined in the frame.** A failed prior-turn extraction binds its column as JSON `null`
  on the frame (not "unbound"), which is a different JSONata semantic than turn 0's genuinely unbound
  variable (`$append(null_bound, x)` prepends `null`; `$append(unbound, x)` does not). Authors relying on
  `$append($history, ...)` need to be aware extraction failures show up as a `null` history entry, not a
  skipped one. Documented, not silently special-cased.
- **Runaway JSONata expressions** could hang a worker thread indefinitely without `setRuntimeBounds` (now
  addressed by WP1's `JsonataProperties`).
- **Breaking the JSON-echo assumption for exotic payloads.** Any legacy request body whose JSON literal
  happens to look like a JSONata operator sequence (extremely unlikely for a chat-completions/tool-call
  body — these are keys/strings/numbers, not bare operator tokens) could evaluate differently than intended;
  mitigated by the unconditional-echo property of JSON ⊂ JSONata and pinned by
  `JsonataFrameSpikeTest`'s JSON-echo-fidelity test.

## Rollout Plan

1. WP1 lands the evaluation seam and properties with no caller changes (dead code path) — safe to merge
   independently.
2. WP2–WP5 refactor the body/extraction/turn-invocation internals behind the existing single-turn and
   multi-turn call sites; single-turn behavior for Map-content bodies must remain byte-identical
   (verified by WP8 functional tests) since JSON ⊂ JSONata is what guarantees no regression.
3. WP6 swaps `MultiTurnExecutor`'s fixed loop for the unified turn loop and removes the now-dead
   `messages`/`choices[0].message` special-casing; this is the only wave with an intended behavior change
   for existing multi-turn suites (they keep working because their templates already resolve to JSON
   objects — the loop-length rule change only matters for suites that add per-turn bindings retroactively).
4. WP7 extends streaming accumulation for DIAL custom content; WP8 runs the full functional suite,
   syncs delta specs into main specs, and updates `AGENTS.md`/OpenAPI examples.
5. No feature flag — this is an in-process executor rewrite with no external contract break for existing
   suites; rollout is "merge each wave, functional tests green before the next wave starts."

## Test Plan

- `JsonataFrameSpikeTest` (WP0, already passing) pins the `Frame` API contract every later wave depends on.
- WP1–WP7 each add/extend unit tests for their component (`TemplateContentResolver`, `JsonataSourcePreprocessor`,
  `RequestBodyEvaluator`, `JsonataReservedNames`/validator 400s, `ResponseColumnExtractor` frame variant,
  `DeploymentTurnInvoker` streaming, `PerTurnBindingDetector`, `TurnLoopExecutor`, `CustomContentAccumulator`).
- WP8 adds/updates functional tests covering: Map-content single-turn suite unchanged behavior; String-content
  JSONata body suite; multi-turn suite with per-turn binding (N turns) vs. without (N=1 collapse); streaming
  DIAL custom-content accumulation across turns; reserved-name 400s at suite save time.
