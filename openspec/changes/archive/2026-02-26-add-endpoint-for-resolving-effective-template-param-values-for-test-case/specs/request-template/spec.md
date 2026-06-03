## MODIFIED Requirements

### Requirement: Template variable extraction convenience API
The service SHALL provide `GET /api/v1/test-suites/{id}/template-variables` to return all extracted template variables with metadata.

#### Scenario: Extract variables from suite template
- **WHEN** client calls `GET /api/v1/test-suites/{id}/template-variables`
- **THEN** system SHALL return a list of `TemplateVariableDto` entries extracted from `requestTemplate`

#### Scenario: TemplateVariableDto structure
- **WHEN** system extracts template variables
- **THEN** each `TemplateVariableDto` SHALL include: `name` (String — the variable name), `sources` (Set of `TemplateVariableSource` enum — BODY, URL, QUERY, HEADER), `hasDefault` (boolean), `defaultValue` (String, nullable — raw default from `${{var:default}}`), `binding` (InputBindingDto, nullable — resolved binding from suite's `inputBindings`), `inferredType` (SchemaFieldType, nullable — from endpointRef schema or testCaseSchema), `resolvedValue` (Object, nullable — the resolved typed value for this variable, see resolution rules below)

#### Scenario: Variable source tracking
- **WHEN** `${{model}}` appears in both `body` and `queryParams`
- **THEN** system SHALL return a single entry with `sources: [BODY, QUERY]`

#### Scenario: Type inference priority
- **WHEN** system infers the type of a template variable
- **THEN** it SHALL prioritize: (1) `endpointRef.requestBodySchema` or parameter definition, (2) `testCaseSchema` field type (via binding's `dataField`), (3) fallback to `STRING`

#### Scenario: Non-existent TestSuite
- **WHEN** client calls `GET /api/v1/test-suites/{id}/template-variables` with a non-existent id
- **THEN** system SHALL respond with HTTP 404

#### Scenario: TestSuite with no template
- **WHEN** client calls the endpoint for a TestSuite with `requestTemplate: null`
- **THEN** system SHALL return an empty list

#### Scenario: Suite-level resolvedValue for constant-value binding
- **WHEN** a template variable has a binding with `constantValue` (e.g., `constantValue: "gpt-4"`)
- **THEN** `resolvedValue` SHALL be the constant value (e.g., `"gpt-4"`)

#### Scenario: Suite-level resolvedValue for template default without binding
- **WHEN** a template variable has `${{var:default}}` syntax and no binding exists
- **THEN** `resolvedValue` SHALL be the default value string (e.g., `"0.7"`)

#### Scenario: Suite-level resolvedValue for data-field binding
- **WHEN** a template variable has a binding with `dataField` (no constant)
- **THEN** `resolvedValue` SHALL be `null` (no test case data available at suite level)

#### Scenario: Suite-level resolvedValue for data-field binding with template default
- **WHEN** a template variable has a binding with `dataField` and the template has a default `${{var:default}}`
- **THEN** `resolvedValue` SHALL be the template default value (data-field cannot be resolved without test case data, so default is used as fallback)

#### Scenario: Suite-level resolvedValue for unbound variable without default
- **WHEN** a template variable has no binding and no default
- **THEN** `resolvedValue` SHALL be `null`

### Requirement: Template variables API for TestCase (effective template)
The service SHALL provide `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/template-variables` to return template variables for the **effective** template and bindings of that test case (suite template/bindings, or overrides when present). This is useful when the test case has `requestTemplateOverride` and/or `inputBindingsOverride`.

#### Scenario: Extract variables from effective template for test case
- **WHEN** client calls `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/template-variables`
- **THEN** system SHALL return a list of `TemplateVariableDto` entries extracted from the effective template (test case's `requestTemplateOverride` if present, otherwise suite's `requestTemplate`), with bindings from effective bindings (test case's `inputBindingsOverride` if present, otherwise suite's `inputBindings`)

#### Scenario: Test case with overrides returns override-based variables
- **WHEN** the test case has `requestTemplateOverride` and/or `inputBindingsOverride`
- **THEN** system SHALL use the override template and/or override bindings to extract and resolve variables (same `TemplateVariableDto` structure as suite endpoint)

#### Scenario: Test case without overrides returns suite-based variables
- **WHEN** the test case has no overrides
- **THEN** system SHALL return the same logical result as `GET /api/v1/test-suites/{testSuiteId}/template-variables` for that suite, but with `resolvedValue` fully resolved using test case data

#### Scenario: Non-existent TestCase or TestSuite
- **WHEN** client calls the endpoint with a non-existent testSuiteId or testCaseId
- **THEN** system SHALL respond with HTTP 404

#### Scenario: Test case with no effective template
- **WHEN** effective template is null (suite has no template and test case has no override, or override is null)
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
- **WHEN** a template variable has `${{prompt}}` (no default, no binding)
- **THEN** `resolvedValue` SHALL be `null`
