## MODIFIED Requirements

### Requirement: Create and manage TestCases inside a TestSuite
The service SHALL manage TestCases as children of a TestSuite with full CRUD operations. TestCases store a unified `data` map (replacing separate `parameters` and `facts`), and optionally `requestTemplateOverride` (a `RequestTemplateDto` with embedded `${{variable}}` syntax) and `inputBindingsOverride` (list of `InputBindingDto`).

#### Scenario: Create a test case
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/test-cases` with a valid body
- **THEN** system SHALL create a TestCase linked to the TestSuite; require `testCaseName`; default `data` to `{}`, `isEnabled` to `true`; calculate `isValid` from schema, template variables, and bindings

#### Scenario: List test cases
- **WHEN** client calls `GET /api/v1/test-suites/{testSuiteId}/test-cases`
- **THEN** system SHALL return a paginated list of TestCases

#### Scenario: Sort and filter test cases
- **WHEN** client calls `GET .../test-cases?sort=...&filter=...`
- **THEN** system SHALL apply sorting and filtering per entity-filtering spec; support filter fields testCaseName, isEnabled, isValid, createdAt; support sort fields testCaseName, createdAt, updatedAt, isEnabled, isValid

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
- **THEN** system SHALL replace the TestCase, recalculate `isValid`, update `updatedAt`

#### Scenario: Delete single test case
- **WHEN** client calls `DELETE .../test-cases/{testCaseId}`
- **THEN** system SHALL delete the TestCase and return HTTP 204

### Requirement: TestCase structure with unified data map
Each TestCase SHALL have: `testCaseName`, `data` (Map<String,Object> — single unified map for all column values), `requestTemplateOverride` (nullable `RequestTemplateDto`), `inputBindingsOverride` (nullable list of `InputBindingDto`), `isEnabled`, `isValid`, `validationWarnings`.

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
The service SHALL allow partial updates using RFC 7396 JSON Merge Patch. Allowed fields: `testCaseName`, `isEnabled`, `data`, `requestTemplateOverride`, `inputBindingsOverride`. NOT patchable: `id`, `isValid`, `createdAt`, `updatedAt`.

#### Scenario: Patch data only
- **WHEN** client calls PATCH with `{ "data": { "user_prompt": "new prompt" } }`
- **THEN** system SHALL apply merge patch to data, recalculate isValid, and return updated TestCase

#### Scenario: Patch requestTemplateOverride
- **WHEN** client calls PATCH with `{ "requestTemplateOverride": { ... } }`
- **THEN** system SHALL update the override and recalculate isValid

#### Scenario: Clear requestTemplateOverride
- **WHEN** client calls PATCH with `{ "requestTemplateOverride": null }`
- **THEN** system SHALL clear the override (fall back to suite template) and recalculate isValid

#### Scenario: Patch inputBindingsOverride
- **WHEN** client calls PATCH with `{ "inputBindingsOverride": [...] }`
- **THEN** system SHALL update the bindings override and recalculate isValid

#### Scenario: Clear inputBindingsOverride
- **WHEN** client calls PATCH with `{ "inputBindingsOverride": null }`
- **THEN** system SHALL clear the override (fall back to suite bindings) and recalculate isValid

#### Scenario: Patch recalculates isValid
- **WHEN** client calls PATCH with body that modifies `data`, `requestTemplateOverride`, or `inputBindingsOverride`
- **THEN** system SHALL recalculate isValid and include validationWarnings if isValid=false

### Requirement: Validate TestCases against schema, template, and bindings (Soft Validation)
The service SHALL validate `data` against `testCaseSchema` and validate template variable requirements against effective bindings. Validation uses the effective template (`requestTemplateOverride ?? suite.requestTemplate`) and effective bindings (`inputBindingsOverride ?? suite.inputBindings`). Validation failures produce warnings (not rejection). `isValid=false` when any validation fails.

**Layering**: TestCase `isValid` covers **data-specific checks only** and is independent of suite-level `isValid`. Suite-level warnings (urlTemplate null, binding coverage, binding references, schema conformance) are stored on the TestSuite and are NOT duplicated on each TestCase. The client/UI combines both flags to determine overall test readiness.

#### Scenario: Missing required field in data
- **WHEN** a `testCaseSchema` field has `required: true` and the corresponding key is missing or null in `data`
- **THEN** system SHALL set isValid=false and add a validation warning with `fieldName`

#### Scenario: Type mismatch in data
- **WHEN** a `data` value does not match the `testCaseSchema` field's declared `type`
- **THEN** system SHALL set isValid=false and add a validation warning with `fieldName` and expected type

#### Scenario: Required template variable with bound data field missing value
- **WHEN** a `${{var}}` (no default) in the effective template has a binding with `dataField`, and `data[dataField]` is null/missing
- **THEN** system SHALL set isValid=false and add a validation warning: "Required field '$field' has no value in data"

#### Scenario: Required template variable unbound (override path only)
- **WHEN** a test case has `requestTemplateOverride` and/or `inputBindingsOverride`, and a `${{var}}` (no default) in the effective template has no matching binding in effective bindings
- **THEN** system SHALL set isValid=false and add a validation warning: "Required template variable '$var' has no binding"
- **NOTE**: When the test case uses suite defaults (no overrides), this check is already performed at suite level and is NOT duplicated here

#### Scenario: Override binding references unknown data field
- **WHEN** a test case has `inputBindingsOverride` with a binding whose `dataField` does not match any field in `testCaseSchema`
- **THEN** system SHALL add a validation warning

#### Scenario: Override binding references variable not in override template
- **WHEN** a test case has `inputBindingsOverride` with a binding whose `templateVariable` does not match any `${{...}}` in the effective template
- **THEN** system SHALL add a validation warning

#### Scenario: Unknown fields in data (not in schema)
- **WHEN** `data` contains keys not present in `testCaseSchema`
- **THEN** system SHALL add a validation warning (e.g., "Field 'x' is not defined in testCaseSchema")

#### Scenario: Schema validation against endpoint (if schema present)
- **WHEN** endpoint `requestBodySchema` is present
- **THEN** system SHALL resolve the full request (template + bindings + data) and soft-validate against the schema

### Requirement: Structured validation warnings in TestCase responses
When a TestCase has validation warnings, the service SHALL return them as structured objects. Each object SHALL include: `fieldName` (the field in `data` or template variable that triggered the warning), `path` (location context), `message` (human-readable), and optionally `code` (stable identifier).

#### Scenario: Create returns structured warnings when invalid
- **WHEN** client creates a TestCase that fails validation
- **THEN** response SHALL include `validationWarnings` as a list of objects each with `fieldName`, `path`, `message`, and optionally `code`

#### Scenario: Warnings use fieldName
- **WHEN** a TestCase has validation warnings
- **THEN** each warning object SHALL use `fieldName` (replaces previous `source: PARAMETERS | FACTS` + `property` fields; `ValidationWarningSource` enum removed) since data is unified

### Requirement: CSV export of TestCase dataset
The service SHALL allow exporting TestCases as CSV. Column order SHALL be **by schema order**: fixed columns first (e.g. `testCaseName`, optionally `isEnabled`), then data columns in the order fields appear in `testCaseSchema`.

#### Scenario: CSV columns reflect unified schema
- **WHEN** system exports CSV
- **THEN** header SHALL be: `testCaseName`, then `testCaseSchema` fields in schema order; optionally `isEnabled` if `includeIsEnabled=true`

#### Scenario: Export with custom delimiter
- **WHEN** client calls `GET .../export.csv?delimiter=;`
- **THEN** system SHALL use semicolon as delimiter

### Requirement: CSV bulk upload with schema detection
The service SHALL allow bulk uploading TestCases via CSV. All columns map to the unified `data` map. Column names match `testCaseSchema` field names. When mapping columns to `data`, the system SHALL use schema order for deterministic handling (e.g. when inferring or validating column set).

#### Scenario: Import maps all columns to data
- **WHEN** CSV header has column names matching `testCaseSchema` fields
- **THEN** system SHALL map all non-testCaseName, non-isEnabled columns to `data`

#### Scenario: Column name not in schema (warning)
- **WHEN** CSV header has a column name not matching any `testCaseSchema` field
- **THEN** system SHALL import the value into `data` and add a validation warning on affected rows

#### Scenario: Auto-detect schema from CSV (no existing schema)
- **WHEN** `testCaseSchema` is empty and CSV is imported
- **THEN** system SHALL auto-detect field definitions from CSV columns: all headers except reserved names (`testCaseName`, `isEnabled`) become `FieldDefinitionDto` entries with `required: false` and `description: null`; schema field order follows CSV column order (left to right)

#### Scenario: Auto-detect type inference
- **WHEN** system auto-detects schema from CSV
- **THEN** system SHALL scan all row values per column and infer type: all non-empty values parse as JSON objects → OBJECT; JSON arrays → ARRAY; `true`/`false` (case-insensitive) → BOOLEAN; whole numbers → INTEGER; decimal numbers → NUMBER; otherwise → STRING

#### Scenario: Auto-detected schema is persisted
- **WHEN** CSV import commits with auto-detected schema
- **THEN** system SHALL persist the schema to the TestSuite's `testCaseSchema`, bump `version`, and trigger suite-level re-validation; no `inputBindings` are auto-created

#### Scenario: Auto-detected schema in preview
- **WHEN** client calls the CSV import preview endpoint and `testCaseSchema` is empty
- **THEN** the preview response SHALL include the auto-detected schema so the client can review before committing

#### Scenario: No auto-detection when schema exists
- **WHEN** `testCaseSchema` is non-empty and CSV is imported
- **THEN** system SHALL NOT auto-detect; existing schema is used, unknown CSV columns produce validation warnings

#### Scenario: CSV with only reserved columns
- **WHEN** CSV has only `testCaseName` (and optionally `isEnabled`) with no data columns, and `testCaseSchema` is empty
- **THEN** schema stays empty (no fields to auto-detect)

## REMOVED Requirements

### Requirement: Manage embedded TestCasesDefinition via TestSuite API
**Reason**: Replaced by `testCaseSchema` at TestSuite level. `parameterFields` are no longer computed. See `test-suites` delta spec.
**Migration**: The concept of parameterFields is replaced by inputBindings. factFields become testCaseSchema entries.

### Requirement: Column disambiguation with prefixes
**Reason**: With a unified `data` map, there is no longer a parameters/facts distinction requiring `param.`/`fact.` prefixes. All columns map to `data`.
**Migration**: CSV columns use plain field names matching `testCaseSchema`. Legacy prefixed CSVs are not supported.
