## 1. Configuration

- [x] 1.1 Add `maxDeleteIds` field (with `@Min(1)`) to `TestCaseProperties.Bulk` inner class in `configuration/properties/testcase/TestCaseProperties.java` (done: field present, `@Min(1)` annotation applied, no Java field initializer)
- [x] 1.2 Add `max-delete-ids: 10000` under `test-case.bulk` in `src/main/resources/application.yml` (done: property present at correct YAML path with value 10000)

## 2. DTOs

- [x] 2.1 Create `TestCaseBulkDeleteRequestDto` in `service/domain/dto/testcase/bulk/` with a `List<UUID> ids` field and `@Schema` description (done: class present, Lombok `@Data @Builder @NoArgsConstructor @AllArgsConstructor`, OpenAPI `@Schema` annotation)
- [x] 2.2 Create `TestCaseBulkDeleteResponseDto` in `service/domain/dto/testcase/bulk/` with `List<UUID> deleted` and `List<UUID> notFound` fields and `@Schema` descriptions (done: class present with both fields annotated)

## 3. Validator

- [x] 3.1 Create `TestCaseBulkDeleteValidator` `@Component @LogExecution` in `service/domain/` with a single `validate(TestCaseBulkDeleteRequestDto)` method that enforces: not-null/non-empty `ids`, size ≤ `testCaseProperties.getBulk().getMaxDeleteIds()`, no null elements, no duplicate UUIDs (done: class present, all four checks throw `ValidationException` on failure)

## 4. Repository

- [x] 4.1 Add `List<UUID> deleteByIdsAndDatasetId(UUID datasetId, List<UUID> ids)` to `TestCaseRepository` interface in `data/db/repository/` (done: method signature present)
- [x] 4.2 Implement `deleteByIdsAndDatasetId` in `PostgresTestCaseRepository` using jOOQ `dsl.deleteFrom(TEST_CASES).where(...).returningResult(TEST_CASES.ID).fetch()`, mapping returned strings to UUIDs (done: implementation uses typed jOOQ DSL with RETURNING clause, no SQL strings)

## 5. Service

- [x] 5.1 Inject `TestCaseBulkDeleteValidator` into `TestCaseService` via constructor (done: field present in constructor, no `@Autowired` on field)
- [x] 5.2 Add `@Transactional("metaTransactionManager") bulkDelete(UUID datasetId, TestCaseBulkDeleteRequestDto request)` method to `TestCaseService`: call `ensureDatasetExists`, call `bulkDeleteValidator.validate`, call `testCaseRepository.deleteByIdsAndDatasetId`, compute `deleted` and `notFound` lists by filtering input IDs against a `HashSet` of returned IDs, return `TestCaseBulkDeleteResponseDto` (done: method present, transaction annotation correct, both lists preserve input ordering)

## 6. Controller

- [x] 6.1 Create `TestCaseBulkDeleteController` — a dedicated `@RestController @LogExecution @Validated @RequiredArgsConstructor` class with a single `@DeleteMapping("/api/v1/datasets/{datasetId}/test-cases:bulk")` method accepting `@Valid @RequestBody TestCaseBulkDeleteRequestDto` and returning `TestCaseBulkDeleteResponseDto` (done: class present, follows `TestCaseBulkPatchController` pattern, full OpenAPI `@Operation`, `@ApiResponse` annotations for 200/400/404)

## 7. Tests

- [x] 7.1 Create unit test `TestCaseBulkDeleteValidatorTest` covering: null request → exception, empty `ids` → exception, `ids` count exceeds cap → exception, null element in list → exception, duplicate UUID → exception, valid list → no exception (done: all six cases present, `./gradlew test --tests "*.TestCaseBulkDeleteValidatorTest"` passes)
- [x] 7.2 Add nested class `BulkDeleteByIds` to the existing test cases functional test class with scenarios: all-found, mixed (partial), all-not-found, dataset-not-found (HTTP 404), empty-ids (HTTP 400), duplicate-ids (HTTP 400), cap-exceeded (HTTP 400) — verify DB state via repository assertions after deletion (done: `./gradlew test --tests "*TestCaseFunctionalTests*"` passes)

## 8. Documentation and Spec Sync

- [x] 8.1 Add a row to `docs/configuration.md` for `test-case.bulk.max-delete-ids` / `TEST_CASE_BULK_MAX_DELETE_IDS` with default `10000`, required `No`, applied-when `Always`, description `Maximum number of IDs accepted in a single bulk-delete-by-IDs request` (done: row present in the correct table section)
- [x] 8.2 Update `openspec/specs/README.md` per Spec Index Maintenance Policy — add entry for `test-case-bulk-delete-by-ids` spec under TestCases area (done: entry present and accurate)
