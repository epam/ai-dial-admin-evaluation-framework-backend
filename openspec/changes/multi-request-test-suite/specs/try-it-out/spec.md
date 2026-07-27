## ADDED Requirements

### Requirement: Try-it-out targets a single chain request
Try-it-out SHALL remain a **single-endpoint** operation: it resolves and sends exactly one request and returns exactly one response. It SHALL NOT execute a chain. Both try-it-out endpoints SHALL accept an optional `requestIndex` query parameter selecting which chain request's `endpointRef`, `requestTemplate`, and `inputBindings` to instantiate, defaulting to `0` (request 0, the suite's flat configuration). `requestIndex` rather than a label is the selector because the index is a natural-key component and therefore the stable handle for clients.
Status: **Planned**

#### Scenario: Default targets request 0
- **WHEN** a client calls either try-it-out endpoint without `requestIndex`
- **THEN** the system SHALL instantiate request 0's template against the suite's flat `endpointRef`, exactly as before this capability existed

#### Scenario: Explicit index selects a chain request
- **WHEN** a client calls try-it-out with `requestIndex=2` on a suite whose chain has four requests
- **THEN** the system SHALL resolve chain request 2's template with request 2's `inputBindings` and send it to request 2's `endpointRef` method and resolved URL

#### Scenario: Index beyond the chain is rejected
- **WHEN** `requestIndex` is greater than or equal to the normalized chain length, or is negative
- **THEN** the system SHALL respond with HTTP 400 `VALIDATION_ERROR`

#### Scenario: Only the selected request is sent
- **WHEN** try-it-out targets chain request 3, whose earlier requests include a session-creating call
- **THEN** only request 3 SHALL be sent; no preceding chain request SHALL be executed

### Requirement: responseField variables are caller-supplied or warned, never chain-executed
Because try-it-out sends only one request, a `responseField` binding has no producing request to resolve from. In variables-based mode the caller supplies the value directly through `variables`, keyed by the template variable name, so resolution succeeds without executing any prior request. In test-case-based mode, where no such value is available, the system SHALL apply the placeholder's declared default when present, SHALL record a `ValidationWarningDto` in `resolvedRequest.warnings` naming the unresolved variable, and SHALL still send the request and return HTTP 200.
Status: **Planned**

#### Scenario: Variables mode supplies the value directly
- **WHEN** a client calls the variables-based endpoint with `requestIndex=1` and supplies a value for a variable bound to `responseField`
- **THEN** the supplied value SHALL be substituted and no unresolved-variable warning SHALL be produced

#### Scenario: Test-case mode warns and still sends
- **WHEN** a client calls the test-case-based endpoint for a chain request having a `responseField` binding with no declared default
- **THEN** the system SHALL return HTTP 200 with a `resolvedRequest.warnings` entry naming the unresolved variable, and SHALL send the request

#### Scenario: Declared default is applied in test-case mode
- **WHEN** the placeholder for the `responseField` variable declares a default
- **THEN** the default SHALL be substituted and the request SHALL be sent

## Implementation notes

`TestSuiteTryOutController` (variables mode) and `TestCaseTryOutController` (test-case mode) gain the optional `requestIndex` parameter; the selected `RequestSpec` comes from the shared chain normalizer. `TryItOutResponseDto` is unchanged — one request in, one response out. Unresolved variables use the existing `ResolvedRequestDto.warnings` channel.
