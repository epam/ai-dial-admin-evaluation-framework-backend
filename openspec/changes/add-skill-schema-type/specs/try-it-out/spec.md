# Try It Out — delta for `add-skill-schema-type`

## ADDED Requirements

### Requirement: Try-it-out resolves skill placeholders to canonical references
Test-case try-out, variables try-out and the resolved-request preview SHALL resolve full-value skill placeholders exactly as a run does (see `request-template`, "Runtime skill reference resolution for full-value placeholders"): the resolved request shown/sent SHALL contain the canonical `skills/{bucket}/{path}` value. In variables mode, a user-supplied value for a variable whose `effectiveType` is `SKILL` SHALL be treated as that variable's constant and canonicalised. No DIAL Core skill route and no file download SHALL be invoked as part of resolving a skill value; only the deployment/tool invocation itself is performed.

Status: **Implemented**
Implementation notes: resolution shares `ResourceRefPlaceholderResolver`; variables mode keeps the column type via `TryItOutVariableBindings.bindToFileColumns` (now FILE or SKILL). Tests: `TryItOutVariableBindingsTest`, `SkillFieldFunctionalTests` (test-case try-out, both variables try-out cases).

#### Scenario: Test-case try-out sends the canonical ref
- **WHEN** a suite body has `{"skill": "${{s|skill}}"}`, `s` is bound to a `SKILL` column, and the test case holds `"skills/b/x/"`
- **THEN** the resolved request SHALL contain `"skill": "skills/b/x"`

#### Scenario: Variables try-out for a SKILL variable
- **WHEN** the client calls variables try-out with `{"s": "skills/b/x"}` for a `${{s|skill}}` template
- **THEN** the resolved request SHALL contain `"skills/b/x"` and the variables listing SHALL report `effectiveType: SKILL`

#### Scenario: No skill download
- **WHEN** a try-out resolves a skill value
- **THEN** no file download/upload and no skill-route call SHALL occur

## MODIFIED Requirements

### Requirement: Try it out with variables
The system SHALL provide `POST /api/v1/test-suites/{testSuiteId}/try-it-out` accepting a `variables` object in the request body, keyed by **request index** (`"0"` = the suite's own request, `"n"` = `additionalRequests[n-1]`; the same index semantics as the template-variables response and `resolved-request?requestIndex`). Each value maps template variable names to constant values for that request. The system SHALL resolve each request's template by treating each of that request's variables as a constant-value binding, except that a variable the suite binds via `dataField` to a `FILE` or `SKILL` schema column SHALL keep its constant value but additionally carry that column as its `dataField` (via `TryItOutVariableBindings.bindToFileColumns`; the resolver takes the value from the constant, the FILE and SKILL detectors read the column type) so a hintless `${{var}}` resolves to a DIAL file ref (FILE column) or the canonical `skills/{bucket}/{path}` ref (SKILL column) as in a real run, and any number of variables MAY share one FILE or SKILL column, each keeping its own value, send the resolved request(s) to the DIAL Core deployment, and return the response.

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
