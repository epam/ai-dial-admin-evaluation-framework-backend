## MODIFIED Requirements

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
- **WHEN** template contains `"${{some_prop|file}}"` and an `inputBinding` exists with `templateVariable == "some_prop"`
- **THEN** system SHALL look up the binding by the bare name `"some_prop"` (stripping `|file`), resolve the value, and include it in the outgoing request
- **AND** system SHALL NOT leave the property absent due to the type hint

#### Scenario: Type-hinted placeholder with default resolves correctly
- **WHEN** template contains `"${{doc|file:files/public/default.txt}}"` and no binding exists for `"doc"`
- **THEN** system SHALL resolve to the default value `"files/public/default.txt"`

#### Scenario: MCP argument template type hint does not affect resolution
- **WHEN** an MCP argument template contains `"${{input_doc|file}}"` and the test case data map has key `"input_doc"`
- **THEN** `McpRequestResolver` SHALL look up `"input_doc"` in the data map (stripping `|file`), resolve to the stored value, and include it in the resolved arguments

#### Scenario: Multipart body resolution
- **WHEN** the body content type is `multipart/form-data` and the template specifies FormPart entries with placeholder values
- **THEN** system SHALL resolve each part's `value` and `filename` independently using the same binding/data lookup described above

#### Scenario: URL-encoded body resolution
- **WHEN** the body content type is `application/x-www-form-urlencoded` and the template specifies entries with placeholder values
- **THEN** system SHALL resolve each entry's `value` and stringify the result
