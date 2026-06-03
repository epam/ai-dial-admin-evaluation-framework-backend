## MODIFIED Requirements

### Requirement: List TestSuites (paginated)
The service SHALL provide a paginated endpoint to list TestSuites and SHALL support optional multi-column sorting.

#### Scenario: Default pagination
- **WHEN** client calls `GET /api/v1/test-suites` without params
- **THEN** response SHALL be a page of TestSuites with default `page=0` and `size=20`

#### Scenario: Pagination bounds
- **WHEN** client calls `GET /api/v1/test-suites?page=<p>&size=<s>`
- **THEN** `page` SHALL be \(>= 0\) and `size` SHALL be between 1 and 100

#### Scenario: Single-key sorting
- **WHEN** client calls `GET /api/v1/test-suites?sort=<field>,<direction>`
- **THEN** the system SHALL return the page sorted by the requested field and direction

#### Scenario: Multi-key sorting
- **WHEN** client calls `GET /api/v1/test-suites?sort=<field1>,<direction1>&sort=<field2>,<direction2>`
- **THEN** the system SHALL sort by `<field1>` first and then by `<field2>` as a tie-breaker

#### Scenario: Unknown sort field
- **WHEN** client calls `GET /api/v1/test-suites?sort=<unknownField>,asc`
- **THEN** the system SHALL reject the request with HTTP 400

