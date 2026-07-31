## MODIFIED Requirements

### Requirement: RequestTemplate structure
`RequestTemplateDto` SHALL have the following fields, all optional: `urlTemplate` (String, nullable, unchanged — `${{variable}}`-only, no JSONata); `queryParams` (List of `KeyValueTemplateDto`, nullable, unchanged — `${{variable}}`-only, no JSONata); `headers` (List of `KeyValueTemplateDto`, nullable, unchanged — `${{variable}}`-only, no JSONata); `body` (`RequestBodyDto`, nullable). When `body` is null, the endpoint has no request body. For `application/json`, `content` is `Object` — either a `Map<String, Object>` (legacy authoring: structural `${{}}` resolution, then the resolved Map is JSON-serialized and JSONata-evaluated — a plain JSON body evaluates to itself, so this is behaviorally identical to today for any suite not using JSONata-specific syntax) or a `String` (JSONata source: `${{}}` placeholders preprocessed into the source text, then the combined text evaluated directly). For `multipart/form-data` and `application/x-www-form-urlencoded`, `content` is unchanged (List-typed, `${{}}`-only, no JSONata involvement). The `${{variable}}` and `${{variable:default}}` placeholder syntax SHALL be supported within all body content types, as before.
Status: **Implemented**

#### Scenario: Template with JSON body as a Map (legacy, unchanged behavior)
- **WHEN** client creates a template with `body: { "contentType": "application/json", "content": { "model": "${{model}}", "messages": "${{messages}}" } }`
- **THEN** system SHALL accept and persist the template; placeholders in `content` are extracted as template variables with source BODY; at resolution time the Map is structurally resolved, serialized, and JSONata-evaluated, producing the identical request body a pre-JSONata resolution would have produced

#### Scenario: Template with JSON body as a JSONata source string
- **WHEN** client creates a template with `body: { "contentType": "application/json", "content": "{\"messages\": $append($history, [{\"role\":\"user\",\"content\":\"${{prompt}}\"}]), \"temperature\": 0.7}" }`
- **THEN** system SHALL accept and persist the template; `${{prompt}}` is extracted as a template variable with source BODY; at resolution time `${{prompt}}` is preprocessed into the source text before the combined text is evaluated as JSONata

## ADDED Requirements

### Requirement: Unconditional JSONata evaluation of JSON request bodies
Every `application/json` request body — Map-authored or String-authored, single-turn or multi-turn — SHALL be evaluated as a JSONata expression before being sent. There SHALL be no content-inspection or mode flag that decides whether to evaluate; a plain JSON literal is valid JSONata source that evaluates to itself (JSON is a syntactic subset of JSONata), so this SHALL NOT change the resolved body of any pre-existing Map-authored template that does not itself use JSONata-specific syntax (functions, `$` variables, operators).
Status: **Implemented**

#### Scenario: Legacy Map body with no JSONata syntax is unaffected
- **WHEN** a suite's `requestTemplate.body.content` is a plain Map with only literal values and `${{variable}}` placeholders (no JSONata functions or `$` references)
- **THEN** the resolved request body sent to the deployment is identical to the body that would have been produced by pre-JSONata structural resolution alone

### Requirement: Placeholder resolution precedes JSONata evaluation
`${{variable}}`/`${{variable|type:default}}` placeholders SHALL be resolved to typed values (using the existing binding-priority and default rules) and spliced into the request body's raw text before that text is parsed and evaluated as JSONata source. Three textual substitution modes SHALL apply depending on where the placeholder appears in the source text: quoted-full-value (the placeholder is the entire content of a quoted string, e.g. `"${{temp:0.7}}"`) is replaced with the JSON serialization of the resolved typed value, so a number/boolean/array/object becomes real JSONata literal syntax rather than a quoted string; embedded-in-literal (the placeholder appears alongside other text inside a string literal, e.g. `"Hello ${{name}}"`) is replaced with the JSON-string-escaped form of the stringified resolved value, preserving the surrounding literal as one string; bare (the placeholder appears outside any string literal — reachable only in String-content JSONata source, e.g. spliced as a function argument) is replaced with the JSON serialization of the resolved value, same rule as quoted-full-value.
Status: **Implemented**

#### Scenario: Quoted-full-value placeholder in a JSONata string source
- **WHEN** a String-content body source contains `"temperature": "${{temp:0.7}}"` and `temp` resolves to the typed number `0.7`
- **THEN** the substituted source text contains `"temperature": 0.7` (a JSONata number literal, not a quoted string)

#### Scenario: Embedded placeholder in a JSONata string source
- **WHEN** a String-content body source contains `"greeting": "Hello ${{name}}"` and `name` resolves to `"World \"quoted\""`
- **THEN** the substituted source text JSON-string-escapes the resolved value so the result remains one valid string literal (e.g. `"greeting": "Hello World \"quoted\""`)

#### Scenario: Bare placeholder spliced into a JSONata expression
- **WHEN** a String-content body source contains `{"messages": $append($history, ${{newMessages}}), ...}` and `newMessages` resolves to an array
- **THEN** the substituted source text contains that array's JSON serialization at the splice point, producing a syntactically valid JSONata function-call argument

#### Scenario: Resolved text cannot smuggle in JSONata syntax
- **WHEN** a resolved placeholder value's string form contains characters that would otherwise be significant JSONata syntax (quotes, backslashes)
- **THEN** the JSON-serialization/escaping step neutralizes them, so the resolved value is always evaluated as a data literal, never as injected JSONata source

### Requirement: Runtime object contract for evaluated request body
The result of JSONata-evaluating a request body SHALL be a JSON object. When the evaluated result is not an object (a scalar, array, or undefined), or evaluation throws (invalid runtime expression, or a `Frame.setRuntimeBounds` abort), the request SHALL NOT be sent and the corresponding result row SHALL be `ERROR`.
Status: **Implemented**

#### Scenario: Evaluated body is a valid JSON object
- **WHEN** JSONata evaluation of the resolved body produces a JSON object
- **THEN** that object is serialized and sent as the request body

#### Scenario: Evaluated body is not an object
- **WHEN** JSONata evaluation of the resolved body produces a non-object value (e.g. an array or a plain string) or the expression throws
- **THEN** the request is not sent and the result row is persisted as `ERROR`

### Requirement: Numeric fidelity caveat (F1)
The service SHALL document that JSONata evaluation (backed by `com.dashjoin:jsonata`) represents numbers as Java `double` internally. Consequently an explicit decimal literal with no fractional part (e.g. `1.0`) MAY be echoed back as an integral value (e.g. `1`) after evaluation, and an integer value above `2^53` (`9007199254740992`) MAY lose precision, rounding to the nearest representable `double`. This is a documented library limitation, not a defect to be silently worked around; suites requiring exact large-integer fidelity (e.g. snowflake-style IDs) in a JSONata-evaluated body SHOULD carry them as strings.
Status: **Implemented**

#### Scenario: Explicit double with no fractional part loses double-ness
- **WHEN** a request body contains an explicit `1.0` literal (Map-authored, serialized before evaluation, or String-authored source)
- **THEN** the value sent in the evaluated request body MAY be the integral `1` rather than `1.0`

#### Scenario: Long above 2^53 loses precision
- **WHEN** a request body contains an integer value above `2^53` (e.g. `9007199254740993`)
- **THEN** the evaluated value MAY differ from the input by rounding to the nearest representable `double` (e.g. `9007199254740992`); this is not treated as an evaluation error

### Requirement: JSONata syntax validation for String-content request bodies
When a `requestTemplate.body.content` is a `String` (JSONata source), the service SHALL validate at suite create/update time that the source parses as syntactically valid JSONata. An invalid source SHALL be rejected with HTTP 400.
Status: **Implemented**

#### Scenario: Valid JSONata String-content body accepted
- **WHEN** client saves a suite with `body.content` as a syntactically valid JSONata source string
- **THEN** system SHALL accept and persist the template

#### Scenario: Invalid JSONata String-content body rejected
- **WHEN** client saves a suite with `body.content` as a String containing a JSONata syntax error
- **THEN** system SHALL respond with HTTP 400
