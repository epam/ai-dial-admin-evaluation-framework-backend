## RENAMED Requirements

- FROM: `### Requirement: Response column name MUST NOT contain `:` (colon)`
- TO: `### Requirement: Response column name MUST NOT contain `::` (double colon)`

## MODIFIED Requirements

### Requirement: Response column name MUST NOT contain `::` (double colon)
The service SHALL reject TestSuite create and update requests in which any `responseColumns[i].name` contains the `::` (double-colon) sequence. The `::` sequence is reserved as the column-family separator in the evaluation summary CSV export; a single colon `:` is permitted in the name. Validation applies uniformly to `POST /api/v1/test-suites` and `PUT /api/v1/test-suites/{id}`. Pre-existing rows with `::`-bearing column names are NOT migrated; any subsequent update of such a suite SHALL fail validation until the column is renamed.

#### Scenario: Create rejected when response column name contains a double colon
- **WHEN** client calls `POST /api/v1/test-suites` with a `responseColumns` entry whose `name` is `"with::colon"`
- **THEN** system SHALL respond with HTTP 400, error code `VALIDATION_ERROR`, and a field-bound message identifying the offending entry

#### Scenario: Update rejected when response column name contains a double colon
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with a `responseColumns` entry whose `name` is `"with::colon"`
- **THEN** system SHALL respond with HTTP 400, error code `VALIDATION_ERROR`, and a field-bound message identifying the offending entry

#### Scenario: Create accepted when a response column name contains a single colon
- **WHEN** client calls `POST /api/v1/test-suites` with a `responseColumns` entry whose `name` is `"with:colon"` (single colon, no `::` sequence)
- **THEN** system SHALL persist the suite and respond with HTTP 201
