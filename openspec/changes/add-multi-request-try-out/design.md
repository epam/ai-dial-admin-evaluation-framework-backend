# Design — try-out support for multi-request test suites

## Context

See `proposal.md` — Why. Current state that shapes the approach:

- `TryItOutService.tryWithTestCase` handles multi-turn via `runTurnSequence` (added by archived change `2026-08-12-try-it-out-multi-turn`): planning inside `ResolvedRequestService.planTurns` (transactional, DB), loop + HTTP invocation re-implemented in `TryItOutService` (outside tx, after the DB connection is released). Neither path reads `additionalRequests`.
- `runTurnSequence` today **replaces** `frameBindings` each turn (`frameBindings = extractFrameBindings(...)`), while the run engine **accumulates** (`TurnLoopExecutor.mergeAccumulated`, later key wins). It also hoists a single suite-level `endpointRef.getMethod()` for every turn — correct for one request, wrong for a chain.
- The run engine's chain semantics live in `evaluation-runner-core`: `RequestChainExecutor` (outer request loop, one accumulated frame threaded via `RequestExecutionResult.accumulatedFrame()`, abort breaks the chain) and `TurnLoopExecutor` (per-request turn plan via `PerTurnBindingDetector`, `stampIdentity` stamping `turnIndex`/`totalTurns` only when N>1 turns and `requestIndex`/`totalRequests` only when >1 requests).
- `runner.job.RequestExecutionSpec` is a plain record (`requestIndex, totalRequests, name, endpointRef, requestTemplate, inputBindings, responseColumns`) — a DB-free carrier already public to the main app.
- Write-time invariants we lean on: `TestSuiteRequestValidator` enforces suite-wide response-column union uniqueness (cross-request frame collision impossible) and rejects `MCP_TOOL` + non-empty `additionalRequests` (no new MCP guard needed here).

## Goals / Non-Goals

**Goals:**
- Try-out executes chains with semantics that **predict a real run**: same frame accumulation, same per-request turn detection, same fail-fast, same identity-stamping rules.
- Zero response-shape change for existing single-request suites beyond the additive `extractedColumns`/`extractionWarnings`.
- No changes to `evaluation-runner-core`.

**Non-Goals:**
- Reusing `RequestChainExecutor`/`TurnLoopExecutor` directly (see Decision 2).
- Fixing the pre-existing transport-exception gap (a 502/504 or post-resolution `ValidationException`/`TryItOutValidationException` during any invocation after the first propagates uncaught instead of becoming a `history` entry). Already documented in the multi-turn spec notes; kept consistent for chain entries > 0. A future change could catch these into history entries for both dimensions at once.
- Changing `GET …/resolved-request?requestIndex=N` — its empty-frame, no-invocation preview semantics stay as designed (D19 of `add-multi-request-suite`).
- MCP chain try-out (MCP chaining itself is deferred at write time).
- Persisting anything — try-out remains stateless.

## Decisions

### D1 — API shape: flat `history` + nullable identity stamps (user-approved)
Keep the single flat `history` list in execution order (request-major, turn-minor). Each entry and the top level gain `requestIndex`/`totalRequests` (stamped only when `totalRequests > 1`) and `turnIndex`/`totalTurns` (stamped only when that request planned > 1 turns), all `@JsonInclude(NON_NULL)`. The four **numeric** stamps mirror `TurnLoopExecutor.stampIdentity`'s guards exactly (`spec.totalRequests() > 1` for the request pair; the N>1 per-turn-binding plan for the turn pair). `requestName` is a **try-out-only convenience with no run analogue** — `TestCaseRunResult` persists no request name and `stampIdentity` never touches one; it is sourced from `TestSuite.requestName` (request #0) / `RequestDefinitionDto.getName()` (additional requests), serialized only when `totalRequests > 1` AND the request is labelled. Net compatibility: a single-request **single-turn** response is byte-identical to today's apart from the additive extraction fields; an existing single-request **multi-turn** response newly gains `turnIndex`/`totalTurns` on its entries and top level, plus the extraction fields — additive, called out in the delta spec.

*Alternative rejected:* nested `requests[].turns[]` structure — richer but a breaking shape change for the existing multi-turn `history` consumers, and diverges from the flat `(request_index, turn_index)` row model everywhere else in the system.

### D2 — Do NOT reuse `RequestChainExecutor`/`TurnLoopExecutor`; DO reuse the DB-free seams
The runner-core executors are run-shaped: they take an `EvaluationContext`, emit persisted `TestCaseRunResult` rows, apply retries/cancellation, and discard exactly what try-out must surface (the full `ResolvedRequestDto`, the SSE event list, per-invocation `durationMs`/`traceId`). Adapting them would mean invasive runner-core changes for a preview feature. Instead, mirror their semantics in `TryItOutService` (as the multi-turn precedent already does) and reuse the same DB-free building blocks so behavior can't drift on the pieces that matter: `RequestResolver.resolveForRun(template, bindings, data, frame)`, `ResponseColumnExtractor.extract(...)`, `PerTurnBindingDetector`, `RequestBodySerializerRegistry`, `DialCoreUrlBuilder`, `DeploymentInvocationSupport`, and the `RequestExecutionSpec` record as the per-request carrier. No runner-core changes expected.

### D3 — Planning layer: chain-aware plan in `ResolvedRequestService`
New transactional plan method, e.g. `ChainPlan planChain(suiteId, testCaseId)` returning an ordered list of per-request plans — each pairing a `RequestExecutionSpec` (components `requestIndex, totalRequests, name, endpointRef, requestTemplate, inputBindings, responseColumns`) with that request's `turnDataList` (decided per request via `perTurnBindingDetector.referencesPerTurnField(spec.inputBindings(), schema)`, reusing the existing `mergeSharedAndTurn` logic). The dataset schema is loaded **lazily, at most once for the whole chain** — only when the test case has readable `multiTurnData` turns, matching `planTurns`'s existing behavior; a chain over a single-turn case adds no schema read. Spec 0 comes from the suite's own fields, with the suite-level `requestName` threaded into the spec's `name` component; specs 1..N from `jsonbMapper.mapAdditionalRequests(suite.getAdditionalRequests())` (null bindings/columns normalized to `List.of()`, as `RequestChainExecutor.buildSpecs` does). Existing `planTurns` either delegates to the new method (taking request #0's plan) or is folded in — implementor's choice, keeping the single-invocation fast path's call pattern intact.

For `tryWithVariables`, a DB-only chain loader (suite fields + additional requests, **no** test case, no schema/turn planning — every request is one turn) so the variables path never touches `TestCaseRepository`.

Transaction boundary unchanged: all DB reads inside `ResolvedRequestService`'s `@Transactional(value = "metaTransactionManager", readOnly = true)` methods; all HTTP invocation in `TryItOutService` after the tx completes.

### D4 — Execution layer: generalize `runTurnSequence` into a chain runner
`TryItOutService` gets one chain runner: outer loop over the plan's requests, inner loop over that request's turns. One accumulated frame (`LinkedHashMap`, later key wins — `mergeAccumulated` semantics) threaded across turns AND requests; request `i`'s first turn sees everything requests `0..i-1` extracted. This is simultaneously the **behavioral fix** for the existing multi-turn path: replace → accumulate, so try-out matches the run engine when a later turn's extraction fails to re-produce a column.

Per iteration, mirroring today's turn body: `resolveForRun` → `validateResolutionResult` → `invokeTurn` with the **current spec's** `endpointRef().getMethod()` → status check via `DeploymentInvocationSupport.resolveExecutionStatus`. `RequestBodyEvaluationException` → history entry with the `REQUEST_BODY_EVALUATION_ERROR` envelope (existing `buildEvaluationFailureResult`), fail-fast. Any failed invocation breaks both loops (matches `RequestExecutionResult.aborted()` + `RequestChainExecutor`'s break). Identity stamps and `extractedColumns`/`extractionWarnings` are set on each history entry per D1/D6.

Extraction now runs for **every successful invocation** (today it is skipped for the last turn since nothing consumed it) — required because the DTO now exposes it — but ONLY when the suite defines at least one response column: for a zero-response-column suite the extractor is never called and both fields stay null/omitted (D6), so such suites' responses do not grow noise fields. Extraction after a failed invocation is not attempted (the entry's `extractedColumns` stays null). **Accepted divergence from the run path**: `TurnLoopExecutor` does best-effort extraction even on the aborting row when a response was received (its `TurnControl.ABORT` branch, ~lines 197–218) because a persisted row must carry reconciled `extracted_columns`; a preview has no persistence obligation, and the failing entry's payload is the error envelope — stated here so nobody "fixes" the asymmetry by accident.

### D5 — Entry condition and fast path
The chain runner is entered when the plan spans more than one invocation (`totalRequests > 1` or any request's `turnDataList.size() > 1`). A single-request single-turn plan keeps the **existing fast path untouched** — except the top level gains `extractedColumns`/`extractionWarnings` (D6). Note the pre-existing asymmetry this preserves on **both** endpoints: the fast paths use the lenient `resolve` (test-case fast path via `resolveRequest`, variables fast path via direct `resolve(template, bindings, Map.of())`), while the loop uses fail-fast `resolveForRun` — exactly the split the multi-turn precedent established, extended unchanged to chains. This keeps the guarantee that pre-existing suites hit pre-existing code.

### D6 — `extractedColumns` = that invocation's own extraction; JSON-null-safe exposure
Each entry (and the top level) exposes its own invocation's reconciled extraction — `ExtractionResult.extractedColumns()` / `.extractionWarnings()` — not the accumulated union the run path persists as `extracted_columns`. Rationale: the accumulated frame is reconstructible by the client (fold `history` in order), while a per-entry view answers the question try-out exists for ("what did THIS request produce for the next one?"); exposing the union would duplicate every earlier entry's values into every later entry. The `@Schema` description for `extractedColumns` must say explicitly: *this invocation's own extraction, not the accumulated frame*.

**Mandatory mechanism (not an implementor choice):** the DTO fields are typed `JsonNode`, populated via `objectMapper.readTree(extraction.extractedColumns())` / `readTree(extraction.extractionWarnings())` — the extractor already builds these JSON strings via `ObjectNode.putNull`, so explicit nulls are preserved end-to-end. A `Map<String, Object>` field is **unimplementable** here: `JsonMapperConfiguration` (lines 60–61) sets NON_NULL for **content** inclusion too, so a map field serializes `{"col": null}` as `{}` on the way OUT regardless of how it was populated. (`@JsonInclude(value = NON_NULL, content = ALWAYS)` on a map field would also work, but the `JsonNode` typing is chosen — it carries the extractor's output verbatim with zero re-serialization risk.) Tests must assert the serialized response body literally contains `"<col>":null` for a failed extraction.

**Zero-response-column suites** (coordinator decision): when the suite defines no response columns, the extractor is not called at all and both fields stay `null`/omitted — never `{}`/`[]` — so every existing try-out response for such suites is byte-identical. Pinned by a delta-spec scenario.

### D7 — Per-request preconditions, validated up-front
The current `validateSuitePreconditions(DeploymentReferenceDto, EndpointContractDto, String rawTemplateJson)` blank-checks a raw JSON string and cannot cover typed chain elements. New shape: `validateChainPreconditions(DeploymentReferenceDto deploymentRef, EndpointContractDto endpointRef, String rawTemplateJson, List<RequestDefinitionDto> additionalRequests)` — `deploymentRef` checked once (suite-level); request #0 keeps its existing checks (typed `endpointRef` + non-null method, raw-JSON blank check on the template); each chain element null-checks its own `endpointRef` + `getMethod()` and its typed `RequestTemplateDto`. Per-element failure messages include the element's position using the `additionalRequests[i]` prefix convention `TestSuiteRequestValidator` already uses (e.g. `"additionalRequests[1]: endpoint reference with HTTP method is required for try-it-out"`). Returns the existing `ValidationException` → HTTP 400 `VALIDATION_ERROR`.

Validation runs in `TryItOutService`, on the **already-loaded** suite entity (elements via `jsonbMapper.mapAdditionalRequests(suite.getAdditionalRequests())` — no extra DB read), **before the planning call** and therefore before the first invocation. Two consequences: a misconfigured request #2 never burns a real call to request #0, and today's error precedence is preserved — a misconfigured suite combined with a nonexistent `testCaseId` still yields 400 (preconditions) rather than 404 (test-case lookup inside planning).

### D8 — `tryWithVariables` chain semantics
Multi-request suites route through the chain runner with every request single-turn; the user's `variables` map is converted once via the existing `convertVariablesToBindings` and used as the effective bindings for **every** request's template. **Wholesale replacement is kept** (coordinator decision, matches the approved plan): chain elements' own `inputBindings` are ignored in variables mode, consistent with the single-request variables mode fully replacing suite bindings — no base+override merging. Consequence (spec-noted): a template variable of any chain element that the user does not supply falls through to its default or produces a REQUIRED warning, and on the chain path that aborts the whole try-out with a bare 400 (`TryItOutValidationException` from `validateResolutionResult` propagates uncaught for invocations after the first — see Non-Goals). Chain resolution passes an empty data map (`resolveForRun(template, bindings, Map.of(), frame)`) — there is no test case. Frame bindings come from real prior responses. The chain path uses `resolveForRun` (fail-fast on body-evaluation errors, like the turn loop); the single-request path keeps today's lenient `resolve`. This asymmetry is accepted: it matches exactly how the multi-turn precedent split lenient-single vs fail-fast-loop, and chain execution cannot proceed meaningfully past an unresolvable body.

### D9 — OpenAPI examples
New example files with the already-whitelisted `chained` suffix (`OpenApiExampleCustomizer.EXAMPLE_NAMES`) for both endpoints (e.g. `api-v1-test-suites-testSuiteId-test-cases-testCaseId-try-it-out-POST-response-200-chained.json` and the suite-level variables counterpart); the existing `multi-turn`/`full` examples are updated only where the new additive fields appear on their paths (`extractedColumns`/`extractionWarnings`). No `OpenApiQueryParamCustomizer` registry entry needed (no new list endpoint).

## Component interaction flow

```
POST …/test-cases/{id}/try-it-out
  TryItOutService.tryWithTestCase
    loadSuite → MCP branch unchanged (write-time guard ⇒ never a chain)
    validate deploymentRef + per-element preconditions (D7)
    ResolvedRequestService.planChain (tx, read-only)  → ChainPlan [spec + turnDataList per request]
    plan spans 1 invocation? → existing fast path (+ extractedColumns)      (D5)
    else → chain runner (D4):
      frame = {} ; for spec in plan: for turn in spec.turns:
        resolveForRun(spec.template, spec.bindings, turnData, frame)
        invokeTurn(…, spec.endpointRef().getMethod(), …)
        extract (iff suite defines response columns) → entry.extractedColumns/Warnings (D6)
        frame = mergeAccumulated(frame, values)
        stamp identity (D1); fail-fast on non-SUCCESS / RequestBodyEvaluationException
      top level = last executed entry; history = all entries
```

## Risks / Trade-offs

- [Behavioral change: accumulate vs replace in existing multi-turn try-out] → Deliberate fix, called out in proposal/spec; observable only when a later turn fails to re-extract a column that an earlier turn produced — previously that value vanished from the frame mid-preview while a real run kept it. Regression test pins the new behavior.
- [Semantics mirrored, not shared — try-out loop can drift from `TurnLoopExecutor`/`RequestChainExecutor`] → Same accepted trade-off as the multi-turn precedent; mitigated by reusing every DB-free seam that computes anything (resolution, extraction, per-turn detection, status mapping) so only the loop skeleton is duplicated, plus functional tests asserting run-equivalent frame threading.
- [Transport-exception gap now also applies per-request] → Documented in spec NOTE; out of scope (Non-Goals). A mid-chain 502 loses the partial `history` — same loss the multi-turn path already accepts per-turn.
- [Variables mode: mid-chain 400 after real side effects] → With wholesale binding replacement (D8), an unsupplied REQUIRED variable or null resolved URL on a chain element after the first surfaces as an uncaught `TryItOutValidationException`/`ValidationException` — a bare 400 **after request #0 already fired a real, possibly side-effecting call**, and the error response carries no `history` of what ran (interaction with the uncaught-exception Non-Goal). Accepted for now: the failing element's `resolvedRequest` is still in the 400's details, and per-element precondition validation (D7) catches structural misconfiguration before any call; folding post-resolution validation failures into `history` is future work alongside the transport gap.
- [Per-entry `extractedColumns` diverges from the run row's accumulated `extracted_columns`] → Intentional (D6); the accumulated view is derivable client-side and the docs/spec say "that invocation's own extraction" explicitly.
- [Chain try-out issues up to 11 real requests × turns synchronously] → Bounded by `MAX_ADDITIONAL_REQUESTS` (10) and existing per-request read timeouts (`dial.components.core.try-out.read-timeout-ms`); same class of cost as multi-turn try-out, accepted for a developer-facing preview.
- [Extra extraction on the last invocation (previously skipped)] → Negligible cost; required by the new DTO contract.

## Migration Plan

Additive API change only; no DB, config, or runner-core changes. Deploy normally; rollback is a plain redeploy of the previous version. Delta spec syncs to `openspec/specs/try-it-out/spec.md` at archive; `docs/patterns/multi-request-suites.md` gains a try-out coverage line.

## Open Questions

None — `planTurns` fold-vs-delegate (D3) is an implementor-level choice that changes neither specs nor tasks.
