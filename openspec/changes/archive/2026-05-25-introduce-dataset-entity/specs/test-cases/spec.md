# Test Cases — Delta

This delta re-roots test cases from `TestSuite` to `Dataset`. The owning entity changes (FK `test_cases.dataset_id` replaces `test_suite_id`), the URL scope changes (`/api/v1/datasets/{datasetId}/test-cases/*` replaces `/api/v1/test-suites/{testSuiteId}/test-cases/*`), and three TestCase fields are removed entirely: `requestTemplateOverride`, `inputBindingsOverride`, and `enabled`. The `testCaseSchema` consulted by validation, CSV import/export, and revalidation now lives on the referenced dataset rather than the suite.

## MODIFIED Requirements

### Requirement: Scope validation for TestCase operations
All TestCase operations SHALL validate that the testCaseId belongs to the specified datasetId.
Status: **Planned**

#### Scenario: TestCase not in specified Dataset
- **WHEN** client calls any TestCase endpoint where testCaseId exists but belongs to a different datasetId
- **THEN** system SHALL respond with HTTP 404

#### Scenario: Dataset not found
- **WHEN** client calls any TestCase endpoint where datasetId does not exist
- **THEN** system SHALL respond with HTTP 404

#### Scenario: TestCase under suite-scoped resolved-request endpoint
- **WHEN** client calls `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/resolved-request` where the suite does not exist, the test case does not exist, or the test case is not under the dataset referenced by the suite
- **THEN** system SHALL respond with HTTP 404

### Requirement: TestCase structure with unified data map
Each TestCase SHALL have: `testCaseName`, `data` (Map<String,Object> — the unified map for all schema-defined values), `valid` (boolean), `validationWarnings` (structured list). The `requestTemplateOverride`, `inputBindingsOverride`, and `enabled` fields previously on TestCase are removed.
Status: **Planned**

#### Scenario: Default data map
- **WHEN** a TestCase is created without `data`
- **THEN** system SHALL default `data` to an empty map `{}`

#### Scenario: Override fields rejected in request body
- **WHEN** client sends a create/update body containing `requestTemplateOverride` or `inputBindingsOverride` or `enabled`
- **THEN** system SHALL ignore the unknown fields (per Jackson default) or respond with HTTP 400 if strict-binding is enabled; in either case the persisted TestCase SHALL NOT carry those fields

### Requirement: Partial update (PATCH) for a TestCase
The service SHALL allow partial updates using RFC 7396 JSON Merge Patch. Allowed fields: `testCaseName`, `data`. NOT patchable: `id`, `valid`, `createdAt`, `updatedAt`. The previously-patchable `enabled`, `requestTemplateOverride`, and `inputBindingsOverride` fields are removed from the model and no longer accepted.
Status: **Planned**

#### Scenario: Patch data only
- **WHEN** client calls `PATCH .../datasets/{datasetId}/test-cases/{testCaseId}` with `{ "data": { "user_prompt": "new prompt" } }`
- **THEN** system SHALL apply merge patch to `data`, recalculate `valid` against the dataset's schema, and return updated TestCase

#### Scenario: Patch testCaseName
- **WHEN** client calls PATCH with `{ "testCaseName": "Renamed" }`
- **THEN** system SHALL update the name (subject to dataset-scoped uniqueness) and return the updated TestCase

#### Scenario: Patch removed fields rejected or ignored
- **WHEN** client calls PATCH with `{ "enabled": false }` or `{ "requestTemplateOverride": null }` or `{ "inputBindingsOverride": [...] }`
- **THEN** system SHALL ignore the unknown fields (per Jackson default) or respond with HTTP 400 if strict-binding is enabled

#### Scenario: Patch recalculates valid
- **WHEN** client calls PATCH with body that modifies `data`
- **THEN** system SHALL recalculate valid against the dataset's schema and include validationWarnings if `valid=false`

### Requirement: Export test cases (CSV or ZIP)
The service SHALL export test cases of a Dataset in a format appropriate to the dataset's schema and the `materializeFiles` parameter. If the dataset's `testCaseSchema` contains no `FILE` type fields, export SHALL produce a CSV file. If the schema contains `FILE` type fields, the `materializeFiles` parameter controls the export format. Column order SHALL be by schema order: fixed column `testCaseName` first, then data columns in the order fields appear in the dataset's `testCaseSchema`. The previously-supported `includeEnabled` query parameter is removed (TestCase has no `enabled` field; per-suite exclude lists belong to suites, not the dataset). ARRAY and OBJECT values SHALL be serialized as JSON strings.
Status: **Planned**

#### Scenario: Export without FILE fields (CSV)
- **WHEN** client calls `GET /api/v1/datasets/{datasetId}/test-cases/export.csv`
- **AND** the dataset's `testCaseSchema` has no FILE type fields
- **THEN** system SHALL return `Content-Type: text/csv` with test case data as CSV

#### Scenario: CSV columns reflect dataset schema
- **WHEN** system exports CSV
- **THEN** header SHALL be: `testCaseName`, then dataset `testCaseSchema` fields in schema order; no `enabled` column

#### Scenario: Export with custom delimiter
- **WHEN** client calls `GET .../datasets/{datasetId}/test-cases/export.csv?delimiter=;`
- **THEN** system SHALL use semicolon as delimiter

#### Scenario: Export with FILE fields and materializeFiles=true (ZIP)
- **WHEN** client calls `GET /api/v1/datasets/{datasetId}/test-cases/export?materializeFiles=true`
- **AND** the dataset's `testCaseSchema` has one or more FILE type fields
- **THEN** system SHALL return `Content-Type: application/zip` with `Content-Disposition: attachment; filename="test-cases-{datasetId}.zip"`; the ZIP SHALL contain `test-cases.csv` with FILE columns as relative paths and a `files/` directory with file bytes downloaded from DIAL storage; streamed (no full in-memory buffering)

#### Scenario: Export with FILE fields and materializeFiles=false
- **WHEN** client calls `GET /api/v1/datasets/{datasetId}/test-cases/export?materializeFiles=false`
- **THEN** system SHALL return CSV with FILE columns containing raw DIAL paths (current scheme `@ef/suites/{...}/...` is preserved per the file-reference path scheme follow-up)

#### Scenario: Export with FILE fields default materializeFiles
- **WHEN** client calls `GET /api/v1/datasets/{datasetId}/test-cases/export` without `materializeFiles`
- **AND** the dataset's `testCaseSchema` has one or more FILE type fields
- **THEN** system SHALL default `materializeFiles` to `true` and produce a ZIP

#### Scenario: Export ARRAY values as JSON
- **WHEN** system exports test cases with ARRAY-type fields to CSV
- **THEN** the CSV cell SHALL contain the valid JSON string (e.g., `["item1","item2"]`)

#### Scenario: Export OBJECT values as JSON
- **WHEN** system exports test cases with OBJECT-type fields to CSV
- **THEN** the CSV cell SHALL contain the valid JSON string (e.g., `{"key":"value"}`)

#### Scenario: includeEnabled query param is rejected or ignored
- **WHEN** client sends `includeEnabled=true` (or any value) on the export URL
- **THEN** system SHALL ignore the parameter (or reject it with HTTP 400 if strict-validation is enabled); no `enabled` column appears in the export under any setting

### Requirement: CSV bulk upload with schema detection
The service SHALL allow bulk uploading TestCases via CSV under the dataset endpoint `POST /api/v1/datasets/{datasetId}/test-cases/import`. All columns map to the unified `data` map. Column names match the dataset's `testCaseSchema` field names. Schema auto-detection, persistence, and replacement behavior depend on the `importMode` parameter. In OVERRIDE mode, schema is always replaced on the dataset from CSV. In APPEND mode, schema is only auto-detected when the dataset schema is empty. In MERGE mode, new CSV columns are merged into the existing dataset schema. The reserved CSV column is `testCaseName` only; the previously-recognized `enabled` column is no longer parsed (TestCase has no `enabled` field).

Cell values for ARRAY- and OBJECT-typed fields SHALL be stored as structured JSON values (not strings) when the schema specifies those types; when there is no schema or the field has no schema type, valid JSON arrays/objects SHALL be parsed and stored structurally. `CsvCellParser` SHALL NOT treat `"1"` and `"0"` as boolean literals; only `"true"`/`"false"` (case-insensitive). Integer parsing SHALL use `Long.parseLong()`. Inline coercion SHALL match the schema type when known; post-persist fixup SHALL run after schema auto-detection / merge / OVERRIDE replacement.
Status: **Planned**

#### Scenario: Import maps all columns to data
- **WHEN** CSV header has column names matching `testCaseSchema` fields
- **THEN** system SHALL map all non-testCaseName columns to `data` (the `enabled` column header is no longer reserved and is treated like any other data column — falling under the schema rules of the chosen importMode)

#### Scenario: Header `enabled` is no longer reserved
- **WHEN** CSV contains a column named `enabled`
- **THEN** system SHALL treat it like any other data column under the chosen importMode (OVERRIDE/APPEND-empty/MERGE auto-detect it; APPEND-non-empty discards it if not in schema); the column SHALL NOT be promoted to a TestCase boolean field

#### Scenario: Auto-detect schema from CSV (no existing schema)
- **WHEN** the dataset's `testCaseSchema` is empty and CSV is imported (any mode)
- **THEN** system SHALL auto-detect field definitions from CSV columns: all headers except `testCaseName` become `FieldDefinitionDto` entries with `required: false`; schema field order follows CSV column order

#### Scenario: Auto-detect type inference
- **WHEN** system auto-detects schema from CSV
- **THEN** system SHALL scan all row values per column and infer type per the existing precedence: OBJECT → ARRAY → BOOLEAN (`true`/`false` only) → INTEGER → NUMBER → STRING

#### Scenario: Auto-detected schema is persisted on the dataset
- **WHEN** CSV import commits and schema auto-detection or merge occurs
- **THEN** system SHALL persist the new or merged schema to the **Dataset's** `testCaseSchema`, bump dataset `version`, and (if schema actually changed) spawn the dataset-rooted `RevalidationTask` to coerce and revalidate existing test cases against the new schema

#### Scenario: Schema-change side-effects from import
- **WHEN** an import in OVERRIDE or MERGE mode results in a schema change
- **THEN** the response SHALL include both the import result and a `RevalidationTaskDto` (HTTP 202 path) covering the auto-coercion of post-import data and the suite-side fan-out; alternatively the import response carries only the import counts and the client polls `/datasets/{id}/revalidation-tasks` to observe progress — exact API shape is left to implementation but the behavior of triggering a task is required

#### Scenario: Post-persist fixup uses dataset schema
- **WHEN** CSV is imported with an empty schema (or OVERRIDE mode) and the dataset's schema is auto-detected
- **THEN** the fixup pass SHALL coerce values for columns with newly determined types using `SchemaTypeCoercer`, re-validate each test case against the **dataset's** schema, and batch-update changed rows

#### Scenario: Coercion failure preserves original value
- **WHEN** a CSV cell value cannot be coerced to the declared dataset-schema type
- **THEN** system SHALL store the original parsed value unchanged; downstream validation surfaces a TYPE warning

#### Scenario: FILE-typed column coerced to string
- **WHEN** a CSV cell value is in a column declared as `FILE` in the dataset schema and the parsed value is not already a String
- **THEN** system SHALL coerce the value to String via `String.valueOf(value)`

### Requirement: Import test cases (CSV or ZIP)
The import endpoint `POST /api/v1/datasets/{datasetId}/test-cases/import` SHALL accept both CSV files and ZIP archives. The file format is detected by file extension or content.
Status: **Planned**

#### Scenario: Import CSV file
- **WHEN** client sends `POST /api/v1/datasets/{datasetId}/test-cases/import` with a `.csv` file
- **AND** the dataset's `testCaseSchema` has no FILE type fields
- **THEN** system SHALL process the CSV using the current import flow (rescoped to dataset)

#### Scenario: Import ZIP archive
- **WHEN** client sends `POST /api/v1/datasets/{datasetId}/test-cases/import` with a `.zip` file
- **THEN** system SHALL extract and parse `test-cases.csv` from the archive; for each FILE column value (relative path), find the corresponding file in the archive's `files/` directory, sanitize the filename, upload to DIAL storage (file path scheme preserved per the path-scheme follow-up), and map the DIAL file path into the test case's `data`

#### Scenario: Import ZIP with missing file
- **WHEN** a CSV row references a file path that does not exist in the ZIP archive
- **THEN** system SHALL produce a validation warning for that test case and set the FILE field value to null

#### Scenario: Import CSV for dataset with FILE fields
- **WHEN** client imports a CSV file for a dataset whose schema has FILE fields
- **THEN** system SHALL treat FILE columns as string values (DIAL file paths if provided, or empty for null); no file extraction is performed; FILE validation may produce warnings for invalid path format

### Requirement: Import preview (CSV or ZIP)
The import preview endpoint `POST /api/v1/datasets/{datasetId}/test-cases/import/preview` SHALL support both CSV and ZIP formats with the same detection logic as the import endpoint.
Status: **Planned**

#### Scenario: Preview CSV file
- **WHEN** client sends `POST /api/v1/datasets/{datasetId}/test-cases/import/preview` with a CSV file
- **THEN** system SHALL return the preview using the dataset's schema (auto-detected or merged per importMode)

#### Scenario: Preview ZIP archive
- **WHEN** client sends `POST /api/v1/datasets/{datasetId}/test-cases/import/preview` with a ZIP file
- **THEN** system SHALL extract and preview the `test-cases.csv` within the archive; FILE columns SHALL show the relative paths from the CSV (no upload during preview)

### Requirement: CSV import mode parameter
The CSV import and import preview endpoints SHALL accept an optional `importMode` query parameter of type `CsvImportMode` enum with values `OVERRIDE`, `APPEND`, and `MERGE`. When omitted, the system SHALL default to `OVERRIDE`. The mode governs how the **dataset's** schema is treated (replaced / preserved / merged).
Status: **Planned**

#### Scenario: Import with explicit OVERRIDE mode
- **WHEN** client calls `POST /api/v1/datasets/{datasetId}/test-cases/import?importMode=OVERRIDE`
- **THEN** system SHALL delete all existing test cases in the dataset before importing, auto-detect schema from CSV, persist the schema on the dataset (replacing any existing schema), bump dataset version, and trigger the dataset-rooted RevalidationTask for any downstream consequences

#### Scenario: Import with APPEND mode
- **WHEN** client calls `POST /api/v1/datasets/{datasetId}/test-cases/import?importMode=APPEND`
- **THEN** system SHALL NOT delete existing test cases; imported rows SHALL be appended; if the dataset's schema is empty, it is auto-detected from CSV and persisted; otherwise the schema is unchanged

#### Scenario: Import with MERGE mode
- **WHEN** client calls `POST /api/v1/datasets/{datasetId}/test-cases/import?importMode=MERGE`
- **THEN** system SHALL NOT delete existing test cases; imported rows SHALL be appended; system SHALL merge CSV schema with the existing dataset schema (existing fields preserved, new CSV columns appended); if new fields were added the dataset version is bumped and the dataset-rooted RevalidationTask is triggered

#### Scenario: Import with default mode (parameter omitted)
- **WHEN** client calls `POST /api/v1/datasets/{datasetId}/test-cases/import` without `importMode` parameter
- **THEN** system SHALL behave as `OVERRIDE`

#### Scenario: Invalid import mode value
- **WHEN** client calls import or preview with `importMode=INVALID_VALUE`
- **THEN** system SHALL respond with HTTP 400

### Requirement: CSV conflict strategy parameter
The CSV import and import preview endpoints SHALL accept an optional `conflictStrategy` query parameter of type `CsvConflictStrategy` enum with values `FAIL`, `SKIP`, and `OVERRIDE`. When omitted, the system SHALL default to `FAIL`. The strategy governs `testCaseName` collisions (cross-import in APPEND/MERGE, and within-CSV in any mode), applied against the dataset-scoped uniqueness rule.
Status: **Planned**

#### Scenario: FAIL strategy rejects on collision
- **WHEN** client calls import with any `importMode` and `conflictStrategy=FAIL` (or omitted) and a `testCaseName` collision occurs
- **THEN** system SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION`, message identifying the first colliding name; no rows imported

#### Scenario: SKIP strategy skips colliding rows
- **WHEN** client calls import with any `importMode` and `conflictStrategy=SKIP` and collisions occur
- **THEN** system SHALL skip those rows (via `INSERT ... ON CONFLICT DO NOTHING`), import the rest, set `skippedCount` accordingly

#### Scenario: OVERRIDE strategy replaces colliding rows
- **WHEN** client calls import with any `importMode` and `conflictStrategy=OVERRIDE` and collisions occur
- **THEN** system SHALL replace matching rows via `INSERT ... ON CONFLICT (dataset_id, LOWER(test_case_name)) DO UPDATE`; set `overriddenCount` accordingly

#### Scenario: Default conflict strategy
- **WHEN** client calls import without `conflictStrategy`
- **THEN** system SHALL behave as `FAIL`

### Requirement: Schema-change revalidation auto-coerces test case data
The service SHALL auto-coerce test case data when a **dataset's** `testCaseSchema` is mutated via PUT. The async dataset-rooted `RevalidationTask` (Phase 1) coerces existing values via `SchemaChangeCoercer` (strict rules), updates `data` in place (via `updateDataIfUnchanged` optimistic guard), then re-validates via `TestCaseValidationService` against the new dataset schema (via `updateValidationIfUnchanged`). The strict coercion table is unchanged from the baseline (Integer/Long → STRING via `String.valueOf`; STRING → INTEGER via `Long.parseLong`; STRING → NUMBER via `Double.parseDouble`; STRING → BOOLEAN only for literal `"true"`/`"false"`; ARRAY/OBJECT/FILE/Boolean→numeric and Boolean↔Integer/Long conversions are deliberately not part of the schema-change coercer). The `RevalidationTask` exposes `coercedCellCount` reflecting the total number of cells whose value was rewritten during the task.
Status: **Planned**

#### Scenario: Dataset schema change triggers task
- **WHEN** client updates a dataset's `testCaseSchema` via PUT
- **THEN** system SHALL spawn a dataset-rooted `RevalidationTask` (status `PENDING`); the task's Phase 1 iterates all test cases in the dataset, applying `SchemaChangeCoercer` then `TestCaseValidationService`

#### Scenario: Coercion preserves unrelated keys
- **WHEN** `SchemaChangeCoercer` runs against a test case
- **THEN** only keys whose schema type changed (or were added/removed) are touched; other `data` keys are unchanged

#### Scenario: Concurrent test case edit during Phase 1
- **WHEN** Phase 1 attempts `updateDataIfUnchanged` or `updateValidationIfUnchanged` and the test case's `updated_at` has advanced since the task read it
- **THEN** the update SHALL skip that test case (rowsAffected=0) and continue with the rest; `coercedCellCount` is not incremented for skipped rows

### Requirement: Direct API writes do NOT auto-coerce
TestCase writes via `POST/PUT/PATCH /api/v1/datasets/{datasetId}/test-cases/*` SHALL NOT run `SchemaChangeCoercer`. Only `SchemaTypeCoercer` (permissive, applied during CSV import) and the dataset-rooted `RevalidationTask` Phase 1 (strict, applied automatically after a dataset schema mutation) perform coercion. Direct writes are taken at face value; if the data shape doesn't match, validation surfaces TYPE warnings and `valid=false`.
Status: **Planned**

#### Scenario: Direct write rejects miscoerced shape via warning, not coercion
- **WHEN** client POSTs a test case with `data: {"score": "3"}` where the dataset schema declares `score` as `INTEGER`
- **THEN** system SHALL save the test case with the value `"3"` unchanged in `data`, set `valid=false`, and emit a TYPE warning for `score`

### Requirement: RevalidationTask exposes coercedCellCount
The dataset-rooted `RevalidationTask` SHALL expose `coercedCellCount` (Long, default 0) reflecting the total number of test-case data cells coerced by `SchemaChangeCoercer` during Phase 1. The field is part of the `RevalidationTaskDto` response.
Status: **Planned**

#### Scenario: coercedCellCount accumulates across Phase 1
- **WHEN** the task processes test cases and `SchemaChangeCoercer` rewrites cells
- **THEN** `coercedCellCount` SHALL be incremented atomically with each successful `updateDataIfUnchanged` write

#### Scenario: Phase 2 does not affect coercedCellCount
- **WHEN** the task enters Phase 2 (suite revalidation)
- **THEN** `coercedCellCount` SHALL remain at the Phase-1 final value; suite-phase work does not touch test case data

#### Scenario: Pre-existing tasks expose 0 coercedCellCount
- **WHEN** a client reads a `RevalidationTaskDto` for a task created before this change (or any task whose Phase 1 did not coerce any cell)
- **THEN** `coercedCellCount` SHALL be `0` (the column existed before this change and the migration only renames `test_suite_id → dataset_id`; counter values are preserved verbatim)

### Requirement: Cascade delete for TestSuite
When a TestSuite is deleted, all suite-owned child entities (TSMDs, runs, eval-summaries) SHALL be cascade deleted. Test cases SHALL NOT be deleted (they live in the dataset and are reachable from any other suite referencing the same dataset). The delete response SHALL NOT include a `deletedTestCases` count.
Status: **Planned**

#### Scenario: Delete TestSuite with children
- **WHEN** client calls `DELETE /api/v1/test-suites/{id}` and the suite has suite-owned children (TSMDs, runs) and the referenced dataset has test cases and revalidation tasks
- **THEN** system SHALL delete only the suite-owned children; the response body (when present) SHALL contain only the deleted suite identifier and suite-owned child counters (e.g., deleted TSMDs, deleted runs, deleted eval-summaries); the body SHALL NOT include a `deletedTestCases` field and SHALL NOT include a `deletedRevalidationTasks` field — revalidation tasks live with the dataset (FK `revalidation_tasks.dataset_id` → `datasets(id) ON DELETE CASCADE`) and are not affected by suite deletion; the dataset, its test cases, and its revalidation tasks SHALL remain intact

### Requirement: Mutable TestSuite fields
The service SHALL allow updating mutable suite fields (e.g., `deploymentRef`, `endpointRef`, `requestTemplate`, `inputBindings`, `responseColumns`, `datasetId`, `disabledTestCaseIds`). Suite PUTs SHALL trigger synchronous suite-level re-validation only; suite PUTs SHALL NOT spawn an async `RevalidationTask`. Async tasks are spawned only by dataset PUTs that mutate `testCaseSchema` — see the `datasets` and `test-suites` specs.
Status: **Planned**

#### Scenario: Update endpointRef triggers synchronous suite-level re-validation
- **WHEN** client updates `endpointRef` schema on an existing suite
- **THEN** system SHALL re-run synchronous suite-level validation (`SuiteValidationService`) against the referenced dataset's schema, update `isValid`/`validationWarnings`, return HTTP 200 with the updated suite; system SHALL NOT spawn an async `RevalidationTask` and SHALL NOT return HTTP 202

### Requirement: Batch update test cases (PUT)
The service SHALL allow batch full-update of test cases via `PUT /api/v1/datasets/{datasetId}/test-cases` with a JSON array body. Each item SHALL contain an `id` (existing test case UUID) and the mutable fields `testCaseName` and `data`. The previously-mutable fields `requestTemplateOverride`, `inputBindingsOverride`, and `enabled` are removed from TestCase and SHALL NOT appear in batch items. The operation SHALL be atomic (all-or-nothing within a single transaction). The operation SHALL NOT create new test cases — all IDs must reference existing test cases in the specified dataset.
Status: **Planned**

#### Scenario: Successful batch update
- **WHEN** client calls `PUT /api/v1/datasets/{datasetId}/test-cases` with a valid array of items, each containing an `id` of an existing test case in the dataset
- **THEN** system SHALL update all items, recalculate `valid` for each against the dataset's schema, and return HTTP 200 with an ordered list of `TestCaseResponseDto` matching the input order

#### Scenario: Batch update recalculates validation per item
- **WHEN** client calls batch PUT and some items have data that fails schema validation
- **THEN** system SHALL save all items (including invalid ones with `valid=false` and `validationWarnings`), and return the full list; validation warnings do NOT cause rollback

#### Scenario: Batch update with includeWarnings
- **WHEN** client calls `PUT .../test-cases?includeWarnings=true`
- **THEN** response items SHALL include `validationWarnings` for each test case; without the param, warnings SHALL be omitted

#### Scenario: Batch update with non-existent test case ID
- **WHEN** client calls batch PUT and any item `id` does not exist in the specified dataset
- **THEN** system SHALL respond with HTTP 404 and roll back all changes

#### Scenario: Batch update with dataset not found
- **WHEN** client calls batch PUT with a `datasetId` that does not exist
- **THEN** system SHALL respond with HTTP 404

#### Scenario: Batch update with empty array
- **WHEN** client calls batch PUT with an empty array `[]`
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR)

#### Scenario: Batch update exceeds max items
- **WHEN** client calls batch PUT with more items than the configured `test-case.batch.max-items` limit
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR) with a message indicating the maximum allowed batch size

#### Scenario: Batch update with duplicate IDs
- **WHEN** client calls batch PUT and the array contains two or more items with the same `id`
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR) with a message indicating duplicate IDs

#### Scenario: Batch update name uniqueness within batch
- **WHEN** client calls batch PUT and two items in the batch specify the same `testCaseName` (case-insensitive)
- **THEN** system SHALL respond with HTTP 409 (UNIQUE_CONSTRAINT_VIOLATION) with a message identifying the duplicate name

#### Scenario: Batch update name uniqueness with existing test cases
- **WHEN** client calls batch PUT and an item's `testCaseName` collides with an existing test case NOT included in the batch (case-insensitive, same dataset)
- **THEN** system SHALL respond with HTTP 409 (UNIQUE_CONSTRAINT_VIOLATION) and roll back all changes

#### Scenario: Batch update rejects removed fields
- **WHEN** client calls batch PUT and any item carries `requestTemplateOverride`, `inputBindingsOverride`, or `enabled`
- **THEN** system SHALL ignore the unknown fields (per Jackson default) or respond with HTTP 400 (VALIDATION_ERROR) if strict-binding is enabled; the persisted test cases SHALL NOT carry those fields

### Requirement: Batch partial update test cases (PATCH)
The service SHALL allow batch partial-update of test cases via `PATCH /api/v1/datasets/{datasetId}/test-cases` with a JSON array body. Each item SHALL be a JSON object containing a mandatory `id` field and any subset of patchable fields (`testCaseName`, `data`). The previously-patchable fields `requestTemplateOverride`, `inputBindingsOverride`, and `enabled` are removed from TestCase and SHALL NOT appear in batch patch items. Merge-patch semantics (RFC 7396) SHALL apply per item, identical to single-item PATCH. The operation SHALL be atomic (all-or-nothing within a single transaction).
Status: **Planned**

#### Scenario: Successful batch patch
- **WHEN** client calls `PATCH /api/v1/datasets/{datasetId}/test-cases` with a valid array of merge-patch items
- **THEN** system SHALL apply each patch to the corresponding test case, recalculate `valid` for each against the dataset's schema, and return HTTP 200 with an ordered list of `TestCaseResponseDto` matching the input order

#### Scenario: Batch patch merges data at map level
- **WHEN** client calls batch PATCH with an item `{ "id": "...", "data": { "prompt": "new" } }`
- **THEN** system SHALL merge the `data` map (existing keys preserved, specified keys updated, keys set to null removed), identical to single PATCH behavior

#### Scenario: Batch patch ignores removed fields
- **WHEN** client calls batch PATCH with an item carrying `requestTemplateOverride`, `inputBindingsOverride`, or `enabled`
- **THEN** system SHALL ignore the unknown fields (per Jackson default) or respond with HTTP 400 (VALIDATION_ERROR) if strict-binding is enabled; the persisted test case SHALL NOT carry those fields

#### Scenario: Batch patch with includeWarnings
- **WHEN** client calls `PATCH .../test-cases?includeWarnings=true`
- **THEN** response items SHALL include `validationWarnings` for each test case

#### Scenario: Batch patch with non-existent test case ID
- **WHEN** client calls batch PATCH and any item `id` does not exist in the specified dataset
- **THEN** system SHALL respond with HTTP 404 and roll back all changes

#### Scenario: Batch patch with missing id field
- **WHEN** client calls batch PATCH and any item does not contain an `id` field
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR)

#### Scenario: Batch patch with invalid id format
- **WHEN** client calls batch PATCH and any item's `id` is not a valid UUID
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR)

#### Scenario: Batch patch with empty array
- **WHEN** client calls batch PATCH with an empty array `[]`
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR)

#### Scenario: Batch patch exceeds max items
- **WHEN** client calls batch PATCH with more items than the configured limit
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR) with a message indicating the maximum allowed batch size

#### Scenario: Batch patch with duplicate IDs
- **WHEN** client calls batch PATCH and the array contains two or more items with the same `id`
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR)

#### Scenario: Batch patch final-state name uniqueness within batch
- **WHEN** client calls batch PATCH and the final names of two or more batch items are the same (case-insensitive) — considering the new name for items that include `testCaseName` in their patch, and the current (unchanged) name for items that do not
- **THEN** system SHALL respond with HTTP 409 (UNIQUE_CONSTRAINT_VIOLATION) with a message identifying the duplicate name

#### Scenario: Batch patch name uniqueness with existing test cases
- **WHEN** client calls batch PATCH and any item's final `testCaseName` collides with an existing test case NOT included in the batch (case-insensitive, same dataset)
- **THEN** system SHALL respond with HTTP 409 (UNIQUE_CONSTRAINT_VIOLATION) and roll back all changes

### Requirement: Configurable batch size limit
The service SHALL enforce a configurable maximum number of items per batch request via `test-case.batch.max-items` application property. The default value SHALL be 256. The limit applies to both array-body batch PUT and batch PATCH endpoints rooted at `/api/v1/datasets/{datasetId}/test-cases`.
Status: **Planned**

#### Scenario: Default batch limit
- **WHEN** no `test-case.batch.max-items` property is configured
- **THEN** system SHALL use the default limit of 256

#### Scenario: Custom batch limit
- **WHEN** `test-case.batch.max-items` is set to 100
- **THEN** system SHALL reject batch requests with more than 100 items

#### Scenario: Batch limit applied to both PUT and PATCH
- **WHEN** batch limit is configured
- **THEN** system SHALL apply the same limit to both batch PUT and batch PATCH endpoints under the dataset path

### Requirement: Composite bulk partial update test cases (PATCH :bulk)
The service SHALL provide a composite bulk partial-update endpoint at `PATCH /api/v1/datasets/{datasetId}/test-cases:bulk` that accepts a single JSON body combining homogeneous selector-scoped operations and heterogeneous per-item operations. The entire request SHALL execute within a single transaction (all-or-nothing atomicity). The endpoint SHALL be additive — the existing array-body `PATCH /api/v1/datasets/{datasetId}/test-cases` endpoint SHALL remain available with unchanged semantics and its own `test-case.batch.max-items` cap.

The request body SHALL have the shape:

```json
{
  "bulkOperations": [
    { "selector": { "ids": ["<uuid>", ...] },          "patch": { "<field>": <value>, ... } },
    { "selector": { "filter": ["<filter-expr>", ...] }, "patch": { "<field>": <value>, ... } }
  ],
  "itemOperations": [
    { "id": "<uuid>", "patch": { "<field>": <value>, ... } }
  ]
}
```

`bulkOperations` and `itemOperations` are each optional but at least one non-empty array SHALL be present. `selector` SHALL contain exactly one of `ids` or `filter`. After this change the bulk-patch field whitelist is **empty by default** because `enabled` (the previously-whitelisted field) is removed from TestCase. The endpoint MAY still be invoked with `itemOperations` only; `bulkOperations[i].patch` referencing any field SHALL be rejected per the field-whitelist requirement until a new whitelisted field is added by a future change. The `patch` object inside `itemOperations[i]` SHALL follow the same merge-patch semantics as single-row `PATCH /api/v1/datasets/{datasetId}/test-cases/{id}` (allowed fields: `testCaseName`, `data`).
Status: **Planned**

#### Scenario: Successful composite bulk patch with item operations only
- **WHEN** client sends a well-formed request with at least one `itemOperations` entry and an omitted or empty `bulkOperations`
- **THEN** system SHALL apply each item operation (in array order) via merge-patch, commit the transaction, and return HTTP 200 with a response body containing `itemResults` (and an empty or omitted `bulkResults`)

#### Scenario: Item-only request is accepted
- **WHEN** client sends a request with an omitted or empty `bulkOperations` and a non-empty `itemOperations`
- **THEN** system SHALL apply only the item operations atomically and return HTTP 200

#### Scenario: Bulk request rejected while whitelist is empty
- **WHEN** client sends a request with `bulkOperations[i].patch` containing any field
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR) per the empty-default field whitelist; if a future change re-introduces a whitelisted field the bulk-only path becomes usable again

#### Scenario: Empty body is rejected
- **WHEN** client sends a body with both `bulkOperations` and `itemOperations` absent or empty (including a missing body)
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR)

#### Scenario: Atomic rollback on any failure
- **WHEN** any validation, resolver, or DB error occurs during execution of any bulk or item operation
- **THEN** system SHALL roll back all changes from the request and respond with the corresponding error HTTP status (no partial apply)

### Requirement: Selector semantics for bulkOperations
Each `bulkOperations[i].selector` SHALL be either an `ids` selector (explicit UUID list) or a `filter` selector (list of filter expressions using the existing test-case filter whitelist). The set of test cases affected by a `bulkOperations[i]` SHALL be scoped to the URL's `{datasetId}`. A filter selector resolves to the set of test-case ids in that dataset matching all filter expressions at selector-resolution time.
Status: **Planned**

#### Scenario: IDs selector with all ids in the dataset
- **WHEN** client provides `selector.ids` containing UUIDs that all belong to `{datasetId}`
- **THEN** system SHALL apply the shared `patch` (when a non-empty whitelist exists) to exactly those test cases via a single SQL UPDATE

#### Scenario: IDs selector with an id not in the dataset
- **WHEN** client provides `selector.ids` containing one or more UUIDs that do not belong to `{datasetId}` (either nonexistent or belonging to a different dataset)
- **THEN** system SHALL respond with HTTP 404 (NOT_FOUND) and roll back

#### Scenario: Filter selector with an empty filter list
- **WHEN** client provides `selector.filter` as an empty list `[]`
- **THEN** system SHALL treat the selector as matching every test case in the dataset

#### Scenario: Filter selector with valid filter expressions
- **WHEN** client provides `selector.filter` with expressions referencing fields in the test-case filter whitelist
- **THEN** system SHALL apply the shared `patch` to every test case in the dataset that matches all expressions

#### Scenario: Filter selector rejects unknown or non-whitelisted field
- **WHEN** client provides a `filter` expression referencing a field that is not in the test-case filter whitelist (or uses an operator not allowed for that field — note `enabled` is removed and is no longer a valid filter field)
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR) and roll back. The data-layer `InvalidFilterException` raised by the underlying `WhereBuilder` SHALL be translated by the service layer into a `FilterValidationException` so the global exception handler maps it to HTTP 400; an unwrapped data-layer exception SHALL NOT reach the client.

#### Scenario: Selector must declare exactly one variant
- **WHEN** a `selector` contains both `ids` and `filter`, or neither
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR)

### Requirement: Field whitelist for bulkOperations
The service SHALL restrict the set of fields allowed inside `bulkOperations[i].patch` to a code-defined whitelist. After this change the default whitelist is **empty** because the only previously-whitelisted field (`enabled`) is removed from TestCase. The whitelist remains the key set of a single canonical API-field → SQL-column map maintained in code; it is NOT a configuration property. Any request with a bulk patch referencing a field outside the (currently empty) whitelist SHALL be rejected with HTTP 400 (VALIDATION_ERROR). `itemOperations[i].patch` SHALL NOT be subject to this whitelist and SHALL follow the existing single-row PATCH field set (`testCaseName`, `data`).
Status: **Planned**

#### Scenario: Bulk patch with non-whitelisted field
- **WHEN** `bulkOperations[i].patch` contains any key (the whitelist is empty after this change)
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR) naming the offending field, and roll back

#### Scenario: Item operation patches a field not in the bulk whitelist
- **WHEN** `itemOperations[i].patch` contains keys outside the bulk whitelist but valid for single-row PATCH (i.e., `data`, `testCaseName`)
- **THEN** system SHALL accept the item operation

### Requirement: Validation scope for composite bulk patch
The service SHALL re-run per-row test-case validation (recomputing `valid` and `validation_warnings`) only for rows whose applied patch touches a validation-relevant field (`data`, `testCaseName`). Rows whose patches do not touch any validation-relevant field SHALL NOT be re-validated. With the empty default bulk-whitelist, bulk operations cannot currently target a non-validation-relevant field; this clause keeps the contract correct if the whitelist is expanded in a future change. Re-validation cost scales with the number of rows actually receiving a relevant field change.
Status: **Planned**

#### Scenario: Item op on data triggers per-row re-validation
- **WHEN** an `itemOperations[i].patch` modifies `data`
- **THEN** system SHALL re-run validation for that row and persist the updated `valid` / `validation_warnings` against the dataset's schema

#### Scenario: Item op on testCaseName triggers per-row re-validation
- **WHEN** an `itemOperations[i].patch` modifies `testCaseName` only
- **THEN** system SHALL re-run validation for that row (name uniqueness + downstream checks) and persist the updated state

### Requirement: Filter selector resolution semantics
A `filter` selector SHALL be resolved to a concrete set of test-case ids inside the same transaction that performs the UPDATE. Rows inserted into the dataset between the selector-resolution query and the UPDATE that were not part of the resolved id set SHALL NOT be affected by the bulk operation. This matches the behaviour documented for other filter-based bulk endpoints and SHALL be documented in the OpenAPI description.
Status: **Planned**

#### Scenario: Rows inserted concurrently are not matched
- **WHEN** rows are inserted into the dataset after the filter-selector resolution but before transaction commit
- **THEN** those rows SHALL NOT be affected by the bulk operation

#### Scenario: Rows matching at resolution time are updated even if the filter stops matching after a prior op
- **WHEN** a `bulkOperations[0]` changes a field used by `bulkOperations[1].selector.filter` (once a non-empty whitelist is reintroduced)
- **THEN** `bulkOperations[1]` SHALL be resolved against the post-`bulkOperations[0]` state, i.e., filter selectors are resolved at the moment each op executes, not up-front for the whole request

### Requirement: Execution order and conflict resolution
The service SHALL execute `bulkOperations` before `itemOperations` and SHALL preserve array order within each list. An `itemOperations[i].patch` SHALL be applied to the state that results from all preceding bulk and item operations in the same request; per-field last-writer-wins semantics apply across overlaps. After this change the URL path is `PATCH /api/v1/datasets/{datasetId}/test-cases:bulk` and the default `bulkOperations` field whitelist is empty (`enabled` is removed from TestCase); the scenarios below illustrate ordering against the still-available `itemOperations.patch` fields (`testCaseName`, `data`) and against the bulk path that becomes meaningful again once a non-empty whitelist is reintroduced.
Status: **Planned**

#### Scenario: Item operation overrides a field set by a prior bulk operation
- **WHEN** `bulkOperations` (with a future non-empty whitelist) sets a whitelisted field on a set of rows that includes id `X`, and `itemOperations` contains `{ id: X, patch: { testCaseName: "A1" } }`
- **THEN** the final persisted state for `X` SHALL reflect the item operation's `testCaseName` change applied on top of the bulk-op state, and per-field last-writer-wins resolves any overlap

#### Scenario: Item operation patches a field untouched by the bulk operation
- **WHEN** `bulkOperations` (with a future non-empty whitelist) updates a whitelisted field on rows including id `X`, and `itemOperations` contains `{ id: X, patch: { testCaseName: "A2" } }`
- **THEN** the final persisted state for `X` SHALL retain the bulk-op's field change AND have `testCaseName="A2"`

#### Scenario: Two overlapping bulk operations
- **WHEN** `bulkOperations[0]` and `bulkOperations[1]` (under a future non-empty whitelist) both patch the same whitelisted field on overlapping id sets — `bulkOperations[0]` sets it for all rows and `bulkOperations[1]` sets a different value on ids `[X]`
- **THEN** the final persisted state for `X` SHALL reflect `bulkOperations[1]`'s value and for every other row SHALL reflect `bulkOperations[0]`'s value (array-order, last-writer-wins)

### Requirement: Configurable limits for composite bulk patch
The service SHALL enforce the following configurable caps on the dataset-rooted `PATCH /api/v1/datasets/{datasetId}/test-cases:bulk` endpoint. Violations SHALL result in HTTP 400 (VALIDATION_ERROR) and a message identifying which cap was exceeded.

- `test-case.bulk.max-operations` (default `512`) — maximum combined count of `bulkOperations.length + itemOperations.length`. SHALL be configured to a value greater than or equal to `test-case.bulk.max-item-operations`; otherwise the item-operations cap would be unreachable.
- `test-case.bulk.max-ids-per-selector` (default `10000`) — maximum `selector.ids.length` for a single `bulkOperations[i]`; also an upper bound on the id-set materialised from a `filter` selector.
- `test-case.bulk.max-item-operations` (default `500`) — maximum `itemOperations.length`.
The bulk-patch field whitelist itself is NOT a configuration property — it is the key set of the code-defined API-field → SQL-column map (currently empty after this change; see "Field whitelist for bulkOperations").

The endpoint SHALL NOT apply `test-case.batch.max-items` — that property continues to govern only the array-body batch endpoint at `PATCH /api/v1/datasets/{datasetId}/test-cases`.
Status: **Planned**

#### Scenario: Combined op count exceeds max-operations
- **WHEN** `bulkOperations.length + itemOperations.length` exceeds `test-case.bulk.max-operations`
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR)

#### Scenario: Selector ids exceed max-ids-per-selector
- **WHEN** a `bulkOperations[i].selector.ids.length` exceeds `test-case.bulk.max-ids-per-selector`
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR)

#### Scenario: Filter selector materialises more than max-ids-per-selector
- **WHEN** a `filter`-based selector would match more test cases than `test-case.bulk.max-ids-per-selector`
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR) and roll back

#### Scenario: Item operations exceed max-item-operations
- **WHEN** `itemOperations.length` exceeds `test-case.bulk.max-item-operations`
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR)

#### Scenario: Defaults apply when properties are unset
- **WHEN** no `test-case.bulk.*` property is configured
- **THEN** system SHALL use the defaults listed above

#### Scenario: Legacy batch cap does not apply
- **WHEN** `itemOperations.length` is between `test-case.batch.max-items` (e.g., 256) and `test-case.bulk.max-item-operations` (e.g., 500)
- **THEN** system SHALL accept the request (the legacy `test-case.batch.max-items` cap SHALL NOT apply to `:bulk`)

### Requirement: Duplicate-id detection within a request
The service SHALL reject `itemOperations` arrays containing two or more entries with the same `id`. Duplicates inside a single `bulkOperations[i].selector.ids` SHALL also be rejected. Repeating an id across different operations (e.g., a row appears in a bulk selector AND in `itemOperations`) SHALL be allowed — last-writer-wins semantics apply. The endpoint is rooted at `PATCH /api/v1/datasets/{datasetId}/test-cases:bulk`.
Status: **Planned**

#### Scenario: Duplicate id within itemOperations
- **WHEN** `itemOperations` contains two entries with the same `id`
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR)

#### Scenario: Duplicate id within a single selector's ids
- **WHEN** `bulkOperations[i].selector.ids` contains the same UUID twice
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR)

#### Scenario: Same id referenced across a bulk selector and itemOperations
- **WHEN** an id appears in a `bulkOperations[i].selector.ids` AND in `itemOperations` (with a future non-empty bulk whitelist)
- **THEN** system SHALL accept the request and apply the item operation on top of the bulk state (last-writer-wins)

#### Scenario: Same id referenced across two bulkOperations entries
- **WHEN** an id appears in `bulkOperations[0].selector.ids` AND in `bulkOperations[1].selector.ids` (or is materialised by a filter selector in either op), under a future non-empty whitelist
- **THEN** system SHALL accept the request and apply both updates in array order (last-writer-wins per field)

### Requirement: Response shape for composite bulk patch
The service SHALL return a compact response body containing counts per operation, not full entity rows, from the dataset-rooted `PATCH /api/v1/datasets/{datasetId}/test-cases:bulk`. The response SHALL preserve the input order of operations.

Response shape:

```json
{
  "bulkResults": [ { "opIndex": 0, "matched": <int>, "updated": <int> }, ... ],
  "itemResults": [ { "id": "<uuid>", "updated": true | false }, ... ]
}
```

`matched` is the number of test cases the selector resolved to; `updated` is the number of rows whose state actually changed (may be less than `matched` when the patched value already equals the existing value). The `updated` count SHALL exclude rows whose every whitelisted patched column already equals the requested value (NULL-safe comparison, i.e., `NULL` and `NULL` count as "equal" and a single non-NULL value differing from `NULL` counts as "changed"). For an item operation, `updated` is `true` if the merge patch changed at least one column.
Status: **Planned**

#### Scenario: Bulk op counts reflect the selector
- **WHEN** a bulk op's selector resolves to N test cases and the patch differs from current state for all N (under a future non-empty whitelist)
- **THEN** `bulkResults[i]` SHALL report `matched=N, updated=N`

#### Scenario: Bulk op no-op for already-matching state
- **WHEN** a bulk op's selector resolves to N test cases and the patch equals current state for K of them (under a future non-empty whitelist)
- **THEN** `bulkResults[i]` SHALL report `matched=N, updated=N-K`

#### Scenario: Item op no-op when patch equals current state
- **WHEN** an item op's patch values (e.g., `testCaseName` or `data`) equal the test case's current state
- **THEN** `itemResults[i].updated` SHALL be `false`

### Requirement: Name uniqueness when composite bulk patch affects testCaseName
When the composite request targeting `PATCH /api/v1/datasets/{datasetId}/test-cases:bulk` touches `testCaseName` on one or more rows (only possible via `itemOperations` under the default whitelist, or via `bulkOperations` if a future whitelist extension adds `testCaseName`), the service SHALL validate name uniqueness against the final state, applying the same case-insensitive rules and HTTP 409 (UNIQUE_CONSTRAINT_VIOLATION) semantics already in effect for the existing batch PATCH endpoint. Uniqueness scope is the **dataset**, not the suite (see "Unique testCaseName within Dataset").
Status: **Planned**

#### Scenario: Item ops produce duplicate names within the request
- **WHEN** two `itemOperations` result in the same `testCaseName` (case-insensitive) inside the same dataset
- **THEN** system SHALL respond with HTTP 409 (UNIQUE_CONSTRAINT_VIOLATION) and roll back

#### Scenario: Item op collides with existing name outside the request
- **WHEN** an `itemOperations[i]` sets `testCaseName` to a value already used by a test case in the same dataset that is not part of the request
- **THEN** system SHALL respond with HTTP 409 (UNIQUE_CONSTRAINT_VIOLATION) and roll back

## ADDED Requirements

### Requirement: Test case endpoints under dataset scope
All test-case CRUD, bulk PATCH, CSV import/preview, and CSV/ZIP export endpoints SHALL be mounted under `/api/v1/datasets/{datasetId}/test-cases/*`. The `testCaseId` in nested paths SHALL belong to the specified `datasetId`. The previously-mounted equivalents under `/api/v1/test-suites/{testSuiteId}/test-cases/*` SHALL NOT exist after this change (with the single exception of the suite-scoped `resolved-request` endpoint, which remains under the suite because it depends on suite-level execution config).
Status: **Planned**

#### Scenario: URL scope matches dataset
- **WHEN** client calls any test-case endpoint under `/api/v1/datasets/{datasetId}/test-cases/*`
- **THEN** the implementation SHALL resolve `datasetId` from the path and reject requests whose `testCaseId` (when present) does not belong to that dataset (HTTP 404)

#### Scenario: Legacy suite-rooted paths return 404
- **WHEN** a client calls `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}` (or any other test-case CRUD/import/export verb under the suite path)
- **THEN** system SHALL respond with HTTP 404 (the endpoint is removed)

#### Scenario: resolved-request endpoint stays under suite
- **WHEN** client calls `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/resolved-request`
- **THEN** system SHALL serve this endpoint under the suite path because the resolved request needs the suite's `requestTemplate`/`inputBindings`; the implementation cross-checks that the test case belongs to a dataset the suite references

### Requirement: Create and manage TestCases inside a Dataset
The service SHALL manage TestCases as children of a Dataset with full CRUD operations. TestCases store a unified `data` map (Map<String, Object>). Per-case overrides of suite-level templates and bindings are no longer supported; test cases carry only their identity, the data map, and validity metadata.
Status: **Planned**

#### Scenario: Create a test case
- **WHEN** client calls `POST /api/v1/datasets/{datasetId}/test-cases` with a valid body
- **THEN** system SHALL create a TestCase linked to the Dataset; require `testCaseName`; default `data` to `{}`; calculate `valid` from the dataset's `testCaseSchema`

#### Scenario: List test cases
- **WHEN** client calls `GET /api/v1/datasets/{datasetId}/test-cases`
- **THEN** system SHALL return a paginated list of TestCases under the dataset

#### Scenario: Sort and filter test cases
- **WHEN** client calls `GET .../test-cases?sort=...&filter=...`
- **THEN** system SHALL apply sorting and filtering per entity-filtering spec; supported filter fields: `testCaseName`, `valid`, `createdAt`; supported sort fields: `testCaseName`, `createdAt`, `updatedAt`, `valid` (the `enabled` field is removed — see "Per-suite `disabledTestCaseIds`" in the `test-suites` spec for the replacement)

#### Scenario: Pagination with optional total count
- **WHEN** client calls `GET .../test-cases?page=0&size=50&includeTotalCount=true`
- **THEN** system SHALL return `totalElements` and `totalPages`; without param, omit them

#### Scenario: Range filter with multiple conditions
- **WHEN** client calls `GET .../test-cases?filter=createdAt:gte:1000&filter=createdAt:lte:2000`
- **THEN** system SHALL return test cases in the time range

#### Scenario: Invalid filter field
- **WHEN** client calls `GET .../test-cases?filter=unknownField:eq:value`
- **THEN** system SHALL respond with HTTP 400 and include invalid field in error details

#### Scenario: Default sort order
- **WHEN** client calls `GET .../test-cases` without sort parameter
- **THEN** system SHALL return results sorted by `createdAt,desc`

#### Scenario: Get test case by id
- **WHEN** client calls `GET /api/v1/datasets/{datasetId}/test-cases/{testCaseId}` for an existing TestCase
- **THEN** system SHALL return the TestCase including `data`; no `requestTemplateOverride` or `inputBindingsOverride` fields are present in the response

#### Scenario: Get resolved request for test case (under suite context)
- **WHEN** client calls `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/resolved-request`
- **THEN** system SHALL return the resolved request (URL, query params, headers, body) using the suite's template/bindings and the test case's `data`; the `testCaseId` MUST belong to a dataset referenced by `testSuiteId` (otherwise HTTP 404). The endpoint stays suite-scoped because the resolved request depends on suite-level execution config; no per-case overrides apply.

#### Scenario: Update test case (full replacement)
- **WHEN** client calls `PUT .../datasets/{datasetId}/test-cases/{testCaseId}` with a valid body
- **THEN** system SHALL replace the TestCase, recalculate `valid` against the dataset's schema, update `updatedAt`

#### Scenario: Delete single test case
- **WHEN** client calls `DELETE .../datasets/{datasetId}/test-cases/{testCaseId}`
- **THEN** system SHALL delete the TestCase and return HTTP 204

#### Scenario: Bulk delete test cases
- **WHEN** client calls `DELETE .../datasets/{datasetId}/test-cases` with optional filter
- **THEN** system SHALL delete matching TestCases (or all if no filter) and return count of deleted items

### Requirement: Unique testCaseName within Dataset
The service SHALL enforce that `testCaseName` is unique within a Dataset (case-insensitive). `"TestA"` and `"testa"` are considered duplicates within the same dataset. Create, update (PUT/PATCH when changing name), and CSV import SHALL enforce uniqueness. CSV import `conflictStrategy` continues to govern collision behavior (FAIL/SKIP/OVERRIDE) for both cross-import collisions and within-CSV duplicates. The DB unique constraint is `(dataset_id, LOWER(test_case_name))`; upsert-based strategies leverage it directly.
Status: **Planned**

#### Scenario: Duplicate testCaseName on create
- **WHEN** client calls `POST /api/v1/datasets/{datasetId}/test-cases` with a `testCaseName` that already exists in that Dataset (case-insensitive match)
- **THEN** system SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION`, and message including the duplicated name

#### Scenario: Duplicate testCaseName on update
- **WHEN** client calls `PUT /api/v1/datasets/{datasetId}/test-cases/{testCaseId}` with a body whose `testCaseName` already exists for another TestCase in the same Dataset (case-insensitive match)
- **THEN** system SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION`

#### Scenario: Duplicate testCaseName on PATCH
- **WHEN** client calls `PATCH .../datasets/{datasetId}/test-cases/{testCaseId}` with a `testCaseName` that already exists for another TestCase in the same Dataset (case-insensitive match)
- **THEN** system SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION`

#### Scenario: Same testCaseName in different datasets succeeds
- **WHEN** client creates a TestCase with `testCaseName` that exists in a different Dataset (but not in the target dataset)
- **THEN** system SHALL create it successfully (HTTP 201, no 409)

#### Scenario: Case variation is duplicate within dataset
- **WHEN** a TestCase named `"CaseOne"` exists in a dataset and client creates a TestCase named `"caseone"` in the same dataset
- **THEN** system SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION`

#### Scenario: CSV import within-CSV duplicates handled by conflictStrategy
- **WHEN** client calls CSV import with the CSV containing two rows with the same `testCaseName` (case-insensitive)
- **THEN** system SHALL handle the duplicate per `conflictStrategy`: FAIL → HTTP 409; SKIP → first occurrence preserved, subsequent skipped; OVERRIDE → last occurrence wins via upsert

### Requirement: Validate TestCases against schema (Soft Validation)
The service SHALL validate `data` against the referenced **dataset's** `testCaseSchema`. Validation failures produce warnings (not rejection). `valid=false` when any validation fails. Per-test-case template/binding overrides are removed from the model; "effective template" and "effective bindings" no longer exist at the test-case level — the suite's template and bindings apply uniformly to all test cases in the suite's runs.

TestCase validation includes REQUIRED, ADDITIONAL/UNKNOWN, TYPE, and FILE format checks against the dataset schema. The schema-change revalidation path uses `SchemaChangeCoercer` (strict coercion rules); direct API writes and CSV import use `SchemaTypeCoercer` (permissive). The `Schema-change revalidation auto-coerces test case data` requirement (preserved from the baseline) continues to define the strict coercion table — only the *trigger* changes (now: dataset-rooted RevalidationTask spawned from dataset PUT).

**Layering**: TestCase `valid` covers data-specific checks only and is independent of suite-level `valid`. Suite-level warnings (urlTemplate null, binding coverage, binding references against dataset schema, schema conformance) are stored on the TestSuite. The client/UI combines both flags to determine overall test readiness.

During CSV import and preview, validation SHALL use the **target dataset schema** (the schema that will be in effect after import commits) rather than the pre-import dataset schema, derived per `importMode` exactly as before but resolved against the dataset.
Status: **Planned**

#### Scenario: Missing required field in data
- **WHEN** a dataset `testCaseSchema` field has `required: true` and the corresponding key is missing or null in `data`
- **THEN** system SHALL set `valid=false` and add a validation warning with `fieldName`

#### Scenario: Type mismatch in data
- **WHEN** a `data` value does not match the dataset `testCaseSchema` field's declared `type`
- **THEN** system SHALL set `valid=false` and add a validation warning with `fieldName` and expected type

#### Scenario: API create with type mismatch produces warning
- **WHEN** a client creates a test case via POST API with data not matching the dataset schema
- **THEN** system SHALL save the test case with `valid=false` and TYPE warnings

#### Scenario: CSV import with coercion produces no type warnings
- **WHEN** a CSV is imported and `SchemaTypeCoercer` successfully coerces values to match dataset schema types
- **THEN** validation SHALL NOT emit TYPE warnings for those fields

#### Scenario: No effective-template/binding warnings
- **WHEN** a test case fails validation
- **THEN** validation SHALL NOT emit warnings about "Required template variable unbound" or "Required field has no value in data" stemming from per-case overrides — those scenarios no longer apply because TestCase carries no overrides; binding/variable warnings now live exclusively on the suite (`SuiteValidationService`)

### Requirement: Optimistic locking for Dataset
A version field SHALL be present on `Dataset` for optimistic concurrency control. Dataset PUTs require `If-Match`. The TestSuite version field continues to govern suite-level optimistic concurrency for suite PUTs (unchanged).
Status: **Planned**

#### Scenario: Concurrent dataset edit returns 412
- **WHEN** two clients hold the same dataset version and both submit PUT with that version as `If-Match`
- **THEN** the first wins (HTTP 200/202); the second SHALL respond with HTTP 412 `VERSION_CONFLICT`

### Requirement: Cascade delete for Dataset
Dataset deletion SHALL cascade to test cases (`test_cases.dataset_id` FK with `ON DELETE CASCADE`). Dataset deletion SHALL be rejected by FK `ON DELETE RESTRICT` if any TestSuite references the dataset (HTTP 409).
Status: **Planned**

#### Scenario: Cascade deletes test cases
- **WHEN** client successfully deletes a dataset (no suites reference it)
- **THEN** all test cases under the dataset SHALL be deleted as part of the same transaction

#### Scenario: RESTRICT prevents delete when suites depend
- **WHEN** client attempts to delete a dataset that has at least one referencing suite
- **THEN** system SHALL respond with HTTP 409; the dataset and its test cases SHALL remain intact

## REMOVED Requirements

### Requirement: Get template variables for test case (effective template)
**Reason**: This endpoint (`GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/template-variables`) existed because TestCase carried `requestTemplateOverride` and `inputBindingsOverride` that could change the effective template per case. Without those overrides, the per-test-case template variables are identical to the suite's template variables. The endpoint is redundant.
**Migration**: Use the suite-level template-variables endpoint (`GET /api/v1/test-suites/{testSuiteId}/template-variables`) to retrieve the variables that apply to every test case under the suite's dataset.

### Requirement: Create and manage TestCases inside a TestSuite
**Reason**: TestCases now live under Dataset, not TestSuite (see proposal.md and design.md). The TestSuite-rooted variant is superseded.
**Migration**: Use the new dataset-rooted endpoints under /api/v1/datasets/{datasetId}/test-cases; see the ADDED Requirement "Create and manage TestCases inside a Dataset".

### Requirement: Unique testCaseName within TestSuite
**Reason**: Uniqueness scope moves from TestSuite to Dataset because TestCases are now owned by Dataset (FK `test_cases.dataset_id`). The TestSuite-scoped uniqueness rule is superseded.
**Migration**: Use the dataset-scoped uniqueness rule; see the ADDED Requirement "Unique testCaseName within Dataset". The DB unique index is now `(dataset_id, LOWER(test_case_name))`.

### Requirement: Validate TestCases against schema, template, and bindings (Soft Validation)
**Reason**: TestCase no longer carries per-case template/binding overrides (`requestTemplateOverride`, `inputBindingsOverride` removed in this change). Template/binding validation now lives exclusively on the TestSuite via `SuiteValidationService`; TestCase soft validation is reduced to data-vs-schema checks against the referenced dataset's `testCaseSchema`.
**Migration**: Use the new ADDED Requirement "Validate TestCases against schema (Soft Validation)" for the data-vs-schema rules; refer to the `test-suites` spec for the suite-level template/binding warnings that previously co-lived under this requirement.

### Requirement: Optimistic locking for TestSuite
**Reason**: Optimistic-lock version field moved from TestSuite to Dataset; suite continues to have its own version field for suite-mutable fields (see test-suites delta).
**Migration**: Clients that previously sent If-Match for TestSuite version when updating test cases via the suite-scoped path should send If-Match against the dataset version on the new dataset-scoped paths.

## Implementation notes

- Repository: `TestCaseRepository` interface and `PostgresTestCaseRepository` rename every `testSuiteId` parameter/method to `datasetId`; the jOOQ where-clauses target `TEST_CASES.DATASET_ID`. Snapshot-phase methods gain a `Collection<UUID> excludedIds` argument (used by `findValidByDatasetIdExcludingIds`).
- Service: `TestCaseService` is rescoped to dataset; `RevalidationService` is rescoped to dataset (single async flow with two phases — see `datasets` spec and `design.md`).
- Controller: `TestCaseController` and `TestCaseBulkPatchController` are remounted under `/api/v1/datasets/{datasetId}/test-cases`; the suite controller no longer carries test-case subroutes.
- DTOs: drop `requestTemplateOverride`, `inputBindingsOverride`, `enabled` from `TestCaseRequestDto` / `TestCaseResponseDto` / `TestCaseBatchPutItemDto`.
- Mapper: `TestCaseMapper.toEntity(TestCaseRequestDto, UUID datasetId)` replaces the previous suite-scoped signature.
- Schema source: `TestSuiteService` resolves the dataset's schema via `DatasetSchemaProvider` for any cross-check (bindings, response columns, metric definitions); `CsvImportService` / `CsvExportService` read and write the schema on the **Dataset**.
