## MODIFIED Requirements

### Requirement: Create and manage TestCases inside a TestSuite
The service SHALL manage TestCases as children of a TestSuite with full CRUD operations. TestCases store a unified `data` map (replacing separate `parameters` and `facts`), and optionally `requestTemplateOverride` (a `RequestTemplateDto` with embedded `${{variable}}` syntax) and `inputBindingsOverride` (list of `InputBindingDto`).

#### Scenario: Create a test case
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/test-cases` with a valid body
- **THEN** system SHALL create a TestCase linked to the TestSuite; require `testCaseName`; default `data` to `{}`, `enabled` to `true`; calculate `valid` from schema, template variables, and bindings

#### Scenario: List test cases
- **WHEN** client calls `GET /api/v1/test-suites/{testSuiteId}/test-cases`
- **THEN** system SHALL return a paginated list of TestCases

#### Scenario: Sort and filter test cases
- **WHEN** client calls `GET .../test-cases?sort=...&filter=...`
- **THEN** system SHALL apply sorting and filtering per entity-filtering spec; support filter fields testCaseName, enabled, valid, createdAt; support sort fields testCaseName, createdAt, updatedAt, enabled, valid

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
- **WHEN** client calls `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}` for an existing TestCase
- **THEN** system SHALL return the TestCase including `data`, `requestTemplateOverride`, and `inputBindingsOverride`

#### Scenario: Get template variables for test case (effective template)
- **WHEN** client calls `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/template-variables`
- **THEN** system SHALL return template variables for the effective template and bindings of that test case (see request-template spec); useful when the test case has request/binding overrides

#### Scenario: Get resolved request for test case
- **WHEN** client calls `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/resolved-request`
- **THEN** system SHALL return the resolved request (URL, query params, headers, body) for that test case after applying effective template, bindings, and data (see request-template spec)

#### Scenario: Update test case (full replacement)
- **WHEN** client calls `PUT .../test-cases/{testCaseId}` with a valid body
- **THEN** system SHALL replace the TestCase, recalculate `valid`, update `updatedAt`

#### Scenario: Delete single test case
- **WHEN** client calls `DELETE .../test-cases/{testCaseId}`
- **THEN** system SHALL delete the TestCase and return HTTP 204

#### Scenario: Bulk delete test cases
- **WHEN** client calls `DELETE .../test-cases` with optional filter
- **THEN** system SHALL delete matching TestCases (or all if no filter) and return count of deleted items

### Requirement: TestCase structure with unified data map
Each TestCase SHALL have: `testCaseName`, `data` (Map<String,Object> — single unified map for all column values), `requestTemplateOverride` (nullable `RequestTemplateDto`), `inputBindingsOverride` (nullable list of `InputBindingDto`), `enabled`, `valid`, `validationWarnings`.

#### Scenario: Default data map
- **WHEN** a TestCase is created without `data`
- **THEN** system SHALL default `data` to an empty map `{}`

#### Scenario: Default overrides
- **WHEN** a TestCase is created without `requestTemplateOverride` or `inputBindingsOverride`
- **THEN** system SHALL default both to null (use suite defaults)

#### Scenario: Template override must be valid RequestTemplateDto or null
- **WHEN** client sends `requestTemplateOverride` that is not a valid `RequestTemplateDto`
- **THEN** system SHALL respond with HTTP 400

#### Scenario: Bindings override must be valid list or null
- **WHEN** client sends `inputBindingsOverride` with entries that violate `InputBindingDto` constraints (e.g., both `dataField` and `constantValue` set)
- **THEN** system SHALL respond with HTTP 400

### Requirement: Partial update (PATCH) for a TestCase
The service SHALL allow partial updates using RFC 7396 JSON Merge Patch. Allowed fields: `testCaseName`, `enabled`, `data`, `requestTemplateOverride`, `inputBindingsOverride`. NOT patchable: `id`, `valid`, `createdAt`, `updatedAt`.

#### Scenario: Patch data only
- **WHEN** client calls PATCH with `{ "data": { "user_prompt": "new prompt" } }`
- **THEN** system SHALL apply merge patch to data, recalculate valid, and return updated TestCase

#### Scenario: Patch requestTemplateOverride
- **WHEN** client calls PATCH with `{ "requestTemplateOverride": { ... } }`
- **THEN** system SHALL update the override and recalculate valid

#### Scenario: Clear requestTemplateOverride
- **WHEN** client calls PATCH with `{ "requestTemplateOverride": null }`
- **THEN** system SHALL clear the override (fall back to suite template) and recalculate valid

#### Scenario: Patch inputBindingsOverride
- **WHEN** client calls PATCH with `{ "inputBindingsOverride": [...] }`
- **THEN** system SHALL update the bindings override and recalculate valid

#### Scenario: Clear inputBindingsOverride
- **WHEN** client calls PATCH with `{ "inputBindingsOverride": null }`
- **THEN** system SHALL clear the override (fall back to suite bindings) and recalculate valid

#### Scenario: Patch recalculates valid
- **WHEN** client calls PATCH with body that modifies `data`, `requestTemplateOverride`, or `inputBindingsOverride`
- **THEN** system SHALL recalculate valid and include validationWarnings if valid=false

### Requirement: CSV export of TestCase dataset
The service SHALL allow exporting TestCases as CSV. Column order SHALL be **by schema order**: fixed columns first (e.g. `testCaseName`, optionally `enabled`), then data columns in the order fields appear in `testCaseSchema`.

#### Scenario: CSV columns reflect unified schema
- **WHEN** system exports CSV
- **THEN** header SHALL be: `testCaseName`, then `testCaseSchema` fields in schema order; optionally `enabled` if `includeEnabled=true`

#### Scenario: Export with custom delimiter
- **WHEN** client calls `GET .../export.csv?delimiter=;`
- **THEN** system SHALL use semicolon as delimiter

### Requirement: CSV bulk upload with schema detection
The service SHALL allow bulk uploading TestCases via CSV. All columns map to the unified `data` map. Column names match `testCaseSchema` field names. Schema auto-detection, persistence, and replacement behavior depend on the `importMode` parameter. In OVERRIDE mode, schema is always replaced from CSV. In APPEND mode, schema is only auto-detected when the suite schema is empty. In MERGE mode, new CSV columns are merged into the existing schema.

#### Scenario: Import maps all columns to data
- **WHEN** CSV header has column names matching `testCaseSchema` fields
- **THEN** system SHALL map all non-testCaseName, non-enabled columns to `data`

#### Scenario: Column name not in schema is discarded (APPEND mode only)
- **WHEN** client imports with `importMode=APPEND`, the suite `testCaseSchema` is non-empty, and a CSV column name does not match any schema field
- **THEN** system SHALL discard that column's values and NOT store them in `data`; no validation warning is added for the unknown column itself
- **Note:** This filtering applies only to `APPEND` mode with a non-empty schema. In `OVERRIDE` mode all CSV data columns are stored (schema is replaced). In `MERGE` mode all CSV data columns are stored (all columns end up in the merged schema — either existing or newly added).

#### Scenario: Auto-detect schema from CSV (no existing schema)
- **WHEN** `testCaseSchema` is empty and CSV is imported (any mode)
- **THEN** system SHALL auto-detect field definitions from CSV columns: all headers except reserved names (`testCaseName`, `enabled`) become `FieldDefinitionDto` entries with `required: false` and `description: null`; schema field order follows CSV column order (left to right)

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
- **WHEN** CSV has only `testCaseName` (and optionally `enabled`) with no data columns, and `testCaseSchema` is empty
- **THEN** schema stays empty (no fields to auto-detect)
