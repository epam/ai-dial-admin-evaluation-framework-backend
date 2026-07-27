## MODIFIED Requirements

### Requirement: Input binding source — response field from an earlier chain request
`InputBindingDto` SHALL support a `responseField` source in addition to `dataField` and `constantValue`. This supersedes the baseline `InputBinding structure` requirement's two-field exclusivity rule ("Exactly one of `dataField` or `constantValue` SHALL be non-null"): with `responseField` added, **exactly one of the three sources (`dataField`, `constantValue`, `responseField`) SHALL be set**; setting more than one of the three, or none of the three, SHALL be rejected with HTTP 400 `VALIDATION_ERROR`. A `responseField` names, by bare column name, a response column declared by a strictly earlier request in the suite's chain. For a single-request suite no `responseField` can be valid, since there is no earlier request.
Status: **Planned**

#### Scenario: Exactly one source is required
- **WHEN** a binding sets `dataField` and `responseField` together, or sets none of the three sources
- **THEN** the request SHALL be rejected with HTTP 400 `VALIDATION_ERROR`

#### Scenario: responseField on a single-request suite is rejected
- **WHEN** a single-request suite declares a binding with `responseField`
- **THEN** the request SHALL be rejected with HTTP 400 `VALIDATION_ERROR`, because no earlier request exists

#### Scenario: Valid backward reference is accepted
- **WHEN** chain request 1 binds a template variable to `responseField` naming a column declared by request 0
- **THEN** the binding SHALL be accepted

### Requirement: Each chain request carries its own endpoint contract and template
Each chain element SHALL carry its own `endpointRef`, `requestTemplate`, and `inputBindings`, so chain requests MAY differ in HTTP method, relative URL pattern, parameters, and request body schema. `deploymentRef` SHALL remain suite-level. URL and method resolution for a chain request SHALL use that request's own `endpointRef`. This supersedes the baseline `Runtime request assembly contract` and `Resolved request preview for TestCase` requirements' assumption that request assembly always uses "the suite's `requestTemplate` + suite's `inputBindings`": for chain element 0, assembly SHALL continue to use the suite's flat `requestTemplate`/`inputBindings` exactly as in the single-request case (unchanged); for chain elements 1..N-1, assembly SHALL use that element's own `requestTemplate`/`inputBindings` in place of the suite's flat fields.
Status: **Planned**

#### Scenario: Differing methods across the chain
- **WHEN** chain request 0 declares `POST /session` and request 3 declares `DELETE /session/{id}`
- **THEN** each request SHALL be issued with its own method and resolved URL

#### Scenario: Body schema validated per request
- **WHEN** chain request 1's body is valid against its own `endpointRef.requestBodySchema` but would be invalid against request 0's
- **THEN** no validation warning SHALL be produced for request 1

#### Scenario: Deployment is shared
- **WHEN** any chain request is resolved
- **THEN** its URL SHALL be built against the suite-level `deploymentRef`

#### Scenario: Request 0 uses the suite's flat template and bindings
- **WHEN** chain request 0 is resolved (including for a single-request suite)
- **THEN** it SHALL use the suite's flat `requestTemplate` and `inputBindings`, unchanged from today's behavior

#### Scenario: Later chain elements use their own template and bindings
- **WHEN** chain request N (N ≥ 1) is resolved
- **THEN** it SHALL use that chain element's own `requestTemplate` and `inputBindings`, not the suite's flat fields

## ADDED Requirements

### Requirement: responseField resolution at request-resolution time
When resolving a chain request's template, a `responseField` binding SHALL be resolved from the accumulated map of response columns extracted by earlier requests in the same test-case run. When the named column is absent from that map, the placeholder's declared default (`${{var|type:default}}`) SHALL be substituted if present; otherwise resolution SHALL be reported as unresolved so the caller can fail the request. Resolution SHALL substitute scalar values only — the existing placeholder syntax does not support array-valued substitution.
Status: **Planned**

#### Scenario: Value present in the accumulated map
- **WHEN** the accumulated map holds `session_id = "abc"` and a binding names `responseField: "session_id"`
- **THEN** the resolved request SHALL carry `abc` at the bound placeholder

#### Scenario: Value absent with a declared default
- **WHEN** the accumulated map has no entry for the named column and the placeholder declares a default
- **THEN** the default SHALL be substituted and resolution SHALL succeed

#### Scenario: Value absent with no declared default
- **WHEN** the accumulated map has no entry for the named column and the placeholder declares no default
- **THEN** resolution SHALL report the variable as unresolved rather than substituting an empty value

## Implementation notes

`InputBindingDto` (new `responseField` field and updated `isValidBinding` assertion), `ResolvedRequestService` (resolution against the accumulated column map), `DialCoreUrlBuilder` usage per chain element, and the chain normalizer that supplies each element's `endpointRef`/`requestTemplate`/`inputBindings`.
