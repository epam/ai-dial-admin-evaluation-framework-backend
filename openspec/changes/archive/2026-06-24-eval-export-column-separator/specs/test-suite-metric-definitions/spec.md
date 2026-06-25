## RENAMED Requirements

- FROM: `### Requirement: TSMD name MUST NOT contain `:` (colon)`
- TO: `### Requirement: TSMD name MUST NOT contain `::` (double colon)`

## MODIFIED Requirements

### Requirement: TSMD name MUST NOT contain `::` (double colon)
The service SHALL reject Test Suite Metric Definition create and update requests in which `name` contains the `::` (double-colon) sequence. The `::` sequence is reserved as the column-family separator in the evaluation summary CSV export; a single colon `:` is permitted in the name. Validation applies uniformly to `POST /api/v1/test-suites/{suiteId}/metric-definitions` and `PUT /api/v1/test-suites/{suiteId}/metric-definitions/{id}`. Pre-existing rows with `::`-bearing names are NOT migrated; any subsequent update of such a TSMD SHALL fail validation until the name is changed.

#### Scenario: Create rejected when TSMD name contains a double colon
- **WHEN** client calls `POST /api/v1/test-suites/{suiteId}/metric-definitions` with `name = "Acc::uracy"`
- **THEN** system SHALL respond with HTTP 400, error code `VALIDATION_ERROR`, and a field-bound message identifying the `name` field

#### Scenario: Update rejected when TSMD name contains a double colon
- **WHEN** client calls `PUT /api/v1/test-suites/{suiteId}/metric-definitions/{id}` with `name = "Acc::uracy"`
- **THEN** system SHALL respond with HTTP 400, error code `VALIDATION_ERROR`, and a field-bound message identifying the `name` field

#### Scenario: Create accepted when TSMD name contains a single colon
- **WHEN** client calls `POST /api/v1/test-suites/{suiteId}/metric-definitions` with a `name` containing a single colon `:` but no `::` sequence (e.g. `"Acc:uracy"`)
- **THEN** system SHALL persist the TSMD and respond with HTTP 201
