## 1. Enums and DTO

- [x] 1.1 Create `CsvImportMode` enum (`OVERRIDE`, `APPEND`, `MERGE`) in `service.domain.dto.csv` package
- [x] 1.2 Create `CsvConflictStrategy` enum (`FAIL`, `SKIP`, `OVERRIDE`) in `service.domain.dto.csv` package
- [x] 1.3 Extend `CsvImportResultDto` with nullable `skippedCount` (Integer) and `overriddenCount` (Integer) annotated with `@JsonInclude(NON_NULL)`

## 2. Repository Layer — Upsert Methods

- [x] 2.1 Add `insertOrSkip(TestCase)` to `TestCaseRepository` — returns int (1 = inserted, 0 = skipped)
- [x] 2.2 Implement `insertOrSkip` in `PostgresTestCaseRepository` — `INSERT ... ON CONFLICT (test_suite_id, LOWER(test_case_name)) DO NOTHING`; return `jdbcTemplate.update()` result
- [x] 2.3 Add `insertOrOverride(TestCase)` to `TestCaseRepository` — returns boolean (true = existing row was replaced)
- [x] 2.4 Implement `insertOrOverride` in `PostgresTestCaseRepository` — `INSERT ... ON CONFLICT (test_suite_id, LOWER(test_case_name)) DO UPDATE SET ... RETURNING (xmax <> 0)::int AS was_update`; use `jdbcTemplate.queryForObject("...", params, Integer.class)` (NOT `jdbcTemplate.update()` — `update()` discards RETURNING results and cannot distinguish insert from override)
- [x] 2.5 Add `updateTestCaseSchema(UUID id, String schemaJson)` to `TestSuiteRepository`
- [x] 2.6 Implement `updateTestCaseSchema` in `PostgresTestSuiteRepository` — `UPDATE test_suites SET test_case_schema = :schemaJson, version = version + 1, updated_at_ms = :updatedAt WHERE id = :id`
- [x] 2.7 Add `removeDataFields(UUID testSuiteId, Collection<String> fieldNames)` to `TestCaseRepository`
- [x] 2.8 Implement `removeDataFields` in `PostgresTestCaseRepository` — `UPDATE test_cases SET data = data - :fields::text[] WHERE test_suite_id = :testSuiteId`; bind the field names as a PostgreSQL array using `new SqlParameterValue(Types.ARRAY, fieldNames.toArray(new String[0]))` — do NOT pass as a plain `Collection` (NamedParameterJdbcTemplate cannot auto-convert `Collection<String>` to `text[]` and will throw a runtime `PSQLException`)

## 3. Service Layer — CSV Import Mode and Schema

- [x] 3.1 Add `CsvImportMode` and `CsvConflictStrategy` parameters to `CsvImportService.importCsv()` signature
- [x] 3.2 Implement APPEND/MERGE mode: skip `deleteAllByTestSuiteId` when mode is not `OVERRIDE`
- [x] 3.3 Implement unknown column filtering in `parseRow`: only when `mode == APPEND` AND schema is non-empty, discard columns not present in `fieldTypes`; in OVERRIDE and MERGE modes all CSV data columns are stored (no filtering) — guard: `if (mode != APPEND || schemaEmpty || fieldTypes.containsKey(b.fieldName()))`
- [x] 3.4 Implement OVERRIDE schema logic: always auto-detect schema from CSV using **incremental type inference** (maintain `Map<String, SchemaFieldType> inferredTypes` updated per row via `widenType()` — do NOT use `collectRawDataColumns()` from preview, which holds all values in memory); after streaming completes, call `updateTestCaseSchema` with the full auto-detected schema (replaces any existing schema)
- [x] 3.5 Implement APPEND schema logic: when suite schema is empty, apply same incremental type inference as 3.4 during streaming; call `updateTestCaseSchema` only after streaming completes; when suite schema exists, skip schema detection entirely
- [x] 3.6 Implement MERGE schema logic: track columns not in existing schema during streaming using incremental type inference; after streaming completes, if new fields found call `updateTestCaseSchema` with merged schema (existing fields + new fields); if no new fields, skip the update and do NOT bump version

## 4. Service Layer — Conflict Strategy

- [x] 4.1 Implement FAIL strategy: use existing `save()` path for all import modes; DB constraint fires on the first collision (cross-import or within-CSV), `UniqueConstraintViolationDetector` converts it to application exception, transaction rolls back — no additional code needed beyond routing to `save()`; no in-memory name tracking required
- [x] 4.2 Route OVERRIDE import mode through the strategy-based insert path: after `deleteAllByTestSuiteId`, insert each CSV row using `save()` / `insertOrSkip()` / `insertOrOverride()` based on `conflictStrategy`, same as APPEND/MERGE modes; since existing rows are deleted first, only within-CSV duplicates can trigger conflicts — these are handled by the same DB mechanisms as cross-import collisions; no `LinkedHashSet` or special deduplication logic needed
- [x] 4.3 Implement SKIP strategy for all import modes: use `insertOrSkip` per row; accumulate `skippedCount` when return value is 0 (covers both cross-import and within-CSV collisions)
- [x] 4.4 Implement OVERRIDE (conflict) strategy for all import modes: use `insertOrOverride` per row; accumulate `overriddenCount` when return value is true (covers both cross-import and within-CSV collisions)
- [x] 4.5 Set `skippedCount`/`overriddenCount` in result DTO; leave null only for `conflictStrategy=FAIL` (regardless of import mode)

## 5. Service Layer — Schema Cleanup on TestSuite Update

- [x] 5.1 In `TestSuiteService.update()`, compute `removedFields` = field names in old schema that are absent from new schema (compute BEFORE calling `testSuiteMapper.update()` since that mutates `existing`)
- [x] 5.2 When `removedFields` is non-empty, call `testCaseRepository.removeDataFields(suiteId, removedFields)` within the same transaction
- [x] 5.3 No change to `isSchemaChanged` is required — `removedFields` is computed directly inline in `update()` BEFORE mapper mutation (task 5.1). `isSchemaChanged` keeps its current boolean signature; the pre-mutation `removedFields` set computed in 5.1 is what gets passed to `removeDataFields()` in 5.2
- [x] 5.4 Add `TestCaseRepository` as an injected dependency in `TestSuiteService` (via `@RequiredArgsConstructor` field) — currently `TestSuiteService` only injects `TestSuiteRepository`

## 6. Service Layer — Preview

- [x] 6.1 Add `CsvImportMode` and `CsvConflictStrategy` parameters to `CsvImportService.preview()` signature
- [x] 6.2 Implement mode-aware preview schema: OVERRIDE always includes `autoDetectedSchema` (full replacement); APPEND includes only when schema is empty; MERGE includes delta fields only
- [x] 6.3 Implement conflict-aware preview: for APPEND/MERGE, collect all CSV names during streaming into a `List<String>`, then execute `SELECT test_case_name FROM test_cases WHERE test_suite_id = :suiteId AND LOWER(test_case_name) IN (:csvNamesLower)` after streaming; annotate per-row warnings based on `conflictStrategy` (e.g. "would be skipped", "would override existing 'CaseA'", "would fail with 409"); for OVERRIDE import mode no DB query is needed (no existing rows after delete) — only within-CSV duplicate annotations apply (see task 6.4)
- [x] 6.4 Implement within-CSV duplicate detection in preview (all modes): maintain a running `LinkedHashSet<String>` of lowercased names during streaming; if `add()` returns `false`, annotate the duplicate row with a strategy-appropriate warning — FAIL: "would cause import failure (409) — duplicate of earlier row with same name"; SKIP: "would be skipped — duplicate of earlier row with same name"; OVERRIDE: "would replace earlier row with same name (last wins)"; preview endpoint itself never throws 409 regardless of strategy

## 7. Controller Layer

- [x] 7.1 Add `importMode` query parameter (`CsvImportMode`, default `OVERRIDE`) to `importCsv` and `importPreview` endpoints in `TestCaseController`
- [x] 7.2 Add `conflictStrategy` query parameter (`CsvConflictStrategy`, default `FAIL`) to `importCsv` and `importPreview` endpoints
- [x] 7.3 Update OpenAPI `@Operation` summaries and `@Parameter` descriptions to document both parameters and the breaking OVERRIDE schema replacement behavior

## 8. Tests — Unit

- [x] 8.1 Unit tests for `insertOrSkip`, `insertOrOverride`, `updateTestCaseSchema`, `removeDataFields`
- [x] 8.2 Unit tests for schema merge logic: new columns added, no new columns (no bump), empty schema (full auto-detect)
- [x] 8.3 Unit tests for `parseRow` unknown column filtering: APPEND+schema non-empty → discarded; APPEND+schema empty → kept; MERGE+schema non-empty → new columns stored (not discarded); OVERRIDE → all columns stored
- [x] 8.4 Unit tests for `TestSuiteService` schema cleanup: removed fields passed to `removeDataFields`, empty removal set skips the call
- [x] 8.5 Unit tests for within-CSV collision behavior: FAIL strategy + within-CSV dup → DB constraint fires → 409 (no special dedup logic, same as cross-import collision); SKIP → first row kept, skippedCount incremented; OVERRIDE conflict strategy → last row wins via upsert, overriddenCount incremented

## 9. Tests — Functional

- [x] 9.1 OVERRIDE mode replaces existing schema even when non-empty
- [x] 9.2 OVERRIDE mode deletes all existing rows and inserts CSV rows (conflictStrategy=FAIL, no within-CSV dups)
- [x] 9.2a OVERRIDE import mode + SKIP strategy + within-CSV duplicates: deleteAll runs, CSV imported, within-CSV duplicate is silently skipped, `skippedCount=1`, no 409
- [x] 9.2b OVERRIDE import mode + OVERRIDE conflict strategy + within-CSV duplicates: deleteAll runs, CSV imported, within-CSV duplicate is upserted (last wins), `overriddenCount=1`, no 409
- [x] 9.3 APPEND mode appends rows, existing rows preserved
- [x] 9.4 APPEND + FAIL: first name collision returns HTTP 409, no rows imported
- [x] 9.5 APPEND + SKIP: skips colliding rows, returns skippedCount
- [x] 9.6 APPEND + OVERRIDE: replaces colliding rows, returns overriddenCount
- [x] 9.7 APPEND with empty schema: auto-detects and persists schema
- [x] 9.8 APPEND with existing schema: no schema change; unknown CSV columns not stored in data
- [x] 9.9 MERGE adds new schema fields and appends rows
- [x] 9.10 MERGE with no new columns: schema and version unchanged
- [x] 9.11 MERGE + SKIP: new columns added to schema, colliding rows skipped
- [x] 9.12 Preview OVERRIDE: always returns autoDetectedSchema
- [x] 9.13 Preview APPEND + SKIP: collision warnings show "would be skipped"
- [x] 9.14 Preview MERGE: autoDetectedSchema shows only delta fields
- [x] 9.15 FAIL strategy + within-CSV duplicates → 409: import a CSV with two rows sharing the same `testCaseName` (case-insensitive) using `conflictStrategy=FAIL` (any import mode); verify HTTP 409 is returned on the second occurrence; no rows committed
- [x] 9.15a SKIP strategy + within-CSV duplicates → first wins (APPEND/MERGE modes): import same CSV with `importMode=APPEND` and `conflictStrategy=SKIP`; verify only the first occurrence is stored; `skippedCount` reflects the deduplicated row(s); no 409 (see 9.2a for OVERRIDE import mode equivalent)
- [x] 9.15b OVERRIDE strategy + within-CSV duplicates → last wins (APPEND/MERGE modes): import same CSV with `importMode=APPEND` and `conflictStrategy=OVERRIDE`; verify the last occurrence is stored; `overriddenCount` reflects the replaced row(s); no 409 (see 9.2b for OVERRIDE import mode equivalent)
- [x] 9.15c Preview with within-CSV duplicates: verify preview annotates duplicate rows with strategy-appropriate warnings; no HTTP 409 from the preview endpoint itself
- [x] 9.16 Schema cleanup: removing a field from TestSuite schema removes that key from all TestCase data
- [x] 9.17 Schema cleanup: adding a field to TestSuite schema does not modify existing TestCase data
- [x] 9.18 Unknown CSV column not stored in data when APPEND mode with existing schema
- [x] 9.19 MERGE mode: new CSV columns (added to schema) ARE stored in imported testCase data; existing testCase data is NOT modified

## 10. OpenAPI and Documentation

- [x] 10.1 Update OpenAPI examples for import and preview endpoints to include `importMode` and `conflictStrategy` parameters
- [x] 10.2 Update `@Operation` summary on import endpoint (remove stale "replace-all" language, document breaking change)
