## 1. DialCoreClient — single-item deployment lookup

- [x] 1.1 Add `DialCoreClient.getDeploymentById(String id)` targeting `DEPLOYMENTS_PATH + "/" + id` (`GET /v1/deployments/{id}`), following the `withRetry(path, () -> get(path, DialCoreDeploymentDto.class))` pattern already used by `getModel`/`getApplication`/`getToolset`
- [x] 1.2 Add `DialCoreClientTest` cases for `getDeploymentById` via `MockRestServiceServer`: happy-path polymorphic deserialization (including `interfaces`) resolving model/application/toolset subtypes, plus 404/403/400 status propagation as `DialCoreClientException` carrying the raw status
- [x] 1.3 If direct deserialization does not resolve the subtype correctly, fall back to the `JsonNode` + `objectMapper.convertValue` workaround `getDeployments` uses, and adjust 1.1/1.2 accordingly
- [x] 1.4 Run `./gradlew test --tests "com.epam.aidial.evaluation.client.dialcore.DialCoreClientTest"` and confirm it passes

## 2. DeploymentService — single-call by-id lookup with scoped 404

- [x] 2.1 Rewrite `DeploymentService.getDeployment(String)` to call `dialCoreClient.getDeploymentById(deploymentId)`, reuse the existing private `toDeploymentInfoDto(DialCoreDeploymentDto)` mapping, and set `interfaces` via `dto.setInterfaces(deployment.getInterfaces())`
- [x] 2.2 Add a dedicated not-found exception (or equivalent handling in `DeploymentService`/`DeploymentController`) that catches the `DialCoreClientException` thrown by `getDeploymentById` when `getStatusCode()` is 404 and resolves it to HTTP 404 `NOT_FOUND`, bypassing `DialCoreErrorMapper`; rethrow the original `DialCoreClientException` unchanged for every other status so it flows through the normal `DefaultExceptionHandler.handleDialCoreClientException` path
- [x] 2.3 Delete the now-dead private helpers used only by the old fan-out (`probeAsync`, `probe`, `logProbeFailure`, `awaitProbe`, `asDialCoreClientException`) and the `deploymentProbeCollapser` field; remove now-unused imports (`Context`, `CompletableFuture`, `ExecutionException`, `Executor`, `ExecutorService`, `Executors`, `Supplier`, `TokenPropagationHelper`), keeping `AuthorizationTokenHolder` and `HttpStatus` since they remain used elsewhere in the class
- [x] 2.4 Confirm `getDeployment(DeploymentType, String)` (the by-type path) is left untouched — it must keep going through the unmodified shared `DialCoreErrorMapper` (404 → 502)

## 3. Expose `interfaces` on deployment DTOs

- [x] 3.1 Add `private List<InterfaceType> interfaces;` to `DeploymentInfoDto` with a `@Schema` example, reusing `client.dialcore.dto.InterfaceType` directly (no duplicate API-level enum) — pulled forward as a compile prerequisite for task 2.1
- [x] 3.2 In `DeploymentMapper`, map `interfaces` in `toDeploymentInfoShortDto`'s builder chains (list endpoint) so list responses carry it
- [x] 3.3 In `DeploymentMapper`, add explicit `@Mapping(target = "interfaces", ignore = true)` to `toDialModelInfoDto`, `toDialApplicationInfoDto`, and `toToolsetInfoDto` (shared by by-type and by-id) so MapStruct's implicit same-name matching never auto-maps it from the DIAL Core DTOs' inherited `getInterfaces()` — pulled forward as a correctness prerequisite for task 2's by-type/by-id `interfaces` split
- [x] 3.4 Regenerate MapStruct sources via `./gradlew compileJava` (never hand-edit `DeploymentMapperImpl`) and confirm the generated mapper reflects 3.2/3.3

## 4. Remove probe/collapse machinery

- [x] 4.1 Delete `DeploymentProbe.java` and `DeploymentProbeCollapser.java`
- [x] 4.2 Delete `DeploymentProbeCollapserTest.java`
- [x] 4.3 Trim `DeploymentServiceTokenPropagationTest`: remove the `getDeploymentById*` tests and their probe-thread-token helpers (a synchronous single call no longer needs `TokenPropagationHelper`); keep the `getAllDeployments*` tests, which are unaffected — pulled forward as a compile prerequisite for task 2.3 (constructor call updated to the new 5-arg signature)

## 5. Controller and OpenAPI documentation

- [x] 5.1 Update `DeploymentController#getDeploymentById`'s `@Operation`/`@ApiResponse`s: drop probe/fan-out language, describe the single-request flow, add a `404` response, and narrow the `502` response so it no longer implies covering not-found
- [x] 5.2 Add an `"interfaces"` array to `api-v1-deployments-all-GET-response-200-{minimal,full}.json` and `api-v1-deployments-GET-response-200-{minimal,full}.json`; rewrite any probe-referencing description text in the by-id `-full.json` example
- [x] 5.3 Verify `api-v1-deployments-deploymentType-GET-response-200-*.json` examples are unchanged and carry no `interfaces` key

## 6. Functional, mapper, and DTO tests

- [x] 6.1 Rewrite the `all/**` probe/collapse block in `DeploymentFunctionalTests` into single-stub equivalents against the mocked `DialCoreClient.getDeploymentById`: model/application(+resolved routes)/toolset happy paths, slash-containing ID, percent-encoded ID, empty-ID 400, and status propagation (404→404, 403→403, 401→502, 5xx→502)
- [x] 6.2 Add `interfaces` assertions to `getAllDeploymentsReturnsMergedList` (list) and extend the by-id/by-type example-coverage test to assert `interfaces` presence on list/by-id and absence on by-type
- [x] 6.3 Update `DeploymentMapperTest`: assert `toDeploymentInfoShortDto` maps `interfaces`, and that `toDialModelInfoDto`/`toDialApplicationInfoDto`/`toToolsetInfoDto` leave it null
- [x] 6.4 Update `DeploymentInfoDtoSerializationTest` to round-trip a DTO with `interfaces` set
- [x] 6.5 Run `./gradlew test --tests "com.epam.aidial.evaluation.service.domain.mapper.DeploymentMapperTest"`, the DTO serialization test, and `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$DeploymentTests"`; confirm all pass

## 7. Documentation placeholders

- [x] 7.1 Add a TODO-style placeholder (no invented version number) for the minimum DIAL Core version requirement in `README.md`'s DIAL Core integration description
- [x] 7.2 Add the same placeholder in `docs/configuration.md` §5.1 ("DIAL Core Client")

## 8. Spec sync

- [x] 8.1 Sync the delta in `specs/dial-core-client/spec.md` into `openspec/specs/dial-core-client/spec.md` (done: main spec reflects the single-call by-id lookup, the scoped 404 fix, and the two new requirements for `getDeploymentById` and `interfaces` exposure)

## 9. Final verification

- [x] 9.1 Run `./gradlew spotlessApply`
- [x] 9.2 Run `./gradlew checkstyleMain checkstyleTest`
- [x] 9.3 Run `./gradlew build` (full suite, including `LayeredArchitectureTest` and `LoggingConventionTest`) and confirm it passes
