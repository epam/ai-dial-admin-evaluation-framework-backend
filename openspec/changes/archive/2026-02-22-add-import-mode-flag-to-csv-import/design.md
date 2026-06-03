## Context

CSV import (`POST .../test-cases/import` and `.../import/preview`) currently operates in replace-all mode: `deleteAllByTestSuiteId` runs before inserting rows. Schema auto-detection runs in preview only and is not persisted to the suite. There is no way to add rows to an existing suite without wiping the current data, no way to evolve a schema incrementally, and no control over what happens on name collisions.

Current flow:
1. Controller receives file + delimiter + optional `If-Match`
2. `CsvImportService.importCsv` → fetches suite, checks `If-Match` version, deletes all test cases, parses CSV, batch-inserts rows
3. Auto-detect in current code: preview only, schema not persisted

`TestSuite.version` is a `BIGINT` (default 0) bumped via `version = version + 1` on any `UPDATE test_suites` statement. It is used exclusively for optimistic locking (`If-Match` header). It has no semantic meaning beyond "the suite was changed".

The unique constraint on test cases is a functional index: `uq_test_cases_suite_name ON test_cases (test_suite_id, LOWER(test_case_name))`. This enables native PostgreSQL upsert via `ON CONFLICT`.

## Goals / Non-Goals

**Goals:**
- Add `importMode` enum query parameter (`OVERRIDE`, `APPEND`, `MERGE`) to both import and preview endpoints
- Add `conflictStrategy` enum query parameter (`FAIL`, `SKIP`, `OVERRIDE`) to both endpoints
- `importMode=OVERRIDE` + `conflictStrategy=FAIL` (defaults) is a clean break: OVERRIDE will now always replace schema from CSV (even if one exists); callers that relied on OVERRIDE preserving an existing schema must migrate
- APPEND mode keeps existing data and schema (auto-detects only when schema is empty), appends rows
- MERGE mode keeps existing data, merges schemas (adds new CSV columns to existing schema), appends rows
- Conflict strategy controls name collision behavior for all import modes (including OVERRIDE); use DB-level upsert for SKIP and OVERRIDE strategies; FAIL relies on existing DB constraint + transaction rollback
- Add `updateTestCaseSchema` to `TestSuiteRepository` to persist schema changes from import
- Unknown CSV columns are silently discarded in `APPEND` mode when schema exists — only schema-aligned data is stored; MERGE mode stores all CSV columns (new ones are added to the merged schema)
- When `testCaseSchema` changes via TestSuite PUT/PATCH (fields removed), clean orphaned keys from all TestCase `data` fields in the suite synchronously

**Non-Goals:**
- Upsert mode (field-level merge of existing and imported row data) — out of scope
- Schema column removal or rename on import — not in scope

## Decisions

### 1. Two separate enums as query parameters

**Decision:** `importMode` and `conflictStrategy` are separate query parameters, not a single combined enum.

**Rationale:** These are orthogonal concerns — `importMode` controls data/schema behavior, `conflictStrategy` controls name collision resolution. Separate params allow independent extension.

### 2. Enum types in `service.domain.dto.csv` package

**Decision:**
- `CsvImportMode` enum: `OVERRIDE`, `APPEND`, `MERGE`
- `CsvConflictStrategy` enum: `FAIL`, `SKIP`, `OVERRIDE`

### 3. FAIL strategy — rely on DB constraint within transaction

**Decision:** For FAIL strategy, use plain `testCaseRepository.save()` (existing INSERT). The DB unique constraint `uq_test_cases_suite_name` fires on the first collision and throws `DataIntegrityViolationException`. The existing `UniqueConstraintViolationDetector.rethrowIfUniqueViolation()` converts this to an application exception. Because import runs inside `@Transactional("metaTransactionManager")`, the entire transaction rolls back — no rows are persisted.

**Rationale:** No extra memory for name tracking, no extra DB roundtrip before import. CSV can be arbitrarily large; collecting all names upfront just to detect one collision would waste memory. The DB constraint is the definitive authority anyway. Trade-off: only the first collision name is reported (not all), which is acceptable — the user fixes and retries.

**Alternative considered:** Targeted pre-check `SELECT ... WHERE LOWER(test_case_name) IN (:csvNamesLower)` — rejected because it requires collecting all CSV names in memory (one `String` per row = O(n) heap for large CSVs). DB constraint is simpler and more memory-efficient.

**Important — this rationale applies to the actual import commit only, NOT to preview.** Preview cannot rely on DB rollback to detect collisions. See decision 7 for preview behavior.

### 4. SKIP and OVERRIDE strategies — PostgreSQL upsert

**Decision:** Delegate to the DB via `ON CONFLICT` on the `(test_suite_id, LOWER(test_case_name))` functional index.
- **SKIP:** Add `insertOrSkip(TestCase)` to `TestCaseRepository`. SQL: `INSERT ... ON CONFLICT (test_suite_id, LOWER(test_case_name)) DO NOTHING`. Returns rows affected (0 = skipped, 1 = inserted). Service accumulates `skippedCount` from affected row counts.
- **OVERRIDE (conflict strategy):** Add `insertOrOverride(TestCase)` to `TestCaseRepository`. SQL: `INSERT ... ON CONFLICT (test_suite_id, LOWER(test_case_name)) DO UPDATE SET data = EXCLUDED.data, test_case_name = EXCLUDED.test_case_name, is_enabled = EXCLUDED.is_enabled, is_valid = EXCLUDED.is_valid, validation_warnings = EXCLUDED.validation_warnings, updated_at_ms = EXCLUDED.updated_at_ms RETURNING (xmax <> 0)::int AS was_update`. Returns boolean (true = existing row was replaced, false = new insert). See "Note on counting overrides" below for the correct JDBC method.

**`conflictStrategy` applies uniformly to all import modes:** In OVERRIDE import mode, `deleteAllByTestSuiteId` runs first; each CSV row is then inserted via the same strategy-based path as APPEND/MERGE modes (`save()`, `insertOrSkip()`, or `insertOrOverride()`). Since all existing rows are deleted before inserting, cross-import name collisions cannot occur in OVERRIDE mode — only within-CSV duplicates (multiple rows in the same file with the same `testCaseName`, case-insensitive) can cause conflicts. These are handled identically to cross-import collisions under the chosen strategy:
- **FAIL (any mode):** The second occurrence of a duplicate name hits the DB unique constraint (the first row was already inserted in this transaction) and throws `DataIntegrityViolationException`; the transaction rolls back with HTTP 409. No in-memory name tracking needed — the DB constraint is the authority.
- **SKIP (any mode):** `ON CONFLICT DO NOTHING` silently keeps the first insertion and skips subsequent duplicates; `skippedCount` is incremented for each skipped row (whether cross-import or within-CSV).
- **OVERRIDE conflict strategy (any mode):** `ON CONFLICT DO UPDATE` replaces the earlier row with the later one; `overriddenCount` is incremented. For within-CSV duplicates, `xmax <> 0` will correctly return true since the first occurrence is being replaced in the same transaction.

No in-memory name tracking (`LinkedHashSet` or similar) is required for any strategy or mode.

**Note on counting overrides:** PostgreSQL `INSERT ... ON CONFLICT DO UPDATE` reports affected rows as 1 for both insert and update, so `jdbcTemplate.update()` cannot distinguish them. To count overrides, use `RETURNING (xmax <> 0)::int AS was_update` and execute via `jdbcTemplate.queryForObject("... RETURNING (xmax <> 0)::int AS was_update", params, Integer.class)` — NOT `jdbcTemplate.update()` which discards RETURNING results. Accumulate the Integer return value (1 = was override, 0 = was new insert) across the streaming loop. Alternatively: snapshot `countByTestSuiteId` before import; after import, `overriddenCount = (preCount + csvRows) - postCount`. The `RETURNING xmax` approach via `queryForObject` is cleaner and avoids the extra count queries.

**Alternative considered:** Load existing names + individual deletes then inserts — rejected; two roundtrips per colliding row, no benefit over upsert.

### 5. Schema handling per import mode

**Decision:**

| Mode | Suite schema state | Schema action |
|------|--------------------|---------------|
| OVERRIDE | empty OR existing | Always auto-detect from CSV, persist, bump version — **replaces any existing schema** |
| APPEND | empty | Auto-detect from CSV, persist, bump version |
| APPEND | exists | Use as-is for validation; do NOT modify |
| MERGE | empty | Auto-detect from CSV, persist, bump version |
| MERGE | exists | Merge: keep existing fields, auto-detect types for new CSV columns, append new fields, persist, bump version |

**OVERRIDE is a breaking change:** Currently OVERRIDE does not persist schema (auto-detect runs in preview only). New behavior: OVERRIDE always replaces schema from CSV. This is an intentional, approved breaking change.

**Merge algorithm:**
1. Start with existing schema fields (preserve order, types, required flags)
2. For each CSV data column not in existing schema: auto-detect type from all CSV values for that column, add as new `FieldDefinitionDto` with `required: false`
3. New fields are appended after existing fields in CSV column order
4. If no new fields are found: schema is unchanged, version is NOT bumped

**Schema auto-detection in `importCsv()` — incremental type inference (critical):** `preview()` uses `collectRawDataColumns()` which holds all raw cell values in memory (`List<List<String>>`) for batch type inference — this is acceptable for preview which bounds rows via `maxRows` with a modest default. `importCsv()` must NOT copy this approach for schema detection. Instead, use **incremental type inference**: maintain a `Map<String, SchemaFieldType> inferredTypes` keyed by column name, initialized to `null`. For each row during streaming, for each new-schema column, call `inferCellType(rawValue)` and update the map via `widenType(currentBest, cellType)`. After streaming completes, the map contains the final inferred type for each new column. This is O(columns) memory, not O(rows × columns). The same incremental approach applies to OVERRIDE mode (all data columns) and APPEND mode with empty schema.

### 6. `updateTestCaseSchema` in `TestSuiteRepository`

**Decision:** Add `updateTestCaseSchema(UUID id, String schemaJson)` to `TestSuiteRepository`. SQL:
```sql
UPDATE test_suites SET test_case_schema = :schemaJson, version = version + 1, updated_at_ms = :updatedAt WHERE id = :id
```
No `WHERE version = :version` guard — the import already verified the version at start via `If-Match`; a second check is unnecessary within the same transaction.

**Rationale:** A targeted method is cleaner than routing through `TestSuiteService.update()` which carries unrelated validation and triggers re-validation tasks.

### 7. Preview endpoint awareness

**Decision:** Pass both `importMode` and `conflictStrategy` to `CsvImportService.preview()`. Preview surfaces:
- Name collision warnings per strategy context (e.g. "Row X would be skipped", "Row X would override existing 'CaseA'")
- Schema changes: in MERGE mode, `autoDetectedSchema` shows only new fields to be added; in OVERRIDE mode, it shows the full replacement schema

**Preview collision detection:**
- **APPEND and MERGE modes:** Preview cannot rely on the DB constraint + transaction rollback mechanism (decision 3) because there is nothing to roll back. Preview SHALL perform a targeted DB query after streaming completes:
  ```sql
  SELECT test_case_name FROM test_cases WHERE test_suite_id = :suiteId AND LOWER(test_case_name) IN (:csvNamesLower)
  ```
  CSV names are collected during the streaming pass into a `List<String>` and passed to this query. The result is used to annotate per-row warnings reflecting what the current `conflictStrategy` would do. The actual import commit still uses decision 3 (DB constraint) for FAIL — the preview query is separate and does not replace it.
- **OVERRIDE mode:** No DB query needed — all existing test cases would be deleted before import, so cross-import name collisions are impossible. Only within-CSV duplicates can occur; these are detected purely in-memory during streaming (see within-CSV duplicate handling below).

**Preview within-CSV duplicate handling:** Preview SHALL track a running `LinkedHashSet<String>` of lowercased names during streaming to detect within-CSV duplicates (bounded by `maxRows`, so in-memory tracking is safe). If `add()` returns `false` for a row, the row is annotated with a strategy-appropriate warning rather than silently excluded:
- **FAIL:** annotate as "would cause import failure (409) — duplicate of earlier row with same name"; included in `sampleRows` to inform the user
- **SKIP:** annotate as "would be skipped — duplicate of earlier row with same name"
- **OVERRIDE (conflict strategy):** annotate as "would replace earlier row with same name (last wins)"

No HTTP 409 is returned from the preview endpoint itself. The `totalRows` count includes all CSV rows; `sampleRows` reflects all rows including annotated duplicates so the user can see the collision context.

### 8. Unknown CSV columns — filtered in APPEND mode only

**Decision:** Column filtering depends on import mode:

| Mode | Schema state | Filtering |
|------|-------------|-----------|
| OVERRIDE | any | No filtering — all CSV data columns stored (schema is replaced from CSV) |
| APPEND | empty | No filtering — all CSV data columns stored (schema is being auto-detected) |
| APPEND | exists | **Filter** — only columns present in existing `fieldTypes` are stored in `data`; columns absent from schema are silently discarded |
| MERGE | any | No filtering — all CSV data columns stored (columns either exist in schema or are being added to it; all end up in the merged schema) |

**Rationale for APPEND filter:** When existing schema is the authority, storing columns not defined in it creates orphaned data that is never validated or shown in the UI. Discarding unknown columns keeps `data` clean and predictable.

**Rationale for no filtering in MERGE:** In MERGE mode the merged schema = existing fields + all new CSV data fields. Every CSV data column ends up in the merged schema, so no column is "unknown" after the merge. Filtering new columns out of `data` during parsing would lose the values that belong to the newly-added schema fields.

**Affects:** `CsvImportService.parseRow()` — add a guard: `if (mode != APPEND || schemaEmpty || fieldTypes.containsKey(b.fieldName()))` before adding to `data`.

**Alternative considered:** Filter whenever schema is non-empty regardless of mode — rejected because it would silently discard new MERGE-mode columns from `data`, storing empty values in the rows that caused the schema to grow.

### 9. Schema-driven data cleanup on TestSuite schema change

**Decision:** When `testCaseSchema` is updated via TestSuite PUT/PATCH and fields are removed from the schema, the system SHALL synchronously remove the orphaned keys from the `data` JSONB of all TestCases in the suite.

**Implementation:**
1. In `TestSuiteService.update()`, after `isSchemaChanged` returns `true`, compute `removedFields = previousSchemaFields - newSchemaFields` (Set difference by field name).
2. If `removedFields` is non-empty, call `testCaseRepository.removeDataFields(suiteId, removedFields)`.
3. Repository SQL: `UPDATE test_cases SET data = data - :fields::text[] WHERE test_suite_id = :suiteId` (PostgreSQL `jsonb - text[]` removes multiple keys in one statement).

**Rationale:** Synchronous cleanup in the same transaction as the schema update ensures consistency: after a successful PUT, all TestCases reflect the new schema. A separate async job would leave a window where test cases have stale keys. The `jsonb - text[]` bulk operation is efficient — a single SQL UPDATE for the entire suite.

**Scope note:** This applies to API-driven schema changes (PUT/PATCH TestSuite) only. For CSV import, unknown columns are discarded at parse time (decision 8), so no post-import cleanup is needed.

### 10. Import result DTO extensions

**Decision:** Extend `CsvImportResultDto` with two nullable Integer fields:
- `skippedCount` — populated when `conflictStrategy=SKIP` (any import mode)
- `overriddenCount` — populated when `conflictStrategy=OVERRIDE` (any import mode)

Use `@JsonInclude(NON_NULL)` — fields are null (omitted from JSON) only when `conflictStrategy=FAIL`.

## Risks / Trade-offs

**[Risk] OVERRIDE mode schema replacement is a breaking change**
→ Mitigation: Document clearly in API changelog. Default remains `OVERRIDE` but schema behavior changes. Teams using OVERRIDE with an existing schema must migrate to APPEND.

**[Risk] OVERRIDE upsert `xmax` check is Postgres-specific**
→ Mitigation: Acceptable — entire stack is PostgreSQL. `xmax <> 0` is a stable, well-documented heuristic for detecting updates in `RETURNING` clauses.

**[Risk] MERGE mode may produce unexpected schema additions if CSV has typos in column names**
→ Mitigation: Preview endpoint shows exactly which fields would be added. Users should preview before committing with MERGE.

**[Trade-off] FAIL strategy reports only the first collision, not all**
→ Acceptable: Collecting all names in memory to detect every collision would be O(n) heap for large CSVs — unacceptable. One error at a time is the standard DB-constraint experience. Users fix and retry.

**[Risk] Schema cleanup removes data that may be hard to recover**
→ Mitigation: Preview before committing schema changes (existing UI flow). The operation is intentional — user explicitly changed the schema. No soft-delete; deleted data is gone.

**[Risk] `jsonb - text[]` bulk UPDATE may lock many rows for large suites**
→ Mitigation: Runs in same transaction as schema update, so the lock duration is bounded by the transaction. Acceptable for typical suite sizes. Future: could be made async via a dedicated job if suites grow very large.
