## ADDED Requirements

### Requirement: CSV import mode parameter
The CSV import and import preview endpoints SHALL accept an optional `importMode` query parameter of type `CsvImportMode` enum with values `OVERRIDE`, `APPEND`, and `MERGE`. When omitted, the system SHALL default to `OVERRIDE`.

#### Scenario: Import with explicit OVERRIDE mode
- **WHEN** client calls `POST .../test-cases/import?importMode=OVERRIDE` with a CSV file
- **THEN** system SHALL delete all existing test cases in the suite before importing, auto-detect schema from CSV, persist the schema (replacing any existing schema), and bump suite version

#### Scenario: Import with APPEND mode
- **WHEN** client calls `POST .../test-cases/import?importMode=APPEND` with a CSV file
- **THEN** system SHALL NOT delete existing test cases; imported rows SHALL be appended to the suite

#### Scenario: Import with MERGE mode
- **WHEN** client calls `POST .../test-cases/import?importMode=MERGE` with a CSV file
- **THEN** system SHALL NOT delete existing test cases; imported rows SHALL be appended; system SHALL merge CSV schema with the existing suite schema

#### Scenario: Import with default mode (parameter omitted)
- **WHEN** client calls `POST .../test-cases/import` without `importMode` parameter
- **THEN** system SHALL behave as `OVERRIDE`

#### Scenario: Preview with import mode
- **WHEN** client calls `POST .../test-cases/import/preview` with any `importMode` value
- **THEN** system SHALL include mode-specific information in the preview response

#### Scenario: Invalid import mode value
- **WHEN** client calls import or preview with `importMode=INVALID_VALUE`
- **THEN** system SHALL respond with HTTP 400

### Requirement: CSV conflict strategy parameter
The CSV import and import preview endpoints SHALL accept an optional `conflictStrategy` query parameter of type `CsvConflictStrategy` enum with values `FAIL`, `SKIP`, and `OVERRIDE`. When omitted, the system SHALL default to `FAIL`. The conflict strategy governs behavior when a `testCaseName` collision occurs — either a CSV row name matching an existing test case in the suite (case-insensitive), or a duplicate name within the CSV itself. The parameter applies to all import modes, including `OVERRIDE` (where cross-import collisions are impossible after deleteAll, but within-CSV duplicates are still subject to the strategy). Within-CSV duplicates are handled identically to cross-import collisions under the chosen strategy: `FAIL` rejects the import with HTTP 409 on the first duplicate, `SKIP` silently skips duplicate rows (first wins), `OVERRIDE` replaces the earlier row with the later one (last wins via upsert).

#### Scenario: FAIL strategy rejects import on name collision
- **WHEN** client calls import with any `importMode` and `conflictStrategy=FAIL` (or omitted) and a `testCaseName` collision occurs (a CSV row name matching an existing test case in APPEND/MERGE modes, or a within-CSV duplicate in any mode)
- **THEN** system SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION`, and message identifying the first colliding name; no rows SHALL be imported (transaction rolled back by DB constraint)

#### Scenario: SKIP strategy skips colliding rows
- **WHEN** client calls import with any `importMode` and `conflictStrategy=SKIP` and `testCaseName` collisions occur (cross-import collisions in APPEND/MERGE, or within-CSV duplicates in any mode)
- **THEN** system SHALL skip those rows (using `INSERT ... ON CONFLICT DO NOTHING`), import the remaining rows, and return result with `skippedCount` set to the total number of skipped rows

#### Scenario: OVERRIDE strategy replaces colliding rows
- **WHEN** client calls import with any `importMode` and `conflictStrategy=OVERRIDE` and `testCaseName` collisions occur (cross-import collisions in APPEND/MERGE, or within-CSV duplicates in any mode)
- **THEN** system SHALL replace the matching rows with the CSV rows (using `INSERT ... ON CONFLICT DO UPDATE`); result SHALL include `overriddenCount` set to the number of replaced rows
- **Note:** Replacement is a full data substitution — the existing row's `data` is entirely replaced with the CSV row's data. Fields defined in the schema but absent from the CSV row are NOT preserved. Field-level merge of existing and imported row data is out of scope.

#### Scenario: Conflict strategy applies in OVERRIDE import mode for within-CSV duplicates
- **WHEN** client calls import with `importMode=OVERRIDE` (which deletes all existing test cases first) and the CSV file contains two or more rows with the same `testCaseName` (case-insensitive)
- **THEN** system SHALL handle those within-CSV duplicates according to `conflictStrategy`: FAIL → HTTP 409 on the second occurrence; SKIP → first occurrence wins, `skippedCount` is set; OVERRIDE → last occurrence wins via upsert, `overriddenCount` is set

#### Scenario: OVERRIDE import mode + SKIP strategy returns skippedCount for within-CSV duplicates
- **WHEN** client calls import with `importMode=OVERRIDE` and `conflictStrategy=SKIP` and the CSV contains rows with duplicate `testCaseName` values
- **THEN** system SHALL import the first occurrence of each duplicate name, skip subsequent duplicates, and return `skippedCount` equal to the number of skipped within-CSV duplicate rows; `overriddenCount` SHALL be null

#### Scenario: OVERRIDE import mode + OVERRIDE strategy returns overriddenCount for within-CSV duplicates
- **WHEN** client calls import with `importMode=OVERRIDE` and `conflictStrategy=OVERRIDE` and the CSV contains rows with duplicate `testCaseName` values
- **THEN** system SHALL store the last occurrence of each duplicate name (via upsert) and return `overriddenCount` equal to the number of replaced within-CSV duplicate rows; `skippedCount` SHALL be null

#### Scenario: Default conflict strategy
- **WHEN** client calls import without `conflictStrategy` parameter
- **THEN** system SHALL behave as `FAIL`

#### Scenario: Preview shows conflict-specific context
- **WHEN** client calls preview with `importMode=APPEND` (or `MERGE`) and CSV row names collide with existing test case names
- **THEN** system SHALL include warnings indicating which names collide and what action the current `conflictStrategy` would take (skip / override / fail)

#### Scenario: Within-CSV duplicates follow conflictStrategy
- **WHEN** client calls import with any `importMode` and the CSV contains multiple rows with the same `testCaseName` (case-insensitive)
- **THEN** system SHALL handle them per `conflictStrategy`: FAIL → HTTP 409 on the first within-CSV duplicate encountered; SKIP → first occurrence is imported, subsequent duplicates are silently skipped (skippedCount incremented); OVERRIDE → last occurrence wins via upsert (overriddenCount incremented for each replacement)

#### Scenario: Preview annotates within-CSV duplicates with strategy-appropriate warnings
- **WHEN** client calls the preview endpoint with a CSV that contains duplicate `testCaseName` values
- **THEN** preview response SHALL annotate duplicate rows with strategy-appropriate warnings (FAIL: "would cause import failure"; SKIP: "would be skipped"; OVERRIDE: "would replace earlier row"); no HTTP 409 is returned from the preview endpoint itself

### Requirement: Import result extended counts
The `CsvImportResultDto` SHALL include optional fields `skippedCount` and `overriddenCount` (nullable Integer, omitted from JSON when null via `@JsonInclude(NON_NULL)`).

#### Scenario: Result includes skippedCount for SKIP strategy
- **WHEN** import completes with `conflictStrategy=SKIP` and some rows were skipped
- **THEN** the result SHALL include `skippedCount` with the number of skipped rows; `overriddenCount` SHALL be null

#### Scenario: Result includes overriddenCount for OVERRIDE strategy
- **WHEN** import completes with `conflictStrategy=OVERRIDE` and some existing test cases were replaced
- **THEN** the result SHALL include `overriddenCount` with the number of replaced test cases; `skippedCount` SHALL be null

#### Scenario: Extended counts are null when not applicable
- **WHEN** import completes with `conflictStrategy=FAIL` (any import mode)
- **THEN** `skippedCount` and `overriddenCount` SHALL be null (omitted from JSON)

### Requirement: OVERRIDE mode schema handling
In OVERRIDE mode, the system SHALL always auto-detect the schema from the CSV and persist it to the suite, replacing any existing schema. This applies whether the suite schema is empty or not.

#### Scenario: OVERRIDE replaces existing schema
- **WHEN** client calls import with `importMode=OVERRIDE` and the suite has an existing `testCaseSchema`
- **THEN** system SHALL replace the schema with the auto-detected schema from CSV, persist it, and bump the suite version

#### Scenario: OVERRIDE with empty schema auto-detects
- **WHEN** client calls import with `importMode=OVERRIDE` and the suite's `testCaseSchema` is empty
- **THEN** system SHALL auto-detect schema from CSV, persist, and bump suite version (same as when schema exists)

#### Scenario: OVERRIDE preview shows replacement schema
- **WHEN** client calls preview with `importMode=OVERRIDE`
- **THEN** the preview response SHALL include `autoDetectedSchema` regardless of whether the suite already has a schema

### Requirement: APPEND mode schema handling
In APPEND mode, the system SHALL handle the test case schema as follows: if the suite's `testCaseSchema` is empty, auto-detect and persist the schema from CSV. If the suite's `testCaseSchema` exists, use it as-is for validation without modification.

#### Scenario: APPEND with empty schema triggers auto-detection
- **WHEN** client calls import with `importMode=APPEND` and the suite's `testCaseSchema` is empty
- **THEN** system SHALL auto-detect field definitions from CSV columns, persist the schema to TestSuite, and bump the suite version

#### Scenario: APPEND with existing schema preserves it
- **WHEN** client calls import with `importMode=APPEND` and the suite's `testCaseSchema` is non-empty
- **THEN** system SHALL validate CSV against the existing schema; SHALL NOT modify the schema or bump the version; unknown CSV columns are silently discarded (not stored in `data`)

#### Scenario: APPEND preview with empty schema shows auto-detected schema
- **WHEN** client calls preview with `importMode=APPEND` and the suite's `testCaseSchema` is empty
- **THEN** the preview response SHALL include `autoDetectedSchema`

### Requirement: MERGE mode schema handling
In MERGE mode, the system SHALL merge the existing suite schema with fields detected from the CSV. Existing fields are preserved (order, type, required flag). New CSV columns not present in the existing schema are auto-detected and appended after existing fields. The merged schema is persisted and the suite version is bumped. If no new fields are found, the schema is unchanged and the version is NOT bumped.

#### Scenario: MERGE with empty schema auto-detects entirely
- **WHEN** client calls import with `importMode=MERGE` and the suite's `testCaseSchema` is empty
- **THEN** system SHALL auto-detect all field definitions from CSV, persist, and bump version

#### Scenario: MERGE adds new columns to existing schema
- **WHEN** client calls import with `importMode=MERGE`, the suite schema has fields `[A, B]`, and the CSV has columns `[A, B, C, D]`
- **THEN** system SHALL keep fields `A` and `B` with their existing definitions, auto-detect types for `C` and `D`, append `C` and `D` to the schema, persist the merged schema, and bump the suite version

#### Scenario: MERGE with no new columns leaves schema unchanged
- **WHEN** client calls import with `importMode=MERGE` and all CSV data columns already exist in the suite schema
- **THEN** system SHALL validate CSV against existing schema, persist rows; schema SHALL NOT be modified; suite version SHALL NOT be bumped

#### Scenario: MERGE preserves existing field definitions
- **WHEN** client calls import with `importMode=MERGE` and a CSV column name matches an existing schema field
- **THEN** system SHALL use the existing field definition (type, required flag) for validation; SHALL NOT override the existing field's type with the CSV-inferred type

#### Scenario: MERGE preview shows new fields only
- **WHEN** client calls preview with `importMode=MERGE` and CSV has columns not in the existing schema
- **THEN** the preview response SHALL include `autoDetectedSchema` showing only the new fields that would be added (the delta, not the full merged schema)

## MODIFIED Requirements

### Requirement: CSV bulk upload with schema detection
The service SHALL allow bulk uploading TestCases via CSV. All columns map to the unified `data` map. Column names match `testCaseSchema` field names. Schema auto-detection, persistence, and replacement behavior depend on the `importMode` parameter. In OVERRIDE mode, schema is always replaced from CSV. In APPEND mode, schema is only auto-detected when the suite schema is empty. In MERGE mode, new CSV columns are merged into the existing schema.

#### Scenario: Import maps all columns to data
- **WHEN** CSV header has column names matching `testCaseSchema` fields
- **THEN** system SHALL map all non-testCaseName, non-isEnabled columns to `data`

#### Scenario: Column name not in schema is discarded (APPEND mode only)
- **WHEN** client imports with `importMode=APPEND`, the suite `testCaseSchema` is non-empty, and a CSV column name does not match any schema field
- **THEN** system SHALL discard that column's values and NOT store them in `data`; no validation warning is added for the unknown column itself
- **Note:** This filtering applies only to `APPEND` mode with a non-empty schema. In `OVERRIDE` mode all CSV data columns are stored (schema is replaced). In `MERGE` mode all CSV data columns are stored (all columns end up in the merged schema — either existing or newly added).

#### Scenario: Auto-detect schema from CSV (no existing schema)
- **WHEN** `testCaseSchema` is empty and CSV is imported (any mode)
- **THEN** system SHALL auto-detect field definitions from CSV columns: all headers except reserved names (`testCaseName`, `isEnabled`) become `FieldDefinitionDto` entries with `required: false` and `description: null`; schema field order follows CSV column order (left to right)

#### Scenario: Auto-detect type inference
- **WHEN** system auto-detects schema from CSV
- **THEN** system SHALL scan all row values per column and infer type: all non-empty values parse as JSON objects → OBJECT; JSON arrays → ARRAY; `true`/`false` (case-insensitive) → BOOLEAN; whole numbers → INTEGER; decimal numbers → NUMBER; otherwise → STRING

#### Scenario: Auto-detected schema is persisted
- **WHEN** CSV import commits and schema auto-detection or merge occurs
- **THEN** system SHALL persist the new or merged schema to the TestSuite's `testCaseSchema` and bump `version`; no `inputBindings` are auto-created

#### Scenario: Auto-detected schema in preview
- **WHEN** client calls the CSV import preview endpoint and `testCaseSchema` would be auto-detected or schema would be replaced/merged
- **THEN** the preview response SHALL include `autoDetectedSchema` reflecting what would change

#### Scenario: CSV with only reserved columns
- **WHEN** CSV has only `testCaseName` (and optionally `isEnabled`) with no data columns, and `testCaseSchema` is empty
- **THEN** schema stays empty (no fields to auto-detect)

### Requirement: Unique testCaseName within TestSuite
The service SHALL enforce that `testCaseName` is unique within a TestSuite (case-insensitive). `"TestA"` and `"testa"` are considered duplicates within the same suite. Create, update (PUT/PATCH when changing name), and CSV import SHALL enforce uniqueness. In CSV import, the `conflictStrategy` parameter controls collision behavior for all import modes — both cross-import collisions (existing test cases vs. CSV rows, relevant for APPEND/MERGE) and within-CSV duplicates (multiple rows in the CSV with the same name, relevant for all modes). The DB unique constraint is `(test_suite_id, LOWER(test_case_name))`; upsert-based strategies leverage it directly.

#### Scenario: Duplicate testCaseName on create
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/test-cases` with a `testCaseName` that already exists in that TestSuite (case-insensitive match)
- **THEN** system SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION`, and message including the duplicated name

#### Scenario: Duplicate testCaseName on update
- **WHEN** client calls `PUT /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}` with a body whose `testCaseName` already exists for another TestCase in the same TestSuite (case-insensitive match)
- **THEN** system SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION`, and message including the duplicated name

#### Scenario: Duplicate testCaseName on PATCH
- **WHEN** client calls `PATCH .../test-cases/{testCaseId}` with a `testCaseName` that already exists for another TestCase in the same TestSuite (case-insensitive match)
- **THEN** system SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION`, and message including the duplicated name

#### Scenario: Same testCaseName in different suites succeeds
- **WHEN** client creates a TestCase with `testCaseName` that exists in a different TestSuite (but not in the target suite)
- **THEN** system SHALL create it successfully (HTTP 201, no 409)

#### Scenario: Case variation is duplicate within suite
- **WHEN** a TestCase named `"CaseOne"` exists in a suite and client creates a TestCase named `"caseone"` in the same suite
- **THEN** system SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION`

#### Scenario: CSV import (OVERRIDE mode) handles within-CSV duplicates via conflictStrategy
- **WHEN** client calls CSV import with `importMode=OVERRIDE` and the CSV contains two or more rows with the same `testCaseName` (case-insensitive match)
- **THEN** system SHALL handle the duplicate per `conflictStrategy`: FAIL → HTTP 409 on the second occurrence; SKIP → first occurrence is preserved, subsequent duplicates skipped; OVERRIDE → last occurrence wins via upsert

#### Scenario: CSV import (FAIL strategy) fails on first collision
- **WHEN** client calls CSV import with any `importMode` and `conflictStrategy=FAIL` and a `testCaseName` collision occurs (existing test case in APPEND/MERGE, or within-CSV duplicate in any mode)
- **THEN** system SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION`, listing the colliding name; no rows SHALL be imported

#### Scenario: CSV import (SKIP strategy) skips colliding rows
- **WHEN** client calls CSV import with any `importMode` and `conflictStrategy=SKIP` and `testCaseName` collisions occur
- **THEN** system SHALL skip those rows and import the rest; no 409 error; `skippedCount` in result reflects how many were skipped

#### Scenario: CSV import (OVERRIDE strategy) replaces colliding rows
- **WHEN** client calls CSV import with any `importMode` and `conflictStrategy=OVERRIDE` and `testCaseName` collisions occur
- **THEN** system SHALL replace those rows with the CSV data; no 409 error; `overriddenCount` in result reflects how many were replaced

**Note**: In OVERRIDE import mode, existing test cases are deleted before importing, so cross-import name collisions are impossible. `conflictStrategy` still applies to within-CSV duplicates in all modes.
