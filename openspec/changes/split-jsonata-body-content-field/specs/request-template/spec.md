## ADDED Requirements

### Requirement: Mutual exclusivity of `content` and `jsonataContent`

An `application/json` request body SHALL carry its template in exactly one of two fields: `content` (`Map<String, Object>`, legacy structural template) or `jsonataContent` (`String`, JSONata source). When both are non-null the service SHALL reject the suite create/update with HTTP 400 (`VALIDATION_ERROR`). When both are null the body SHALL be treated as "no request body", identical to `content: null` alone.

Status: **Planned**

#### Scenario: Both fields set is rejected

- **WHEN** client saves a suite with `body: { "contentType": "application/json", "content": { "a": 1 }, "jsonataContent": "{\"a\": 1}" }`
- **THEN** system SHALL respond with HTTP 400 and code `VALIDATION_ERROR`, and SHALL NOT persist the suite

#### Scenario: Only `content` set is accepted

- **WHEN** client saves a suite with `body: { "contentType": "application/json", "content": { "model": "${{model}}" } }`
- **THEN** system SHALL accept and persist the template, and resolution SHALL follow the legacy structural path

#### Scenario: Only `jsonataContent` set is accepted

- **WHEN** client saves a suite with `body: { "contentType": "application/json", "jsonataContent": "{\"model\": \"gpt-4\", \"messages\": $append($history, [{\"role\": \"user\", \"content\": \"${{question}}\"}])}" }`
- **THEN** system SHALL accept and persist the template, and resolution SHALL preprocess `${{question}}` into the source text before evaluating it as JSONata

#### Scenario: Neither field set means no request body

- **WHEN** client saves a suite with `body: { "contentType": "application/json" }` (both `content` and `jsonataContent` absent)
- **THEN** system SHALL treat the request as having no body, and the resolved body content SHALL be null

#### Scenario: A JSON string in `content` is rejected

- **WHEN** client saves a suite with `body: { "contentType": "application/json", "content": "{\"model\": \"gpt-4\"}" }` (a String value in the Map-typed field)
- **THEN** system SHALL respond with HTTP 400 — a JSONata source SHALL be sent in `jsonataContent`, not in `content`

### Requirement: JSONata syntax validation for `jsonataContent` request bodies

When a `requestTemplate.body.jsonataContent` is present, the service SHALL validate at suite create/update time that the source parses as syntactically valid JSONata after every `${{}}` placeholder has been replaced with a fixed neutral token (JSON `null` for quoted-full-value and bare placeholders, an empty string for embedded ones). An invalid source SHALL be rejected with HTTP 400. A `Map`-typed `content` SHALL NOT be JSONata-validated at write time (it is validated at resolution time, as before).

Status: **Planned**

#### Scenario: Valid `jsonataContent` accepted

- **WHEN** client saves a suite with `body.jsonataContent` as a syntactically valid JSONata source string
- **THEN** system SHALL accept and persist the template

#### Scenario: Invalid `jsonataContent` rejected

- **WHEN** client saves a suite with `body.jsonataContent` as a String containing a JSONata syntax error
- **THEN** system SHALL respond with HTTP 400 with a message identifying `requestTemplate.body.jsonataContent`

#### Scenario: Bare placeholder does not fail write-time validation

- **WHEN** client saves a suite with `body.jsonataContent` of `{"q": ${{question}}}` — not valid JSONata until the placeholder is substituted
- **THEN** system SHALL neutralize the placeholder before validating and SHALL accept the template

### Requirement: Template variable extraction from `jsonataContent`

`${{}}` placeholders in `requestTemplate.body.jsonataContent` SHALL be extracted as template variables with source `BODY`, exactly as placeholders in a `Map`-typed `content` are. Extraction SHALL apply to both the suite-level and test-case-level template-variable APIs and to the binding cross-validation that consumes them.

Status: **Planned**

#### Scenario: Variable in `jsonataContent` is extracted with source BODY

- **WHEN** a suite's `body.jsonataContent` is `{"messages": $append($history, [{"role": "user", "content": "${{question}}"}])}`
- **THEN** `GET /api/v1/test-suites/{id}/template-variables` SHALL return a variable named `question` with `sources = [BODY]`

#### Scenario: Bound `jsonataContent` variable does not produce validation warnings

- **WHEN** a suite's only placeholder lives in `body.jsonataContent` and `inputBindings` binds that variable to an existing dataset field
- **THEN** suite validation SHALL report neither an unbound-required-variable warning for that variable nor an orphan-binding warning for that binding

## MODIFIED Requirements

### Requirement: RequestTemplate structure
`RequestTemplateDto` SHALL have the following fields, all optional:
- `urlTemplate` (String, nullable): Valid URL path that can contain `${{variable}}` placeholders (e.g., `/api/v1/models/${{model_id}}/completions`). When null, a **soft validation warning** SHALL be added (e.g. "urlTemplate is required for request assembly"). When non-null, the value SHALL be a **valid path** (literal segments and `${{var}}` placeholders only); invalid values SHALL produce a soft validation warning. After resolving all placeholders, the **final request URL SHALL match** `endpointRef.relativeUrlPattern`.
- `queryParams` (List of `KeyValueTemplateDto`, nullable): Query parameters. Each entry has a literal `key` and a `value` that can contain `${{variable}}` or `${{variable:default}}` placeholders.
- `headers` (List of `KeyValueTemplateDto`, nullable): Custom headers. Same structure as `queryParams`.
- `body` (`RequestBodyDto`, nullable): Polymorphic request body with `contentType` discriminator. When null, the endpoint has no request body. For `application/json` the body carries two mutually exclusive, independently typed fields: `content` (`Map<String, Object>`, nullable — legacy authoring: structural `${{}}` resolution, then the resolved Map is JSON-serialized and JSONata-evaluated; a plain JSON body evaluates to itself, so this is behaviorally identical for any suite not using JSONata-specific syntax) and `jsonataContent` (`String`, nullable — JSONata source: `${{}}` placeholders preprocessed into the source text, then the combined text evaluated directly). Both non-null SHALL be rejected with HTTP 400; both null means no request body. For `multipart/form-data` (content: `List<FormPartDto>`) and `application/x-www-form-urlencoded` (content: `List<KeyValueTemplateDto>` — reuses existing key-value pair type, supports duplicate keys), `content` is unchanged (List-typed, `${{}}`-only, no JSONata involvement) and there is no `jsonataContent` field. The `${{variable}}` and `${{variable:default}}` placeholder syntax is supported within all body content types, as before. See the "Unconditional JSONata evaluation of JSON request bodies", "Mutual exclusivity of `content` and `jsonataContent`", and related requirements below for the full JSON-body evaluation contract.

#### Scenario: Template with JSON body as a Map (legacy, unchanged behavior)
- **WHEN** client creates a template with `body: { "contentType": "application/json", "content": { "model": "${{model}}", "messages": "${{messages}}" } }`
- **THEN** system SHALL accept and persist the template; placeholders in `content` are extracted as template variables with source BODY; at resolution time the Map is structurally resolved, serialized, and JSONata-evaluated, producing the identical request body a pre-JSONata resolution would have produced

#### Scenario: Template with JSON body as a JSONata source string
- **WHEN** client creates a template with `body: { "contentType": "application/json", "jsonataContent": "{\"messages\": $append($history, [{\"role\":\"user\",\"content\":\"${{prompt}}\"}]), \"temperature\": 0.7}" }`
- **THEN** system SHALL accept and persist the template; `${{prompt}}` is extracted as a template variable with source BODY; at resolution time `${{prompt}}` is preprocessed into the source text before the combined text is evaluated as JSONata

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

### Requirement: Unconditional JSONata evaluation of JSON request bodies
Every `application/json` request body — `content`-authored (Map) or `jsonataContent`-authored (String), single-turn or multi-turn — SHALL be evaluated as a JSONata expression before being sent. There SHALL be no content-inspection or mode flag that decides whether to evaluate; the field a template is authored in selects only how the body text is produced (structural resolution + serialization vs. textual placeholder preprocessing), never whether evaluation happens. A plain JSON literal is valid JSONata source that evaluates to itself (JSON is a syntactic subset of JSONata), so this SHALL NOT change the resolved body of any pre-existing Map-authored template that does not itself use JSONata-specific syntax (functions, `$` variables, operators).

#### Scenario: Legacy Map body with no JSONata syntax is unaffected
- **WHEN** a suite's `requestTemplate.body.content` is a plain Map with only literal values and `${{variable}}` placeholders (no JSONata functions or `$` references)
- **THEN** the resolved request body sent to the deployment is identical to the body that would have been produced by pre-JSONata structural resolution alone

#### Scenario: Field choice does not gate evaluation
- **WHEN** a suite's body is authored in `jsonataContent` and another suite's equivalent body is authored in `content`
- **THEN** both SHALL be JSONata-evaluated before being sent, and both SHALL be subject to the same runtime object contract

### Requirement: Placeholder resolution precedes JSONata evaluation
`${{variable}}`/`${{variable|type:default}}` placeholders SHALL be resolved to typed values (using the existing binding-priority and default rules) and spliced into the request body's raw text before that text is parsed and evaluated as JSONata source. Three textual substitution modes SHALL apply depending on where the placeholder appears in the source text: quoted-full-value (the placeholder is the entire content of a quoted string, e.g. `"${{temp:0.7}}"`) is replaced with the JSON serialization of the resolved typed value, so a number/boolean/array/object becomes real JSONata literal syntax rather than a quoted string; embedded-in-literal (the placeholder appears alongside other text inside a string literal, e.g. `"Hello ${{name}}"`) is replaced with the JSON-string-escaped form of the stringified resolved value, preserving the surrounding literal as one string; bare (the placeholder appears outside any string literal — reachable only in `jsonataContent` source, e.g. spliced as a function argument) is replaced with the JSON serialization of the resolved value, same rule as quoted-full-value.

#### Scenario: Quoted-full-value placeholder in a JSONata string source
- **WHEN** a `jsonataContent` source contains `"temperature": "${{temp:0.7}}"` and `temp` resolves to the typed number `0.7`
- **THEN** the substituted source text contains `"temperature": 0.7` (a JSONata number literal, not a quoted string)

#### Scenario: Embedded placeholder in a JSONata string source
- **WHEN** a `jsonataContent` source contains `"greeting": "Hello ${{name}}"` and `name` resolves to `"World \"quoted\""`
- **THEN** the substituted source text JSON-string-escapes the resolved value so the result remains one valid string literal (e.g. `"greeting": "Hello World \"quoted\""`)

#### Scenario: Bare placeholder spliced into a JSONata expression
- **WHEN** a `jsonataContent` source contains `{"messages": $append($history, ${{newMessages}}), ...}` and `newMessages` resolves to an array
- **THEN** the substituted source text contains that array's JSON serialization at the splice point, producing a syntactically valid JSONata function-call argument

#### Scenario: Resolved text cannot smuggle in JSONata syntax
- **WHEN** a resolved placeholder value's string form contains characters that would otherwise be significant JSONata syntax (quotes, backslashes)
- **THEN** the JSON-serialization/escaping step neutralizes them, so the resolved value is always evaluated as a data literal, never as injected JSONata source

### Requirement: Numeric fidelity caveat (F1)
The service SHALL document that JSONata evaluation (backed by `com.dashjoin:jsonata`) represents numbers as Java `double` internally. Consequently an explicit decimal literal with no fractional part (e.g. `1.0`) MAY be echoed back as an integral value (e.g. `1`) after evaluation, and an integer value above `2^53` (`9007199254740992`) MAY lose precision, rounding to the nearest representable `double`. This is a documented library limitation, not a defect to be silently worked around; suites requiring exact large-integer fidelity (e.g. snowflake-style IDs) in a JSONata-evaluated body SHOULD carry them as strings.

#### Scenario: Explicit double with no fractional part loses double-ness
- **WHEN** a request body contains an explicit `1.0` literal (`content`-authored, serialized before evaluation, or `jsonataContent`-authored source)
- **THEN** the value sent in the evaluated request body MAY be the integral `1` rather than `1.0`

#### Scenario: Long above 2^53 loses precision
- **WHEN** a request body contains an integer value above `2^53` (e.g. `9007199254740993`)
- **THEN** the evaluated value MAY differ from the input by rounding to the nearest representable `double` (e.g. `9007199254740992`); this is not treated as an evaluation error

### Requirement: Runtime request assembly contract
The service SHALL assemble an HTTP request from template, bindings, and test case data according to the contract below (used by the runner implementation and the try-it-out feature).

The `ResolvedRequestService` provides two reuse paths for `TryItOutService` (both in `service.domain`):
- **`resolveRequest(UUID testSuiteId, UUID testCaseId)`** — public, `@Transactional(readOnly=true)`. Used by the test-case try-it-out path to delegate the full suite/case loading + resolution flow.
- **`resolve(RequestTemplateDto, List<InputBindingDto>, Map<String, Object>)`** — package-private. Used by the variables try-it-out path (same package, no visibility change needed).

#### Scenario: Resolution flow
- **WHEN** assembling a request for a test case
- **THEN** the runner SHALL:
  1. Use the suite's `requestTemplate` (per-test-case overrides were removed when test cases moved to datasets)
  2. Use the suite's `inputBindings`
  3. Extract all placeholders (`${{var}}`, `${{var:default}}`, `${{var|type}}`, `${{var|type:default}}`) from effective template
  4. For each placeholder, strip any `|type` hint to obtain the bare variable name, then find binding where `templateVariable == varName`
  5. If binding found with `constantValue` → use constant
  6. If binding found with `dataField` → use `data[dataField]` (fall back to template default if null)
  7. If no binding and variable has default → use default
  8. If no binding and variable has no default → validation warning (required variable unbound)
  9. Replace placeholders using resolution mode: **full-value** (entire string is one `${{...}}`) → replace with typed resolved value preserving its type; **embedded** (multiple placeholders or surrounding text) → stringify and concatenate into a string
  10. For full-value defaults, interpret per schema type (e.g. `"0.7"` → number `0.7` when type is NUMBER; `"true"` → boolean `true` when type is BOOLEAN)
  11. Validate that the resolved URL matches `endpointRef.relativeUrlPattern`; if not, add a validation warning
  12. **Body resolution is content-type-aware**: for `application/json`, resolve per the JSON-body requirements above — when `content` (Map) is present it is resolved recursively then serialized and JSONata-evaluated; when `jsonataContent` (String) is present its placeholders are preprocessed into the source text, which is then evaluated directly as JSONata (`RequestBodyEvaluator` selects the path by which field is present, not by inspecting a value's runtime type); when both are null the resolved body content is null. For `multipart/form-data`, resolve each `FormPartDto.value` and `FormPartDto.filename` (unaffected by JSONata). For `application/x-www-form-urlencoded`, resolve each `KeyValueTemplateDto.value` placeholder and stringify all resolved values (unaffected by JSONata).
  13. Return `ResolvedRequestDto` with the body as the corresponding `ResolvedBodyDto` variant

#### Scenario: Assembly uses suite template and bindings
- **WHEN** assembling a request for any test case
- **THEN** the runner SHALL use `suite.requestTemplate` + `suite.inputBindings` (per-test-case overrides no longer exist)

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

## REMOVED Requirements

### Requirement: JSONata syntax validation for String-content request bodies

**Reason**: `content` no longer accepts a `String`; the JSONata source moved to the dedicated `jsonataContent` field, so a requirement phrased around "String-content" no longer describes any reachable state.

**Migration**: Replaced verbatim in semantics by the ADDED requirement "JSONata syntax validation for `jsonataContent` request bodies" — same neutralize-then-validate behavior, same HTTP 400 outcome, only the field name and error path change (`requestTemplate.body.content` → `requestTemplate.body.jsonataContent`). API clients move the JSONata source string from `body.content` to `body.jsonataContent`.
