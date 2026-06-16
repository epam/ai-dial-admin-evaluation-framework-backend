## Why

CSV import currently operates in replace-all mode only — it deletes all existing test cases before importing. Users need the ability to **append** rows to an existing suite without losing current data, to **merge** schemas when importing files with new columns, and to control what happens when imported row names collide with existing ones. Two explicit flags make the behavior transparent and extensible.

## What Changes

- Add a `importMode` enum query parameter to the CSV import endpoint (`POST /api/v1/test-suites/{testSuiteId}/test-cases/import`) and the preview endpoint (`POST .../import/preview`)
- Three modes:
  - **OVERRIDE** (**default**) — deletes all existing test cases, always auto-detects and replaces schema from CSV regardless of whether a schema already exists; **BREAKING**: previously schema was only auto-detected when empty, now it is always replaced
  - **APPEND** — keeps existing test cases; auto-detects and persists schema only if the suite schema is currently empty, otherwise validates against the existing schema; appends imported rows
  - **MERGE** — keeps existing test cases; merges existing schema with CSV columns (adds new fields from CSV to existing schema, keeps existing fields intact); appends imported rows
- Add a `conflictStrategy` enum query parameter controlling what happens when a `testCaseName` collision occurs — either a CSV row name matching an existing test case **or** a duplicate name within the CSV itself (applies to all import modes):
  - **FAIL** — reject the entire import with HTTP 409 (default)
  - **SKIP** — silently skip colliding CSV rows; first occurrence wins
  - **OVERRIDE** — replace the colliding row; last occurrence wins for within-CSV duplicates
- Preview endpoint reflects both parameters in its response (mode-specific and conflict-specific warnings)
- **BREAKING (1)**: `importMode=OVERRIDE` now always replaces the suite schema from CSV even when a schema already exists. Prior behavior: schema was only auto-detected when empty. `conflictStrategy` defaults to `FAIL`, preserving current collision behavior.
- **BREAKING (2)**: When `importMode=APPEND` and the suite has a non-empty `testCaseSchema`, CSV columns whose names are not defined in the schema are silently discarded — they are not stored in `data`. Prior behavior: all CSV columns were stored in `data` regardless of schema. Callers using APPEND mode with a non-empty schema and extra CSV columns will silently lose those values; they should align their CSV column names with the schema before importing. **Note: MERGE mode is not affected** — in MERGE mode all CSV columns are stored (new columns are added to the schema, so no column is "unknown" after the merge).

## Capabilities

### New Capabilities

_(none — this enhances an existing capability)_

### Modified Capabilities

- `test-cases`: CSV import gains two query parameters: `importMode` (`OVERRIDE` | `APPEND` | `MERGE`) controlling data/schema handling, and `conflictStrategy` (`FAIL` | `SKIP` | `OVERRIDE`) controlling name collision resolution. Preview endpoint also accepts both parameters to surface mode-specific warnings.

## Impact

- **API**: Two new optional query params on import + preview endpoints. Defaults preserve current behavior.
- **Service**: `CsvImportService.importCsv` and `preview` gain mode-aware and conflict-aware logic — APPEND/MERGE skip delete; MERGE merges schemas; conflict strategy determines collision handling.
- **Database**: No schema changes — same tables, same batch inserts.
- **Tests**: New functional tests for APPEND mode, MERGE mode, and all three conflict strategies.
- **OpenAPI examples**: Update import request/response examples to show both parameters.
