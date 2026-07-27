## ADDED Requirements

### Requirement: Suite carries an additionalRequests chain and a request label
The `test_suites` table SHALL carry an `additional_requests` JSONB column (nullable) and a `request_label` VARCHAR(255) column (nullable), managed through the existing suite create/update endpoints. `TestSuiteRequestDto` and `TestSuiteResponseDto` SHALL expose them as `additionalRequests` (an ordered array of request specs) and `requestLabel` (naming request 0). Both SHALL be optional; when absent the suite is single-request and behaves exactly as before.
Status: **Planned**

#### Scenario: Create suite with a chain
- **WHEN** client calls `POST /api/v1/test-suites` with two `additionalRequests` entries and a `requestLabel`
- **THEN** the system SHALL persist both fields and return them in the response

#### Scenario: Update suite chain
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with a different `additionalRequests` array
- **THEN** the system SHALL replace the stored chain with the new array

#### Scenario: Chain removed by clearing the array
- **WHEN** client updates a multi-request suite with `additionalRequests: []` or null
- **THEN** the suite SHALL become single-request and execute through the single-request path

#### Scenario: Get suite returns the chain
- **WHEN** client calls `GET /api/v1/test-suites/{id}` for a multi-request suite
- **THEN** the response SHALL include `additionalRequests` in stored order and `requestLabel`

#### Scenario: Suite saved before this capability is unchanged
- **WHEN** client reads a suite persisted before `additional_requests` existed
- **THEN** `additionalRequests` SHALL be absent or empty and `requestLabel` null, and the suite SHALL remain valid

### Requirement: Chain validation at suite save
Suite save SHALL validate the normalized chain and reject with HTTP 400 `VALIDATION_ERROR` when: the chain exceeds the configured maximum request count; response column names are not unique across the chain; the resolved request-label set contains a duplicate; a `responseField` binding references a column declared by the same or a later request, or by no request; or a chain element declares `type: MCP_TOOL`. Per-element template-versus-`endpointRef` validation SHALL contribute to `validationWarnings` and `isValid` rather than rejecting the request.
Status: **Planned**

#### Scenario: Hard validation failures reject the save
- **WHEN** a suite is saved violating any of the chain's hard rules (cap, duplicate column name, duplicate label, invalid `responseField` reference, MCP-typed element)
- **THEN** the system SHALL respond HTTP 400 `VALIDATION_ERROR` and SHALL NOT persist the suite

#### Scenario: Soft validation failures mark the suite invalid
- **WHEN** a chain element's template references a template variable with no binding
- **THEN** the suite SHALL persist with `isValid = false` and a `validationWarnings` entry identifying the offending request index

#### Scenario: Valid chain persists as valid
- **WHEN** a multi-request suite satisfies all chain rules and every element's template validates against its own `endpointRef`
- **THEN** the suite SHALL persist with `isValid = true` and no chain-related warnings

### Requirement: Validation warnings identify the originating chain request
`ValidationWarningDto` SHALL carry an optional `requestIndex` field holding the 0-based chain position the warning originates from, null for warnings not attributable to a specific request. This mirrors the existing optional `turnIndex` field.
Status: **Planned**

#### Scenario: Chain warning carries its request index
- **WHEN** validation produces a warning for chain request 2
- **THEN** the warning SHALL carry `requestIndex = 2`

#### Scenario: Non-chain warning omits the field
- **WHEN** validation produces a warning not tied to a specific request
- **THEN** `requestIndex` SHALL be null and omitted from the serialized response

## Implementation notes

`TestSuite` model, `TestSuiteRequestDto`, `TestSuiteResponseDto`, `TestSuiteMapper`, `TestSuiteRecordMapper`, `PostgresTestSuiteRepository`, `JsonbMapper`, `TestSuiteService.normalizeRequest`, `SuiteValidationService`, `ValidationWarningDto`. Chain rules are enforced through the shared chain normalizer described in the `multi-request-test-suite` capability.
