## MODIFIED Requirements

### Requirement: Template variables API for TestCase (effective template)
The service SHALL provide `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/template-variables` to return template variables for a specific test case: the suite's template and bindings resolved against that test case's `data`. The test case is looked up dataset-scoped via the suite's `datasetId`. Per-test-case `requestTemplateOverride` / `inputBindingsOverride` were removed when test cases moved to datasets, so the "effective" template/bindings are always the suite's; the only difference from `GET /api/v1/test-suites/{testSuiteId}/template-variables` is that `resolvedValue` is fully resolved using the test case's `data`. For `MCP_TOOL` suites, variables are extracted from `argumentTemplate`; for HTTP suites, from `requestTemplate`. The test-case schema used for type inference is sourced from the suite's dataset.

#### Scenario: Extract variables for a test case
- **WHEN** client calls `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/template-variables`
- **THEN** system SHALL return a list of `TemplateVariableDto` entries (same structure as the suite endpoint) extracted from the suite's `requestTemplate` (or `argumentTemplate` for `MCP_TOOL` suites), with `binding` populated from the suite's `inputBindings`

#### Scenario: resolvedValue resolved from test case data
- **WHEN** the test case exists in the suite's dataset
- **THEN** system SHALL return the same logical variables as the suite endpoint for that suite, but with `resolvedValue` fully resolved using the test case's `data`

#### Scenario: Non-existent TestSuite
- **WHEN** client calls the endpoint with a non-existent `testSuiteId`
- **THEN** system SHALL respond with HTTP 404

#### Scenario: Non-existent TestCase
- **WHEN** client calls the endpoint with a `testCaseId` that does not exist in the suite's dataset
- **THEN** system SHALL respond with HTTP 404

#### Scenario: Suite not bound to a dataset
- **WHEN** the suite has `datasetId: null` (unbound suite, which can own no test cases)
- **THEN** system SHALL respond with HTTP 404 for any `testCaseId`

#### Scenario: Suite with no template
- **WHEN** the suite has `requestTemplate: null` (HTTP suite) or `argumentTemplate: null` (MCP suite)
- **THEN** system SHALL return an empty list

#### Scenario: Test-case-level resolvedValue for constant-value binding
- **WHEN** a template variable has a binding with `constantValue`
- **THEN** `resolvedValue` SHALL be the constant value (same as suite level — constants always win)

#### Scenario: Test-case-level resolvedValue for data-field binding with data present
- **WHEN** a template variable has a binding with `dataField: "user_prompt"` and the test case has `data["user_prompt"] = "Hello"`
- **THEN** `resolvedValue` SHALL be `"Hello"` (the typed value from test case data)

#### Scenario: Test-case-level resolvedValue for data-field binding with missing data and template default
- **WHEN** a template variable has `${{var:fallback}}`, a binding with `dataField: "field"`, and `data["field"]` is null/missing
- **THEN** `resolvedValue` SHALL be `"fallback"` (template default used as fallback)

#### Scenario: Test-case-level resolvedValue for data-field binding with missing data and no default
- **WHEN** a template variable has `${{var}}` (no default), a binding with `dataField: "field"`, and `data["field"]` is null/missing
- **THEN** `resolvedValue` SHALL be `null`

#### Scenario: Test-case-level resolvedValue preserves typed values
- **WHEN** a template variable resolves to a Number (e.g., `data["temperature"] = 0.7`) or Boolean (e.g., `constantValue: true`)
- **THEN** `resolvedValue` SHALL preserve the original type (Number, Boolean, etc.), not stringify it

#### Scenario: Test-case-level resolvedValue for unbound variable with default
- **WHEN** a template variable has `${{model:gpt-3.5}}` and no binding exists
- **THEN** `resolvedValue` SHALL be `"gpt-3.5"` (the default string)

#### Scenario: Test-case-level resolvedValue for unbound variable without default
- **WHEN** a template variable has `${{prompt}}` (no default, no binding) and `data` has no matching entry
- **THEN** `resolvedValue` SHALL be `null`

### Requirement: Template variables for MCP suites

The `TemplateVariableService` SHALL support MCP_TOOL suites via `GET /api/v1/test-suites/{id}/template-variables` and `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/template-variables`. When the suite type is `MCP_TOOL`, the service SHALL extract variables from the `argumentTemplate` (not `requestTemplate`) and resolve them using the MCP-specific resolution path with input bindings support.

MCP suites support the same `inputBindings` mechanism as HTTP suites. The resolution priority for MCP template variables follows the same chain as `McpRequestResolver`: binding `constantValue` > binding `dataField` lookup > direct variable name lookup > template default > `null`.

Status: **Implemented**

#### Scenario: MCP suite-level template variables extracted from argument template
- **WHEN** a suite with `suiteType = MCP_TOOL` has `argumentTemplate.arguments = {"query": "${{userQuery}}", "limit": "${{maxResults:10}}"}`
- **THEN** `GET /api/v1/test-suites/{id}/template-variables` SHALL return variables `userQuery` and `maxResults` with `sources = [ARGUMENT]`
- **AND** `resolvedValue` SHALL be `null` for `userQuery` (no default, no data at suite level) and `"10"` for `maxResults` (has default)

#### Scenario: MCP suite-level template variables with constant-value binding
- **WHEN** a suite with `suiteType = MCP_TOOL` has a binding with `templateVariable: "userQuery"` and `constantValue: "fixed query"`
- **THEN** `GET /api/v1/test-suites/{id}/template-variables` SHALL return `userQuery` with `resolvedValue = "fixed query"` and `binding` populated

#### Scenario: MCP test-case-level template variables resolved from bindings and data
- **WHEN** a test case in the dataset of an MCP_TOOL suite has a binding mapping `userQuery` to `dataField: "question"`
- **AND** the test case has `data = {"question": "What is AI?"}`
- **THEN** `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/template-variables` SHALL return `userQuery` with `resolvedValue = "What is AI?"` (resolved via binding dataField lookup)

#### Scenario: MCP test-case-level template variables with no bindings (direct name lookup)
- **WHEN** a test case in the dataset of an MCP_TOOL suite with no input bindings
- **AND** the test case has `data = {"userQuery": "What is AI?"}`
- **THEN** the variable `userQuery` SHALL resolve via direct variable name lookup in data, returning `resolvedValue = "What is AI?"`

#### Scenario: MCP variable type inference
- **WHEN** an MCP template variable has no declared type hint
- **THEN** `effectiveType` SHALL be inferred from the dataset's test-case schema by matching the variable name to a schema field name
- **AND** if no match is found, `effectiveType` SHALL default to `STRING`
