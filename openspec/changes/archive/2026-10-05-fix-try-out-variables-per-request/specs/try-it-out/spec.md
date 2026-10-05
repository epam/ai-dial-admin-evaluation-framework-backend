## MODIFIED Requirements

### Requirement: Try it out with variables
The system SHALL provide `POST /api/v1/test-suites/{testSuiteId}/try-it-out` accepting a `variables` object in the request body, keyed by **request index** (`"0"` = the suite's own request, `"n"` = `additionalRequests[n-1]`; the same index semantics as the template-variables response and `resolved-request?requestIndex`). Each value maps template variable names to constant values for that request. The system SHALL resolve each request's template by treating each of that request's variables as a constant-value binding, send the resolved request(s) to the DIAL Core deployment, and return the response.

Status: **Implemented**

#### Scenario: Successful try-it-out with variables
- **WHEN** authenticated user sends POST to `/api/v1/test-suites/{testSuiteId}/try-it-out` with body `{ "variables": { "0": { "prompt": "Hello", "model": "gpt-4" } } }`
- **AND** the test suite has a valid `deploymentRef`, `requestTemplate`, and `endpointRef` and no `additionalRequests`
- **THEN** the system SHALL resolve the suite's request template with `prompt` and `model` as constant-value bindings; the suite's own `inputBindings` SHALL NOT be used — they are fully replaced by the user-provided variables of index `0`
- **AND** send the resolved request to the DIAL Core deployment
- **AND** return HTTP 200 with `TryItOutResponseDto`

#### Scenario: Variables must not be null
- **WHEN** user sends try-it-out request with `variables` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Empty variables map is valid
- **WHEN** user sends try-it-out request with `variables` as an empty object `{}`
- **AND** the template has no `${{...}}` placeholders (fully static)
- **THEN** the system SHALL accept the request and proceed with resolution and invocation

#### Scenario: Missing or null request entry means no variables for that request
- **WHEN** user sends try-it-out request whose `variables` has no key `"i"`, or maps `"i"` to null, for a request index `i` of the suite
- **THEN** request `i` SHALL be resolved with no constant bindings: its placeholders fall through to their default values, or produce a `REQUIRED` warning and the existing `Unresolved required template variables` rejection

#### Scenario: Variable with null value
- **WHEN** user sends try-it-out request with a variable mapped to null (e.g., `{ "variables": { "0": { "myVar": null } } }`)
- **THEN** the system SHALL skip that entry when building bindings (treat it as if the variable was not provided)
- **AND** the template variable will fall through to its default value (if any) or produce a `REQUIRED` warning if no default exists

#### Scenario: Variable with blank key
- **WHEN** user sends try-it-out request with a blank variable name (e.g., `{ "variables": { "0": { "": "value" } } }`)
- **THEN** the system SHALL skip that entry when building bindings (a blank key cannot match any `${{var}}` placeholder)

#### Scenario: Non-integer request index
- **WHEN** user sends try-it-out request whose `variables` has a key that is not an integer (e.g., the legacy flat shape `{ "variables": { "prompt": "Hello" } }`)
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` without invoking the deployment
- **AND** the rejection SHALL happen while reading the request body, before the suite is looked up (so it takes precedence over a 404 for a non-existent suite)

#### Scenario: Request index out of range
- **WHEN** user sends try-it-out request whose `variables` has a key `i` with `i < 0` or `i > N`, where `N` is the number of the suite's `additionalRequests`
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message `variables: request index <i> is out of range (chain length <N+1>)`, naming the first offending key in iteration order
- **AND** SHALL NOT invoke the deployment for any request
- **AND** suite-configuration preconditions (missing deployment reference, request template, endpoint reference, or a misconfigured `additionalRequests[i]`) SHALL be checked first and take precedence over the index rejection

#### Scenario: Non-zero request index on a single-request suite
- **WHEN** user sends try-it-out request with key `"1"` for a `DEPLOYMENT` suite without `additionalRequests`
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message `variables: request index 1 is out of range (chain length 1)` without invoking the deployment

#### Scenario: Test suite not found
- **WHEN** user sends try-it-out request with non-existent `testSuiteId`
- **THEN** the system SHALL return HTTP 404 with error code `NOT_FOUND`

---

### Requirement: Try it out with MCP tool call (variables)

The system SHALL support try-it-out with variables for MCP_TOOL suites via `POST /api/v1/test-suites/{testSuiteId}/try-it-out`. MCP suites have a single request, so only index `"0"` is meaningful; its entries map argument template variable names to constant values.

Status: **Implemented**

#### Scenario: Successful MCP try-it-out with variables
- **WHEN** authenticated user sends POST to `/api/v1/test-suites/{testSuiteId}/try-it-out` with `{ "variables": { "0": { "search_query": "MCP protocol" } } }`
- **AND** the test suite has `suiteType = MCP_TOOL`
- **THEN** the system SHALL resolve the tool arguments using the index-`0` variables as constant-value bindings (same per-entry rules as HTTP try-it-out with variables)
- **AND** execute the MCP tool call and return the response

#### Scenario: MCP try-it-out rejects a non-zero request index
- **WHEN** user sends try-it-out request with `variables` containing any key other than `"0"` for an `MCP_TOOL` suite
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message `variables: request index <i> is out of range (chain length 1)` without invoking the MCP tool
- **AND** MCP preconditions (missing MCP deployment or tool reference) SHALL be checked first

---

### Requirement: TryItOutWithVariablesRequestDto structure
The request body for the suite-level try-it-out endpoint SHALL carry a request-index-keyed `variables` object.

Status: **Implemented**

#### Scenario: Request structure
- **WHEN** client sends a try-it-out with variables request
- **THEN** `TryItOutWithVariablesRequestDto` SHALL include:
  - `variables` (JSON object, required, not null, may be empty) — keys are request indices serialized as JSON strings (`"0"`, `"1"`, …); each value is an object (or null) mapping template variable names to constant values for that request. An empty object is valid when no request has placeholders.

#### Scenario: OpenAPI documents the indexed shape
- **WHEN** a client reads `/v3/api-docs`
- **THEN** the try-it-out-with-variables operation SHALL expose request examples `minimal`, `full` and `chained` in the indexed shape, `chained` carrying entries for indices `0` and `1`

---

### Requirement: `tryWithVariables` remains single-turn
The variables-based try-it-out endpoint (`POST /api/v1/test-suites/{testSuiteId}/try-it-out`) SHALL remain single-turn per request. It has no bound test case and therefore no `multiTurnData` source.

Status: **Implemented**

#### Scenario: Variables-based try-it-out is unaffected by multi-turn support
- **WHEN** authenticated user sends POST to `/api/v1/test-suites/{testSuiteId}/try-it-out` with a `variables` object
- **THEN** the system SHALL resolve and invoke exactly one turn per chain request (exactly one request for a suite without `additionalRequests`)

## ADDED Requirements

### Requirement: Variables are scoped per chain request
For a suite with `additionalRequests`, variables-mode try-it-out SHALL resolve request `i` using constant bindings built only from `variables["i"]`, wholesale-replacing that request's own `inputBindings`. Variables given for one request SHALL NOT be visible to any other request. Values extracted into the accumulated frame by earlier requests' response columns SHALL remain available to later requests as in a run.

Status: **Implemented**

#### Scenario: Same variable name carries different values per request
- **WHEN** a suite's request `0` and `additionalRequests[0]` both use `${{user_message}}`
- **AND** user sends `{ "variables": { "0": { "user_message": "first" }, "1": { "user_message": "second" } } }`
- **THEN** request `0` SHALL be invoked with `user_message` resolved to `"first"`
- **AND** request `1` SHALL be invoked with `user_message` resolved to `"second"`

#### Scenario: A request's variables do not leak into another request
- **WHEN** user sends `{ "variables": { "0": { "temperature": 0.2 } } }` for a two-request suite whose request `1` uses `${{temperature}}` with a default value
- **THEN** request `1` SHALL resolve `temperature` to its default value, not `0.2`

#### Scenario: Frame values from earlier requests still resolve
- **WHEN** request `0` extracts response column `configId` and request `1`'s JSONata body (`jsonataContent`) references `$configId`
- **AND** `variables` has no entry for index `1`
- **THEN** request `1`'s body SHALL resolve `$configId` from the accumulated frame (frame values are visible to JSONata bodies only, not to `${{…}}` placeholders, as in a run)

## Implementation notes

- `TryItOutWithVariablesRequestDto.variables`: `Map<Integer, Map<String, Object>>`.
- `TryItOutVariableBindings.toBindingsByRequest` converts and validates; `TryItOutService.tryWithVariables` calls it against the suite before any invocation, then: MCP and single-request paths convert `variables.get(0)`; chain path passes per-request bindings to `runChain`, which looks them up by `RequestExecutionSpec.requestIndex()`.
