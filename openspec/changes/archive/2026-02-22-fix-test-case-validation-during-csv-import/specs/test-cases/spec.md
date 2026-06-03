## MODIFIED Requirements

### Requirement: Validate TestCases against schema, template, and bindings (Soft Validation)
The service SHALL validate `data` against `testCaseSchema` and validate template variable requirements against effective bindings. Validation uses the effective template (`requestTemplateOverride ?? suite.requestTemplate`) and effective bindings (`inputBindingsOverride ?? suite.inputBindings`). Validation failures produce warnings (not rejection). `isValid=false` when any validation fails.

**During CSV import and preview**, validation SHALL use the **target schema** (the schema that will be in effect after import completes) rather than the pre-import suite schema. The target schema is derived from CSV column headers and the import mode:
- **OVERRIDE mode** (any schema state): target schema = all data columns from CSV headers (each as `required=false`)
- **MERGE mode** (non-empty schema): target schema = existing schema fields + new data columns from CSV headers (new fields as `required=false`)
- **APPEND/MERGE mode** (empty schema): target schema = all data columns from CSV headers (each as `required=false`)
- **APPEND mode** (non-empty schema): target schema = existing `testCaseSchema` (unchanged)

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

#### Scenario: Get validation warnings on request
- **WHEN** client calls `GET .../test-cases/{id}?includeWarnings=true` for invalid case
- **THEN** system SHALL include validationWarnings in response; without param, omit them

#### Scenario: Schema update triggers async re-validation
- **WHEN** testCaseSchema or requestTemplate or inputBindings in TestSuite is updated
- **THEN** system SHALL respond HTTP 202 with RevalidationTaskDto and start async re-validation

#### Scenario: Track re-validation task status
- **WHEN** client calls `GET /api/v1/test-suites/{id}/revalidation-tasks/{taskId}`
- **THEN** system SHALL return task status (PENDING, RUNNING, COMPLETED, FAILED, TIMED_OUT) and progress (processedCases, validCount, invalidCount)

#### Scenario: PATCH always recalculates isValid
- **WHEN** client calls PATCH with any field
- **THEN** system SHALL recalculate isValid from current schema

#### Scenario: CSV import OVERRIDE mode validates against target schema
- **WHEN** client imports CSV with `importMode=OVERRIDE` and the suite has an existing schema with different fields than the CSV
- **THEN** system SHALL validate each row against the target schema derived from CSV headers (not the old suite schema); imported test cases SHALL have `isValid=true` when data matches the CSV-derived schema

#### Scenario: CSV import OVERRIDE mode with empty suite schema validates correctly
- **WHEN** client imports CSV with `importMode=OVERRIDE` and the suite's `testCaseSchema` is empty
- **THEN** system SHALL validate each row against the target schema derived from CSV headers; imported test cases SHALL NOT have "Unknown data field" warnings for columns present in the CSV

#### Scenario: CSV import MERGE mode with new columns validates against merged schema
- **WHEN** client imports CSV with `importMode=MERGE` and the CSV has columns not in the existing suite schema
- **THEN** system SHALL validate each row against the merged schema (existing fields + new CSV columns); new columns SHALL NOT produce "Unknown data field" warnings

#### Scenario: CSV import APPEND mode with empty schema validates against target schema
- **WHEN** client imports CSV with `importMode=APPEND` and the suite's `testCaseSchema` is empty
- **THEN** system SHALL validate each row against the target schema derived from CSV headers; imported test cases SHALL have `isValid=true` when data matches the CSV-derived schema

#### Scenario: CSV preview validates sample rows against target schema
- **WHEN** client calls the CSV import preview endpoint with any mode where the schema would change
- **THEN** system SHALL validate sample rows against the target schema (not the pre-import schema); sample rows SHALL NOT have spurious "Unknown data field" warnings for columns present in the CSV
