## ADDED Requirements

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
- `body` (Map<String,Object>, nullable): Request body as a JSON object. Values at any nesting depth can be `${{variable}}` or `${{variable:default}}` strings. When null, the endpoint has no request body.

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

#### Scenario: Template body with nested variables
- **WHEN** client creates a template with body containing nested `${{}}` placeholders (e.g., inside arrays or nested objects)
- **THEN** system SHALL extract all placeholders regardless of nesting depth

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
- `${{variable}}` — a required variable (must be bound or produces a validation warning)
- `${{variable:default}}` — an optional variable with a default value (the raw string after the colon)

The placeholder syntax SHALL be recognized in: `urlTemplate` string, `queryParams[*].value` strings, `headers[*].value` strings, and any string value at any depth in `body`.

#### Scenario: Parse simple variable
- **WHEN** template contains `"${{prompt}}"`
- **THEN** system SHALL extract variable `prompt` with `hasDefault: false`

#### Scenario: Parse variable with default
- **WHEN** template contains `"${{model:gpt-4}}"`
- **THEN** system SHALL extract variable `model` with `hasDefault: true` and `defaultValue: "gpt-4"`

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
- **THEN** each `TemplateVariableDto` SHALL include: `name` (String — the variable name), `sources` (Set of `TemplateVariableSource` enum — BODY, URL, QUERY, HEADER), `hasDefault` (boolean), `defaultValue` (String, nullable — raw default from `${{var:default}}`), `binding` (InputBindingDto, nullable — resolved binding from suite's `inputBindings`), `inferredType` (SchemaFieldType, nullable — from endpointRef schema or testCaseSchema)

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
- **THEN** system SHALL return the same logical result as `GET /api/v1/test-suites/{testSuiteId}/template-variables` for that suite

#### Scenario: Non-existent TestCase or TestSuite
- **WHEN** client calls the endpoint with a non-existent testSuiteId or testCaseId
- **THEN** system SHALL respond with HTTP 404

#### Scenario: Test case with no effective template
- **WHEN** effective template is null (suite has no template and test case has no override, or override is null)
- **THEN** system SHALL return an empty list

### Requirement: TemplateVariableSource enum
The `TemplateVariableSource` enum SHALL define: `BODY`, `URL`, `QUERY`, `HEADER`. These values represent where in the `RequestTemplateDto` a template variable appears.

### Requirement: Runtime request assembly contract
The service SHALL define the contract for assembling an HTTP request from template, bindings, and test case data (for use by future runner implementation).

#### Scenario: Resolution flow
- **WHEN** assembling a request for a test case
- **THEN** the runner SHALL:
  1. Determine effective template: `testCase.requestTemplateOverride ?? suite.requestTemplate`
  2. Determine effective bindings: `testCase.inputBindingsOverride ?? suite.inputBindings`
  3. Extract all `${{var}}` and `${{var:default}}` from effective template
  4. For each variable, find binding where `templateVariable == var`
  5. If binding found with `constantValue` → use constant
  6. If binding found with `dataField` → use `data[dataField]` (fall back to template default if null)
  7. If no binding and variable has default → use default
  8. If no binding and variable has no default → validation warning (required variable unbound)
  9. Replace placeholders using resolution mode: **full-value** (entire string is one `${{...}}`) → replace with typed resolved value preserving its type; **embedded** (multiple placeholders or surrounding text) → stringify and concatenate into a string
  10. For full-value defaults, interpret per schema type (e.g. `"0.7"` → number `0.7` when type is NUMBER; `"true"` → boolean `true` when type is BOOLEAN)
  11. Validate that the resolved URL matches `endpointRef.relativeUrlPattern`; if not, add a validation warning

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

#### Scenario: Full-value resolution with typed default
- **WHEN** template has `"temperature": "${{temp:0.7}}"`, no binding exists, and schema type is NUMBER
- **THEN** system SHALL interpret the default as number `0.7` and replace the string value with the typed result

#### Scenario: Full-value resolution preserves complex types
- **WHEN** template has `"messages": "${{messages}}"` and `data["messages"]` is an array `[{"role":"user","content":"Hi"}]`
- **THEN** system SHALL replace the string with the array value, producing `"messages": [{"role":"user","content":"Hi"}]`

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
- **THEN** the `ResolvedRequestDto` SHALL include: `url` (String — resolved URL path after placeholder substitution), `queryParams` (List of key-value pairs — resolved query parameters), `headers` (List of key-value pairs — resolved headers), `body` (Map<String,Object>, nullable — resolved request body with all placeholders replaced), `warnings` (List of validation warning objects — unresolved placeholders, missing data, URL pattern mismatch, etc.)

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
