## MODIFIED Requirements

### Requirement: Soft validation of template and bindings
The service SHALL perform soft validation when saving a TestSuite. Validation failures produce warnings (not hard rejection) and are stored in the **TestSuite's** `isValid` and `validationWarnings` fields (see test-suites spec). These suite-level warnings are independent of test case data and are accessible even when no test cases exist.

**This validation SHALL apply to both DEPLOYMENT and MCP_TOOL suite types.** For DEPLOYMENT suites, variables are extracted from `requestTemplate`. For MCP_TOOL suites, variables are extracted from `argumentTemplate` using `TemplateVariableExtractor.extractFromArgumentTemplateWithWarnings()`. The binding cross-validation logic (required variable, unknown field, orphan binding, `|file` constant-value validation) SHALL be shared between both suite types via a `BindingValidator` component.

**Scope clarification:** The shared binding cross-validation covers: required variable without binding, binding to unknown schema field, orphan binding detection, and `|file` constant-value validation. DEPLOYMENT-only checks (endpoint schema conformance, content-type mismatch, header blacklist, FILE form part validation) remain in `validateDeploymentSuite()` and do NOT apply to MCP suites.

#### Scenario: Required template variable without binding
- **WHEN** a `${{var}}` (no default) exists in the template and no binding has `templateVariable == var`
- **THEN** system SHALL add a validation warning: "Required template variable '$var' has no binding"

#### Scenario: Optional template variable without binding
- **WHEN** a `${{var:default}}` exists in the template and no binding has `templateVariable == var`
- **THEN** system SHALL accept without warning (uses template default)

#### Scenario: Binding references variable not in template
- **WHEN** an `inputBindings` entry has `templateVariable` that does not match any `${{...}}` in the template
- **THEN** system SHALL add a validation warning: "Binding for '$var' but no ${{$var}} found in template"

#### Scenario: Binding dataField not in testCaseSchema
- **WHEN** a binding's `dataField` does not match any field name in `testCaseSchema`
- **THEN** system SHALL add a validation warning: "Binding maps to unknown field '$field'"

#### Scenario: Template does not conform to endpoint schema
- **WHEN** endpoint schema is present and resolved template (with defaults only) does not conform to `endpointRef.requestBodySchema`
- **THEN** system SHALL add a validation warning but SHALL NOT reject the request

#### Scenario: MCP suite — required argument template variable without binding
- **WHEN** an MCP_TOOL suite has `argumentTemplate.arguments = {"query": "${{userQuery}}"}` (no default) and no binding has `templateVariable == "userQuery"`
- **THEN** system SHALL add a validation warning with code `REQUIRED` and path `$.inputBindings`: "Required variable 'userQuery' has no binding"

#### Scenario: MCP suite — optional argument template variable without binding
- **WHEN** an MCP_TOOL suite has `argumentTemplate.arguments = {"limit": "${{maxResults:10}}"}` and no binding for `maxResults`
- **THEN** system SHALL accept without warning (uses template default)

#### Scenario: MCP suite — binding references variable not in argument template
- **WHEN** an MCP_TOOL suite has a binding with `templateVariable = "unused"` that does not match any `${{...}}` in `argumentTemplate`
- **THEN** system SHALL add a validation warning with code `ADDITIONAL`: "Binding for 'unused' but no ${{unused}} in template"

#### Scenario: MCP suite — binding dataField not in testCaseSchema
- **WHEN** an MCP_TOOL suite has a binding with `dataField = "nonexistent"` and `testCaseSchema` has no field named `nonexistent`
- **THEN** system SHALL add a validation warning with code `UNKNOWN`: "Binding maps variable 'userQuery' to unknown field 'nonexistent'"

#### Scenario: MCP suite — all bindings valid
- **WHEN** an MCP_TOOL suite has `argumentTemplate.arguments = {"query": "${{userQuery}}"}`, a binding `templateVariable = "userQuery"`, `dataField = "question"`, and `testCaseSchema` includes field `question`
- **THEN** system SHALL produce no binding-related validation warnings

#### Scenario: MCP suite — null argument template warns
- **WHEN** an MCP_TOOL suite has `argumentTemplate` as null
- **THEN** system SHALL add a validation warning with code `ADDITIONAL`: "argumentTemplate is recommended for MCP tool invocation"
- **AND** the suite's `isValid` SHALL be `false` (warnings are non-empty)
- **AND** no binding cross-validation SHALL be performed (no template to validate against)

#### Scenario: MCP suite — unrecognized type hint warning
- **WHEN** an MCP_TOOL suite has `argumentTemplate.arguments = {"data": "${{input|unknown_type}}"}`
- **THEN** system SHALL add a validation warning with code `TYPE` for the unrecognized type hint, same as deployment suites

#### Scenario: MCP suite — empty bindings list with required variables produces REQUIRED warnings
- **WHEN** an MCP_TOOL suite has `argumentTemplate.arguments = {"query": "${{userQuery}}"}` and `inputBindings = []` (explicit empty list)
- **THEN** system SHALL add a validation warning with code `REQUIRED`: "Required variable 'userQuery' has no binding"
- **AND** the behavior SHALL be identical to `inputBindings = null` — both mean no bindings are configured

#### Scenario: MCP suite — duplicate variable in argument template validated once
- **WHEN** an MCP_TOOL suite has `argumentTemplate.arguments = {"q1": "${{query}}", "q2": "${{query}}"}` (same variable used twice)
- **AND** a single binding exists for `templateVariable = "query"`
- **THEN** system SHALL produce no REQUIRED warning (the single binding covers both occurrences)
- **AND** only one binding cross-validation check SHALL be performed for the deduplicated variable

#### Scenario: MCP suite — |file binding with dataField (no file ref validation at save time)
- **WHEN** an MCP_TOOL suite has `argumentTemplate.arguments = {"document": "${{doc|file}}"}`
- **AND** a binding maps `doc` to `dataField = "file_path"` (not `constantValue`)
- **AND** `testCaseSchema` includes field `file_path`
- **THEN** system SHALL produce no validation warning (the actual file reference value is unknown at save time — it comes from test case data at runtime; only `constantValue` bindings for `|file` variables are validated as file refs)

## Implementation Notes

- Shared binding validation logic extracted into a `BindingValidator` `@Component` in `service.domain` — injected by `SuiteValidationService`. Method: `validate(variables, bindings, schema, suiteId)` returning `List<ValidationWarningDto>`
- `validateDeploymentSuite()` calls `bindingValidator.validate(...)` with variables from `TemplateVariableExtractor.extractWithWarnings(requestTemplate)`
- `validateMcpSuite()` calls `bindingValidator.validate(...)` with variables from `TemplateVariableExtractor.extractFromArgumentTemplateWithWarnings(argumentTemplate)` — new method that returns `ExtractionResult` (same as `extractWithWarnings()`)
- The shared component includes `|file` constant-value validation — applies to both suite types
- MCP path does NOT perform content-type mismatch, header blacklist, or endpoint schema checks (these are deployment-specific)
- Warning codes and paths are consistent between deployment and MCP paths for frontend uniformity
