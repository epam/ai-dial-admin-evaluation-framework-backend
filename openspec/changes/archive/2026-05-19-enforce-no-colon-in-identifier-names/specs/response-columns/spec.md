## ADDED Requirements

### Requirement: Response column name MUST NOT contain `:` (colon)
The service SHALL reject TestSuite create and update requests in which any `responseColumns[i].name` contains the `:` (colon) character. The colon is reserved as the column-family separator in the evaluation summary CSV export. Validation applies uniformly to `POST /api/v1/test-suites` and `PUT /api/v1/test-suites/{id}`. Pre-existing rows with colon-bearing column names are NOT migrated; any subsequent update of such a suite SHALL fail validation until the column is renamed.

#### Scenario: Create rejected when response column name contains a colon
- **WHEN** client calls `POST /api/v1/test-suites` with a `responseColumns` entry whose `name` is `"with:colon"`
- **THEN** system SHALL respond with HTTP 400, error code `VALIDATION_ERROR`, and a field-bound message identifying the offending entry

#### Scenario: Update rejected when response column name contains a colon
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with a `responseColumns` entry whose `name` is `"with:colon"`
- **THEN** system SHALL respond with HTTP 400, error code `VALIDATION_ERROR`, and a field-bound message identifying the offending entry

#### Scenario: Create accepted when no response column name contains a colon
- **WHEN** client calls `POST /api/v1/test-suites` with all `responseColumns[i].name` values free of `:`
- **THEN** system SHALL persist the suite and respond with HTTP 201
