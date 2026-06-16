## MODIFIED Requirements

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

#### Scenario: Template body with nested variables (JSON variant)
- **WHEN** client creates a JSON body template containing nested `${{}}` placeholders (e.g., inside arrays or nested objects in `content`)
- **THEN** system SHALL extract all placeholders regardless of nesting depth

#### Scenario: Template body variable extraction from multipart parts
- **WHEN** client creates a multipart body template
- **THEN** system SHALL extract `${{}}` placeholders from each `FormPartDto.value` and `FormPartDto.filename` fields

#### Scenario: Null template equivalent to all-null-fields template
- **WHEN** client sends `requestTemplate: null` vs `requestTemplate: { "urlTemplate": null, "queryParams": null, "headers": null, "body": null }`
- **THEN** system SHALL treat these as semantically equivalent — both produce the same validation warnings and behavior

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
  3. Extract all `${{var}}` and `${{var:default}}` from effective template
  4. For each variable, find binding where `templateVariable == var`
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

#### Scenario: Multipart body resolution
- **WHEN** the template body is `MultipartFormDataRequestBodyDto`
- **THEN** the system SHALL resolve each `FormPartDto.value` using the same placeholder resolution rules (full-value or embedded) and produce a `ResolvedMultipartBodyDto` with `ResolvedFormPartDto` entries

#### Scenario: URL-encoded body resolution
- **WHEN** the template body is `UrlEncodedFormRequestBodyDto`
- **THEN** the system SHALL resolve each `KeyValueTemplateDto.value` placeholder and stringify all resolved values (URL-encoded forms are always string-valued), producing a `ResolvedUrlEncodedBodyDto` with resolved `List<KeyValueTemplateDto>` entries

#### Scenario: resolve() method is accessible via package-private visibility
- **WHEN** `TryItOutService` (same `service.domain` package) needs to resolve a template with bindings and data for the variables path
- **THEN** it SHALL call `ResolvedRequestService.resolve(template, bindings, data)` directly (package-private access)
- **AND** receive a `ResolvedRequestDto` with resolved URL, query params, headers, body (`ResolvedBodyDto`), and warnings

#### Scenario: resolveRequest() method reused for test-case path
- **WHEN** `TryItOutService` needs to resolve a request for a specific test case
- **THEN** it SHALL delegate to the existing public `ResolvedRequestService.resolveRequest(testSuiteId, testCaseId)` method
- **AND** the `@Transactional(readOnly=true)` scope SHALL be confined to that call, releasing the DB connection before the DIAL Core invocation

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
