## 1. Database migration

- [x] 1.1 Add Flyway migration `src/main/resources/db/migration/meta/POSTGRES/V1.21__AddCoercedCellCountToRevalidationTasks.sql` adding `coerced_cell_count BIGINT NOT NULL DEFAULT 0` to `revalidation_tasks` (done: migration runs cleanly against an empty and a non-empty table; existing rows show 0)
- [x] 1.2 Update `docs/database-schema.md` `revalidation_tasks` section to include the new column (done: new column documented with type and default)

## 2. Data layer

- [x] 2.1 Add `Long coercedCellCount` field to `data.db.model.RevalidationTask` (Lombok `@Builder.Default` to `0L`) (done: field present, getters/setters generated)
- [x] 2.2 Update `data.db.mapper.RevalidationTaskRowMapper` to read `coerced_cell_count` from result set (done: column read into model)
- [x] 2.3 Update `data.db.repository.PostgresRevalidationTaskRepository` insert/update SQL to include the new column (done: inserts/updates persist the value)
- [x] 2.4 Add `int updateDataIfUnchanged(UUID id, UUID testSuiteId, String dataJson, long expectedUpdatedAt, long newUpdatedAt)` to `data.db.repository.TestCaseRepository` and implement in `PostgresTestCaseRepository` using a `WHERE id = :id AND test_suite_id = :testSuiteId AND updated_at = :expectedUpdatedAt` precondition (done: method returns 0 on guard miss, 1 on success — verified by unit test)
- [x] 2.5 Add `int updateValidationIfUnchanged(UUID id, UUID testSuiteId, boolean isValid, String warningsJson, long expectedUpdatedAt, long newUpdatedAt)` mirroring 2.4 (done: method returns 0/1; existing unguarded `updateValidation` left intact for CSV import path)

## 3. Coercer component

- [x] 3.1 Create `service.domain.csv.SchemaChangeCoercer` (`@Component`, `@LogExecution`) with `Object coerce(Object value, SchemaFieldType targetType)` implementing the strict conversion table from `design.md` (done: every cell of the table covered by an explicit branch; identity for matching types and `null`)
- [x] 3.2 Add `CoercionResult coerceMap(Map<String,Object> data, List<FieldDefinitionDto> schema)` returning `(coercedData, coercedCellCount, changed)` to the same component (done: counts per-cell, `changed=true` only when at least one value differs by reference or value)
- [x] 3.3 Write `SchemaChangeCoercerTest` in `src/test/java/.../service/domain/csv/SchemaChangeCoercerTest.java` covering: every positive coercion (10 pairs from the table), every "skip" pair (Integer→BOOLEAN, Boolean→INTEGER/NUMBER, non-String→FILE, Object/Array→STRING), `null` identity for every target type, fractional Double→INTEGER skip, idempotency on already-matching types, and `coerceMap` accumulating cell count across multiple fields (done: `./gradlew test --tests "*SchemaChangeCoercerTest"` passes)

## 4. Service orchestration

- [x] 4.1 Inject `SchemaChangeCoercer` into `service.domain.RevalidationService` via constructor (done: bean wired)
- [x] 4.2 Modify `RevalidationService.runRevalidationAsync` per-row loop: capture `seenAt = tc.updatedAt`, run `coerceMap`, persist data via `updateDataIfUnchanged` only if `result.changed`, validate post-coercion data, persist validation via `updateValidationIfUnchanged`, accumulate `coercedCellCount` and `skippedCount` (done: flow matches the design.md sequence including guard-miss handling)
- [x] 4.3 Persist `coercedCellCount` on the `RevalidationTask` per batch (alongside the existing `processedCases`/`validCount`/`invalidCount` updates) (done: counter visible via task GET while task is RUNNING)
- [x] 4.4 Log per-task summary at completion: `Revalidated suite={} task={}: total={} valid={} invalid={} coerced_cells={} skipped={}` (done: log line emitted at INFO; per-row guard-miss at DEBUG)

## 5. DTO and API surface

- [x] 5.1 Add `Long coercedCellCount` to `service.domain.dto.RevalidationTaskDto` with `@Schema(example = "42", description = "Total number of (row, field) cells auto-coerced during this revalidation. Counts cells, not rows.")` annotation (done: field serializes in JSON responses)
- [x] 5.2 Map `coercedCellCount` in `RevalidationService.toDto` (done: DTO populated from model)
- [x] 5.3 Update OpenAPI example files for revalidation responses under `src/main/resources/openapi/examples/` (or inline `@ExampleObject` if used) to include `coercedCellCount` (done: Swagger UI shows new field with example value)

## 6. Tests

- [x] 6.1 In `src/test/java/com/epam/aidial/evaluation/functional/PostgresFunctionalTests.java` add a nested `RevalidationCoercionTests` block (or extend existing revalidation tests) covering: Boolean→STRING happy path (cells coerced, row valid, `coercedCellCount > 0`); Integer→STRING and Number→STRING; Object→STRING (skipped, row invalid); Number→FILE (skipped, row invalid); String→BOOLEAN with both `"true"` and `"yes"` (one coerced, one invalid); fractional Double→INTEGER skip (done: `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$RevalidationCoercionTests"` passes)
- [x] 6.2 Add a concurrency test: simulate a PATCH between read and update by directly bumping `updated_at` on the test case after revalidation reads it; assert that the row's `data` and validation state are NOT mutated by revalidation, and that `coercedCellCount` reflects only completed rows (done: deterministic test using direct repository helpers, no flaky timing)
- [x] 6.3 Add an idempotency test: run revalidation twice in succession against the same coerced suite; assert second run yields `coercedCellCount = 0` and no `data` UPDATEs (verify via spy on repository or by asserting `updated_at` unchanged on rows that didn't need re-coercion) (done: assertion on coercedCellCount and updated_at)

## 7. Documentation and conventions

- [x] 7.1 Update AGENTS.md "Unique Patterns" section to add a short paragraph contrasting `SchemaTypeCoercer` (CSV import, permissive) with `SchemaChangeCoercer` (schema-change revalidation, strict), per AGENTS.md Maintenance guidelines (done: both classes listed, table differences summarized in 2-3 sentences)
- [x] 7.2 Verify `./gradlew checkstyleMain checkstyleTest` passes for the new files (done: no Checkstyle violations)
- [x] 7.3 Verify `./gradlew test` passes end-to-end (done: full suite green)
