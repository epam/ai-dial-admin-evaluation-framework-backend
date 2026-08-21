# Try-out support for multi-request test suites

## Why

Multi-request suites (`additionalRequests` chain, GH #98) are **silently ignored** by both try-out endpoints today: `ResolvedRequestService.planTurns` reads only request #0's template/bindings/responseColumns, and `TryItOutService` never touches `additionalRequests` — the multi-request change explicitly deferred try-out (archived change `2026-08-04-add-multi-request-suite`, task 7.4). The chain-aware preview `GET …/resolved-request?requestIndex=N` resolves with an **empty frame**, so a chained request's JSONata references to earlier requests' response columns degrade to warnings and cannot be verified against real responses anywhere. A suite author building a 2+ request chain has no way to see whether request N+1's bindings actually resolve from request N's live output before committing to a full run.

## What Changes

- **Test-case try-out** (`POST /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out`) executes the suite's **whole request chain** (turns × requests), threading one accumulated JSONata frame across turns AND requests exactly as the run engine does, so request N+1 resolves against request N's real response columns.
- **Variables try-out** (`POST /api/v1/test-suites/{testSuiteId}/try-it-out`) executes the whole chain too — every request single-turn (no test case ⇒ no `multiTurnData`), user variables applied as constant bindings to every request's template (wholesale replacement — chain elements' own `inputBindings` are ignored), frame bindings from real prior responses. This supersedes the "`tryWithVariables` remains single-turn" requirement for multi-request suites (it stays single-invocation for single-request suites). Known rough edge (see design Risks): an unsupplied required variable of a later chain element surfaces as a bare 400 after earlier requests already fired real calls.
- **API shape — flat `history` + identity stamps**: `TryItOutResponseDto` (each history entry and the top level) gains `requestIndex`/`totalRequests`/`requestName` (stamped only when the chain has >1 request) and `turnIndex`/`totalTurns` (stamped only when that request ran >1 turn). The numeric stamps mirror `TurnLoopExecutor.stampIdentity`'s guards; `requestName` is a try-out-only convenience (run rows persist no request name). Existing single-request **single-turn** responses stay byte-identical apart from the additive extraction fields; existing single-request **multi-turn** responses additionally gain `turnIndex`/`totalTurns` (additive only).
- **Expose extracted columns**: each history entry (and the top level) gains `extractedColumns` + `extractionWarnings` (carried as JSON trees to preserve explicit nulls through the NON_NULL response mapper) — that invocation's own extraction, i.e. the frame values it produced for its successors. Today try-out computes these internally and discards them. Suites with **no** response columns omit both fields entirely — their responses stay byte-identical.
- **Behavioral fix — frame merge replace → accumulate**: try-out's existing multi-turn loop *replaces* `frameBindings` each turn; the run path *accumulates* (`TurnLoopExecutor.mergeAccumulated`, later key wins). Try-out switches to accumulate so its predictions match a real run.
- **Per-request execution parameters**: each chain element's own `endpointRef.getMethod()` is used (today the suite-level method is hoisted once — wrong for chains) and preconditions (`endpointRef` with method, `requestTemplate` present) are validated per chain element.
- Fail-fast: the first failed turn/request stops the whole chain, matching the run path and the existing multi-turn try-out.
- New `chained` OpenAPI examples for both endpoints (suffix already whitelisted in `OpenApiExampleCustomizer.EXAMPLE_NAMES`).

Explicitly **not** changing:
- `GET …/resolved-request?requestIndex=N` keeps its empty-frame, no-invocation preview semantics.
- MCP suites need no new guard — `MCP_TOOL` + non-empty `additionalRequests` is already rejected at write time.
- The known transport-exception gap (a 502/504 during a turn after the first propagates uncaught instead of becoming a history entry) is kept consistent for chain entries > 0 and stays out of scope (documented in design).
- No DB schema changes, no Flyway migrations, no configuration properties, no runner-core module changes.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `try-it-out`: both try-out endpoints become chain-aware for multi-request suites (turns × requests execution, accumulated frame, per-request method/preconditions); `TryItOutResponseDto` gains identity stamps (`requestIndex`/`totalRequests`/`requestName`/`turnIndex`/`totalTurns`) and `extractedColumns`/`extractionWarnings`; the multi-turn frame-threading requirement changes from replace to accumulate; the "`tryWithVariables` remains single-turn" requirement is narrowed to single-request suites.

## Impact

- **Code** (main app only; no `evaluation-runner-core` changes expected):
  - `service/domain/ResolvedRequestService.java` — new chain-aware plan method (list of per-request plans built from suite fields + `jsonbMapper.mapAdditionalRequests(...)` + `suite.getRequestName()`), reusing the `runner.job.RequestExecutionSpec` record as the per-request carrier; a DB-only chain loader for the variables path.
  - `service/domain/TryItOutService.java` — `runTurnSequence` generalized to a chain runner (outer request loop, inner turn loop, accumulated frame, per-request method + preconditions, identity stamping, extracted-column capture); `tryWithVariables` routed through the chain runner for multi-request suites.
  - `service/domain/dto/TryItOutResponseDto.java` — 7 additive `@JsonInclude(NON_NULL)` fields (`extractedColumns`/`extractionWarnings` typed as `JsonNode` — the shared mapper's NON_NULL content inclusion would strip explicit nulls from map fields).
  - `src/main/resources/openapi/examples/` — new `…-chained.json` example files; `multi-turn`/`full` examples updated where the new fields appear.
- **API**: additive-only response fields on both try-out endpoints; single-request single-turn responses unchanged except the additive top-level `extractedColumns`/`extractionWarnings`. One deliberate behavioral change inside existing multi-turn try-out: frame accumulation (a turn that fails to re-extract a column no longer erases the previous turn's value — matches the run engine).
- **Reused seams** (DB-free, runner-core): `RequestResolver.resolveForRun`, `ResponseColumnExtractor`, `PerTurnBindingDetector`, `RequestBodySerializerRegistry`, `DialCoreUrlBuilder`, `DeploymentInvocationSupport`, `RequestExecutionSpec`. `RequestChainExecutor`/`TurnLoopExecutor` are **not** called (run-shaped); their semantics are mirrored.
- **Tests**: unit (`TryItOutServiceTest`, `ResolvedRequestServiceTest`) + functional (`TryItOutFunctionalTests` chain scenarios registered in `PostgresFunctionalTests`).
- **Docs**: `docs/patterns/multi-request-suites.md` gains a try-out coverage note; delta spec syncs to `openspec/specs/try-it-out/spec.md` at archive. No `docs/configuration.md` / `docs/database-schema.md` changes.
- **Security/rollout**: no new endpoints, no auth changes, no migration ordering concerns; safe to ship as a regular release.
