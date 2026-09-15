## 1. Rename and extend the ADAS cost query builder

- [x] 1.1 Rename `src/main/java/com/epam/aidial/evaluation/service/domain/RunCostQueryBuilder.java` →
  `AdasCostQueryBuilder.java`; rename `buildAggregateQuery(UUID, String)` → `buildRunAggregateQuery(UUID, String)`
  (method body unchanged); update the class javadoc to describe both the run-scoped and deployment-scoped
  query shapes.
- [x] 1.2 Fix the baggage field to dial-adas's direct, queryable `usage_request_baggage.baggage` field
  (design.md Decision 1a — this changes the outbound query used by
  both the existing run-costs query and the new deployment-costs query, not any response contract).
- [x] 1.3 Add `DEPLOYMENT_FIELD = "deployment"` / `REQUEST_TIME_FIELD = "request_time"` constants and
  `buildDeploymentAggregateQuery(String deploymentId, long fromMs, long toMs, String phase)`, reusing
  `baggageField()`/`baggageContains()` for the `eval.phase` filter and new `eq`/`ge`/`le` helper
  methods for `deployment`/`request_time` (design.md Decision 1).
- [x] 1.4 Update `TestSuiteRunService`'s call site to the renamed `AdasCostQueryBuilder`/`buildRunAggregateQuery`.

## 2. Update and extend query-builder tests

- [x] 2.1 Rename `RunCostQueryBuilderTest.java` → `AdasCostQueryBuilderTest.java`; update references to the
  renamed class/method.
- [x] 2.2 Update the existing `serializesToDialAdasWireShape` assertion to the flattened
  `{"type": "field", "name": "usage_request_baggage.baggage"}` shape, removing the `json_extract_string`
  `fn` wrapper entirely.
- [x] 2.3 Add unit tests for `buildDeploymentAggregateQuery`: structural `StructuredQuery`/filter-tree
  assertion (`eq(deployment)`, `ge(request_time)`, `le(request_time)`, `co` baggage-phase) and a full
  JSON wire-shape assertion confirming `value_type: "timestamp"` and epoch-millis-as-string values.
- [x] 2.4 Run `./gradlew test --tests "com.epam.aidial.evaluation.service.domain.AdasCostQueryBuilderTest"`
  and confirm it passes.

## 3. Response DTO

- [x] 3.1 Create `DeploymentCostsResponseDto` (`service.domain.dto`): `@Data @Builder @NoArgsConstructor
  @AllArgsConstructor`, nullable `Double totalTestCaseCost` / `totalMetricEvalCost` fields with `@Schema`
  documentation, mirroring `RunCostsResponseDto`.

## 4. `CostService`

- [x] 4.1 Create `CostService` (`@Service @LogExecution @RequiredArgsConstructor`, `service.domain`) with
  `getDeploymentCosts(String deploymentId, long fromMs, long toMs)`; throw the existing `ValidationException`
  inline when `fromMs > toMs` (design.md Decision 4).
- [x] 4.2 Implement a per-phase `fetchTotalCost` helper calling `DialAdasClient.executeAggregate` via
  `AdasCostQueryBuilder.buildDeploymentAggregateQuery`, mapping empty/zero-count rows to `null`; call it
  sequentially once for `PHASE_EXECUTION` and once for `PHASE_METRIC_EVALUATION` (design.md Decision 5).

## 5. `CostController`

- [x] 5.1 Create `CostController` (`@RestController @LogExecution @Validated @RequiredArgsConstructor`,
  `web.controller`) exposing `GET /api/v1/costs` with `@NotBlank @RequestParam String deploymentId` and
  required `@RequestParam Long from`/`to`, delegating to `CostService.getDeploymentCosts`.
- [x] 5.2 Add OpenAPI annotations: `@Tag`, `@Operation` (summary/description covering the time-range and
  null-total semantics), `@Parameter` on each param, `@ApiResponse` for 200/400/502/504.
- [x] 5.3 Add minimal + full OpenAPI example JSON files under `src/main/resources/openapi/examples/` for
  `GET /api/v1/costs` per the openapi-examples spec.

## 6. Unit tests for service and controller

- [x] 6.1 Add `CostServiceTest`: verifies the two sequential dial-adas calls (one per phase), null-mapping
  on empty/zero-count rows, and `ValidationException` when `from > to`.
- [x] 6.2 Add `CostControllerTest`: request binding, 400 on missing `from`/`to` or blank `deploymentId`,
  delegation to `CostService`.
- [x] 6.3 Run `./gradlew test --tests "com.epam.aidial.evaluation.service.domain.CostServiceTest"` and
  `./gradlew test --tests "com.epam.aidial.evaluation.web.controller.CostControllerTest"`; confirm both pass.

## 7. Functional test coverage

- [x] 7.1 Add a `CostFunctionalTests` nested class (or extend the existing run-costs functional test file)
  under `@PostgresFunctionalTests` with `shouldGetDeploymentCosts` and a `from > to` validation-error case,
  mocking the already-`@Autowired` `DialAdasClient` bean and calling `GET /api/v1/costs` via the test
  `TestRestTemplate`.
- [x] 7.2 Run the new functional test class/nested group and confirm it passes — this boots the full Spring
  context, required because `CostController`/`CostService` are new beans with constructor injection.
- [x] 7.3 Re-run the existing `shouldGetRunCosts` functional test and confirm it still passes with the
  corrected `usage_request_baggage.baggage` field wired through end-to-end.

## 8. Docs and spec index

- [ ] 8.1 Update `docs/key-packages.md` to list `AdasCostQueryBuilder` (renamed), `CostService`,
  `CostController`, `DeploymentCostsResponseDto`.
- [ ] 8.2 Update `openspec/specs/README.md` per the Spec Index Maintenance Policy — this change adds a new
  `deployment-costs` spec folder.
- [ ] 8.3 Run `./gradlew spotlessApply` then `./gradlew checkstyleMain checkstyleTest` and confirm both are
  clean.
