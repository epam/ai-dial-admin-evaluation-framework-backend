## 1. Refactor DatasetCloneService

- [x] 1.1 Add `String name` parameter before `createdBy` in `DatasetCloneService.cloneRowAndTestCases()` and use it directly instead of calling `deriveCloneName()` internally (`service/domain/DatasetCloneService.java`)
- [x] 1.2 Update `TestSuiteCloneService.executeDbWrites()` call site to pass `datasetCloneService.deriveCloneName(datasetToClone.getName())` as the `name` argument (no behavior change)
- [x] 1.3 Verify build compiles with no other callers of `cloneRowAndTestCases` broken: `./gradlew compileJava`

## 2. Add error code and DTO

- [x] 2.1 Use an error code for the "suite has no dataset bound" 409 condition — reused the existing `SUITE_HAS_NO_DATASET` value in `DatasetVisibilityErrorCode` (equivalent semantics; no new enum value needed)
- [x] 2.2 Create `DatasetDetachRequestDto` in `service/domain/dto/` with optional `@Size(max = ValidationConstants.MAX_DATASET_NAME_LENGTH) String name` field, Lombok `@Data @Builder @NoArgsConstructor @AllArgsConstructor`

## 3. Service — TestSuiteService.detachDataset()

- [x] 3.1 Inject the dependencies `TestSuiteService.detachDataset()` needs (clone service, file service, source-dataset read access, meta transaction manager) via constructor — added `DatasetCloneService` and `Clock`; `FileService` and `metaTransactionManager` already present; added `findById` to `DatasetQueryService` and injected it for cross-domain dataset reads (NOT `DatasetRepository`, per the cross-domain rule)
- [x] 3.2 Implement `detachDataset(UUID suiteId, DatasetDetachRequestDto dto, Jwt jwt)` in `TestSuiteService`

## 4. Repository — updateDatasetId (if not present)

- [x] 4.1 Check if `TestSuiteRepository` already exposes a method to update `dataset_id` and `version`/`updated_at_ms`; if not, add an update method and implement in `PostgresTestSuiteRepository` using the jOOQ DSL `update(...).set(...).where(...)` pattern — added `void updateDatasetId(UUID suiteId, UUID newDatasetId, List<UUID> disabledTestCaseIds, long updatedAt)` (also rewrites `disabled_test_case_ids` with the remapped IDs atomically)

## 5. Controller

- [x] 5.1 Add `POST /{id}/detach-dataset` endpoint to `TestSuiteController` returning `ResponseEntity<TestSuiteResponseDto>` with `@Valid @RequestBody DatasetDetachRequestDto`, `@AuthenticationPrincipal Jwt jwt`, and `@Operation` + `@ApiResponse` OpenAPI annotations
- [x] 5.2 Add OpenAPI request/response example annotations for the new endpoint (empty body `{}` as minimal example, body with `name` as full example)

## 6. Functional tests

- [x] 6.1 Create `DatasetDetachFunctionalTests` (annotated with `@PostgresFunctionalTests`) with `@Autowired MetaTestDataHelper` and `@Autowired TestSuiteRepository` / `DatasetRepository` for state assertions
- [x] 6.2 Implement `shouldDetachFromPublicDatasetWithDerivedName`: POST `{}` → 200, suite's `datasetId` changed, new dataset is PRIVATE, original PUBLIC dataset unchanged, test cases copied
- [x] 6.3 Implement `shouldDetachFromPublicDatasetWithCustomName`: POST `{"name":"My Copy"}` → 200, new PRIVATE dataset has name `"My Copy"`
- [x] 6.4 Implement `shouldReturn409WhenSuiteIsBoundToPrivateDataset`: suite bound to PRIVATE → 409
- [x] 6.5 Implement `shouldReturn409WhenSuiteHasNoDataset`: no bound dataset → 409
- [x] 6.6 Implement `shouldReturn404WhenSuiteNotFound`: unknown suite id → 404
- [x] 6.6a Implement `shouldRemapDisabledTestCaseIdsToClonedIds`: suite's disabledTestCaseIds remapped to the cloned test-case IDs
- [x] 6.7 Run the new functional tests: `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$DatasetDetachTests"` — 6/6 passed
- [x] 6.8 Run `TestSuiteCloneService` functional tests to confirm the refactored `cloneRowAndTestCases` call site still passes — all passed

## 7. Build and spec sync

- [x] 7.1 Run Spotless and Checkstyle: `./gradlew spotlessApply checkstyleMain checkstyleTest`
- [x] 7.2 Update `openspec/specs/README.md` per Spec Index Maintenance Policy — add `detach-dataset` entry (new spec folder added)
