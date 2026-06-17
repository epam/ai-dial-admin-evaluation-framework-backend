## 1. Data layer — projection + query

- [x] 1.1 Add pure-carrier projection `data.db.model.TestSuiteSummary` as `record TestSuiteSummary(UUID id, String name, String description)` (done: record compiles, no logic/validation)
- [x] 1.2 Add `List<TestSuiteSummary> findSuiteSummariesReferencingDataset(UUID datasetId)` to `data.db.repository.TestSuiteRepository` (done: interface method declared with imports, no FQNs)
- [x] 1.3 Implement the method in `PostgresTestSuiteRepository` using `dsl.select(TEST_SUITES.ID, TEST_SUITES.NAME, TEST_SUITES.DESCRIPTION).from(TEST_SUITES).where(TEST_SUITES.DATASET_ID.eq(datasetId.toString()))` mapping each row to `TestSuiteSummary` (done: selective projection only — id/name/description; class has `@LogExecution`)

## 2. DTO

- [x] 2.1 Add `service.domain.dto.DatasetDependentSuiteDto` with exactly `UUID id`, `String name`, `String description`; Lombok `@Data/@Builder/@NoArgsConstructor/@AllArgsConstructor`; `@Schema(example=...)` on each field (done: DTO compiles, no other suite fields present)

## 3. Service layer

- [x] 3.1 Add `List<DatasetDependentSuiteDto> getDependentSuiteSummaries(UUID datasetId)` to `TestSuiteService` — calls `testSuiteRepository.findSuiteSummariesReferencingDataset(...)` and maps `TestSuiteSummary` → `DatasetDependentSuiteDto`; `@Transactional(value="metaTransactionManager", readOnly=true)` (done: method returns mapped list; mapping in an injectable/testable location, not a controller private method)
- [x] 3.2 Add `List<DatasetDependentSuiteDto> getDependentSuites(UUID id)` to `DatasetService` — performs dataset existence check via the existing not-found path (e.g. `getById(id)`, yielding 404 `NOT_FOUND` for unknown id) then delegates to `testSuiteService.getDependentSuiteSummaries(id)`; `@Transactional(value="metaTransactionManager", readOnly=true)` (done: cross-domain read goes through `TestSuiteService`, not `TestSuiteRepository`; `DatasetService` uses its already-injected `TestSuiteService`)

## 4. Web layer + OpenAPI

- [x] 4.1 Add `@GetMapping("/{id}/test-suites")` to `DatasetController` returning `List<DatasetDependentSuiteDto>` from `datasetService.getDependentSuites(id)`; add `@Operation` summary, `@ApiResponse` 200 (array of `DatasetDependentSuiteDto`) and 404, under the existing "Datasets" tag (done: endpoint compiles, imports only — no FQNs; uses `@ArraySchema`)
- [x] 4.2 Add an OpenAPI response example for the endpoint per the openapi-examples spec (`@ExampleObject` inline or JSON file under `src/main/resources/openapi/examples/`) (done: Swagger UI shows an example array of `{id,name,description}`)

## 5. Tests

- [x] 5.1 Functional test: existing dataset with bound suites returns their `{id,name,description}` summaries and no other suite fields (done: passes in `PostgresFunctionalTests$TestSuiteDatasetTests`; uses `MetaTestDataHelper`)
- [x] 5.2 Functional test: existing dataset with no bound suites returns `200` and empty array (done: deterministic assertion on empty list)
- [x] 5.3 Functional test: unknown dataset id returns `404` `NOT_FOUND` (done: deterministic assertion on status)
- [x] 5.4 Functional test: PRIVATE dataset bound to a suite returns the suite summary (visibility does not block) (done: deterministic assertion)

## 6. Spec sync + verification

- [x] 6.1 Sync the delta spec into `openspec/specs/datasets/spec.md` (done: new requirements present in the main datasets spec via `/opsx:sync` or archive)
- [x] 6.2 Run `./gradlew spotlessApply checkstyleMain checkstyleTest` (done: formatting + style clean)
- [x] 6.3 Run the full relevant test suite `./gradlew test` (done: build green, including `LayeredArchitectureTest`, `JdbcTemplateFenceTest`, `LoggingConventionTest`)
