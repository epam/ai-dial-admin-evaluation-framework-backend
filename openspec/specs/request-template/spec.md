# Request Template

## Purpose
This spec describes the request template system for TestSuites, including `${{variable}}` placeholder syntax, input bindings, template variable extraction, and runtime request assembly.

Status: **Implemented** (template storage, validation, variable extraction APIs, resolved-request preview).

## Requirements

### Requirement: Request template on TestSuite
The service SHALL allow storing a `requestTemplate` (`RequestTemplateDto`, nullable) on a TestSuite. The template uses embedded `${{variable}}` and `${{variable:default}}` placeholder syntax (Postman-style) to mark variable parts of the request. The template SHALL be nullable (null when the endpoint has no request body and no URL/query/header templating is needed).

#### Scenario: Create TestSuite with request template
- **WHEN** client calls `POST /api/v1/test-suites` with `requestTemplate` containing a valid `RequestTemplateDto`
- **THEN** system SHALL persist the template and return it in the response

#### Scenario: Create TestSuite without request template
- **WHEN** client calls `POST /api/v1/test-suites` without `requestTemplate` or with `requestTemplate: null`
- **THEN** system SHALL accept the request, persist `requestTemplate` as null, and add a suite-level soft validation warning (same as null `urlTemplate`)

#### Scenario: Update request template
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with an updated `requestTemplate`
- **THEN** system SHALL persist the updated template and trigger re-validation of existing TestCases

### Requirement: RequestTemplate structure
`RequestTemplateDto` SHALL have the following fields, all optional:
- `urlTemplate` (String, nullable): Valid URL path that can contain `${{variable}}` placeholders (e.g., `/api/v1/models/${{model_id}}/completions`). When null, a **soft validation warning** SHALL be added (e.g. "urlTemplate is required for request assembly"). When non-null, the value SHALL be a **valid path** (literal segments and `${{var}}` placeholders only); invalid values SHALL produce a soft validation warning. After resolving all placeholders, the **final request URL SHALL match** `endpointRef.relativeUrlPattern`.
- `queryParams` (List of `KeyValueTemplateDto`, nullable): Query parameters. Each entry has a literal `key` and a `value` that can contain `${{variable}}` or `${{variable:default}}` placeholders.
- `headers` (List of `KeyValueTemplateDto`, nullable): Custom headers. Same structure as `queryParams`.
- `body` (`RequestBodyDto`, nullable): Polymorphic request body with `contentType` discriminator. When null, the endpoint has no request body. Supported content types: `application/json` (content: `Map<String, Object>`), `multipart/form-data` (content: `List<FormPartDto>`), `application/x-www-form-urlencoded` (content: `List<KeyValueTemplateDto>` — reuses existing key-value pair type, supports duplicate keys). The `${{variable}}` and `${{variable:default}}` placeholder syntax is supported within all body content types.

#### Scenario: Template with JSON body (current behavior, wrapped)
- **WHEN** client creates a template with `body: { "contentType": "application/json", "content": { "model": "${{model}}", "messages": "${{messages}}" } }`
- **THEN** system SHALL accept and persist the template; placeholders in `content` are extracted as template variables with source BODY

#### Scenario: Template with multipart/form-data body
- **WHEN** client creates a template with `body: { "contentType": "multipart/form-data", "content": [ { "name": "prompt", "type": "text", "value": "${{prompt}}" }, { "name": "file", "type": "file", "value": "${{document}}" } ] }`
- **THEN** system SHALL accept and persist the template; `${{prompt}}` and `${{document}}` are extracted as template variables with source BODY

#### Scenario: Template with URL-encoded body
- **WHEN** client creates a template with `body: { "contentType": "application/x-www-form-urlencoded", "content": [ { "key": "username", "value": "${{user}}" }, { "key": "action", "value": "process" } ] }`
- **THEN** system SHALL accept and persist the template; `${{user}}` is extracted as a template variable with source BODY

#### Scenario: Null body means no request body
- **WHEN** client creates a template with `body: null`
- **THEN** system SHALL treat this as an endpoint with no request body

#### Scenario: Template with body only
- **WHEN** client creates a template with only `body` set (urlTemplate, queryParams, headers all null)
- **THEN** system SHALL accept the template and SHALL add a soft validation warning for null urlTemplate

#### Scenario: Template with URL variables
- **WHEN** client creates a template with `urlTemplate: "/api/v1/deployments/${{deployment_id}}/chat/completions"` (valid path with placeholders)
- **THEN** system SHALL accept and persist the template; `${{deployment_id}}` is extracted as a template variable with source URL

#### Scenario: Null urlTemplate causes soft validation warning
- **WHEN** client creates or updates a TestSuite with `requestTemplate: null`, or with `requestTemplate` non-null and `urlTemplate: null`
- **THEN** system SHALL accept the request, persist the value, and add a suite-level soft validation warning (e.g. "urlTemplate is required for request assembly")

#### Scenario: Invalid urlTemplate causes soft validation warning
- **WHEN** client sends a non-null `urlTemplate` that is not a valid path
- **THEN** system SHALL add a soft validation warning

#### Scenario: Resolved URL must match endpoint pattern
- **WHEN** the system resolves `urlTemplate` (replaces all `${{var}}` placeholders with values)
- **THEN** the resulting URL SHALL match `endpointRef.relativeUrlPattern` using Java `Pattern.matches()` semantics; if it does not, system SHALL add a validation warning
- **NOTE**: `relativeUrlPattern` accepts a literal path (e.g. `/chat/completions`) or a Java regex (e.g. `/api/v[\\d]+/client/.*`). A literal path matches only itself.

#### Scenario: Template with query parameters
- **WHEN** client creates a template with `queryParams: [{"key": "api-version", "value": "${{api_version:2024-01-01}}"}]`
- **THEN** system SHALL accept and persist; `${{api_version}}` is extracted with source QUERY and default `"2024-01-01"`

#### Scenario: Template with headers
- **WHEN** client creates a template with `headers: [{"key": "X-Request-Id", "value": "${{request_id}}"}]`
- **THEN** system SHALL accept and persist; `${{request_id}}` is extracted with source HEADER

#### Scenario: Template body with nested variables (JSON variant)
- **WHEN** client creates a JSON body template containing nested `${{}}` placeholders (e.g., inside arrays or nested objects in `content`)
- **THEN** system SHALL extract all placeholders regardless of nesting depth

#### Scenario: Template body variable extraction from multipart parts
- **WHEN** client creates a multipart body template
- **THEN** system SHALL extract `${{}}` placeholders from each `FormPartDto.value` and `FormPartDto.filename` fields

#### Scenario: Null template equivalent to all-null-fields template
- **WHEN** client sends `requestTemplate: null` vs `requestTemplate: { "urlTemplate": null, "queryParams": null, "headers": null, "body": null }`
- **THEN** system SHALL treat these as semantically equivalent — both produce the same validation warnings and behavior

### Requirement: KeyValueTemplate structure
`KeyValueTemplateDto` SHALL have: `key` (String, required, non-blank — the literal parameter or header name) and `value` (String, required — can contain `${{variable}}` or `${{variable:default}}` placeholders, or be a literal value).

#### Scenario: Literal query parameter
- **WHEN** client creates a query param with `key: "stream"` and `value: "false"`
- **THEN** system SHALL accept it as a static parameter (no variables extracted)

#### Scenario: Variable query parameter with default
- **WHEN** client creates a query param with `key: "api-version"` and `value: "${{api_version:2024-01-01}}"`
- **THEN** system SHALL extract `api_version` as a template variable with source QUERY and default `"2024-01-01"`

### Requirement: Template variable syntax
The service SHALL support the following placeholder syntax within `requestTemplate` fields:
- `${{variable}}` — a required variable (no type hint, no default)
- `${{variable:default}}` — an optional variable with a default value (the raw string after the colon)
- `${{variable|type}}` — a required variable with an explicit type hint
- `${{variable|type:default}}` — an optional variable with an explicit type hint and a default value

Variable names SHALL match `[a-zA-Z0-9_]+` (alphanumeric and underscore only). The `|` character SHALL NOT appear in a variable name; its first occurrence after the name is always the type-hint separator.

The type hint SHALL be a case-insensitive `SchemaFieldType` keyword: `string`, `integer`, `number`, `boolean`, `object`, `array`, `file`. Default values (after `:`) are unrestricted — they may contain `|`, `:`, `::`, or any character other than `}}`.

The placeholder syntax SHALL be recognised in: `urlTemplate` string, `queryParams[*].value` strings, `headers[*].value` strings, and any string value at any depth in `body`.

#### Scenario: Parse simple variable
- **WHEN** template contains `"${{prompt}}"`
- **THEN** system SHALL extract variable `prompt` with `declaredType: null`, `hasDefault: false`

#### Scenario: Parse variable with default
- **WHEN** template contains `"${{model:gpt-4}}"`
- **THEN** system SHALL extract variable `model` with `declaredType: null`, `hasDefault: true`, `defaultValue: "gpt-4"`

#### Scenario: Parse variable with type hint
- **WHEN** template contains `"${{doc|file}}"`
- **THEN** system SHALL extract variable `doc` with `declaredType: FILE`, `hasDefault: false`

#### Scenario: Parse variable with type hint and default
- **WHEN** template contains `"${{ctx|file:files/public/default-context.txt}}"`
- **THEN** system SHALL extract variable `ctx` with `declaredType: FILE`, `hasDefault: true`, `defaultValue: "files/public/default-context.txt"`

#### Scenario: Default value may contain pipe character
- **WHEN** template contains `"${{q|string:opt-a|opt-b}}"`
- **THEN** system SHALL extract variable `q` with `declaredType: STRING`, `hasDefault: true`, `defaultValue: "opt-a|opt-b"`

#### Scenario: Default value may contain double-colon
- **WHEN** template contains `"${{query|string:SELECT id::uuid FROM t}}"`
- **THEN** system SHALL extract variable `query` with `declaredType: STRING`, `hasDefault: true`, `defaultValue: "SELECT id::uuid FROM t"`

#### Scenario: Unknown type hint produces soft validation warning
- **WHEN** template contains `"${{doc|unknowntype}}"`
- **THEN** system SHALL extract the variable with `declaredType: null` and add a suite-level soft validation warning indicating the unrecognised type hint
- **AND** system SHALL NOT reject the request with HTTP 400

#### Scenario: Type hint is case-insensitive
- **WHEN** template contains `"${{doc|FILE}}"` or `"${{doc|File}}"` or `"${{doc|file}}"`
- **THEN** system SHALL normalise to `SchemaFieldType.FILE` in all cases

#### Scenario: Multiple variables in one string
- **WHEN** a template value contains `"Hello ${{name}}, your score is ${{score:0}}"`
- **THEN** system SHALL extract both `name` (no default) and `score` (default `"0"`)

#### Scenario: Duplicate variables across template sections
- **WHEN** the same variable name `${{model}}` appears in both `body` and `queryParams`
- **THEN** system SHALL track the variable once with multiple sources (e.g., `sources: [BODY, QUERY]`)

#### Scenario: Same variable appears multiple times within one section
- **WHEN** `${{model}}` appears at multiple paths within `body` (e.g. in two nested objects)
- **THEN** system SHALL track the variable once with source BODY; all occurrences are resolved with the same value

### Requirement: Input bindings on TestSuite
The service SHALL store `inputBindings` (a list of `InputBindingDto`, never null, defaults to empty list when omitted in requests) on a TestSuite. Each binding explicitly maps a template variable to a `testCaseSchema` data field or a constant value. Bindings are the sole mapping mechanism — there is no implicit auto-mapping by variable name.

#### Scenario: Create TestSuite with input bindings
- **WHEN** client calls `POST /api/v1/test-suites` with `inputBindings` list
- **THEN** system SHALL persist the bindings and return them in the response

#### Scenario: Empty input bindings
- **WHEN** client calls `POST /api/v1/test-suites` with `inputBindings: []` or without `inputBindings`
- **THEN** system SHALL accept the request and persist an empty binding list

#### Scenario: Update input bindings triggers re-validation
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with updated `inputBindings`
- **THEN** system SHALL persist the updated bindings and trigger re-validation of existing TestCases

### Requirement: InputBinding structure
Each `InputBindingDto` SHALL have: `templateVariable` (String, required, non-blank — the `${{var}}` name), `dataField` (String, nullable — name of a field in `testCase.data`), and `constantValue` (Object, nullable — a fixed value). Exactly one of `dataField` or `constantValue` SHALL be non-null.

#### Scenario: Data field binding
- **WHEN** client creates a binding with `templateVariable: "prompt"` and `dataField: "user_prompt"`
- **THEN** system SHALL accept the binding (maps template variable `${{prompt}}` to `data["user_prompt"]`)

#### Scenario: Constant value binding
- **WHEN** client creates a binding with `templateVariable: "model"` and `constantValue: "gpt-4"`
- **THEN** system SHALL accept the binding (template variable `${{model}}` always resolves to `"gpt-4"`)

#### Scenario: Both dataField and constantValue set
- **WHEN** client sends a binding with both `dataField` and `constantValue` non-null
- **THEN** system SHALL respond with HTTP 400

#### Scenario: Neither dataField nor constantValue set
- **WHEN** client sends a binding with both `dataField` and `constantValue` null
- **THEN** system SHALL respond with HTTP 400

#### Scenario: Missing templateVariable
- **WHEN** client sends a binding without `templateVariable` or with blank value
- **THEN** system SHALL respond with HTTP 400

#### Scenario: Duplicate templateVariable in bindings
- **WHEN** client sends `inputBindings` (or `inputBindingsOverride`) with two or more entries having the same `templateVariable` value
- **THEN** system SHALL respond with HTTP 400

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

### Requirement: Default value strategy
For template variables with `${{var:default}}` syntax, the default value is the raw string after the colon in the placeholder. Defaults SHALL be interpreted with **typed defaults**: where schema type is known (e.g. from endpoint or testCaseSchema), the default string SHALL be interpreted as that type (e.g. number `0.7`, boolean `true`). When a binding exists and the data field has a value, the data value overrides the default. When a binding exists with `constantValue`, the constant always overrides the default.

#### Scenario: Default from template syntax (typed)
- **WHEN** template has `"temperature": "${{temp:0.7}}"` and no binding exists for `temp`
- **THEN** the resolved value SHALL be the default interpreted per schema type (e.g. number `0.7` when schema type is NUMBER; string `"0.7"` when type is STRING or unknown)

#### Scenario: Data value overrides default
- **WHEN** template has `"${{temp:0.7}}"`, binding maps `temp` to `dataField: "temperature"`, and test case has `data.temperature = 0.3`
- **THEN** the resolved value SHALL be `0.3`

#### Scenario: Constant overrides default
- **WHEN** template has `"${{model:gpt-3.5}}"` and binding has `constantValue: "gpt-4"`
- **THEN** the resolved value SHALL be `"gpt-4"`

#### Scenario: Bound field with no data value falls back to default
- **WHEN** binding maps `temp` to `dataField: "temperature"`, template has `"${{temp:0.7}}"`, and `data.temperature` is null/missing
- **THEN** the resolved value SHALL be `"0.7"` (the template default)

#### Scenario: Bound field with no data value and no default
- **WHEN** binding maps `prompt` to `dataField: "user_prompt"`, template has `"${{prompt}}"` (no default), and `data.user_prompt` is null/missing
- **THEN** system SHALL record a validation warning: "Required field 'user_prompt' has no value in data"

### Requirement: Per-test-case request template override
The service SHALL allow each TestCase to optionally override the suite's `requestTemplate` with a `requestTemplateOverride` (nullable `RequestTemplateDto`). When present, it fully replaces the suite template for that test case.

#### Scenario: Test case with template override
- **WHEN** client creates a TestCase with `requestTemplateOverride` as a valid `RequestTemplateDto`
- **THEN** system SHALL persist the override and use it instead of the suite template for that test case

#### Scenario: Test case without template override
- **WHEN** client creates a TestCase without `requestTemplateOverride` or with `requestTemplateOverride: null`
- **THEN** system SHALL use the suite's `requestTemplate` for that test case

#### Scenario: Override template validation
- **WHEN** a TestCase has `requestTemplateOverride`
- **THEN** system SHALL extract template variables from the override and validate against effective bindings

### Requirement: Per-test-case input bindings override
The service SHALL allow each TestCase to optionally override the suite's `inputBindings` with `inputBindingsOverride` (nullable list of `InputBindingDto`). When present, it fully replaces (not merges with) the suite bindings for that test case.

#### Scenario: Test case with bindings override
- **WHEN** client creates a TestCase with `inputBindingsOverride` as a list of bindings
- **THEN** system SHALL persist the override and use it instead of suite bindings for that test case

#### Scenario: Test case without bindings override
- **WHEN** client creates a TestCase without `inputBindingsOverride` or with `inputBindingsOverride: null`
- **THEN** system SHALL use the suite's `inputBindings` for that test case

#### Scenario: Override bindings must have valid structure
- **WHEN** an override binding violates the `InputBindingDto` structure constraints (e.g., both dataField and constantValue set)
- **THEN** system SHALL respond with HTTP 400

#### Scenario: Override binding references unknown data field
- **WHEN** an override binding's `dataField` does not match any field in `testCaseSchema`
- **THEN** system SHALL add a validation warning

### Requirement: Template variable extraction convenience API
The service SHALL provide `GET /api/v1/test-suites/{id}/template-variables` to return all extracted template variables with metadata.

#### Scenario: Extract variables from suite template
- **WHEN** client calls `GET /api/v1/test-suites/{id}/template-variables`
- **THEN** system SHALL return a list of `TemplateVariableDto` entries extracted from `requestTemplate`

#### Scenario: TemplateVariableDto structure
- **WHEN** system extracts template variables
- **THEN** each `TemplateVariableDto` SHALL include: `name` (String — the variable name), `sources` (Set of `TemplateVariableSource` enum — BODY, URL, QUERY, HEADER), `hasDefault` (boolean), `defaultValue` (String, nullable — raw default from `${{var:default}}`), `binding` (InputBindingDto, nullable — resolved binding from suite's `inputBindings`), `declaredType` (SchemaFieldType, nullable — the type explicitly declared in the placeholder syntax via `|type`; null when no type hint is present), `effectiveType` (SchemaFieldType, non-null — the fully resolved type determined by the priority chain below), `resolvedValue` (Object, nullable — the resolved typed value for this variable, see resolution rules below)

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

### Requirement: TemplateVariableSource enum
The `TemplateVariableSource` enum SHALL define: `BODY`, `URL`, `QUERY`, `HEADER`, `ARGUMENT`. HTTP suites use `BODY`, `URL`, `QUERY`, `HEADER` (representing where in the `RequestTemplateDto` a variable appears). MCP suites use `ARGUMENT` (representing a variable in the `ArgumentTemplateDto`).

### Requirement: Template variables for MCP suites

The `TemplateVariableService` SHALL support MCP_TOOL suites via `GET /api/v1/test-suites/{id}/template-variables` and `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/template-variables`. When the suite type is `MCP_TOOL`, the service SHALL extract variables from the `argumentTemplate` (not `requestTemplate`) and resolve them using the MCP-specific resolution path with input bindings support.

MCP suites support the same `inputBindings` and `inputBindingsOverride` mechanism as HTTP suites. The resolution priority for MCP template variables follows the same chain as `McpRequestResolver`: binding `constantValue` > binding `dataField` lookup > direct variable name lookup > template default > `null`.

Status: **Implemented**

#### Scenario: MCP suite-level template variables extracted from argument template
- **WHEN** a suite with `suiteType = MCP_TOOL` has `argumentTemplate.arguments = {"query": "${{userQuery}}", "limit": "${{maxResults:10}}"}`
- **THEN** `GET /api/v1/test-suites/{id}/template-variables` SHALL return variables `userQuery` and `maxResults` with `sources = [ARGUMENT]`
- **AND** `resolvedValue` SHALL be `null` for `userQuery` (no default, no data at suite level) and `"10"` for `maxResults` (has default)

#### Scenario: MCP suite-level template variables with constant-value binding
- **WHEN** a suite with `suiteType = MCP_TOOL` has a binding with `templateVariable: "userQuery"` and `constantValue: "fixed query"`
- **THEN** `GET /api/v1/test-suites/{id}/template-variables` SHALL return `userQuery` with `resolvedValue = "fixed query"` and `binding` populated

#### Scenario: MCP test-case-level template variables resolved from bindings and data
- **WHEN** a test case belongs to an MCP_TOOL suite with a binding mapping `userQuery` to `dataField: "question"`
- **AND** the test case has `data = {"question": "What is AI?"}`
- **THEN** `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/template-variables` SHALL return `userQuery` with `resolvedValue = "What is AI?"` (resolved via binding dataField lookup)

#### Scenario: MCP test-case-level template variables with no bindings (direct name lookup)
- **WHEN** a test case belongs to an MCP_TOOL suite with no input bindings
- **AND** the test case has `data = {"userQuery": "What is AI?"}`
- **THEN** the variable `userQuery` SHALL resolve via direct variable name lookup in data, returning `resolvedValue = "What is AI?"`

#### Scenario: MCP test-case with inputBindingsOverride
- **WHEN** a test case has `inputBindingsOverride` and the suite has `inputBindings`
- **THEN** the system SHALL use the test case's `inputBindingsOverride` (fully replaces, not merges with, suite-level bindings) — same override semantics as HTTP suites

#### Scenario: MCP variable type inference
- **WHEN** an MCP template variable has no declared type hint
- **THEN** `effectiveType` SHALL be inferred from `testCaseSchema` by matching the variable name to a schema field name
- **AND** if no match is found, `effectiveType` SHALL default to `STRING`

#### Scenario: MCP variable with declared type hint takes priority
- **WHEN** an MCP template variable has a declared type hint (e.g., `${{count|integer}}`)
- **THEN** `declaredType` SHALL take priority over the `testCaseSchema` type

#### Scenario: MCP suite with null argument template
- **WHEN** an MCP_TOOL suite has `argumentTemplate` as null
- **THEN** the template variables endpoint SHALL return an empty list

#### Scenario: MCP variable extraction uses TemplateVariableExtractor
- **WHEN** extracting variables from an MCP argument template
- **THEN** the system SHALL use `TemplateVariableExtractor.extractFromArgumentTemplate(argumentTemplate)` which recursively scans `argumentTemplate.arguments` for `${{variable}}` placeholders using the same extraction logic as HTTP templates

### Requirement: Runtime request assembly contract
The service defines the contract for assembling an HTTP request from template, bindings, and test case data (for use by future runner implementation and try-it-out feature).

The `ResolvedRequestService` provides two reuse paths for `TryItOutService` (both in `service.domain`):
- **`resolveRequest(UUID testSuiteId, UUID testCaseId)`** — public, `@Transactional(readOnly=true)`. Used by the test-case try-it-out path to delegate the full suite/case loading + resolution flow.
- **`resolve(RequestTemplateDto, List<InputBindingDto>, Map<String, Object>)`** — package-private. Used by the variables try-it-out path (same package, no visibility change needed).

#### Scenario: Resolution flow
- **WHEN** assembling a request for a test case
- **THEN** the runner SHALL:
  1. Determine effective template: `testCase.requestTemplateOverride ?? suite.requestTemplate`
  2. Determine effective bindings: `testCase.inputBindingsOverride ?? suite.inputBindings`
  3. Extract all placeholders (`${{var}}`, `${{var:default}}`, `${{var|type}}`, `${{var|type:default}}`) from effective template
  4. For each placeholder, strip any `|type` hint to obtain the bare variable name, then find binding where `templateVariable == varName`
  5. If binding found with `constantValue` → use constant
  6. If binding found with `dataField` → use `data[dataField]` (fall back to template default if null)
  7. If no binding and variable has default → use default
  8. If no binding and variable has no default → validation warning (required variable unbound)
  9. Replace placeholders using resolution mode: **full-value** (entire string is one `${{...}}`) → replace with typed resolved value preserving its type; **embedded** (multiple placeholders or surrounding text) → stringify and concatenate into a string
  10. For full-value defaults, interpret per schema type (e.g. `"0.7"` → number `0.7` when type is NUMBER; `"true"` → boolean `true` when type is BOOLEAN)
  11. Validate that the resolved URL matches `endpointRef.relativeUrlPattern`; if not, add a validation warning
  12. **Body resolution is content-type-aware**: for `application/json`, resolve the `content` Map recursively (current behavior). For `multipart/form-data`, resolve each `FormPartDto.value` and `FormPartDto.filename`. For `application/x-www-form-urlencoded`, resolve each `KeyValueTemplateDto.value` placeholder and stringify all resolved values.
  13. Return `ResolvedRequestDto` with the body as the corresponding `ResolvedBodyDto` variant

#### Scenario: Assembly with suite defaults
- **WHEN** a test case has no overrides
- **THEN** the runner SHALL use `suite.requestTemplate` + `suite.inputBindings`

#### Scenario: Assembly with per-case overrides
- **WHEN** a test case has `requestTemplateOverride` and/or `inputBindingsOverride`
- **THEN** the runner SHALL use the overrides in place of suite defaults

#### Scenario: Full-value placeholder resolution (type-preserving)
- **WHEN** a template string value consists of exactly one `${{var}}` or `${{var:default}}` placeholder with no surrounding text (e.g. `"temperature": "${{temp:0.7}}"`)
- **THEN** system SHALL replace the string with the resolved value preserving its original type (e.g. number `0.7`, boolean `true`, array, object)

#### Scenario: Embedded placeholder resolution (string interpolation)
- **WHEN** a template string value contains multiple placeholders or any text outside placeholders (e.g. `"prompt": "Hello ${{name}}, score: ${{score}}"`)
- **THEN** system SHALL stringify all resolved values and concatenate them, producing a string result (e.g. `"Hello John, score: 42"`)

#### Scenario: Type hint does not affect binding lookup
- **WHEN** a placeholder contains a type hint (e.g. `${{temperature|number}}` or `${{enabled|boolean:true}}`)
- **THEN** system SHALL strip the `|type` suffix before looking up the binding, so the binding with `templateVariable == "temperature"` is matched correctly
- **AND** the resolved value is handled with full-value or embedded resolution rules as normal

#### Scenario: Type-hinted placeholder with default resolves correctly
- **WHEN** a placeholder has both a type hint and a default (e.g. `${{temp|number:0.7}}`) and no binding is found
- **THEN** system SHALL use the default `"0.7"` and interpret it as the specified type (`number` → `0.7`), producing a typed resolved value

#### Scenario: MCP argument template type hint does not affect resolution
- **WHEN** an MCP tool argument template contains a type-hinted placeholder (e.g. `${{count|integer:5}}`)
- **THEN** system SHALL strip the `|integer` hint before binding lookup, apply the same full-value resolution rules, and produce a typed integer result

#### Scenario: Multipart body resolution
- **WHEN** the template body is `MultipartFormDataRequestBodyDto`
- **THEN** the system SHALL resolve each `FormPartDto.value` using the same placeholder resolution rules (full-value or embedded) and produce a `ResolvedMultipartBodyDto` with `ResolvedFormPartDto` entries

#### Scenario: URL-encoded body resolution
- **WHEN** the template body is `UrlEncodedFormRequestBodyDto`
- **THEN** the system SHALL resolve each `KeyValueTemplateDto.value` placeholder and stringify all resolved values (URL-encoded forms are always string-valued), producing a `ResolvedUrlEncodedBodyDto` with resolved `List<KeyValueTemplateDto>` entries

#### Scenario: Full-value resolution with typed default
- **WHEN** template has `"temperature": "${{temp:0.7}}"`, no binding exists, and schema type is NUMBER
- **THEN** system SHALL interpret the default as number `0.7` and replace the string value with the typed result

#### Scenario: Full-value resolution preserves complex types
- **WHEN** template has `"messages": "${{messages}}"` and `data["messages"]` is an array `[{"role":"user","content":"Hi"}]`
- **THEN** system SHALL replace the string with the array value, producing `"messages": [{"role":"user","content":"Hi"}]`

#### Scenario: resolve() method is accessible via package-private visibility
- **WHEN** `TryItOutService` (same `service.domain` package) needs to resolve a template with bindings and data for the variables path
- **THEN** it SHALL call `ResolvedRequestService.resolve(template, bindings, data)` directly (package-private access)
- **AND** receive a `ResolvedRequestDto` with resolved URL, query params, headers, body (`ResolvedBodyDto`), and warnings

#### Scenario: resolveRequest() method reused for test-case path
- **WHEN** `TryItOutService` needs to resolve a request for a specific test case
- **THEN** it SHALL delegate to the existing public `ResolvedRequestService.resolveRequest(testSuiteId, testCaseId)` method
- **AND** the `@Transactional(readOnly=true)` scope SHALL be confined to that call, releasing the DB connection before the DIAL Core invocation

### Requirement: Maximum template size
The service SHALL enforce a **configurable maximum size** (in bytes) for the serialized request template (URL + query params + headers + body). The limit applies to both `requestTemplate` on a TestSuite and `requestTemplateOverride` on a TestCase. The default limit SHALL be **64KB**. When the serialized template exceeds the limit, the service SHALL reject the request with HTTP 400.

#### Scenario: Template within limit
- **WHEN** client sends a `requestTemplate` whose serialized size is within the configured limit
- **THEN** system SHALL accept and persist the template

#### Scenario: Template exceeds limit
- **WHEN** client sends a `requestTemplate` whose serialized size exceeds the configured limit
- **THEN** system SHALL respond with HTTP 400

### Requirement: Maximum input bindings count
The service SHALL enforce a **configurable maximum count** for `inputBindings` on a TestSuite and for `inputBindingsOverride` on a TestCase. The default limit SHALL be **64**. When the count exceeds the limit, the service SHALL reject the request with HTTP 400.

#### Scenario: Bindings within limit
- **WHEN** client sends `inputBindings` (or `inputBindingsOverride`) with count within the configured limit
- **THEN** system SHALL accept and persist the bindings

#### Scenario: Bindings exceed limit
- **WHEN** client sends `inputBindings` (or `inputBindingsOverride`) with count exceeding the configured limit
- **THEN** system SHALL respond with HTTP 400

### Requirement: Resolved request preview for TestCase
The service SHALL provide `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/resolved-request` to return the **resolved request** for that test case: URL, query parameters, headers, and body after applying the effective template, effective bindings, and test case `data`. This supports debugging and UI preview without executing the request.

#### Scenario: Get resolved request for test case
- **WHEN** client calls `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/resolved-request`
- **THEN** system SHALL resolve the effective template with effective bindings and test case `data` (per Runtime request assembly contract) and return a `ResolvedRequestDto`

#### Scenario: ResolvedRequestDto structure
- **WHEN** system returns the resolved request
- **THEN** the `ResolvedRequestDto` SHALL include: `url` (String — resolved URL path after placeholder substitution), `queryParams` (List of key-value pairs — resolved query parameters), `headers` (List of key-value pairs — resolved headers), `body` (`ResolvedBodyDto`, nullable — polymorphic resolved request body matching the template's content type), `warnings` (List of validation warning objects — unresolved placeholders, missing data, URL pattern mismatch, etc.)

#### Scenario: Resolved request uses effective template and bindings
- **WHEN** the test case has `requestTemplateOverride` and/or `inputBindingsOverride`
- **THEN** system SHALL use those overrides to compute the resolved request (same resolution rules as assembly)

#### Scenario: Missing bindings or data produce warnings in response
- **WHEN** resolution encounters required variables with no binding or missing data
- **THEN** system SHALL still return a best-effort resolved request and SHALL include validation warnings (e.g. in response metadata or a `warnings` field) indicating unresolved placeholders or fallbacks used

#### Scenario: Non-existent TestCase or TestSuite
- **WHEN** client calls the endpoint with a non-existent testSuiteId or testCaseId
- **THEN** system SHALL respond with HTTP 404

#### Scenario: No effective template
- **WHEN** effective template is null (suite and test case have no template)
- **THEN** system SHALL respond with HTTP 400 or 404, or return a response that reflects "no template" (e.g. no resolvable path/body) per implementation choice

## Implementation Notes

- Shared binding validation logic extracted into a `BindingValidator` `@Component` in `service.domain` — injected by `SuiteValidationService`. Method: `validate(variables, bindings, schema, suiteId)` returning `List<ValidationWarningDto>`
- `validateDeploymentSuite()` calls `bindingValidator.validate(...)` with variables from `TemplateVariableExtractor.extractWithWarnings(requestTemplate)`
- `validateMcpSuite()` calls `bindingValidator.validate(...)` with variables from `TemplateVariableExtractor.extractFromArgumentTemplateWithWarnings(argumentTemplate)` — new method that returns `ExtractionResult` (same as `extractWithWarnings()`)
- The shared component includes `|file` constant-value validation — applies to both suite types
- MCP path does NOT perform content-type mismatch, header blacklist, or endpoint schema checks (these are deployment-specific)
- Warning codes and paths are consistent between deployment and MCP paths for frontend uniformity
