# Test Cases

## Purpose
This spec describes TestCase authoring and management as children of a `Dataset`: CRUD, schema validation, CSV export/import, and re-validation. TestCases store a unified `data` map. Per-test-case template/binding overrides are no longer supported — `requestTemplateOverride`, `inputBindingsOverride`, and `enabled` have been removed from the model. Exclusion of test cases from a specific suite's runs is handled by the suite's `disabledTestCaseIds` list (see `test-suites` spec).

Status: **Planned** (dataset-rooted re-scoping).

## Key Terms
- **TestCase**: A single row owned by a `Dataset`: name, data (unified map for all column values), valid, validationWarnings.
- **testCaseSchema**: Stored at Dataset level; defines the structure and validation rules for the unified `data` map in each TestCase.
- **DatasetSchemaProvider**: Cross-cutting `@Component` resolving `List<FieldDefinitionDto>` for a `datasetId`; used by suite-side validation and CSV import.

## Type System Reference

| Type | Java Mapping | JSON Representation | CSV Auto-Inference |
|------|--------------|---------------------|-------------------|
| `STRING` | `String` | `"value"` | Yes (default) |
| `INTEGER` | `Long` | `123` | Yes |
| `NUMBER` | `Double` | `123.45` | Yes |
| `BOOLEAN` | `Boolean` | `true`/`false` | Yes |
| `OBJECT` | `Map<String, Object>` | `{...}` | No (requires schema) |
| `ARRAY` | `List<Object>` | `[...]` | No (requires schema) |
| `FILE` | `String` (DIAL file reference path) | `"files/@ef/suites/{suiteId}/filename.ext"` | No (requires schema) |

## API Conventions

### Timestamps
All timestamps in API responses use **epoch milliseconds (Long)**.

### Pagination
- `page` (0-based, default 0), `size` (default 100, max 1000), `includeTotalCount` (default false).

### Filtering
- Repeatable `filter=<field>:<op>:<value>`; AND semantics; whitelist per entity. Operators: eq, ne, contains, gt, gte, lt, lte.

### Sorting
- Repeatable `sort=<field>[,<asc|desc>]`; default `createdAt,desc`.

## Requirements

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
- **WHEN** client calls `GET .../test-cases?filter=createdAt:ge:1000&filter=createdAt:le:2000`
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

### Requirement: Test case endpoints under dataset scope
All test-case CRUD, bulk PATCH, CSV import/preview, and CSV/ZIP export endpoints SHALL be mounted under `/api/v1/datasets/{datasetId}/test-cases/*`. The `testCaseId` in nested paths SHALL belong to the specified `datasetId`. The previously-mounted equivalents under `/api/v1/test-suites/{testSuiteId}/test-cases/*` SHALL NOT exist after this change (with the single exception of the suite-scoped `resolved-request` endpoint, which remains under the suite because it depends on suite-level execution config).
Status: **Planned**

#### Scenario: URL scope matches dataset
- **WHEN** client calls any test-case endpoint under `/api/v1/datasets/{datasetId}/test-cases/*`
- **THEN** the implementation SHALL resolve `datasetId` from the path and reject requests whose `testCaseId` (when present) does not belong to that dataset (HTTP 404)

#### Scenario: Legacy suite-rooted paths return 404
- **WHEN** a client calls `GET /api/v1/datasets/{datasetId}/test-cases/{testCaseId}` (or any other test-case CRUD/import/export verb under the suite path)
- **THEN** system SHALL respond with HTTP 404 (the endpoint is removed)

#### Scenario: resolved-request endpoint stays under suite
- **WHEN** client calls `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/resolved-request`
- **THEN** system SHALL serve this endpoint under the suite path because the resolved request needs the suite's `requestTemplate`/`inputBindings`; the implementation cross-checks that the test case belongs to a dataset the suite references

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

### Requirement: multiTurnData authoring field
The test-case request, response, and batch-put DTOs SHALL expose an optional `multiTurnData` (`List<Map<String,Object>>`); the model and `test_cases` table carry a nullable `multi_turn_data JSONB` column. The field is omitted (`@JsonInclude(NON_NULL)`) for single-turn cases. A test case MAY populate `data` **and** `multiTurnData` together: `data` carries the dataset's **shared** (`perTurn=false`) fields — test-case-level values constant across turns — while each `multiTurnData[i]` carries the **per-turn** (`perTurn=true`) fields. The two fields are NO LONGER mutually exclusive; there SHALL be no DB CHECK constraint or application 400 enforcing exclusivity. The multi-turn discriminator remains `multiTurnData != null` (independent of whether `data` is empty). A field placed in the wrong scope bucket — a per-turn field present in `data`, or a shared field present in any turn map — SHALL be rejected with HTTP 400 `VALIDATION_ERROR` at create/PUT/PATCH/batch (a structural placement error, distinct from content warnings).
Status: **Planned**

#### Scenario: Round-trip a multi-turn case
- **WHEN** a test case is created with a `multiTurnData` array and read back
- **THEN** the response includes `multiTurnData` with the same ordered turns and omits it for single-turn cases

#### Scenario: Shared and per-turn data coexist
- **WHEN** a case is created with `data` carrying the dataset's shared fields and a `multiTurnData` array carrying the per-turn fields
- **THEN** the write SHALL succeed, both are persisted, and the case is treated as multi-turn (`multiTurnData != null`)

#### Scenario: Misplaced field rejected
- **WHEN** a write places a per-turn field inside `data`, or a shared field inside a turn map
- **THEN** the request is rejected with HTTP 400 `VALIDATION_ERROR`

### Requirement: Per-turn validation against the dataset schema
Test-case validation SHALL be scope-aware. Shared fields SHALL be validated against the `data` map, and per-turn fields SHALL be validated against every element of `multiTurnData`, both using the dataset `test_case_schema`. A required shared field missing from `data`, or a required per-turn field missing from any turn, or a type mismatch in either bucket, SHALL be a content warning (not a 400) that sets `is_valid=false`. The case's `is_valid` is true iff no shared-field warning and every turn passes. Validation warnings aggregate across turns, each per-turn warning carrying the originating turn index. A multi-turn case whose per-turn maps are all empty (`{}`) SHALL be valid provided no required per-turn field is unmet — the turn count alone determines the number of turns run.
Status: **Planned**

#### Scenario: One invalid turn invalidates the case
- **WHEN** any turn violates the schema for a per-turn field (missing required, type mismatch, unknown field)
- **THEN** the case is stored with `is_valid=false` and warnings tagged with the offending turn index

#### Scenario: Missing shared required field invalidates the case
- **WHEN** the `data` map omits a required shared field
- **THEN** the case is stored with `is_valid=false` and a warning against `data` (no turn index)

#### Scenario: Empty per-turn maps are valid
- **WHEN** a multi-turn case has all-shared schema fields and each `multiTurnData[i]` is `{}`, with all required shared fields present in `data`
- **THEN** the case is valid and runs one test-case run per the turn count

### Requirement: data and multiTurnData are independently PATCH-able
Both `data` and `multiTurnData` SHALL be part of the merge-PATCH whitelist alongside `testCaseName`. Patching `data` SHALL update only the shared bucket, and patching `multiTurnData` SHALL update only the per-turn bucket; neither SHALL implicitly clear the other. Setting `multiTurnData: null` SHALL revert the case to single-turn. Placement and per-scope validation SHALL run after the merge (misplaced field → 400; content issues → invalidating warnings).
Status: **Planned**

#### Scenario: PATCH updates shared data without clearing turns
- **WHEN** a multi-turn case is PATCHed with a `data` object updating a shared field
- **THEN** the shared field is merged into `data`, `multiTurnData` is unchanged, and the case stays multi-turn

#### Scenario: PATCH updates per-turn data without clearing shared
- **WHEN** a multi-turn case is PATCHed with a `multiTurnData` array
- **THEN** the turns are replaced and the existing shared `data` is unchanged

#### Scenario: PATCH reverts to single-turn
- **WHEN** a multi-turn case is PATCHed with `multiTurnData: null`
- **THEN** the case becomes single-turn and its `data` is retained as the single-turn map

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
- **THEN** system SHALL return `Content-Type: application/zip` with `Content-Disposition: attachment; filename="test-cases-{datasetId}.zip"`; the ZIP SHALL contain `test-cases.csv` where FILE columns hold relative paths `files/{rowIndex}/{fieldName}/{filename}` (1-based CSV row index and schema field name, ensuring uniqueness) and a `files/` directory with the file bytes downloaded from DIAL storage; streamed (no full in-memory buffering)

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

#### Scenario: Export with FILE field but null value
- **WHEN** a test case has a FILE field with null value (no file attached)
- **THEN** the CSV column for that field SHALL be empty; no file entry in the ZIP for that test case's field

#### Scenario: Export null ARRAY/OBJECT values
- **WHEN** system exports a test case where an ARRAY or OBJECT field has a null value
- **THEN** the CSV cell SHALL be empty

#### Scenario: Export primitive values unchanged
- **WHEN** system exports test cases with STRING, INTEGER, NUMBER, or BOOLEAN fields
- **THEN** the CSV cell values SHALL use their natural string representation

### Requirement: CSV bulk upload with schema detection
The service SHALL allow bulk uploading TestCases via CSV under the dataset endpoint `POST /api/v1/datasets/{datasetId}/test-cases/import`. All columns map to the unified `data` map. Column names match the dataset's `testCaseSchema` field names. Schema auto-detection, persistence, and replacement behavior depend on the `importMode` parameter. In OVERRIDE mode, schema is always replaced on the dataset from CSV. In APPEND mode, schema is only auto-detected when the dataset schema is empty. In MERGE mode, new CSV columns are merged into the existing dataset schema. The reserved CSV columns are `testCaseName` and `turnIndex` (both excluded from `data` and from schema auto-detection); `turnIndex` groups and orders the turns of a multi-turn case (see the `multi-turn-test-case` spec). The previously-recognized `enabled` column is no longer parsed (TestCase has no `enabled` field).

Cell values for ARRAY- and OBJECT-typed fields SHALL be stored as structured JSON values (not strings) when the schema specifies those types; when there is no schema or the field has no schema type, valid JSON arrays/objects SHALL be parsed and stored structurally. `CsvCellParser` SHALL NOT treat `"1"` and `"0"` as boolean literals; only `"true"`/`"false"` (case-insensitive). Integer parsing SHALL use `Long.parseLong()`. Inline coercion SHALL match the schema type when known; post-persist fixup SHALL run after schema auto-detection / merge / OVERRIDE replacement.
Status: **Planned**

#### Scenario: Import maps all columns to data
- **WHEN** CSV header has column names matching `testCaseSchema` fields
- **THEN** system SHALL map all columns except the reserved `testCaseName` and `turnIndex` to `data` (the `enabled` column header is no longer reserved and is treated like any other data column — falling under the schema rules of the chosen importMode)

#### Scenario: Header `enabled` is no longer reserved
- **WHEN** CSV contains a column named `enabled`
- **THEN** system SHALL treat it like any other data column under the chosen importMode (OVERRIDE/APPEND-empty/MERGE auto-detect it; APPEND-non-empty discards it if not in schema); the column SHALL NOT be promoted to a TestCase boolean field

#### Scenario: Auto-detect schema from CSV (no existing schema)
- **WHEN** the dataset's `testCaseSchema` is empty and CSV is imported (any mode)
- **THEN** system SHALL auto-detect field definitions from CSV columns: all headers except the reserved `testCaseName` and `turnIndex` become `FieldDefinitionDto` entries with `required: false` and `description: null`; schema field order follows CSV column order (left to right)

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

#### Scenario: Column name not in schema is discarded (APPEND mode only)
- **WHEN** client imports with `importMode=APPEND`, the dataset `testCaseSchema` is non-empty, and a CSV column name does not match any schema field
- **THEN** system SHALL discard that column's values and NOT store them in `data`; no validation warning is added for the unknown column itself
- **Note:** This filtering applies only to `APPEND` mode with a non-empty dataset schema. In `OVERRIDE` mode all CSV data columns are stored (schema is replaced). In `MERGE` mode all CSV data columns are stored (all columns end up in the merged schema — either existing or newly added).

#### Scenario: Auto-detect type inference
- **WHEN** system auto-detects schema from CSV
- **THEN** system SHALL scan all row values per column and infer type: all non-empty values parse as JSON objects → OBJECT; JSON arrays → ARRAY; literal `true`/`false` (case-insensitive, NOT `1`/`0`) → BOOLEAN; whole numbers (including `1`/`0`) → INTEGER; decimal numbers → NUMBER; otherwise → STRING

#### Scenario: Auto-detected schema in preview
- **WHEN** client calls the CSV import preview endpoint and `testCaseSchema` would be auto-detected or schema would be replaced/merged
- **THEN** the preview response SHALL include `autoDetectedSchema` reflecting what would change

#### Scenario: CSV with only reserved columns
- **WHEN** CSV has only `testCaseName` with no data columns, and the dataset's `testCaseSchema` is empty
- **THEN** schema stays empty (no fields to auto-detect)

#### Scenario: JSON array cell stored as array when schema is empty
- **WHEN** a CSV cell value is a valid JSON array (e.g. `["a","b"]`) and there is no existing schema (OVERRIDE mode or empty schema in APPEND mode)
- **THEN** system SHALL parse the cell value and store it in `data` as a JSON array, not as a raw string

#### Scenario: JSON array cell stored as array when schema has no type for that field
- **WHEN** a CSV cell value is a valid JSON array and the column is a new field being added in MERGE mode (no existing schema type for this field)
- **THEN** system SHALL parse the cell value and store it as a JSON array; the auto-detected schema type for that field SHALL be ARRAY

#### Scenario: JSON object cell stored as object when no schema type known
- **WHEN** a CSV cell value is a valid JSON object (e.g. `{"key":"value"}`) and there is no schema type known for that column (empty schema or new MERGE column)
- **THEN** system SHALL parse and store it as a JSON object; auto-detected schema type SHALL be OBJECT

#### Scenario: Invalid JSON in array-looking cell falls back to string
- **WHEN** a CSV cell value starts with `[` or `{` but is not valid JSON
- **THEN** system SHALL store the raw cell string value unchanged; the auto-detected schema type SHALL be STRING

#### Scenario: Preview sample rows reflect coerced values
- **WHEN** client calls the CSV import preview endpoint and the suite has an existing `testCaseSchema` with declared field types
- **THEN** the preview sample rows SHALL reflect coerced values (e.g., a STRING-typed column with CSV value `1865` will show `"1865"` in preview, not `1865`)
- **Note:** `parseRow()` is shared between preview and import. Schema type auto-detection (inference) in preview is unaffected — only per-cell value coercion applies when a schema type is known.

#### Scenario: Numeric cell coerced to string when schema type is STRING
- **WHEN** a CSV cell value is `1865` (parsed as Long by heuristic) and the schema declares the column type as `STRING`
- **THEN** system SHALL coerce the value to String `"1865"` before storage; JSONB SHALL contain `"1865"` (JSON string), not `1865` (JSON number)

#### Scenario: Decimal cell coerced to string when schema type is STRING
- **WHEN** a CSV cell value is `3.14` (parsed as Double by heuristic) and the schema declares the column type as `STRING`
- **THEN** system SHALL coerce the value to String `"3.14"` before storage

#### Scenario: Boolean-like cell coerced to string when schema type is STRING
- **WHEN** a CSV cell value is `true` (parsed as Boolean by heuristic) and the schema declares the column type as `STRING`
- **THEN** system SHALL coerce the value to String `"true"` before storage

#### Scenario: Numeric "1" and "0" coerced to string when schema type is STRING
- **WHEN** a CSV cell value is `1` or `0` (parsed as Long after CsvCellParser fix) and the schema declares the column type as `STRING`
- **THEN** system SHALL coerce the value to String `"1"` or `"0"` before storage via `String.valueOf(value)`
- **Note:** After the CsvCellParser fix, `1`/`0` are no longer parsed as booleans — they are `Long(1)` / `Long(0)`. `String.valueOf(Long(1))` = `"1"`, which is lossless.

#### Scenario: String value coerced to Long when schema type is INTEGER
- **WHEN** a CSV cell value is a valid integer string (e.g., parsed as String in an edge case, or a string from the JSONB round-trip) and the schema declares `INTEGER`
- **THEN** system SHALL attempt `Long.parseLong()` and store the Long value

#### Scenario: Large integer string coerced to Long when schema type is INTEGER
- **WHEN** a CSV cell value is `3000000000` (exceeds `Integer.MAX_VALUE`, parsed as Long by heuristic) and the schema declares `INTEGER`
- **THEN** system SHALL store as `Long(3000000000)` (no-op — already correct type)
- **Note:** This scenario would silently fail with `Integer.parseInt()`. Using `Long.parseLong()` ensures values up to `Long.MAX_VALUE` are handled correctly.

#### Scenario: Whole double value coerced to Long when schema type is INTEGER
- **WHEN** a CSV cell value is `3.0` (parsed as Double, no fractional part) and the schema declares `INTEGER`
- **THEN** system SHALL coerce to Long `3`

#### Scenario: Fractional double value not coerced to integer (coercion failure)
- **WHEN** a CSV cell value is `3.14` (parsed as Double, has fractional part) and the schema declares `INTEGER`
- **THEN** system SHALL NOT truncate to Long `3`; the original Double value SHALL be stored unchanged and downstream validation will catch the type mismatch
- **Note:** Silent truncation of `3.14` to `3` would lose data. Only whole-number Doubles (where `value % 1 == 0`) are coerced to Long.

#### Scenario: Negative integer coerced correctly when schema type is INTEGER
- **WHEN** a CSV cell value is `-42` (parsed as Long by heuristic) and the schema declares `INTEGER`
- **THEN** system SHALL store as Long `-42` (no-op — already correct type)

#### Scenario: CSV "1"/"0" stored as Long when schema type is INTEGER
- **WHEN** a CSV cell value is `1` or `0` (parsed as Long after CsvCellParser fix) and the schema declares the column type as `INTEGER`
- **THEN** system SHALL store as Long `1` / Long `0` (no-op — already correct type)
- **Note:** After the CsvCellParser fix, `1`/`0` are parsed as `Long`, matching the `INTEGER` schema type directly.

#### Scenario: Boolean value coerced to Long when schema type is INTEGER
- **WHEN** a data value is `Boolean(true)` or `Boolean(false)` (e.g., from literal `"true"`/`"false"` in CSV) and the schema declares `INTEGER`
- **THEN** system SHALL coerce `true` to Long `1` and `false` to Long `0`

#### Scenario: Boolean value coerced to number when schema type is NUMBER
- **WHEN** a data value is `Boolean(true)` or `Boolean(false)` (e.g., from literal `"true"`/`"false"` in CSV) and the schema declares `NUMBER`
- **THEN** system SHALL coerce `true` to Double `1.0` and `false` to Double `0.0`

#### Scenario: String value coerced to number when schema type is NUMBER
- **WHEN** a CSV cell value is a valid numeric string and the schema declares `NUMBER`
- **THEN** system SHALL attempt `Double.parseDouble()` and store the number value

#### Scenario: Integer value promoted to double when schema type is NUMBER
- **WHEN** a CSV cell value is `42` (parsed as Long) and the schema declares `NUMBER`
- **THEN** system SHALL coerce to Double `42.0`

#### Scenario: Integer coerced to boolean when schema type is BOOLEAN
- **WHEN** a CSV cell value is `1` or `0` (parsed as Long after CsvCellParser fix) and the schema declares `BOOLEAN`
- **THEN** system SHALL coerce Long to Boolean: `!= 0` → `true`, `0` → `false`

#### Scenario: String coerced to boolean when schema type is BOOLEAN
- **WHEN** a CSV cell value is `"true"` or `"false"` (as String, not caught by heuristic — edge case) and the schema declares `BOOLEAN`
- **THEN** system SHALL coerce to the corresponding Boolean value

#### Scenario: Double value not coerced to boolean (coercion failure)
- **WHEN** a CSV cell value is a Double (e.g., `3.14` or `1.0`) and the schema declares `BOOLEAN`
- **THEN** system SHALL NOT coerce Double to Boolean; the original Double value SHALL be stored unchanged and downstream validation will catch the type mismatch
- **Note:** Unlike `INTEGER ← Double` which coerces whole-number Doubles, `BOOLEAN ← Double` always fails because floating-point truthiness is ambiguous. Integer-to-boolean coercion (`!= 0`) is well-defined; Double-to-boolean is not.

#### Scenario: Empty string not coerced to non-STRING types (coercion failure)
- **WHEN** a CSV cell is blank (parsed as empty string `""` by CsvCellParser) and the schema declares a non-STRING type (`INTEGER`, `NUMBER`, `BOOLEAN`)
- **THEN** system SHALL NOT coerce the empty string; the original empty string SHALL be stored unchanged and downstream validation will catch the type mismatch
- **Note:** `Long.parseLong("")`, `Double.parseDouble("")`, and boolean parsing all fail for empty strings. For STRING-typed columns, the empty string is stored as-is (no-op).

#### Scenario: Coercion failure preserves original value
- **WHEN** a CSV cell value cannot be coerced to the declared schema type (e.g., value is `"hello"` but schema declares `INTEGER`)
- **THEN** system SHALL store the original parsed value unchanged; downstream validation will catch the type mismatch

#### Scenario: No inline coercion when schema type is unknown
- **WHEN** a column has no declared schema type during parseRow (null in fieldTypes map — e.g., empty schema in APPEND mode)
- **THEN** system SHALL store the heuristic-parsed value initially; the post-persist fixup pass will coerce after schema is finalized

#### Scenario: Post-persist fixup corrects types after schema auto-detection
- **WHEN** CSV is imported with an empty schema (or OVERRIDE mode) and schema is auto-detected from CSV content
- **THEN** after schema is persisted, the system SHALL re-read all suite test cases in batches, coerce values for columns with newly determined types using `SchemaTypeCoercer`, re-validate each test case, and batch-update any rows whose data changed
- **Note:** This ensures type correctness even for rows stored before the final schema type was known (e.g., a column widened from INTEGER to STRING during import).

#### Scenario: Post-persist fixup for MERGE mode new columns
- **WHEN** CSV is imported in MERGE mode and new columns are added to the existing schema
- **THEN** the fixup pass SHALL coerce values only for the newly added columns; existing columns (already in schema before import) are not re-coerced

#### Scenario: No fixup when schema is unchanged
- **WHEN** CSV is imported in APPEND mode with an existing non-empty schema
- **THEN** the system SHALL NOT run the fixup pass (inline coercion in parseRow already handled all columns)

#### Scenario: FILE-typed column coerced to string
- **WHEN** a CSV cell value is in a column declared as `FILE` type and the parsed value is not already a String (e.g., Long or Boolean)
- **THEN** system SHALL coerce the value to String via `String.valueOf(value)` (file references are always strings, same coercion as STRING type)
- **Note:** If the parsed value is already a String, it is stored as-is (no-op).

### Requirement: Import test cases (CSV or ZIP)
The import endpoint SHALL accept both CSV files and ZIP archives. The file format is detected by file extension or content.

#### Scenario: Import CSV file (unchanged)
- **WHEN** client sends `POST /api/v1/datasets/{datasetId}/test-cases/import` with a `.csv` file
- **AND** the suite's `testCaseSchema` has no FILE type fields
- **THEN** system SHALL process the CSV using the current import flow

#### Scenario: Import ZIP archive
- **WHEN** client sends `POST /api/v1/datasets/{datasetId}/test-cases/import` with a `.zip` file
- **THEN** system SHALL:
  1. Extract and parse `test-cases.csv` from the archive
  2. For each FILE column value (relative path), find the corresponding file in the archive's `files/` directory
  3. Sanitize the original filename by replacing any characters outside the allowed set (alphanumeric, `-`, `_`, `.`, ` `, `(`, `)`) with `_`, then generate a unique DIAL filename (e.g., `{rowIndex}_{fieldName}_{sanitizedFilename}`) to avoid collisions in the flat suite folder
  4. Upload each file to DIAL storage at `{efBucket}/suites/{suiteId}/{uniqueFilename}` via `DialFileClient`
  5. Map the DIAL file path (`files/@ef/suites/{suiteId}/{uniqueFilename}`) into the test case's `data` field
  6. Create test cases with the resolved data

#### Scenario: Import ZIP with missing file
- **WHEN** a CSV row references a file path (e.g., `files/1/doc/report.pdf`) that does not exist in the ZIP archive
- **THEN** system SHALL produce a validation warning for that test case and set the FILE field value to null

#### Scenario: Import CSV for suite with FILE fields
- **WHEN** client imports a CSV file for a suite whose schema has FILE fields
- **THEN** system SHALL treat FILE columns as string values (DIAL file paths if provided, or empty for null); no file extraction is performed
- **AND** FILE field validation may produce warnings if the DIAL file paths have invalid format or disallowed prefix

### Requirement: Import preview (CSV or ZIP)
The import preview endpoint SHALL support both CSV and ZIP formats with the same detection logic as the import endpoint.

The preview response SHALL report both `totalRows` — the number of CSV data rows parsed — and `totalTestCases` — the number of test cases those rows assemble into. The two differ only when the CSV contains multi-turn cases, whose turn rows assemble into one case each; for a single-turn CSV they are equal. Both describe the CSV as submitted and SHALL NOT be reduced by rows a conflict strategy would skip.

`sampleRows` SHALL contain assembled test cases (bounded by the sample limit), not raw CSV rows. A sample for a multi-turn case SHALL carry its `multiTurnData` turn array and its shared `data`; a sample for a single-turn case SHALL carry a flat `data` with no turn array.

Status: **Implemented**

#### Scenario: Preview CSV file
- **WHEN** client sends `POST /api/v1/datasets/{datasetId}/test-cases/import/preview` with a CSV file
- **THEN** system SHALL return the preview (current behavior)

#### Scenario: Preview ZIP archive
- **WHEN** client sends `POST /api/v1/datasets/{datasetId}/test-cases/import/preview` with a ZIP file
- **THEN** system SHALL extract and preview the `test-cases.csv` within the archive
- **AND** FILE columns SHALL show the relative paths from the CSV (not DIAL file paths, since files are not yet uploaded during preview)

#### Scenario: Preview reports test case count alongside row count
- **WHEN** client previews a CSV whose rows include a multi-turn case of N turns
- **THEN** `totalRows` SHALL count every CSV data row and `totalTestCases` SHALL count the N turn rows as one test case

#### Scenario: Multi-turn sample carries the validity import would produce
- **WHEN** client previews a CSV containing a multi-turn case
- **THEN** the sample's validity and warnings SHALL be those the import would compute for the assembled case — schema validation of its shared and per-turn data, merged with any multi-turn conflict — not a default or a per-row verdict

#### Scenario: Single-turn CSV preview is unchanged apart from the new count
- **WHEN** client previews a CSV containing no `turnIndex` values
- **THEN** `totalTestCases` SHALL equal `totalRows`, each sample row SHALL carry a flat `data` with no turn array, and no other previously reported field SHALL change value

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

Collision and duplicate detection SHALL key on the **assembled test case**, not the raw CSV row. Consecutive rows sharing a `testCaseName` and carrying a `turnIndex` that parses as an integer assemble into one multi-turn test case and SHALL count as a single name occurrence — turn rows of one case are never duplicates of each other. Consecutive rows sharing a `testCaseName` with a blank `turnIndex` remain separate test cases and SHALL each count as an occurrence, so same-named single-turn rows collide exactly as before.

Status: **Implemented**

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

#### Scenario: Turn rows of one case are not a name collision
- **WHEN** client imports or previews a CSV whose consecutive rows share a `testCaseName` and carry distinct non-blank `turnIndex` values
- **THEN** the system SHALL treat them as one test case name occurrence — no duplicate warning on import or preview, no HTTP 409 under `FAIL`, and no `skippedCount`/`overriddenCount` increment under `SKIP`/`OVERRIDE`

#### Scenario: Same-named single-turn rows still collide
- **WHEN** client imports or previews a CSV with two adjacent rows carrying the same `testCaseName` and a blank `turnIndex`
- **THEN** the second row SHALL be treated as a within-CSV duplicate exactly as before, per the chosen `conflictStrategy`

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

Replacement covers field **membership and types** only. Each field's `perTurn` scope SHALL be carried forward from the dataset's current schema by field name, because a CSV expresses values and never scope — see the `multi-turn-test-case` capability, requirement *CSV schema rebuild preserves per-field scope*. A CSV column with no same-named field in the current schema is a new field and SHALL be persisted with `perTurn` absent.

Status: **Implemented**

#### Scenario: OVERRIDE replaces existing schema
- **WHEN** client calls import with `importMode=OVERRIDE` and the suite has an existing `testCaseSchema`
- **THEN** system SHALL replace the schema with the auto-detected schema from CSV, persist it, and bump the suite version

#### Scenario: OVERRIDE with empty schema auto-detects
- **WHEN** client calls import with `importMode=OVERRIDE` and the suite's `testCaseSchema` is empty
- **THEN** system SHALL auto-detect schema from CSV, persist, and bump suite version (same as when schema exists)

#### Scenario: OVERRIDE preview shows replacement schema
- **WHEN** client calls preview with `importMode=OVERRIDE`
- **THEN** the preview response SHALL include `autoDetectedSchema` regardless of whether the suite already has a schema

#### Scenario: OVERRIDE replacement keeps field scope
- **WHEN** client calls import with `importMode=OVERRIDE` and an existing schema field is marked `perTurn: true`
- **THEN** the replacement schema SHALL still mark that field `perTurn: true`, while its type is re-derived from the CSV as usual

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

### Requirement: Validate TestCases against schema (Soft Validation)
The service SHALL validate `data` against the referenced **dataset's** `testCaseSchema`. Validation failures produce warnings (not rejection). `valid=false` when any validation fails. Per-test-case template/binding overrides are removed from the model; "effective template" and "effective bindings" no longer exist at the test-case level — the suite's template and bindings apply uniformly to all test cases in the suite's runs.

TestCase validation includes REQUIRED, ADDITIONAL/UNKNOWN, TYPE, and FILE format checks against the dataset schema. The schema-change revalidation path uses `SchemaChangeCoercer` (strict coercion rules); direct API writes and CSV import use `SchemaTypeCoercer` (permissive). The `Schema-change revalidation auto-coerces test case data` requirement (preserved from the baseline) continues to define the strict coercion table — only the *trigger* changes (now: dataset-rooted RevalidationTask spawned from dataset PUT).

**Layering**: TestCase `valid` covers data-specific checks only and is independent of suite-level `valid`. Suite-level warnings (urlTemplate null, binding coverage, binding references against dataset schema, schema conformance) are stored on the TestSuite. The client/UI combines both flags to determine overall test readiness.

During CSV import and preview, validation SHALL use the **target dataset schema** (the schema that will be in effect after import commits) rather than the pre-import dataset schema, derived per `importMode` exactly as before but resolved against the dataset.
Status: **Planned**

#### Scenario: Missing required field in data
- **WHEN** a `testCaseSchema` field has `required: true` and the corresponding key is missing or null in `data`
- **THEN** system SHALL set valid=false and add a validation warning with `fieldName`

#### Scenario: Type mismatch in data
- **WHEN** a `data` value does not match the `testCaseSchema` field's declared `type`
- **THEN** system SHALL set valid=false and add a validation warning with `fieldName` and expected type

#### Scenario: Type mismatch warning for STRING field with numeric value
- **WHEN** a test case data field contains a numeric value (Integer, Long, or Double) and the schema declares the field type as `STRING`
- **THEN** system SHALL emit a `TYPE` validation warning with message indicating the expected and actual types
- **AND** the test case SHALL still be saved with `isValid=false`

#### Scenario: Type mismatch warning for STRING field with boolean value
- **WHEN** a test case data field contains a Boolean value and the schema declares the field type as `STRING`
- **AND** the value is reaching the validator via a direct API write (POST/PUT/PATCH) or CSV import
- **THEN** system SHALL emit a `TYPE` validation warning
- **NOTE**: On the schema-change revalidation path, `Boolean → STRING` is auto-coerced by `SchemaChangeCoercer` (`true` → `"true"`, `false` → `"false"`) BEFORE validation runs, and no TYPE warning is emitted.

#### Scenario: Type mismatch warning for INTEGER field with string value
- **WHEN** a test case data field contains a String value and the schema declares the field type as `INTEGER`
- **THEN** system SHALL emit a `TYPE` validation warning

#### Scenario: Type mismatch warning for BOOLEAN field with non-boolean value
- **WHEN** a test case data field contains a String, Double, Integer, or Long value and the schema declares the field type as `BOOLEAN`
- **THEN** system SHALL emit a `TYPE` validation warning
- **Note:** On the CSV import path, `BOOLEAN ← Long` is coerced successfully before validation runs, so Long values in BOOLEAN columns will not produce a TYPE warning after CSV import. On the API path (POST/PUT/PATCH), no coercion runs — Integer, Long, and Double values in BOOLEAN columns will produce TYPE warnings. On the schema-change revalidation path, `String → BOOLEAN` is coerced ONLY for the literal values `"true"` / `"false"` (other strings, and Integer/Long/Double, remain unchanged and produce TYPE warnings).

#### Scenario: NUMBER field accepts integer values without warning
- **WHEN** a test case data field contains an Integer or Long value and the schema declares the field type as `NUMBER`
- **THEN** system SHALL NOT emit a TYPE warning (integers are valid numbers)

#### Scenario: No type warning for null values
- **WHEN** a test case data field is null and the schema declares any type
- **THEN** system SHALL NOT emit a TYPE warning for null (nulls are handled by the existing REQUIRED check)

#### Scenario: No type warning when schema type is unknown
- **WHEN** a data field has no corresponding schema field definition
- **THEN** system SHALL NOT emit a TYPE warning (the existing ADDITIONAL/UNKNOWN check handles this)

#### Scenario: CSV import with coercion produces no type warnings
- **WHEN** a CSV is imported and the SchemaTypeCoercer successfully coerces values to match schema types
- **THEN** validation SHALL NOT emit TYPE warnings for those fields (types already match after coercion)

#### Scenario: API create with type mismatch produces warning
- **WHEN** a client creates a test case via POST API with `{"data": {"answer": 1865}}` and schema declares `answer` as `STRING`
- **THEN** system SHALL save the test case with `isValid=false` and a TYPE warning for field `answer`

#### Scenario: Coercion failure produces type mismatch warning
- **WHEN** a CSV cell value is `"hello"` (parsed as String) and the schema declares `INTEGER`
- **THEN** system SHALL store the original String value `"hello"` unchanged (coercion failure)
- **AND** validation SHALL emit a `TYPE` warning for the field (`isValid=false`)
- **Note:** Coercion failure and type mismatch warning are two sides of the same coin — the coercer returns the value unchanged, then the validation layer detects the mismatch.

#### Scenario: Mixed-type auto-detected column widens to STRING without warnings
- **WHEN** CSV is imported with empty schema and a column has values `42`, `99`, `hello` across rows
- **THEN** auto-detection SHALL widen the column type to `STRING` (INTEGER + STRING → STRING)
- **AND** the post-persist fixup pass SHALL coerce the integer values `42` and `99` to strings `"42"` and `"99"`
- **AND** all test cases SHALL have `isValid=true` for that field (all values are strings matching STRING type)

#### Scenario: Schema-declared INTEGER column with non-integer value during APPEND
- **WHEN** CSV is imported with `importMode=APPEND`, existing schema declares a field as `INTEGER`, and one CSV row has value `"hello"` for that field
- **THEN** system SHALL attempt coercion (`Long.parseLong("hello")` fails), store `"hello"` as String
- **AND** validation SHALL produce a `TYPE` warning for that test case (`isValid=false`)
- **AND** other rows with valid integer values SHALL have `isValid=true` for that field

#### Scenario: CSV export-import round-trip preserves typed values
- **WHEN** test cases with INTEGER, NUMBER, BOOLEAN, and STRING fields are exported to CSV and re-imported into the same suite (OVERRIDE mode) with the same schema
- **THEN** the re-imported data values SHALL be type-equivalent to the originals after coercion (no type drift across export-import cycles)
- **Note:** CSV export writes all values as strings. Re-import parses heuristically, then coercion restores the original types from the schema.

#### Scenario: No effective-template/binding warnings
- **WHEN** a test case fails validation
- **THEN** validation SHALL NOT emit warnings about "Required template variable unbound" or "Required field has no value in data" stemming from per-case overrides — those scenarios no longer apply because TestCase carries no overrides; binding/variable warnings now live exclusively on the suite (`SuiteValidationService`)

#### Scenario: Unknown fields in data (not in schema)
- **WHEN** `data` contains keys not present in `testCaseSchema`
- **THEN** system SHALL add a validation warning (e.g., "Field 'x' is not defined in testCaseSchema")

#### Scenario: Schema validation against endpoint (if schema present)
- **WHEN** endpoint `requestBodySchema` is present
- **THEN** system SHALL resolve the full request (template + bindings + data) and soft-validate against the schema

#### Scenario: Get validation warnings on request
- **WHEN** client calls `GET .../test-cases/{id}?includeWarnings=true` for invalid case
- **THEN** system SHALL include validationWarnings in response; without param, omit them

#### Scenario: Schema update triggers async re-validation
- **WHEN** a Dataset's `testCaseSchema` is updated via PUT
- **THEN** system SHALL respond HTTP 202 with `RevalidationTaskDto` and spawn a dataset-rooted `RevalidationTask` (see `datasets` spec)

#### Scenario: Track re-validation task status
- **WHEN** client calls `GET /api/v1/datasets/{id}/revalidation-tasks/{taskId}`
- **THEN** system SHALL return task status (PENDING, RUNNING, COMPLETED, FAILED, TIMED_OUT) and progress (`processedCases`, `validCount`, `invalidCount`, `coercedCellCount`)
- **AND** `coercedCellCount` SHALL default to `0` for tasks created before this feature shipped and for tasks where no cells required coercion

#### Scenario: PATCH always recalculates valid
- **WHEN** client calls PATCH with any field
- **THEN** system SHALL recalculate valid from current schema

#### Scenario: CSV import OVERRIDE mode validates against target schema
- **WHEN** client imports CSV with `importMode=OVERRIDE` and the suite has an existing schema with different fields than the CSV
- **THEN** system SHALL validate each row against the target schema derived from CSV headers (not the old suite schema); imported test cases SHALL have `valid=true` when data matches the CSV-derived schema

#### Scenario: CSV import OVERRIDE mode with empty suite schema validates correctly
- **WHEN** client imports CSV with `importMode=OVERRIDE` and the suite's `testCaseSchema` is empty
- **THEN** system SHALL validate each row against the target schema derived from CSV headers; imported test cases SHALL NOT have "Unknown data field" warnings for columns present in the CSV

#### Scenario: CSV import MERGE mode with new columns validates against merged schema
- **WHEN** client imports CSV with `importMode=MERGE` and the CSV has columns not in the existing suite schema
- **THEN** system SHALL validate each row against the merged schema (existing fields + new CSV columns); new columns SHALL NOT produce "Unknown data field" warnings

#### Scenario: CSV import APPEND mode with empty schema validates against target schema
- **WHEN** client imports CSV with `importMode=APPEND` and the suite's `testCaseSchema` is empty
- **THEN** system SHALL validate each row against the target schema derived from CSV headers; imported test cases SHALL have `valid=true` when data matches the CSV-derived schema

#### Scenario: CSV preview validates sample rows against target schema
- **WHEN** client calls the CSV import preview endpoint with any mode where the schema would change
- **THEN** system SHALL validate sample rows against the target schema (not the pre-import schema); sample rows SHALL NOT have spurious "Unknown data field" warnings for columns present in the CSV

### Requirement: Schema-change revalidation auto-coerces test case data
The system SHALL run a value-coercion pass over each test case's `data` map BEFORE validation when the dataset-rooted `RevalidationTask` (Phase 1) re-validates existing test cases after a **Dataset** schema change (dataset PUT mutating `testCaseSchema`). Coercion MUST use the strict schema-change conversion table below (narrower than the CSV-import table used by `SchemaTypeCoercer`) and SHALL be implemented by a separate `SchemaChangeCoercer` (`service.domain.csv` package). For each `(value, targetType)` pair where the target type is the field's current dataset schema type, the coercer SHALL return either a converted value (if the conversion is in the safe set) or the input value unchanged (if not). When at least one cell in a row is converted, the new `data` JSONB SHALL be persisted in a guarded UPDATE (precondition: `updated_at = :seenAt`) BEFORE validation runs against the post-coercion data. Validation results (`is_valid`, `validation_warnings`) SHALL be persisted in a SECOND guarded UPDATE. If either guarded UPDATE affects 0 rows (concurrent edit), the row SHALL be skipped entirely (no further writes).
Status: **Planned**

**Strict schema-change conversion table** (rows = source Java type, columns = target `SchemaFieldType`; "skip" = return input unchanged):

| Source ↓ \ Target →     | STRING                | INTEGER             | NUMBER                | BOOLEAN                  | FILE         | OBJECT     | ARRAY      |
|-------------------------|-----------------------|---------------------|-----------------------|--------------------------|--------------|------------|------------|
| String                  | identity              | `Long.parseLong`    | `Double.parseDouble`  | `"true"`/`"false"` only  | identity     | skip       | skip       |
| Integer/Long            | `String.valueOf`      | identity            | `doubleValue`         | skip                     | skip         | skip       | skip       |
| Double                  | `String.valueOf`      | only if `% 1 == 0`  | identity              | skip                     | skip         | skip       | skip       |
| Boolean                 | `"true"`/`"false"`    | skip                | skip                  | identity                 | skip         | skip       | skip       |
| Object (Map)            | skip                  | skip                | skip                  | skip                     | skip         | identity   | skip       |
| Array (List)            | skip                  | skip                | skip                  | skip                     | skip         | skip       | identity   |
| `null`                  | identity              | identity            | identity              | identity                 | identity     | identity   | identity   |

This table is intentionally **stricter** than `SchemaTypeCoercer` used by CSV import: `Integer/Long → BOOLEAN`, `Double → BOOLEAN`, `Boolean → INTEGER/NUMBER`, `Object/Array → STRING`, and any non-String → FILE are all **excluded** to avoid silently reinterpreting typed JSON in ways the user did not request.

#### Scenario: Boolean field type changed to STRING coerces existing values
- **WHEN** a Dataset schema field's type is changed from `BOOLEAN` to `STRING` and revalidation runs
- **AND** an existing test case has `data: {"flag": true}`
- **THEN** the system SHALL persist `data: {"flag": "true"}` (Java String) before validation
- **AND** validation SHALL set `isValid=true` (no TYPE warning) for that field
- **AND** the revalidation task `coercedCellCount` SHALL be incremented by 1

#### Scenario: Integer field type changed to STRING coerces existing values
- **WHEN** a schema field's type is changed from `INTEGER` to `STRING` and revalidation runs
- **AND** an existing test case has `data: {"year": 1865}` (Long)
- **THEN** the system SHALL persist `data: {"year": "1865"}` and `isValid=true`

#### Scenario: Number field type changed to STRING coerces existing values
- **WHEN** a schema field's type is changed from `NUMBER` to `STRING` and revalidation runs
- **AND** an existing test case has `data: {"score": 3.14}` (Double)
- **THEN** the system SHALL persist `data: {"score": "3.14"}` and `isValid=true`

#### Scenario: Integer field type changed to NUMBER coerces existing values
- **WHEN** a schema field's type is changed from `INTEGER` to `NUMBER` and revalidation runs
- **AND** an existing test case has `data: {"n": 42}` (Long)
- **THEN** the system SHALL persist `data: {"n": 42.0}` (Double) and `isValid=true`

#### Scenario: String field type changed to BOOLEAN coerces "true"/"false" only
- **WHEN** a schema field's type is changed from `STRING` to `BOOLEAN` and revalidation runs
- **AND** an existing test case has `data: {"flag": "true"}`
- **THEN** the system SHALL persist `data: {"flag": true}` (Boolean) and `isValid=true`
- **AND** when another test case has `data: {"flag": "yes"}`, the value SHALL be left unchanged and the row SHALL be marked invalid with a TYPE warning

#### Scenario: String field type changed to INTEGER coerces parseable values only
- **WHEN** a schema field's type is changed from `STRING` to `INTEGER` and revalidation runs
- **AND** an existing test case has `data: {"n": "42"}`
- **THEN** the system SHALL persist `data: {"n": 42}` (Long) and `isValid=true`
- **AND** when another test case has `data: {"n": "hello"}`, the value SHALL be left unchanged and the row SHALL be marked invalid with a TYPE warning

#### Scenario: Whole-number Double field type changed to INTEGER coerces successfully
- **WHEN** a schema field's type is changed from `NUMBER` to `INTEGER` and revalidation runs
- **AND** an existing test case has `data: {"n": 7.0}` (Double, whole number)
- **THEN** the system SHALL persist `data: {"n": 7}` (Long) and `isValid=true`

#### Scenario: Fractional Double field type changed to INTEGER does not truncate
- **WHEN** a schema field's type is changed from `NUMBER` to `INTEGER` and revalidation runs
- **AND** an existing test case has `data: {"n": 3.14}` (Double, fractional)
- **THEN** the system SHALL leave the value unchanged and the row SHALL be marked invalid with a TYPE warning
- **NOTE**: Truncating `3.14` → `3` would lose data; only whole-number Doubles are coerced.

#### Scenario: Integer-to-BOOLEAN is NOT auto-coerced (stricter than CSV import)
- **WHEN** a schema field's type is changed from `INTEGER` to `BOOLEAN` and revalidation runs
- **AND** an existing test case has `data: {"flag": 1}` (Long)
- **THEN** the system SHALL leave the value unchanged and the row SHALL be marked invalid with a TYPE warning
- **NOTE**: CSV import would coerce `1` → `true`, but on the revalidation path the user did not request that interpretation; the row stays invalid until the user explicitly fixes the data.

#### Scenario: Boolean-to-INTEGER is NOT auto-coerced (stricter than CSV import)
- **WHEN** a schema field's type is changed from `BOOLEAN` to `INTEGER` and revalidation runs
- **AND** an existing test case has `data: {"n": true}` (Boolean)
- **THEN** the system SHALL leave the value unchanged and the row SHALL be marked invalid with a TYPE warning

#### Scenario: Non-String value targeting FILE is NOT auto-coerced
- **WHEN** a schema field's type is changed to `FILE` and revalidation runs
- **AND** an existing test case has a non-String value (Boolean, Number, Object, Array) for that field
- **THEN** the system SHALL leave the value unchanged and the row SHALL be marked invalid with a TYPE warning
- **NOTE**: The validator emits TYPE (not FILE-format) because the value is the wrong Java type and never reaches the FILE-format check. A FILE value must be a valid DIAL file reference; coercing a Boolean/Number to a String would produce text that is not a file path and would still fail downstream validation.

#### Scenario: String value targeting FILE is preserved as-is
- **WHEN** a schema field's type is changed from `STRING` to `FILE` and revalidation runs
- **AND** an existing test case has `data: {"f": "@ef/suites/abc/foo.png"}`
- **THEN** the system SHALL leave the value unchanged (identity coercion)
- **AND** validation SHALL apply the standard FILE format/prefix check (passing for valid `@ef/...` references, warning otherwise)

#### Scenario: Object-to-STRING is NOT auto-coerced
- **WHEN** a schema field's type is changed to `STRING` and revalidation runs
- **AND** an existing test case has `data: {"obj": {"a": 1}}` (Map)
- **THEN** the system SHALL leave the value unchanged and the row SHALL be marked invalid with a TYPE warning
- **NOTE**: `String.valueOf(Map)` produces Java's debug form (e.g. `{a=1}`), which is rarely the user's intent; treat as unconvertible.

#### Scenario: Array-to-STRING is NOT auto-coerced
- **WHEN** a schema field's type is changed to `STRING` and revalidation runs
- **AND** an existing test case has `data: {"arr": [1, 2, 3]}` (List)
- **THEN** the system SHALL leave the value unchanged and the row SHALL be marked invalid with a TYPE warning

#### Scenario: Null values are never coerced
- **WHEN** any test case has `data: {"f": null}` and revalidation runs against any target type
- **THEN** the system SHALL leave the null unchanged
- **AND** the row SHALL be marked invalid only if the field is required (existing REQUIRED check)

#### Scenario: Already-matching types yield zero coerced cells
- **WHEN** revalidation runs and every cell already matches its current schema type (e.g. revalidation re-run after a prior coerced run)
- **THEN** no `data` UPDATE SHALL be issued for that row
- **AND** only the validation UPDATE SHALL fire
- **AND** the task `coercedCellCount` SHALL remain 0 if no prior coercion occurred in this run

#### Scenario: Per-row data update is guarded by updated_at
- **WHEN** revalidation reads a test case row with `updated_at = T0`, computes coerced data, and another caller PATCHes the row to `updated_at = T1` before the data UPDATE runs
- **THEN** the guarded UPDATE SHALL affect 0 rows
- **AND** revalidation SHALL skip both the data write and the validation write for that row
- **AND** the user's edit at T1 SHALL remain intact
- **AND** the row SHALL count toward `processedCases` but SHALL NOT increment `validCount` or `invalidCount`

#### Scenario: Per-row validation update is guarded by updated_at
- **WHEN** revalidation successfully writes coerced data and then attempts the validation UPDATE, but a concurrent edit changes `updated_at` between the two writes
- **THEN** the second guarded UPDATE SHALL affect 0 rows
- **AND** revalidation SHALL skip the validation write; subsequent revalidations will reconcile
- **AND** the row SHALL count toward `processedCases` but SHALL NOT increment `validCount` or `invalidCount`

#### Scenario: Coerced cell counter accumulates across rows
- **WHEN** a revalidation task processes 100 test cases, each with 3 BOOLEAN→STRING fields successfully coerced
- **THEN** the task's `coercedCellCount` SHALL be 300 at completion
- **AND** the task response SHALL include `coercedCellCount` as a Long

### Requirement: RevalidationTask exposes coercedCellCount
The `revalidation_tasks` table SHALL include a `coerced_cell_count BIGINT NOT NULL DEFAULT 0` column, and the `RevalidationTaskDto` returned by the revalidation status endpoints SHALL include a `coercedCellCount` field of type `Long`. The counter SHALL be incremented by the number of cells (one per (row, field) pair) successfully coerced during the run, and SHALL NOT count rows whose data was unchanged.

#### Scenario: Get revalidation task includes coercedCellCount
- **WHEN** client calls `GET /api/v1/datasets/{id}/revalidation-tasks/{taskId}`
- **THEN** the response body SHALL include `"coercedCellCount": <number>` (default 0 when no coercion occurred)

#### Scenario: List revalidation tasks includes coercedCellCount
- **WHEN** client calls `GET /api/v1/datasets/{id}/revalidation-tasks`
- **THEN** every entry in `content[]` SHALL include `coercedCellCount`

#### Scenario: Start revalidation HTTP 202 response includes coercedCellCount
- **WHEN** client triggers revalidation (e.g. via suite update with schema change, or `POST .../revalidation-tasks`)
- **THEN** the HTTP 202 RevalidationTaskDto body SHALL include `coercedCellCount: 0` (initial value)

#### Scenario: Pre-existing tasks expose 0 coercedCellCount
- **WHEN** the migration runs against a database with existing `revalidation_tasks` rows
- **THEN** every existing row SHALL have `coerced_cell_count = 0` (column default)
- **AND** subsequent reads via the API SHALL surface `coercedCellCount: 0` for those tasks

### Requirement: Direct API writes do NOT auto-coerce
The auto-coercion behaviour described in `Schema-change revalidation auto-coerces test case data` SHALL apply ONLY to the schema-change revalidation path (dataset-rooted `RevalidationTask`). Direct test case writes (`POST/PUT/PATCH /api/v1/datasets/{datasetId}/test-cases[/{tcId}]`) SHALL continue to validate the supplied `data` as-is and emit TYPE warnings on type mismatch. CSV import SHALL continue to use the existing `SchemaTypeCoercer` with its own permissive table, unaffected by this change.
Status: **Planned**

#### Scenario: POST test case with Boolean for STRING field still produces TYPE warning
- **WHEN** schema declares field `f` as `STRING`
- **AND** client calls `POST .../test-cases` with body `{"data": {"f": true}}`
- **THEN** the system SHALL save the test case with `isValid=false` and a TYPE warning for field `f`
- **NOTE**: Direct API writes preserve the user's literal intent; auto-coercion is reserved for cases where the user changed the schema and the system must reinterpret existing rows.

#### Scenario: PATCH test case data does not coerce values
- **WHEN** client PATCHes a test case with new `data` containing a type mismatch against the current schema
- **THEN** validation SHALL emit a TYPE warning (existing behaviour); no coercion runs

#### Scenario: CSV import retains its own (permissive) coercion rules
- **WHEN** client imports CSV containing `"1"` for a BOOLEAN-typed field
- **THEN** the existing `SchemaTypeCoercer` SHALL coerce `"1"` → `true` (Long → Boolean rule), unchanged from current behaviour
- **NOTE**: `SchemaChangeCoercer` is stricter than `SchemaTypeCoercer`; the two are sibling components in the `service.domain.csv` package.

### Requirement: FILE type validation
The system SHALL validate FILE type field values in test case data against DIAL file reference format and prefix whitelist. Validation is format-only on save; no existence check is performed. Broken file references fail at eval time on the deployment call.

#### Scenario: FILE type in testCaseSchema
- **WHEN** client creates a test suite with `testCaseSchema` containing a field with `type: FILE`
- **THEN** system SHALL accept the schema; the field's data value in test cases SHALL be a DIAL relative file path (e.g., `files/@ef/suites/{suiteId}/filename.ext` or `files/public/path/to/file.ext`)

#### Scenario: FILE field validation
- **WHEN** a test case has a field declared as `FILE` in `testCaseSchema`
- **AND** the data value is a non-null string
- **THEN** validation SHALL check that the value is a valid DIAL relative file path with an allowed prefix (`@ef` or `public`); if the format is invalid or the prefix is disallowed, a validation warning SHALL be produced

#### Scenario: FILE field with cross-suite @ef reference
- **WHEN** a test case belongs to suite with ID `suite-A`
- **AND** the FILE field value is `files/@ef/suites/suite-B/data.csv` (different suite ID)
- **THEN** validation SHALL produce a warning indicating the file reference points to a different suite's files

#### Scenario: FILE field with null value
- **WHEN** a test case has a `FILE` field with null value and the field is not required
- **THEN** validation SHALL pass without warning (no file attached)

#### Scenario: Required FILE field with null value
- **WHEN** a test case has a `FILE` field with null value and the field is required
- **THEN** validation SHALL produce a warning: "Required field '<name>' has no value in data"

### Requirement: Validate TestSuite embedded schemas
The service SHALL validate that JSON schemas embedded in TestSuite (endpointRef, testCasesDefinition) are well-formed.

#### Scenario: Invalid schema prevents TestSuite save
- **WHEN** client creates/updates TestSuite with malformed JSON Schema
- **THEN** system SHALL respond HTTP 400 and NOT persist

### Requirement: Optimistic locking for TestSuite
The service SHALL implement optimistic locking (version) for TestSuite updates and CSV import.

#### Scenario: Successful update with correct version
- **WHEN** client calls PUT with If-Match matching current version
- **THEN** system SHALL update and increment version; response SHALL include ETag and version field

#### Scenario: Version conflict
- **WHEN** client calls PUT or POST .../import with If-Match that does not match current version
- **THEN** system SHALL respond with HTTP 409 Conflict and error code VERSION_CONFLICT

### Requirement: Cascade delete for TestSuite
When a TestSuite is deleted, all suite-owned child entities (TSMDs, runs, eval-summaries) SHALL be cascade deleted. Test cases SHALL NOT be deleted (they live in the dataset and are reachable from any other suite referencing the same dataset). The delete response SHALL NOT include a `deletedTestCases` count.
Status: **Planned**

#### Scenario: Delete TestSuite with children
- **WHEN** client calls `DELETE /api/v1/test-suites/{id}` and the suite has suite-owned children (TSMDs, runs) and the referenced dataset has test cases and revalidation tasks
- **THEN** system SHALL delete only the suite-owned children; the response body (when present) SHALL contain only the deleted suite identifier and suite-owned child counters (e.g., deleted TSMDs, deleted runs, deleted eval-summaries); the body SHALL NOT include a `deletedTestCases` field and SHALL NOT include a `deletedRevalidationTasks` field — revalidation tasks live with the dataset (FK `revalidation_tasks.dataset_id` → `datasets(id) ON DELETE CASCADE`) and are not affected by suite deletion; the dataset, its test cases, and its revalidation tasks SHALL remain intact

### Requirement: Cascade delete for Dataset
Dataset deletion SHALL cascade to test cases (`test_cases.dataset_id` FK with `ON DELETE CASCADE`). Dataset deletion SHALL be rejected by FK `ON DELETE RESTRICT` if any TestSuite references the dataset (HTTP 409).
Status: **Planned**

#### Scenario: Cascade deletes test cases
- **WHEN** client successfully deletes a dataset (no suites reference it)
- **THEN** all test cases under the dataset SHALL be deleted as part of the same transaction

#### Scenario: RESTRICT prevents delete when suites depend
- **WHEN** client attempts to delete a dataset that has at least one referencing suite
- **THEN** system SHALL respond with HTTP 409; the dataset and its test cases SHALL remain intact

### Requirement: Mutable TestSuite fields
The service SHALL allow updating mutable suite fields (e.g., `deploymentRef`, `endpointRef`, `requestTemplate`, `inputBindings`, `responseColumns`, `datasetId`, `disabledTestCaseIds`). Suite PUTs SHALL trigger synchronous suite-level re-validation only; suite PUTs SHALL NOT spawn an async `RevalidationTask`. Async tasks are spawned only by dataset PUTs that mutate `testCaseSchema` — see the `datasets` and `test-suites` specs.
Status: **Planned**

#### Scenario: Update endpointRef triggers synchronous suite-level re-validation
- **WHEN** client updates `endpointRef` schema on an existing suite
- **THEN** system SHALL re-run synchronous suite-level validation (`SuiteValidationService`) against the referenced dataset's schema, update `isValid`/`validationWarnings`, return HTTP 200 with the updated suite; system SHALL NOT spawn an async `RevalidationTask` and SHALL NOT return HTTP 202

### Requirement: Optimistic locking for Dataset
A version field SHALL be present on `Dataset` for optimistic concurrency control. Dataset PUTs require `If-Match`. The TestSuite version field continues to govern suite-level optimistic concurrency for suite PUTs (unchanged).
Status: **Planned**

#### Scenario: Concurrent dataset edit returns 412
- **WHEN** two clients hold the same dataset version and both submit PUT with that version as `If-Match`
- **THEN** the first wins (HTTP 200/202); the second SHALL respond with HTTP 412 `VERSION_CONFLICT`

### Requirement: createdBy attribution
The service SHALL track createdBy from JWT (oidc mode) or "anonymous" (none mode); reject unauthenticated in oidc mode (401). createdBy is mutable via PUT.

### Requirement: MetricDeclarations read-only stub
The service SHALL provide read-only list of seeded metric declarations (Accuracy, Latency, Relevance). GET /api/v1/metric-declarations (paginated, filterable, sortable); GET .../metric-declarations/{id}.

#### Scenario: List metric declarations
- **WHEN** client calls `GET /api/v1/metric-declarations` with optional pagination, filter, sort
- **THEN** system SHALL return a paginated list of MetricDeclarations (or empty page)

#### Scenario: Get metric declaration by ID
- **WHEN** client calls `GET /api/v1/metric-declarations/{id}` with a valid declaration ID
- **THEN** system SHALL return the MetricDeclaration with id, name, description, createdAt

#### Scenario: Filter and sort metric declarations
- **WHEN** client calls `GET /api/v1/metric-declarations?filter=...&sort=...`
- **THEN** system SHALL apply filtering and sorting per entity-filtering spec

### Requirement: Standard error response format
All error responses SHALL use a standardized format with machine-readable code (e.g. VALIDATION_ERROR, NOT_FOUND, VERSION_CONFLICT).

### Requirement: Structured validation warnings in TestCase responses
When a TestCase has validation warnings, the service SHALL return them as structured objects. Each object SHALL include: `fieldName` (the field in `data` or template variable that triggered the warning), `path` (location context), `message` (human-readable), and optionally `code` (stable identifier).

#### Scenario: Create returns structured warnings when invalid
- **WHEN** client creates a TestCase that fails validation
- **THEN** response SHALL include `validationWarnings` as a list of objects each with `fieldName`, `path`, `message`, and optionally `code`

#### Scenario: Warnings use fieldName
- **WHEN** a TestCase has validation warnings
- **THEN** each warning object SHALL use `fieldName` (replaces previous `source: PARAMETERS | FACTS` + `property` fields; `ValidationWarningSource` enum removed) since data is unified

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

### Requirement: CSV import auto-name generation uses 1-based padded row index
When a CSV row has a blank or missing `testCaseName` column during import (any mode), the system SHALL auto-generate the name as `"Row " + zeroPaddedIndex` where the index is 1-based (first data row = 1) and zero-padded to `digits(csv.import.max-rows)` width (e.g., 100000 → 6 digits → "Row 000001").

#### Scenario: First data row gets name "Row 000001"
- **WHEN** client imports a CSV with blank `testCaseName` for the first data row and `csv.import.max-rows` is 100000
- **THEN** system SHALL assign the name "Row 000001"

#### Scenario: Auto-generated names sort correctly as strings
- **WHEN** client imports a CSV with 10+ rows with blank `testCaseName`
- **THEN** string-sorting the auto-generated names SHALL produce the same order as numeric sorting (e.g., "Row 000002" < "Row 000010")

#### Scenario: Preview shows padded auto-generated names
- **WHEN** client calls the CSV preview endpoint with blank `testCaseName` values
- **THEN** the preview response SHALL show the same padded naming pattern as the actual import

#### Scenario: Padding width derived from maxRows config
- **WHEN** `csv.import.max-rows` config value has N digits
- **THEN** the zero-padding width SHALL be N (e.g., max-rows=100000 → 6-digit padding)

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

### Requirement: Field whitelist for bulkOperations
The service SHALL restrict the set of fields allowed inside `bulkOperations[i].patch` to a code-defined whitelist. After this change the default whitelist is **empty** because the only previously-whitelisted field (`enabled`) is removed from TestCase. The whitelist remains the key set of a single canonical API-field → SQL-column map maintained in code; it is NOT a configuration property. Any request with a bulk patch referencing a field outside the (currently empty) whitelist SHALL be rejected with HTTP 400 (VALIDATION_ERROR). `itemOperations[i].patch` SHALL NOT be subject to this whitelist and SHALL follow the existing single-row PATCH field set (`testCaseName`, `data`).
Status: **Planned**

#### Scenario: Bulk patch with non-whitelisted field
- **WHEN** `bulkOperations[i].patch` contains any key (the whitelist is empty after this change)
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR) naming the offending field, and roll back

#### Scenario: Item operation patches a field not in the bulk whitelist
- **WHEN** `itemOperations[i].patch` contains keys outside the bulk whitelist but valid for single-row PATCH (i.e., `data`, `testCaseName`)
- **THEN** system SHALL accept the item operation

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

### Requirement: Validation scope for composite bulk patch
The service SHALL re-run per-row test-case validation (recomputing `valid` and `validation_warnings`) only for rows whose applied patch touches a validation-relevant field (`data`, `testCaseName`). Rows whose patches do not touch any validation-relevant field SHALL NOT be re-validated. With the empty default bulk-whitelist, bulk operations cannot currently target a non-validation-relevant field; this clause keeps the contract correct if the whitelist is expanded in a future change. Re-validation cost scales with the number of rows actually receiving a relevant field change.
Status: **Planned**

#### Scenario: Item op on data triggers per-row re-validation
- **WHEN** an `itemOperations[i].patch` modifies `data`
- **THEN** system SHALL re-run validation for that row and persist the updated `valid` / `validation_warnings` against the dataset's schema

#### Scenario: Item op on testCaseName triggers per-row re-validation
- **WHEN** an `itemOperations[i].patch` modifies `testCaseName` only
- **THEN** system SHALL re-run validation for that row (name uniqueness + downstream checks) and persist the updated state

### Requirement: Name uniqueness when composite bulk patch affects testCaseName
When the composite request targeting `PATCH /api/v1/datasets/{datasetId}/test-cases:bulk` touches `testCaseName` on one or more rows (only possible via `itemOperations` under the default whitelist, or via `bulkOperations` if a future whitelist extension adds `testCaseName`), the service SHALL validate name uniqueness against the final state, applying the same case-insensitive rules and HTTP 409 (UNIQUE_CONSTRAINT_VIOLATION) semantics already in effect for the existing batch PATCH endpoint. Uniqueness scope is the **dataset**, not the suite (see "Unique testCaseName within Dataset").
Status: **Planned**

#### Scenario: Item ops produce duplicate names within the request
- **WHEN** two `itemOperations` result in the same `testCaseName` (case-insensitive) inside the same dataset
- **THEN** system SHALL respond with HTTP 409 (UNIQUE_CONSTRAINT_VIOLATION) and roll back

#### Scenario: Item op collides with existing name outside the request
- **WHEN** an `itemOperations[i]` sets `testCaseName` to a value already used by a test case in the same dataset that is not part of the request
- **THEN** system SHALL respond with HTTP 409 (UNIQUE_CONSTRAINT_VIOLATION) and roll back

### Requirement: Test case mutations do not trigger suite validity recalculation
Test-case create, update, patch, batch-update, batch-patch, bulk-patch, delete, deleteAll, and CSV import operations SHALL NOT trigger any recalculation or update of the owning suite's `isValid` / `validationWarnings` fields. Suite validity is config-only and is never recalculated from test-case mutations. Test-case presence is enforced at run-creation time only (see `test-suite-runs` spec).
Status: **Implemented**

#### Scenario: Creating a test case does not change suite validity
- **WHEN** a test case is created in a dataset
- **THEN** the `isValid` and `validationWarnings` of any suite bound to that dataset SHALL remain unchanged

#### Scenario: Deleting a test case does not change suite validity
- **WHEN** a test case is deleted from a dataset
- **THEN** the `isValid` and `validationWarnings` of any suite bound to that dataset SHALL remain unchanged

### Requirement: Filter selector resolution semantics
A `filter` selector SHALL be resolved to a concrete set of test-case ids inside the same transaction that performs the UPDATE. Rows inserted into the dataset between the selector-resolution query and the UPDATE that were not part of the resolved id set SHALL NOT be affected by the bulk operation. This matches the behaviour documented for other filter-based bulk endpoints and SHALL be documented in the OpenAPI description.
Status: **Planned**

#### Scenario: Rows inserted concurrently are not matched
- **WHEN** rows are inserted into the dataset after the filter-selector resolution but before transaction commit
- **THEN** those rows SHALL NOT be affected by the bulk operation

#### Scenario: Rows matching at resolution time are updated even if the filter stops matching after a prior op
- **WHEN** a `bulkOperations[0]` changes a field used by `bulkOperations[1].selector.filter` (once a non-empty whitelist is reintroduced)
- **THEN** `bulkOperations[1]` SHALL be resolved against the post-`bulkOperations[0]` state, i.e., filter selectors are resolved at the moment each op executes, not up-front for the whole request

### Requirement: Batch name permutation within a single operation succeeds
When a single batch operation reassigns `testCaseName` values among test cases in the same dataset such that the operation's **final state** contains no duplicate names (case-insensitive), the service SHALL apply the update successfully with HTTP 200 (or the endpoint's normal success status), even when an intermediate assignment would momentarily duplicate a name. This covers arbitrary permutations — pairwise swaps and longer rename cycles. This applies to batch PUT (`PUT /api/v1/datasets/{datasetId}/test-cases`), batch PATCH (`PATCH /api/v1/datasets/{datasetId}/test-cases`), and the `itemOperations` of composite bulk patch (`PATCH /api/v1/datasets/{datasetId}/test-cases:bulk`). Final-state duplicate detection is unchanged: genuine collisions (two items ending with the same name, or an item colliding with a name outside the batch) still return HTTP 409 (UNIQUE_CONSTRAINT_VIOLATION) and roll back the whole transaction.
Status: **Implemented**

#### Scenario: Pairwise name swap via batch PUT succeeds
- **WHEN** a dataset has test cases named `A` and `B`, and client calls `PUT /api/v1/datasets/{datasetId}/test-cases` with the first item renaming `A → B` and the second renaming `B → A`
- **THEN** system SHALL return HTTP 200 and the two test cases SHALL have their names swapped (no HTTP 409)

#### Scenario: Pairwise name swap via batch PATCH succeeds
- **WHEN** a dataset has test cases named `A` and `B`, and client calls `PATCH /api/v1/datasets/{datasetId}/test-cases` with the first item renaming `A → B` and the second renaming `B → A`
- **THEN** system SHALL return HTTP 200 and the two test cases SHALL have their names swapped (no HTTP 409)

#### Scenario: Multi-way rename cycle succeeds
- **WHEN** a dataset has test cases named `A`, `B`, and `C`, and a single batch operation renames `A → B`, `B → C`, and `C → A`
- **THEN** system SHALL return HTTP 200 and the three test cases SHALL reflect the rotated names (no HTTP 409)

#### Scenario: Name swap via composite bulk patch itemOperations succeeds
- **WHEN** a dataset has test cases named `A` and `B`, and client calls `PATCH /api/v1/datasets/{datasetId}/test-cases:bulk` with two `itemOperations`, one renaming `A → B` and the other renaming `B → A`
- **THEN** system SHALL return HTTP 200 and the two test cases SHALL have their names swapped (no HTTP 409)

#### Scenario: Genuine final-state duplicate within a permutation still rejected
- **WHEN** a batch operation assigns the same final `testCaseName` to two items (a real duplicate, not a permutation)
- **THEN** system SHALL respond with HTTP 409 (UNIQUE_CONSTRAINT_VIOLATION) and roll back all changes

## Implementation Notes
- Controllers: TestCaseController, TestCaseBulkPatchController, TestSuiteController (revalidation endpoints), MetricDeclarationController.
- Services: TestCaseService (bulkPatch), TestCaseBulkPatchValidator, TestCaseBulkSelectorResolver, CsvExportService, CsvImportService, SchemaValidationService, RevalidationService.
- DB: test_cases, revalidation_tasks, metric_declarations (V1.2 + V1.7 rename).
- CSV import preview and import: `service/domain/CsvImportService.java`; preview response DTO `service/domain/dto/csv/CsvImportPreviewDto.java`; import/preview endpoints on `web/controller/TestCaseController.java` (`import`, `import/preview`).
- Preview OpenAPI examples: `src/main/resources/openapi/examples/api-v1-datasets-datasetId-test-cases-import-preview-POST-response-200-{minimal,full}.json`.
- CSV multi-turn functional coverage: `CsvImportModeFunctionalTests` (single-turn contract, must stay green unchanged) and `MultiTurnCsvFunctionalTests` (multi-turn preview and round trip).
- Batch name permutation (two-phase write): the transient collision arises because names are persisted via sequential per-row `UPDATE`s while the unique index `(dataset_id, LOWER(test_case_name))` is non-deferrable and checked after each statement. Fix is a two-phase write inside the existing `@Transactional` boundary: phase 1 parks every affected row's `test_case_name` at a collision-proof temporary value, phase 2 applies the final names. Code: `data.db.repository.PostgresTestCaseRepository.parkTestCaseNames` + two-phase `batchUpdate` (covers batch PUT/PATCH via `TestCaseService.persistBatch`); `service.domain.TestCaseService.bulkPatch` item-operations loop restructured into prepare → park → apply. Final-state uniqueness gate is unchanged: `TestCaseService.validateBatchNameUniqueness` still rejects genuine duplicates before any write.
