## ADDED Requirements

### Requirement: TSMD name MUST NOT contain `:` (colon)
The service SHALL reject Test Suite Metric Definition create and update requests in which `name` contains the `:` (colon) character. The colon is reserved as the column-family separator in the evaluation summary CSV export. Validation applies uniformly to `POST /api/v1/test-suites/{suiteId}/metric-definitions` and `PUT /api/v1/test-suites/{suiteId}/metric-definitions/{id}`. Pre-existing rows with colon-bearing names are NOT migrated; any subsequent update of such a TSMD SHALL fail validation until the name is changed.

#### Scenario: Create rejected when TSMD name contains a colon
- **WHEN** client calls `POST /api/v1/test-suites/{suiteId}/metric-definitions` with `name = "Acc:uracy"`
- **THEN** system SHALL respond with HTTP 400, error code `VALIDATION_ERROR`, and a field-bound message identifying the `name` field

#### Scenario: Update rejected when TSMD name contains a colon
- **WHEN** client calls `PUT /api/v1/test-suites/{suiteId}/metric-definitions/{id}` with `name = "Acc:uracy"`
- **THEN** system SHALL respond with HTTP 400, error code `VALIDATION_ERROR`, and a field-bound message identifying the `name` field

#### Scenario: Create accepted when TSMD name has no colon
- **WHEN** client calls `POST /api/v1/test-suites/{suiteId}/metric-definitions` with a `name` free of `:`
- **THEN** system SHALL persist the TSMD and respond with HTTP 201
