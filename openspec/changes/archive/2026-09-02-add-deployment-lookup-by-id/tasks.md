## 1. Verify the upstream premise

- [ ] 1.1 **NOT RUN — needs live dev DIAL Core access.** Against a dev DIAL Core, `GET /openai/models/{id}` with a known **application** ID and with a known **toolset** ID, and the symmetric checks for `/openai/applications/{id}` and `/openai/toolsets/{id}` (done: recorded in this file whether a cross-type ID yields 404 — if any endpoint resolves a foreign-type ID, note it here, since precedence then becomes endpoint semantics rather than an anomaly guard, and confirm the delta spec's precedence scenario still describes the behavior we want)

## 2. Collapse component (pure logic, no HTTP)

- [x] 2.1 Add the probe-outcome carrier record (type + nullable body + nullable `DialCoreClientException`, hit/miss/error derived) alongside the collapser in `service/domain/` (done: compiles; no Jackson annotations — internal carrier, never serialized)
- [x] 2.2 Add `DeploymentProbeCollapser` `@Component` in `service/domain/` implementing hit precedence (`dial-model` > `dial-application` > `dial-toolset`, sourced from `DeploymentType` declaration order) and no-hit unification (severity `401 > 403 > other > 404`, message naming each leg), with `@LogExecution` on the class and the WARN multi-hit log (done: `./gradlew checkstyleMain` clean)
- [x] 2.3 Unit-test the collapser for every branch: single hit per type, multi-hit precedence, all-404, 401-outranks-404, 403-outranks-404, 5xx-outranks-404, all-miss-no-errors synthesizing 404, and message content naming all three legs (done: `./gradlew test --tests "com.epam.aidial.evaluation.service.domain.DeploymentProbeCollapserTest"` passes)

## 3. Service fan-out

- [x] 3.1 Implement `DeploymentService.getDeployment(String)`: capture the token on the request thread, fan out the three raw `DialCoreClient` probes on `Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor())` wrapped in `TokenPropagationHelper.withToken`, unwrap `CompletionException` at the join site, restore the interrupt flag on `InterruptedException`, hand the outcomes to the collapser, and map only the winner through the existing per-type logic (application winner still gets `SchemaRouteExtractor.resolveRoutes`) (done: replaces the `UnsupportedOperationException` stub; `./gradlew compileJava` clean)
- [x] 3.2 Extend `DeploymentServiceTokenPropagationTest` with a case asserting all three probe legs observe the caller's token, and one asserting a null token does not break the fan-out (done: `./gradlew test --tests "com.epam.aidial.evaluation.service.domain.DeploymentServiceTokenPropagationTest"` passes)
- [x] 3.3 Unit-test that a losing application leg triggers **no** `getApplicationTypeSchema` call and that a winning one does (done: same test class or a sibling service test passes, verified with Mockito `verify`/`never`)

## 4. Web layer + OpenAPI

- [x] 4.1 Rename the working-tree stub mapping from `/any/**` to `/all/**` and give the handler the full OpenAPI set — `@Operation` (type-less lookup, `$type` identifies what was found, slash-containing and percent-encoded IDs behave as on the typed endpoint) plus `@ApiResponse` for 200 (`DeploymentInfoDto`), 400 (empty ID / malformed encoding), 401, 403, 502 (`UPSTREAM_AUTH_ERROR` / `UPSTREAM_NOT_FOUND` / `UPSTREAM_ERROR`) (done: Swagger UI at `/swagger-ui.html` shows the endpoint with all responses; `./gradlew checkstyleMain` clean)

## 5. Functional tests

- [x] 5.1 Add by-ID lookup happy-path tests to `DeploymentFunctionalTests` (mocked `DialCoreClient` bean): hit as model, hit as application with resolved schema routes, hit as toolset — each asserting HTTP 200, the `$type` discriminator in the raw JSON body, and payload parity with the typed endpoint (done: `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$DeploymentTests"` passes)
- [x] 5.2 Add outcome-collapsing tests: multi-hit precedence, all-404 → 502 `UPSTREAM_NOT_FOUND`, 401 leg → 502 `UPSTREAM_AUTH_ERROR`, 403 leg → 403 `ACCESS_DENIED`, 5xx leg → 502 `UPSTREAM_ERROR`, and one hit alongside a 500 leg → 200 (done: same suite passes)
- [x] 5.3 Add path-handling tests: slash-containing ID passed to all three probes intact, percent-encoded ID decoded once, empty ID → 400 `VALIDATION_ERROR` with no client calls (`verify(..., never())` on all three), and `/deployments/all/{id}` routed to the type-less handler rather than the by-type wildcard (done: same suite passes)

## 6. Gates and spec sync

- [x] 6.1 Run `./gradlew spotlessApply` then `./gradlew clean build` (done: build green — includes `spotlessCheck`, Checkstyle, `LayeredArchitectureTest`, `LoggingConventionTest`, and the full test suite)
- [x] 6.2 Sync the delta into `openspec/specs/dial-core-client/spec.md` — the ADDED requirement plus the corrected not-found scenario on *Get deployment by type and ID* — and flip the new requirement's `Status` to **Implemented** (done: `openspec validate add-deployment-lookup-by-id` passes and the main spec carries both edits; `openspec/specs/README.md` needs no change — no new spec folder, no status change, summary still accurate)
