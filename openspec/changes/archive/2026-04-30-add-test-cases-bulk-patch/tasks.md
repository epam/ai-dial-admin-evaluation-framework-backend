## 1. Configuration

- [x] 1.1 Add `TestCaseBulkProperties` nested class inside `configuration.properties.testcase.TestCaseProperties` (fields: `maxOperations`, `maxIdsPerSelector`, `maxItemOperations`) with `@Validated`, `@Min`; no Java default initializers. The bulk-patch field whitelist is NOT a configuration property — it lives in code (see task 5.4). (done: class compiles, bound at `test-case.bulk`)
- [x] 1.2 Add `test-case.bulk.max-operations=512`, `test-case.bulk.max-ids-per-selector=10000`, `test-case.bulk.max-item-operations=500` to `src/main/resources/application.yml`. (done: properties present, no duplicates; default `max-operations` ≥ default `max-item-operations`)
- [x] 1.3 Update `docs/configuration.md` with one row per new property (three rows), all six columns (`Property | Environment Variable | Default | Required | Applied when | Description`). (done: rows added under the `test-case.*` group)

## 2. Web-layer DTOs

- [x] 2.1 Create `service.domain.dto.testcase.bulk.TestCaseBulkSelectorDto` (fields: `List<UUID> ids`, `List<String> filter`) with Lombok `@Data` and bean-validation annotations for size caps (use config-driven `@Size` where possible; otherwise validate in `TestCaseBulkPatchValidator`). (done: class compiles, serialization round-trips)
- [x] 2.2 Create `service.domain.dto.testcase.bulk.TestCaseBulkOperationDto` (fields: `TestCaseBulkSelectorDto selector`, `Map<String,Object> patch`) with `@Valid` cascading. (done: class compiles)
- [x] 2.3 Create `service.domain.dto.testcase.bulk.TestCaseItemOperationDto` (fields: `UUID id` (@NotNull), `Map<String,Object> patch`). (done: class compiles)
- [x] 2.4 Create `service.domain.dto.testcase.bulk.TestCaseBulkPatchRequestDto` (fields: `List<TestCaseBulkOperationDto> bulkOperations`, `List<TestCaseItemOperationDto> itemOperations`) with `@Valid` cascading. (done: class compiles)
- [x] 2.5 Create `service.domain.dto.testcase.bulk.TestCaseBulkPatchResponseDto` with nested `BulkResultDto(int opIndex, long matched, long updated)` and `ItemResultDto(UUID id, boolean updated)`. (done: class compiles)
- [x] 2.6 Add `@Schema` `example` attributes and one or more `@ExampleObject` entries on DTO fields to seed OpenAPI. (done: swagger-ui shows example bodies)

## 3. Service-layer validator

- [x] 3.1 Create `service.domain.TestCaseBulkPatchValidator` (`@Component`, `@LogExecution`) with constructor-injected `TestCaseProperties`. (done: class compiles)
- [x] 3.2 Implement `validate(TestCaseBulkPatchRequestDto request)`: empty body rejection; `bulkOperations.length + itemOperations.length ≤ maxOperations`; `itemOperations.length ≤ maxItemOperations`; each `selector` has exactly one of `ids` / `filter`; each `selector.ids.length ≤ maxIdsPerSelector` and has no duplicates; each `bulkOperations[i].patch` keys ⊆ `BULK_PATCH_FIELD_TO_COLUMN.keySet()` (the code-defined whitelist — see task 5.4); `itemOperations` has no duplicate `id`. Throws `ValidationException`. (done: unit tests cover every branch)
- [x] 3.3 Unit tests `TestCaseBulkPatchValidatorTest` — one `shouldDoX_whenY` method per validator rule; covers accept cases AND reject cases. (done: `./gradlew test --tests "*TestCaseBulkPatchValidatorTest"` green)

## 4. Service-layer selector resolver

- [x] 4.1 Create `service.domain.TestCaseBulkSelectorResolver` (`@Component`, `@LogExecution`) with constructor-injected `TestCaseRepository`, `FilterParser`, `TestCaseProperties`. (done: class compiles)
- [x] 4.2 Implement `resolve(UUID testSuiteId, TestCaseBulkSelectorDto selector) → List<UUID>`:
  - `ids` branch: verify every id belongs to `testSuiteId` via a membership-check repository call; throw `EntityNotFoundException` on miss.
  - `filter` branch: parse to `List<FilterCondition>`, call `testCaseRepository.findIdsByTestSuiteIdAndFilter(testSuiteId, filters, maxIdsPerSelector + 1)`, throw `ValidationException` when returned size exceeds `maxIdsPerSelector`.
  - Wrap `InvalidFilterException` (thrown from `WhereBuilder` inside the repository when an unknown / non-whitelisted filter field or operator is used) in `FilterValidationException` per project convention. `DefaultExceptionHandler` only maps `FilterValidationException` to HTTP 400; an unwrapped `InvalidFilterException` would surface as an unmapped 500. Mirror the `try { ... } catch (InvalidFilterException ex) { throw new FilterValidationException(...); }` pattern already used in `TestSuiteService` and `TestSuiteMetricDefinitionService`.
  (done: behaves per spec scenarios)
- [x] 4.3 Unit tests `TestCaseBulkSelectorResolverTest` — mock repository and filter parser; cover happy paths, over-cap rejection, cross-suite id rejection, AND a case where the repository throws `InvalidFilterException` and the resolver re-throws `FilterValidationException` (asserting the wrap). (done: tests green)

## 5. Data-layer repository additions

- [x] 5.1 Add to `data.db.repository.TestCaseRepository` interface: `int updateFieldsByIds(UUID testSuiteId, List<UUID> ids, Map<String,Object> setClause, long updatedAtMs)` and `List<UUID> findIdsByTestSuiteIdAndFilter(UUID testSuiteId, List<FilterCondition> filters, int limit)` and `List<UUID> findExistingIdsInSuite(UUID testSuiteId, List<UUID> ids)`. (done: interface compiles)
- [x] 5.2 Implement the three methods in `data.db.repository.PostgresTestCaseRepository`:
  - `updateFieldsByIds` uses a parameterised `UPDATE test_cases SET <cols>=:v..., updated_at_ms=:ts WHERE test_suite_id=:ts_id AND id = ANY(:ids::uuid[]) AND (<col1> IS DISTINCT FROM :v1 OR <col2> IS DISTINCT FROM :v2 ...)`. The `IS DISTINCT FROM` predicate is REQUIRED so the JDBC affected-row count reflects rows whose state actually changed (PostgreSQL otherwise rewrites the row even for a no-op SET, which would inflate `updated` and contradict the "Bulk op no-op for already-matching state" scenario in the spec). Build the SET clause from the whitelisted keys only (allowlist sourced from the service layer; repository validates via the canonical `BULK_PATCH_FIELD_TO_COLUMN` map — see task 5.4). Bind ids via `Types.ARRAY` / `createArrayOf("uuid", ...)` or `unnest(:ids::uuid[])`. Returns affected row count.
  - `findIdsByTestSuiteIdAndFilter` uses `WhereBuilder` + `FilterWhitelists.TEST_CASES` to add filter conditions; selects `id` only from `test_cases` with `WHERE test_suite_id = :testSuiteId AND <filter-where>` and `LIMIT :limit`. The suite-id constraint MUST be added by the repo (it is not part of `FilterWhitelists.TEST_CASES`).
  - `findExistingIdsInSuite` returns `id` rows intersecting the given set with the suite, used for id-selector membership checks. (done: SQL text blocks compile, queries run)
- [x] 5.3 Functional integration coverage: via `PostgresTestCaseRepository` exercised by the functional tests in §8; no standalone repo tests required.
- [x] 5.4 Define a single canonical immutable mapping `BULK_PATCH_FIELD_TO_COLUMN` (e.g., `Map.of("enabled", "is_enabled")`) — the recommended location is a small constants holder reachable from both `service.domain` and `data.db` layers (e.g., `service.domain.testcase.bulk.BulkPatchFields`), so `TestCaseBulkPatchValidator` and `PostgresTestCaseRepository` reference the same source. The map's key set IS the bulk-patch whitelist. There is no parallel config property, hence no startup drift check is needed. Adding a future field is a one-line change here plus the corresponding validator/test updates. (done: constant defined, both validator and repo reference it, unit test asserts the validator rejects keys outside `BULK_PATCH_FIELD_TO_COLUMN.keySet()`)

## 6. Service orchestration

- [x] 6.1 Add method `bulkPatch(UUID testSuiteId, TestCaseBulkPatchRequestDto request, boolean includeWarnings)` on `TestCaseService` annotated `@Transactional("metaTransactionManager")`. (done: method compiles)
- [x] 6.2 Inside `bulkPatch`: call `bulkPatchValidator.validate(request)` FIRST (before any DB calls — caps, whitelist, duplicate ids must all reject without touching the database); then verify suite exists (`testSuiteRepository.findById` or `ensureTestSuiteExists`); get `updatedAt = transactionTimestampContext.getTimestamp()`. (done: a unit/functional test verifies that an oversized request is rejected before any repository method is invoked)
- [x] 6.3 Implement the `bulkOperations` loop: resolve selector → ids via `bulkSelectorResolver`; call `testCaseRepository.updateFieldsByIds(...)`; collect `matched` (ids.size()) and `updated` (affected rows). Record `BulkResultDto`. (done: counts match expectations in tests)
- [x] 6.4 Implement the `itemOperations` loop: for each item fetch the row post-bulk, reuse `applyMergePatch(...)` + `validateOverrideLimitsOnEntity(...)`; if the patch touched any of `{data, requestTemplateOverride, inputBindingsOverride, testCaseName}` run `runValidation(...)`; `testCaseRepository.update(...)`; record `ItemResultDto`. (done: per-row validation decision is correct per spec)
- [x] 6.5 Add name-uniqueness final-state guard: if any op resulted in a `testCaseName` change, reuse `validateBatchNameUniqueness(...)` on the union of final-state entities touched by the request. (done: unique-name scenarios raise 409 and roll back)
- [x] 6.6 Translate exceptions consistently: `DataIntegrityViolationException` → `UniqueConstraintViolationDetector`; selector `EntityNotFoundException` → HTTP 404 via the existing handler. (done: functional tests verify status codes)

## 7. Web-layer controller & OpenAPI

- [x] 7.1 Add handler method `bulkPatch(...)` for `PATCH /api/v1/test-suites/{testSuiteId}/test-cases:bulk` (note the colon segment). Body: `@Valid @RequestBody TestCaseBulkPatchRequestDto request`. Optional `?includeWarnings=false` (currently unused but kept for forward-compat with item re-validation). **Implementation deviation**: implemented as a dedicated `web.controller.TestCaseBulkPatchController` rather than a method on the existing `TestCaseController`. Spring concatenates class- and method-level `@RequestMapping` paths with a `/` separator, so the colon-segment path `/test-cases:bulk` cannot be expressed as a method-level path under a class-level `/api/v1/test-suites/{testSuiteId}` mapping; a dedicated controller carrying the full path on the method-level mapping is the cleanest workaround. Rationale documented in the controller javadoc. (done: endpoint reachable, 200/400/404/409 paths exercised)
- [x] 7.2 Add `@Operation`, `@ApiResponse` (200/400/404/409), and request-body `@Content` with `@Schema(implementation = TestCaseBulkPatchRequestDto.class)` plus `@ExampleObject[]`. (done: Swagger-UI shows the operation and examples)
- [x] 7.3 Add two OpenAPI example files under `src/main/resources/openapi/examples/`:
  - `test-cases-bulk-deselect-all.json` — `bulkOperations: [{selector:{filter:[]}, patch:{enabled:false}}, {selector:{ids:[...]}, patch:{enabled:true}}]`, empty `itemOperations`.
  - `test-cases-bulk-mixed.json` — one bulk op + three item ops with `testCaseName` / `data` / `requestTemplateOverride`.
  (done: files exist, referenced from controller annotations)

## 8. Functional tests

- [x] 8.1 Create base class `functional.tests.BaseTestCaseBulkPatchFunctionalTests` (abstract) with seed helpers via `MetaTestDataHelper` for a large suite (parameterisable size). Do NOT inject `JdbcTemplate` directly. (done: helper compiles)
- [x] 8.2 Add nested functional test classes under `PostgresFunctionalTests`. Cover: happy-path composite (bulk+item), bulk-only, item-only, empty body → 400, cap violations (`max-operations`, `max-item-operations`, `max-ids-per-selector` for `ids` and filter-resolved), whitelist violation → 400, selector XOR violation → 400, duplicate id in selector / item → 400, cross-suite id → 404, filter-selector over-match → 400, 10k-row `enabled=false` flip happy path (seed 10k, assert `matched=10000, updated=10000`, verify DB state), `enabled` flip skips re-validation (seed row with intentionally broken `data` marked `valid=false`; flip enabled; assert `valid` unchanged), atomic rollback on item op collision (409 for uniqueness + assert all rows unchanged), last-writer-wins where an item op overrides a bulk op. **Layout**: scenarios that fit at default caps live in `TestCaseBulkPatchFunctionalTests`; scenarios that need lowered caps to be triggerable without seeding tens of thousands of rows (filter-selector over-match, combined-op-count over-cap) live in a sibling `TestCaseBulkPatchCapsFunctionalTests` whose nested wrapper carries `@TestPropertySource(properties = {"test-case.bulk.max-operations=3", "test-case.bulk.max-ids-per-selector=3"})`. (done: `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$TestCaseBulkPatchTests" --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$TestCaseBulkPatchCapsTests"` green)
- [x] 8.3 Verify ArchUnit `LayeredArchitectureTest` still passes (no new package violations). (done: `./gradlew test --tests "*LayeredArchitectureTest"` green)
- [x] 8.4 Verify existing `TestCaseBatchPatchFunctionalTests` still passes without modification — confirming the new endpoint is additive. (done: tests green)

## 9. Quality gates

- [x] 9.1 Run `./gradlew checkstyleMain checkstyleTest` and fix any violations. (done: 0 warnings)
- [x] 9.2 Run `./gradlew clean build`. (done: green)
- [x] 9.3 Manual Swagger-UI check: start app, visit `/swagger-ui.html`, verify `PATCH /api/v1/test-suites/{testSuiteId}/test-cases:bulk` is documented with examples. (done: visually verified)

## 10. Spec / docs sync

- [x] 10.1 Update `openspec/specs/README.md` only if the `test-cases` one-line summary becomes inaccurate after archive. (done: index accurate — likely no change needed since this is additive within existing capability)
- [x] 10.2 Confirm AGENTS.md does NOT need updating — this change adds an endpoint following existing patterns and introduces no new top-level package / qualifier / pattern / convention. (done: decision recorded; no edit required)
