## 1. Turn planning (`ResolvedRequestService`)

- [x] 1.1 Locate the existing MCP + multi-turn rejection guard used at run creation (`TestSuiteRunService`, `InvalidOperationException`, `testCaseService.datasetHasMultiTurnCases(...)`) — confirm the exact exception type/message pattern to mirror for try-it-out. (`TestSuiteRunService` lines 92-97: `InvalidOperationException` when `suiteType == MCP_TOOL && testCaseService.datasetHasMultiTurnCases(datasetId)`.)
- [x] 1.2 Check all existing callers of `ResolvedRequestService.resolveRequest(...)`; decide whether to fold it into the new turn-planning method or keep it as a thin single-turn wrapper. (`ResolvedRequestController.getResolvedRequest` also calls it for the separate preview/GET endpoint, out of scope for multi-turn — kept unchanged as its own method.)
- [x] 1.3 Add a `planTurns(testSuiteId, testCaseId)` method to `ResolvedRequestService`: load suite + test case, deserialize `requestTemplate`/`inputBindings`/`responseColumns` (suite) and `data`/`multiTurnData` (test case), look up the dataset's current schema, and use `PerTurnBindingDetector.referencesPerTurnField(...)` to decide turn count N and build the N per-turn merged data maps (mirroring `TurnLoopExecutor.buildTurnPlan`'s `mergeSharedAndTurn` semantics).
- [x] 1.4 Return a small carrier (template, bindings, responseColumns, `List<Map<String, Object>>` turn data) from `planTurns`. (`ResolvedRequestService.TurnPlan` record.)
- [x] 1.5 Unit test `ResolvedRequestServiceTest`: N=1 from no `multiTurnData`; N=1 collapse when `multiTurnData` has multiple entries but no per-turn binding; N=k when a per-turn binding is present, asserting correct shared/per-turn merge precedence. (3 new tests, all passing.)

## 2. Turn loop (`TryItOutService`)

- [x] 2.1 In `tryWithTestCase`, after loading the suite, add the MCP + multi-turn guard (reusing the exception/error code identified in 1.1) before any resolution/invocation happens. (Simplified to presence-based `testCase.getMultiTurnData() != null`, matching the run-creation guard's coarseness — see note below.)
- [x] 2.2 Replace the single call to `resolveRequest(...)` with `planTurns(...)`, then loop `turnIndex = 0..N-1`: resolve via `RequestResolver.resolveForRun(template, bindings, turnData[turnIndex], frameBindings)`, validate (reuse existing validation), invoke via the existing `invokeAndBuildResponse` path, capture `durationMs`/`traceId`. (N<=1 still takes the original `resolveRequest`/`resolve()` single-shot path for byte-identical behavior including graceful warning downgrade; only N>1 runs the new `runTurnSequence` loop using `resolveForRun`.)
- [x] 2.3 On successful turn: run `ResponseColumnExtractor.extract(responseColumns, responseBody, requestBodyJson)` and use its values as `frameBindings` for the next turn.
- [x] 2.4 On failed turn: stop the loop (fail-fast) — this turn's result becomes the returned result. (Failure = non-2xx per `DeploymentInvocationSupport.resolveExecutionStatus`, or a `RequestBodyEvaluationException` from `resolveForRun`. A network/timeout exception during invocation still propagates as before — same 502/504 behavior as single-shot; see note below.)
- [x] 2.5 Build `TryItOutResponseDto` from the last executed turn — `resolvedRequest`/`response`/`durationMs`/`traceId` — the exact same shape as today's single-turn response.
- [x] 2.6 Confirm `tryWithVariables` is untouched (no turn-loop wiring added there). (Verified — unchanged, still single-shot via `requestResolver.resolve(...)`.)

## 3. DTOs

- [x] 3.1 `TryItOutResponseDto` is unchanged by this change; no new fields were added.
- [x] 3.2 No new DTO was added.

**Scope notes from implementation** (narrower than the delta spec's literal wording):
- MCP guard (2.1) rejects on any non-null `multiTurnData`, not "would resolve to more than one turn" — matches the existing run-creation guard's dataset-presence check (`testCaseService.datasetHasMultiTurnCases`), which never runs `PerTurnBindingDetector` either. Cheaper and consistent; the collapse-aware wording in the delta spec's scenario is stricter than actual production precedent. This is already disclosed as a NOTE on the "MCP suite rejects multi-turn test case" scenario in `specs/try-it-out/spec.md` — not blocking.
- Fail-fast (2.4) covers HTTP-status failures and body-evaluation errors, not transport-level exceptions (DIAL Core unreachable/timeout) — those still propagate as a request-level 502/504 exactly as they did pre-change. Extending exception paths to change this behavior would require converting `DialCoreDeploymentInvoker`'s thrown exceptions into a `TurnOutcome`-like result, a larger change out of scope here. This gap is disclosed as a NOTE on the "Turn failure stops the sequence" scenario in `specs/try-it-out/spec.md` and in `design.md`'s fail-fast rationale.
- **Known, disclosed-but-not-fixed limitation**: `validateResolutionResult(resolved)`, called inside `runTurnSequence`'s try block, can also throw plain `ValidationException` (null resolved URL) or `TryItOutValidationException` (unresolved REQUIRED template variables) for turns after the first — `runTurnSequence`'s catch clause only catches `RequestBodyEvaluationException`, so neither is caught. On turn index > 0 these exceptions propagate uncaught, surfacing as a plain error response, exactly like the transport-exception gap above. This is disclosed as an additional NOTE on the "Turn failure stops the sequence" scenario in `specs/try-it-out/spec.md` and in `design.md`'s fail-fast rationale. Fixing it is out of scope for this change.

## 4. Unit tests (`TryItOutService`)

- [x] 4.1 Happy-path multi-turn test: 3 turns, assert `frameBindings` threads correctly turn-to-turn (mock `ResponseColumnExtractor`/`RequestResolver` as needed), assert the returned `TryItOutResponseDto` reflects turn 2 (the last turn) and that `resolveForRun` was invoked with the correct per-turn `frameBindings`.
- [x] 4.2 Fail-fast test: turn 2 of 3 fails (non-2xx status) — assert the loop stops after 2 invocations and the returned response is turn 2's error. Plus a second test for the `RequestBodyEvaluationException` fail-fast path, not just non-2xx status.
- [x] 4.3 Regression test: existing single-turn test case (no `multiTurnData`) is unaffected (covered by the pre-existing `TryWithTestCase` tests, which never exercise the turn loop).
- [x] 4.4 N=1 collapse test: `multiTurnData` has multiple entries but no per-turn binding — assert the response matches a plain single-turn invocation and `ResponseColumnExtractor` is never invoked.
- [x] 4.5 MCP + multi-turn rejection test: `suiteType = MCP_TOOL` with multi-turn test case — assert `InvalidOperationException` (409 `INVALID_OPERATION` at the web layer) before any MCP call is attempted. (Unit-level test added directly to `TryItOutServiceTest`, since MCP flows aren't otherwise covered there — existing MCP coverage lives in functional tests.)

## 5. Functional test

- [x] 5.1 Add a `@PostgresFunctionalTests` scenario (via `MetaTestDataHelper`) that persists a multi-turn `TestCase` bound to a DEPLOYMENT suite with `responseColumns` configured for `$history` accumulation, calls the test-case try-it-out endpoint end-to-end, and asserts the final turn's `resolvedRequest` contains every prior turn's accumulated messages. Plus a fail-fast functional scenario asserting the returned response is the failing turn's error. Reused the `$history`-accumulating suite fixture by promoting `createHistoryAccumulatingChatSuite` from `MultiTurnRunFunctionalTests` into the shared `AbstractMultiTurnFunctionalTest` base — `TryItOutFunctionalTests` now extends that base instead of `BaseFunctionalTest` directly, removing its own duplicate `newDatasetWithSchema`.
- [x] 5.2 (Removed) The single-turn regression scenario is unnecessary — the pre-existing single-turn tests in `TryItOutFunctionalTests` already cover the unchanged response shape.

**Verification**: `PostgresFunctionalTests$TryItOutTests` and `PostgresFunctionalTests$MultiTurnRunTests` pass (no regression from the fixture refactor).

## 6. Documentation

- [x] 6.1 (Removed after review) A dedicated multi-turn OpenAPI example was considered, then dropped — the multi-turn response is byte-identical in shape to the existing single-turn example, so there is nothing distinct left to illustrate.
- [x] 6.2 Run `./gradlew spotlessApply checkstyleMain checkstyleTest` and fix any violations. (Both clean; re-ran all touched test suites after reformatting to confirm no regressions.)

## 7. Verification

- [x] 7.1 Run `./gradlew test --tests "*TryItOutService*"` and `./gradlew test --tests "*ResolvedRequestService*"` — confirm unit tests pass.
- [x] 7.2 Run the relevant `@PostgresFunctionalTests` nested class — confirm functional tests pass and the Spring context boots. (`PostgresFunctionalTests$TryItOutTests`, `PostgresFunctionalTests$MultiTurnRunTests` — Spring context + Testcontainers Postgres boot successfully.)
- [x] 7.3 Manual check via Swagger UI (`config.rest.security.mode=none`): try-it-out against a seeded multi-turn test case and a single-turn one. Manually verified by the developer — after fixing the suite's request template to be JSONata-authored (`jsonataContent` + `$append($history, [...])`) and adding the `history` response column, the final turn's `resolvedRequest` correctly carried the full accumulated conversation.
