## MODIFIED Requirements

### Requirement: List TestSuites (paginated)
The service SHALL provide a paginated endpoint to list TestSuites.
Status: **Implemented**

#### Scenario: Default pagination
- **WHEN** client calls `GET /api/v1/test-suites` without params
- **THEN** response SHALL be a page of TestSuites with default `page=0` and `size=20`

#### Scenario: Pagination bounds
- **WHEN** client calls `GET /api/v1/test-suites?page=<p>&size=<s>`
- **THEN** `page` SHALL be \(>= 0\) and `size` SHALL be between 1 and 100

#### Scenario: Sorting
- **WHEN** client calls `GET /api/v1/test-suites?sort=<field>[,<asc|desc>]` (repeatable)
- **THEN** system SHALL apply sorting using a whitelist of allowed fields

#### Scenario: Structured filtering (`filter`)
- **WHEN** client calls `GET /api/v1/test-suites?filter=<field>:<op>:<value>` (repeatable)
- **THEN** system SHALL apply AND-combined filters using a TestSuite-specific whitelist of fields and operators

### Requirement: Get TestSuite by id
The service SHALL allow retrieving a TestSuite by its id.
Status: **Implemented**

#### Scenario: Existing id
- **WHEN** client calls `GET /api/v1/test-suites/{id}` for an existing TestSuite
- **THEN** system SHALL return the TestSuite

#### Scenario: Missing id
- **WHEN** client calls `GET /api/v1/test-suites/{id}` for a non-existent TestSuite
- **THEN** system SHALL respond with HTTP 404

### Requirement: Create a TestSuite
The service SHALL allow creating a new TestSuite.
Status: **Implemented**

#### Scenario: Valid payload
- **WHEN** client calls `POST /api/v1/test-suites` with a valid body
- **THEN** system SHALL create a new TestSuite

#### Scenario: CreatedBy attribution
- **WHEN** `config.rest.security.mode=oidc` and an authenticated client creates a TestSuite
- **THEN** system SHALL store `createdBy` from JWT subject

#### Scenario: Missing author is rejected in OIDC mode
- **WHEN** `config.rest.security.mode=oidc` and a request is not authenticated (no user detected)
- **THEN** system SHALL reject the request with HTTP 401

#### Scenario: Anonymous author allowed only in no-security mode
- **WHEN** `config.rest.security.mode=none` and an unauthenticated client creates a TestSuite
- **THEN** system SHALL store `createdBy` as `anonymous`

#### Scenario: Embedded deployment and endpoint references
- **WHEN** client calls `POST /api/v1/test-suites` with a valid body including `deploymentRef` and `endpointRef`
- **THEN** system SHALL persist those objects as part of the TestSuite and return them in the response

### Requirement: Update a TestSuite
The service SHALL allow updating an existing TestSuite by id.
Status: **Implemented**

#### Scenario: Existing id
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with a valid body
- **THEN** system SHALL update the existing TestSuite and return the updated entity

#### Scenario: Missing id
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` for a non-existent TestSuite
- **THEN** system SHALL respond with HTTP 404

#### Scenario: Update embedded refs
- **WHEN** client updates `deploymentRef` and/or `endpointRef` of an existing TestSuite
- **THEN** system SHALL persist the updated embedded objects and return the updated suite

### Requirement: Delete a TestSuite
The service SHALL allow deleting a TestSuite by id.
Status: **Implemented**

#### Scenario: Existing id
- **WHEN** client calls `DELETE /api/v1/test-suites/{id}` for an existing TestSuite
- **THEN** system SHALL delete it and respond with HTTP 204

#### Scenario: Cascade delete suite children
- **WHEN** system deletes a TestSuite
- **THEN** it SHALL also delete all child entities owned by that suite (at least TestCases and metric bindings; later runs and analytics results)

#### Scenario: Missing id
- **WHEN** client calls `DELETE /api/v1/test-suites/{id}` for a non-existent TestSuite
- **THEN** system SHALL respond with HTTP 404

## REMOVED Requirements

### Requirement: Manage TestCases inside a TestSuite
**Reason**: TestCases are complex, large datasets and must be managed via dedicated APIs separate from TestSuite endpoints.
**Migration**: Use the TestCasesDefinition/TestCase API (see `specs/test-cases/spec.md`) and associate the resulting dataset with a TestSuite via reference.

