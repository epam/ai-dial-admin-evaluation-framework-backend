## UNCHANGED Requirements (reuse confirmation)

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

#### Scenario: resolve() method is accessible via package-private visibility
- **WHEN** `TryItOutService` (same `service.domain` package) needs to resolve a template with bindings and data for the variables path
- **THEN** it SHALL call `ResolvedRequestService.resolve(template, bindings, data)` directly (package-private access)
- **AND** receive a `ResolvedRequestDto` with resolved URL, query params, headers, body, and warnings

#### Scenario: resolveRequest() method reused for test-case path
- **WHEN** `TryItOutService` needs to resolve a request for a specific test case
- **THEN** it SHALL delegate to the existing public `ResolvedRequestService.resolveRequest(testSuiteId, testCaseId)` method
- **AND** the `@Transactional(readOnly=true)` scope SHALL be confined to that call, releasing the DB connection before the DIAL Core invocation
