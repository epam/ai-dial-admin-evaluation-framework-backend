## MODIFIED Requirements

### Requirement: Template variable extraction convenience API
The service SHALL provide `GET /api/v1/test-suites/{id}/template-variables` to return all extracted template variables with metadata, **grouped by request index** across the suite's request chain.

The response SHALL be a JSON object whose keys are request indices (decimal strings) and whose values are lists of `TemplateVariableDto`:
- key `"0"` — the suite's own request (`requestTemplate`, `inputBindings`, `endpointRef`);
- key `"n"` (n ≥ 1) — `additionalRequests[n - 1]`, using that request's own `requestTemplate`, `inputBindings` and `endpointRef` (nothing is inherited from request #0).

Every index `0..N` of the chain SHALL be present, in chain order, even when its list is empty. The dataset test-case schema used for type inference is shared across all requests. Each request is resolved independently, so the same variable name MAY appear under several keys with different bindings, types and resolved values.

Status: **Implemented**

#### Scenario: Extract variables from suite template
- **WHEN** client calls `GET /api/v1/test-suites/{id}/template-variables` for a suite without `additionalRequests`
- **THEN** system SHALL return `{"0": [...]}` — a single entry whose list holds the `TemplateVariableDto` entries extracted from `requestTemplate`

#### Scenario: Extract variables from every request of a chain
- **WHEN** a suite's request #0 is `GET /settings` with no placeholders and `additionalRequests[0]` is `POST /chat/completions` whose body contains `${{user_message}}` and `${{temperature}}`
- **THEN** `GET /api/v1/test-suites/{id}/template-variables` SHALL return `{"0": [], "1": [user_message, temperature]}`

#### Scenario: Additional request uses its own bindings
- **WHEN** `additionalRequests[0].inputBindings` binds `user_message` to `constantValue: "hi"` and the suite-level `inputBindings` binds `user_message` to `dataField: "question"`
- **THEN** the entry for `user_message` under key `"1"` SHALL carry the `constantValue` binding and `resolvedValue = "hi"`
- **AND** an entry for `user_message` under key `"0"` (if request #0 uses it) SHALL carry the `dataField` binding

#### Scenario: Additional request uses its own endpoint for type inference
- **WHEN** `additionalRequests[0].endpointRef` declares `temperature` as `NUMBER` and request #0's `endpointRef` does not declare it
- **THEN** the entry for `temperature` under key `"1"` SHALL have `effectiveType: "NUMBER"`

#### Scenario: Request without placeholders maps to an empty list
- **WHEN** a chain request has a null `requestTemplate` or a template with no placeholders
- **THEN** its key SHALL be present with an empty list

#### Scenario: OpenAPI declares the map response
- **WHEN** a client reads `/v3/api-docs`
- **THEN** the `200` response of both template-variables operations SHALL be declared under `application/json` as an object whose `additionalProperties` are arrays of `TemplateVariableDto`
- **AND** both operations SHALL carry `minimal` and `full` response examples in the map shape

#### Scenario: TemplateVariableDto structure
- **WHEN** system extracts template variables
- **THEN** each `TemplateVariableDto` SHALL include: `name` (String — the variable name), `sources` (Set of `TemplateVariableSource` enum — BODY, URL, QUERY, HEADER for HTTP requests; ARGUMENT for MCP suites), `hasDefault` (boolean), `defaultValue` (String, nullable — raw default from `${{var:default}}`), `binding` (InputBindingDto, nullable — resolved binding from that request's own `inputBindings`: the suite-level list for key `"0"`, `additionalRequests[n - 1].inputBindings` for key `"n"`), `declaredType` (SchemaFieldType, nullable — the type explicitly declared in the placeholder syntax via `|type`; null when no type hint is present), `effectiveType` (SchemaFieldType, non-null — the fully resolved type determined by the priority chain below), `resolvedValue` (Object, nullable — the resolved typed value for this variable, see resolution rules below)

The legacy `inferredType` field is replaced by `effectiveType`. The JSON property name SHALL be `effectiveType`.

#### Scenario: Variable source tracking
- **WHEN** `${{model}}` appears in both `body` and `queryParams`
- **THEN** system SHALL return a single entry with `sources: [BODY, QUERY]`

#### Scenario: Type inference priority (effectiveType)
- **WHEN** system resolves the type of a template variable
- **THEN** it SHALL prioritize: (1) `declaredType` from placeholder syntax, (2) `endpointRef.requestBodySchema` or parameter definition, (3) `testCaseSchema` field type (via binding's `dataField`), (4) fallback to `STRING`

#### Scenario: TemplateVariableDto with declared FILE type
- **WHEN** template contains `"${{doc|file}}"` and no binding exists
- **THEN** the entry SHALL have `declaredType: "FILE"`, `effectiveType: "FILE"`

#### Scenario: TemplateVariableDto — declared type overrides binding inference
- **WHEN** template contains `"${{doc|file}}"` and a binding maps `doc` → `dataField: "title"` where `title` has `type: STRING` in testCaseSchema
- **THEN** the entry SHALL have `declaredType: "FILE"`, `effectiveType: "FILE"` (declared wins over endpointRef and binding)

#### Scenario: TemplateVariableDto — binding inference used when no declared type
- **WHEN** template contains `"${{doc}}"` and a binding maps `doc` → `dataField: "input_doc"` where `input_doc` has `type: FILE` in testCaseSchema
- **THEN** the entry SHALL have `declaredType: null`, `effectiveType: "FILE"` (from binding)

#### Scenario: TemplateVariableDto — STRING fallback when no declared type and no binding
- **WHEN** template contains `"${{prompt}}"` and no binding exists
- **THEN** the entry SHALL have `declaredType: null`, `effectiveType: "STRING"`

#### Scenario: TemplateVariableDto — no declared type and constant-value binding
- **WHEN** template contains `"${{model}}"`, no endpointRef schema entry exists for `model`, and a binding maps `model` → `constantValue: "gpt-4"`
- **THEN** the entry SHALL have `declaredType: null`, `effectiveType: "STRING"` (constant-value bindings have no dataField, so testCaseSchema type inference is not applicable; falls through to STRING)

#### Scenario: Non-existent TestSuite
- **WHEN** client calls `GET /api/v1/test-suites/{id}/template-variables` with a non-existent id
- **THEN** system SHALL respond with HTTP 404

#### Scenario: TestSuite with no template
- **WHEN** client calls the endpoint for a TestSuite with `requestTemplate: null` and no `additionalRequests`
- **THEN** system SHALL return `{"0": []}`

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
The service SHALL provide `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/template-variables` to return template variables for a specific test case: the suite's template and bindings resolved against that test case's `data`. The test case is looked up dataset-scoped via the suite's `datasetId`. Per-test-case `requestTemplateOverride` / `inputBindingsOverride` were removed when test cases moved to datasets, so the "effective" template/bindings are always the suite's; the only difference from `GET /api/v1/test-suites/{testSuiteId}/template-variables` is that `resolvedValue` is fully resolved using the test case's `data`. For `MCP_TOOL` suites, variables are extracted from `argumentTemplate`; for HTTP suites, from `requestTemplate` and every `additionalRequests[i].requestTemplate`. The response has the same request-index-keyed shape as the suite endpoint (key `"0"` = suite's own request, key `"n"` = `additionalRequests[n - 1]` with its own bindings and endpoint). The test-case schema used for type inference is sourced from the suite's dataset; the test case's `data` is shared across all requests of the chain.

Status: **Implemented**

#### Scenario: Extract variables for a test case
- **WHEN** client calls `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/template-variables`
- **THEN** system SHALL return an object keyed by request index (same shape as the suite endpoint) whose lists hold `TemplateVariableDto` entries extracted from each chain request's `requestTemplate` (or, under key `"0"` only, `argumentTemplate` for `MCP_TOOL` suites), with `binding` populated from that request's own `inputBindings`

#### Scenario: Additional request variables resolved from test case data
- **WHEN** `additionalRequests[0]` binds `user_message` to `dataField: "question"` and the test case has `data = {"question": "What is AI?"}`
- **THEN** the entry for `user_message` under key `"1"` SHALL have `resolvedValue = "What is AI?"`

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
- **WHEN** the suite has `requestTemplate: null` and no `additionalRequests` (HTTP suite) or `argumentTemplate: null` (MCP suite)
- **THEN** system SHALL return `{"0": []}`

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

The `TemplateVariableService` SHALL support MCP_TOOL suites via `GET /api/v1/test-suites/{id}/template-variables` and `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/template-variables`. When the suite type is `MCP_TOOL`, the service SHALL extract variables from the `argumentTemplate` (not `requestTemplate`) and resolve them using the MCP-specific resolution path with input bindings support. MCP suites have no request chain, so both endpoints SHALL return a single-entry object `{"0": [...]}`.

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

#### Scenario: MCP variable with declared type hint takes priority
- **WHEN** an MCP template variable has a declared type hint (e.g., `${{count|integer}}`)
- **THEN** `declaredType` SHALL take priority over the `testCaseSchema` type

#### Scenario: MCP suite with null argument template
- **WHEN** an MCP_TOOL suite has `argumentTemplate` as null
- **THEN** the template variables endpoint SHALL return `{"0": []}`

#### Scenario: MCP variable extraction uses TemplateVariableExtractor
- **WHEN** extracting variables from an MCP argument template
- **THEN** the system SHALL use `TemplateVariableExtractor.extractFromArgumentTemplate(argumentTemplate)` which recursively scans `argumentTemplate.arguments` for `${{variable}}` placeholders using the same extraction logic as HTTP templates

## Implementation notes

- `service.domain.TemplateVariableService` — iterates the chain (`requestTemplate` + `additionalRequests`) and builds the index-keyed map.
- `web.controller.TemplateVariableController` — both endpoints return the map; OpenAPI examples under `src/main/resources/openapi/examples/api-v1-test-suites-testSuiteId{,-test-cases-testCaseId}-template-variables-GET-response-200-{minimal,full}.json`.
