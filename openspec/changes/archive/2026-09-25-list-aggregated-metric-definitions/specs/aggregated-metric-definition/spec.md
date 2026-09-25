## ADDED Requirements

### Requirement: Suite-wide aggregated metric definition list
The system SHALL provide `GET /api/v1/test-suites/{testSuiteId}/metric-definitions/aggregated`, returning HTTP 200 with an unpaginated JSON array of aggregated metric definitions for the requested test suite.

Status: Planned

#### Scenario: List aggregated definitions
- **WHEN** a client requests the aggregated metric-definition list for a suite with metric definitions
- **THEN** the response SHALL contain each definition enriched with its metric declaration and metric declaration version, including descriptions and JSON-object schemas

#### Scenario: Include management definitions
- **WHEN** a suite contains disabled or invalid metric definitions
- **THEN** the aggregated list SHALL include them

#### Scenario: Isolate definitions by suite
- **WHEN** metric definitions exist in another test suite
- **THEN** they SHALL not appear in the requested suite's aggregated list

#### Scenario: No aggregated definitions
- **WHEN** a client requests the aggregated list for a suite with no metric definitions or for an unknown suite ID
- **THEN** the system SHALL return HTTP 200 with an empty array

### Requirement: OpenAPI documentation for aggregated metric definition list
The aggregated metric-definition list endpoint SHALL document its array response and provide minimal and full response examples.

Status: Planned

#### Scenario: List endpoint is documented
- **WHEN** a developer views the OpenAPI document
- **THEN** the aggregated list operation SHALL expose an array schema for aggregated metric definitions and minimal and full examples.