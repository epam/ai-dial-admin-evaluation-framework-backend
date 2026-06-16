# Test Suites — Delta Spec (clone-test-suite change)

## MODIFIED Requirements

### Requirement: Create a TestSuite
The service SHALL allow creating a new TestSuite. The request body SHALL accept `suiteType` (optional, defaults to `DEPLOYMENT`). For `DEPLOYMENT` suites: `testCaseSchema`, `requestTemplate`, `inputBindings`, `deploymentRef`, `endpointRef` (existing behavior — `deploymentRef` hard-required, `endpointRef`/`requestTemplate` soft-validated). For `MCP_TOOL` suites: `testCaseSchema`, `inputBindings`, `mcpDeploymentRef` (hard-required), `toolRef` (hard-required), `argumentTemplate` (soft-validated — null produces warning). The system SHALL perform type-specific validation and suite-level soft validation. Additionally, the system SHALL support cloning an existing suite via `POST /api/v1/test-suites/{sourceId}/clone` (see `test-suite-clone` spec).
Status: **Implemented** (CRUD), **Planned** (clone)

#### Scenario: Valid DEPLOYMENT payload
- **WHEN** client calls `POST /api/v1/test-suites` with a valid body including `deploymentRef`, `testCaseSchema`, `requestTemplate`, and `inputBindings` (see "Type-specific field validation" requirement for `deploymentRef` hard-requirement and `endpointRef`/`requestTemplate` soft-validation rules)
- **THEN** system SHALL create a new TestSuite with `suiteType = DEPLOYMENT`, perform suite-level soft validation, and return the created entity including `isValid` and `validationWarnings`

#### Scenario: Valid MCP_TOOL payload
- **WHEN** client calls `POST /api/v1/test-suites` with `"suiteType": "MCP_TOOL"`, valid `mcpDeploymentRef`, `toolRef`, `testCaseSchema`, and `inputBindings`
- **THEN** system SHALL create a new TestSuite with `suiteType = MCP_TOOL`, perform MCP-specific validation, and return the created entity

#### Scenario: CreatedBy attribution
- **WHEN** `config.rest.security.mode=oidc` and an authenticated client creates a TestSuite
- **THEN** system SHALL store `createdBy` from JWT subject

#### Scenario: Missing author is rejected in OIDC mode
- **WHEN** `config.rest.security.mode=oidc` and a request is not authenticated (no user detected)
- **THEN** system SHALL reject the request with HTTP 401

#### Scenario: Anonymous author allowed only in no-security mode
- **WHEN** `config.rest.security.mode=none` and an unauthenticated client creates a TestSuite
- **THEN** system SHALL store `createdBy` as `anonymous`

#### Scenario: Embedded deployment and endpoint references (DEPLOYMENT only)
- **WHEN** client calls `POST /api/v1/test-suites` with `suiteType = DEPLOYMENT` and valid `deploymentRef` and `endpointRef`
- **THEN** system SHALL persist those objects as part of the TestSuite and return them in the response

#### Scenario: Clone via dedicated endpoint
- **WHEN** client calls `POST /api/v1/test-suites/{sourceId}/clone` with a valid clone request body
- **THEN** system SHALL deep-copy the source suite (including test cases, TSMDs, and DIAL files), apply overrides, trigger revalidation, and return HTTP 201 with the new suite and revalidation task (see `test-suite-clone` spec for full details)
