## MODIFIED Requirements

### Requirement: Request chain fields on the suite API
The suite create, update and read endpoints SHALL accept and return two additional optional fields: `additionalRequests` (a list of `RequestDefinitionDto`, never null in responses, defaulting to `[]` when omitted) and `requestName` (`String`, max 255, nullable — the label for the suite's own request). Both SHALL be persisted on `test_suites` as `additional_requests JSONB NOT NULL DEFAULT '[]'` and `request_name VARCHAR(255)` (nullable). `PUT` SHALL replace `additionalRequests` wholesale, consistent with the existing replace semantics for `responseColumns` and `inputBindings`. Suites created before this capability SHALL read back `additionalRequests: []` and `requestName: null` with no migration of existing rows. Both fields SHALL be accepted for either `suiteType`; an `MCP_TOOL` suite's chain entries carry `toolRef` and `argumentTemplate` where a `DEPLOYMENT` suite's carry `endpointRef` and `requestTemplate`.

Hard write-time validation for these fields SHALL be performed alongside the existing suite validation and SHALL reject with HTTP 400 (`VALIDATION_ERROR`): more than `RunnerValidationConstants.MAX_ADDITIONAL_REQUESTS` entries; a null element in `additionalRequests` (message naming the 0-based index); a response-column name duplicated anywhere across the chain; a chain-wide response-column union exceeding `RunnerValidationConstants.MAX_RESPONSE_COLUMNS`; an entry whose populated transport-specific fields do not match the suite's `suiteType`, or that populates both pairs or neither (message naming the 0-based index); and, per additional request, the same body/argument/schema/template checks already applied to the suite's own request.

The identical hard-validation rule set SHALL also gate the clone endpoint, evaluated against the effective post-override suite — see the `test-suite-clone` capability, which owns that requirement — so no configuration reachable by cloning is one that a `PUT` would reject.
Status: **Planned**

#### Scenario: Create with a chain
- **WHEN** client calls `POST /api/v1/test-suites` with `requestName` and two `additionalRequests`
- **THEN** the system SHALL persist both fields and return them in the 201 response in the submitted order

#### Scenario: Create an MCP suite with a chain
- **WHEN** client calls `POST /api/v1/test-suites` with `"suiteType": "MCP_TOOL"`, a valid `mcpDeploymentRef` and `toolRef`, and two `additionalRequests` entries each carrying `toolRef` and `argumentTemplate`
- **THEN** the system SHALL persist the chain and return it in the 201 response in the submitted order

#### Scenario: Update replaces the chain wholesale
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with a single-entry `additionalRequests` on a suite that had three
- **THEN** the stored chain SHALL be exactly the submitted single entry

#### Scenario: Update omitting the field clears it
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with `additionalRequests` omitted or null
- **THEN** the stored value SHALL be `[]`, matching the existing replace semantics of `responseColumns` / `inputBindings`

#### Scenario: Pre-existing suite reads back empty
- **WHEN** client calls `GET /api/v1/test-suites/{id}` for a suite created before this capability
- **THEN** the response SHALL include `additionalRequests: []` and `requestName: null`

#### Scenario: Chain fields appear on the list endpoint response items
- **WHEN** client calls `GET /api/v1/test-suites`
- **THEN** each item SHALL carry `additionalRequests` and `requestName` consistently with the other suite configuration fields

#### Scenario: Chain entry mismatched with the suite type is rejected
- **WHEN** client submits an `MCP_TOOL` suite whose `additionalRequests[1]` carries `requestTemplate`
- **THEN** the system SHALL respond HTTP 400 (`VALIDATION_ERROR`) naming index `1`, and SHALL NOT persist the suite

### Requirement: Type-specific field validation

The system SHALL validate that suites have the correct fields for their type, and that every entry of a suite's request chain is shaped for that same type.
Status: **Planned**

#### Scenario: DEPLOYMENT suite follows existing soft-validation pattern
- **WHEN** client creates a DEPLOYMENT suite
- **THEN** existing validation rules SHALL apply unchanged: only `deploymentRef` is hard-required (HTTP 400 if absent); `endpointRef` and `requestTemplate` follow the existing soft-validation pattern (null produces `isValid = false` with validation warnings, not HTTP 400)

#### Scenario: DEPLOYMENT suite ignores MCP fields
- **WHEN** client creates a DEPLOYMENT suite with `mcpDeploymentRef`, `toolRef`, or `argumentTemplate`
- **THEN** the system SHALL ignore these fields (not persist them)

#### Scenario: MCP_TOOL suite requires mcpDeploymentRef and toolRef
- **WHEN** client creates an MCP_TOOL suite without `mcpDeploymentRef` or `toolRef`
- **THEN** the system SHALL return HTTP 400

#### Scenario: MCP_TOOL suite ignores HTTP fields
- **WHEN** client creates an MCP_TOOL suite with `deploymentRef`, `endpointRef`, or `requestTemplate` at suite level
- **THEN** the system SHALL ignore these fields (not persist them)

#### Scenario: MCP suite validation — argumentTemplate warning
- **WHEN** an MCP_TOOL suite has `argumentTemplate: null`
- **THEN** `isValid` SHALL be `false` and `validationWarnings` SHALL include a warning indicating argument template is recommended for tool evaluation

#### Scenario: Chain entries are validated against the suite type
- **WHEN** client creates a suite whose `additionalRequests` contains an entry shaped for the other suite type, both types, or neither
- **THEN** the system SHALL return HTTP 400 (`VALIDATION_ERROR`) naming the entry's 0-based index — unlike suite-level fields, a mismatched chain entry is rejected rather than silently ignored, because dropping it would shift every later request's `request_index`

#### Scenario: Per-request argument template warning on an MCP chain
- **WHEN** an `MCP_TOOL` suite's `additionalRequests[0]` carries a `toolRef` but no `argumentTemplate`
- **THEN** the suite SHALL be accepted and `validationWarnings` SHALL include an indexed warning for that chain position, consistent with the suite-level argument-template warning

## Implementation Notes

- `TestSuiteRequestValidator` gains the per-entry shape check; the existing hard rejection of a non-empty `additionalRequests` on an `MCP_TOOL` suite is removed.
- `SuiteValidationService`'s MCP path becomes a chain loop with `additionalRequests[i].` warning-path prefixes, mirroring the DEPLOYMENT path, instead of assuming a chain length of one.
