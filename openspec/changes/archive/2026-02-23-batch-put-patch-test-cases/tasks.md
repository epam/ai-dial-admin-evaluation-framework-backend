## 1. Configuration & DTO Layer

- [x] 1.1 Create `TestCaseProperties` configuration class with nested `Batch` class (`maxItems` field) under `configuration.properties.testcase` package; add `test-case.batch.max-items: 256` default in `application.yml`
- [x] 1.2 Create `TestCaseBatchPutItemDto` — extends `TestCaseRequestDto` fields plus `@NotNull UUID id`; add Jakarta validation annotations
- [x] 1.3 Update `docs/configuration.md` with `test-case.batch.max-items` property documentation

## 2. Repository Layer

- [x] 2.1 Add `findAllByIdsAndTestSuiteId(Collection<UUID> ids, UUID testSuiteId)` method to `TestCaseRepository` interface and `PostgresTestCaseRepository` — single `SELECT ... WHERE id IN (:ids) AND test_suite_id = :suiteId` query returning `List<TestCase>`
- [x] 2.2 Add `batchUpdate(List<TestCase>)` method to `TestCaseRepository` interface and `PostgresTestCaseRepository` — uses `NamedParameterJdbcTemplate.batchUpdate(UPDATE_SQL, SqlParameterSource[])` for JDBC batching; reuses existing `UPDATE_SQL` and `buildParams()`; sets `updatedAt` from `TransactionTimestampContext`
- [x] 2.3 Add `findCollidingNamesByTestSuiteIdExcludingIds(UUID testSuiteId, Collection<UUID> excludeIds, Collection<String> lowercasedNames)` method to `TestCaseRepository` — `SELECT LOWER(test_case_name) FROM test_cases WHERE test_suite_id = :suiteId AND id NOT IN (:excludeIds) AND LOWER(test_case_name) IN (:names)` — returns only the colliding names for targeted uniqueness checking

## 3. Service Layer — Batch Update (PUT)

- [x] 3.1 Add `batchUpdate(UUID testSuiteId, List<TestCaseBatchPutItemDto> items, boolean includeWarnings)` method to `TestCaseService`: validate batch size (from `TestCaseProperties`), validate no duplicate IDs, fetch suite once (`testSuiteRepository.findById`), batch-fetch all existing test cases by IDs (verify all found or 404), apply updates per item, validate final-state name uniqueness (within batch + targeted collision check against DB via `findCollidingNames`), validate override limits per item, run schema validation per item (pass the pre-fetched suite's parsed schema/template/bindings — do NOT call existing `runValidation()` which re-fetches the suite on every call; extract a shared helper or overload that accepts pre-parsed suite context), wrap `repository.batchUpdate(entities)` in try-catch for `DataIntegrityViolationException` with `UniqueConstraintViolationDetector` (same pattern as single-item `update()`), build UUID→input-position map from request to reorder batch-fetch results into input order, return ordered list of `TestCaseResponseDto`

## 4. Service Layer — Batch Patch (PATCH)

- [x] 4.1 Add `batchPatch(UUID testSuiteId, List<Map<String, Object>> items, boolean includeWarnings)` method to `TestCaseService`: extract and validate `id` from each map (must be present, valid UUID), validate batch size, validate no duplicate IDs, fetch suite once (`testSuiteRepository.findById`), batch-fetch all existing test cases by IDs (verify all found or 404), apply merge patch per item, compute final names (new name if patched, current name if not), validate final-state name uniqueness (within batch + targeted collision check against DB via `findCollidingNames`), validate override limits per item, run schema validation per item (reuse same pre-fetched suite context helper from 3.1 — avoid N suite re-fetches), wrap `repository.batchUpdate(entities)` in try-catch for `DataIntegrityViolationException` with `UniqueConstraintViolationDetector`, build UUID→input-position map for response ordering, return ordered list of `TestCaseResponseDto`

## 5. Controller Layer

- [x] 5.1 Add batch PUT endpoint to `TestCaseController`: `@PutMapping` on collection path, accepts `@Valid @RequestBody List<TestCaseBatchPutItemDto>`, `@RequestParam includeWarnings`, delegates to `testCaseService.batchUpdate()`; add OpenAPI annotations
- [x] 5.2 Add batch PATCH endpoint to `TestCaseController`: `@PatchMapping` on collection path, accepts `@RequestBody List<Map<String, Object>>`, `@RequestParam includeWarnings`, delegates to `testCaseService.batchPatch()`; add OpenAPI annotations

## 6. OpenAPI Examples

- [x] 6.1 Add OpenAPI JSON example files for batch PUT request (minimal + full) and response under `src/main/resources/openapi/examples/`
- [x] 6.2 Add OpenAPI JSON example files for batch PATCH request (minimal + full) and response under `src/main/resources/openapi/examples/`

## 7. Functional Tests — Batch PUT

- [x] 7.1 Add batch PUT happy path tests: successful batch update of multiple items, response ordering matches input, validation warnings included/excluded per `includeWarnings` param
- [x] 7.2 Add batch PUT error tests: empty array → 400, exceeds max items → 400, duplicate IDs → 400, non-existent test case ID → 404, non-existent suite → 404
- [x] 7.3 Add batch PUT uniqueness tests: duplicate names within batch → 409, name collision with existing test case outside batch → 409
- [x] 7.4 Add batch PUT atomicity test: when one item fails (e.g., name collision), verify no items were persisted (all rolled back)

## 8. Functional Tests — Batch PATCH

- [x] 8.1 Add batch PATCH happy path tests: successful partial update of multiple items, merge-patch semantics (data merge, override clear with null), response ordering
- [x] 8.2 Add batch PATCH error tests: empty array → 400, exceeds max items → 400, duplicate IDs → 400, missing `id` field → 400, invalid UUID → 400, non-existent test case → 404
- [x] 8.3 Add batch PATCH uniqueness tests: duplicate names within batch → 409, name collision with existing test case outside batch → 409, item renames to current name of another batch item that isn't changing name → 409
- [x] 8.4 Add batch PATCH atomicity test: when one item fails, verify no items were persisted
