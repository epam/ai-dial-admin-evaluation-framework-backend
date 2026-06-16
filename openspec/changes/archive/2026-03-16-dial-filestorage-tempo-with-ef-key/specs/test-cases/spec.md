# Test Cases — Delta Spec (DIAL File Storage Migration)

## MODIFIED Requirements

### Requirement: FILE type validation
The system SHALL validate FILE type field values in test case data against DIAL file reference format and prefix whitelist. Validation is format-only on save; no existence check is performed. Broken file references fail at eval time on the deployment call.

#### Scenario: FILE type in testCaseSchema
- **WHEN** client creates a test suite with `testCaseSchema` containing a field with `type: FILE`
- **THEN** system SHALL accept the schema; the field's data value in test cases SHALL be a DIAL relative file path (e.g., `files/@ef/suites/{suiteId}/filename.ext` or `files/public/path/to/file.ext`)

#### Scenario: FILE field validation
- **WHEN** a test case has a field declared as `FILE` in `testCaseSchema`
- **AND** the data value is a non-null string
- **THEN** validation SHALL check that the value is a valid DIAL relative file path with an allowed prefix (`@ef` or `public`); if the format is invalid or the prefix is disallowed, a validation warning SHALL be produced

#### Scenario: FILE field with cross-suite @ef reference
- **WHEN** a test case belongs to suite with ID `suite-A`
- **AND** the FILE field value is `files/@ef/suites/suite-B/data.csv` (different suite ID)
- **THEN** validation SHALL produce a warning indicating the file reference points to a different suite's files

#### Scenario: FILE field with null value
- **WHEN** a test case has a `FILE` field with null value and the field is not required
- **THEN** validation SHALL pass without warning (no file attached)

#### Scenario: Required FILE field with null value
- **WHEN** a test case has a `FILE` field with null value and the field is required
- **THEN** validation SHALL produce a warning: "Required field '<name>' has no value in data"

### Requirement: Export test cases (CSV or ZIP)
The service SHALL export test cases in a format appropriate to the suite's schema and the `materializeFiles` parameter. If the suite's `testCaseSchema` contains no `FILE` type fields, export SHALL produce a CSV file regardless of the parameter. If the suite's `testCaseSchema` contains `FILE` type fields, the `materializeFiles` parameter controls the export format. Column order SHALL be **by schema order**: fixed columns first (e.g. `testCaseName`, optionally `enabled`), then data columns in the order fields appear in `testCaseSchema`.

#### Scenario: Export without FILE fields (CSV, unchanged)
- **WHEN** client calls `GET /api/v1/test-suites/{testSuiteId}/test-cases/export`
- **AND** the suite's `testCaseSchema` has no FILE type fields
- **THEN** system SHALL return `Content-Type: text/csv` with test case data as CSV

#### Scenario: CSV columns reflect unified schema
- **WHEN** system exports CSV
- **THEN** header SHALL be: `testCaseName`, then `testCaseSchema` fields in schema order; optionally `enabled` if `includeEnabled=true`

#### Scenario: Export with custom delimiter
- **WHEN** client calls `GET .../export.csv?delimiter=;`
- **THEN** system SHALL use semicolon as delimiter

#### Scenario: Export with FILE fields and materializeFiles=true (ZIP)
- **WHEN** client calls `GET /api/v1/test-suites/{testSuiteId}/test-cases/export?materializeFiles=true`
- **AND** the suite's `testCaseSchema` has one or more FILE type fields
- **THEN** system SHALL return `Content-Type: application/zip` with `Content-Disposition: attachment; filename="test-cases-{suiteId}.zip"`
- **AND** the ZIP SHALL contain:
  - `test-cases.csv` — CSV file where FILE columns contain relative paths (e.g., `files/{rowIndex}/{fieldName}/{filename}`) where `rowIndex` is the 1-based CSV row number and `fieldName` is the schema field name, ensuring uniqueness
  - `files/` directory — containing the actual file bytes downloaded from DIAL storage, organized by row index and field name
- **AND** the ZIP SHALL be streamed directly to the response (no full in-memory buffering)

#### Scenario: Export with FILE fields and materializeFiles=false (CSV with DIAL URLs)
- **WHEN** client calls `GET /api/v1/test-suites/{testSuiteId}/test-cases/export?materializeFiles=false`
- **AND** the suite's `testCaseSchema` has one or more FILE type fields
- **THEN** system SHALL return `Content-Type: text/csv` with test case data as CSV
- **AND** FILE columns SHALL contain the raw DIAL file paths (e.g., `files/@ef/suites/{suiteId}/data.csv`)

#### Scenario: Export with FILE fields default materializeFiles
- **WHEN** client calls `GET /api/v1/test-suites/{testSuiteId}/test-cases/export` without specifying `materializeFiles`
- **AND** the suite's `testCaseSchema` has one or more FILE type fields
- **THEN** system SHALL default `materializeFiles` to `true` and produce a ZIP

#### Scenario: Export with FILE field but null value
- **WHEN** a test case has a FILE field with null value (no file attached)
- **THEN** the CSV column for that field SHALL be empty; no file entry in the ZIP for that test case's field

### Requirement: Import test cases (CSV or ZIP)
The import endpoint SHALL accept both CSV files and ZIP archives. The file format is detected by file extension or content.

#### Scenario: Import CSV file (unchanged)
- **WHEN** client sends `POST /api/v1/test-suites/{testSuiteId}/test-cases/import` with a `.csv` file
- **AND** the suite's `testCaseSchema` has no FILE type fields
- **THEN** system SHALL process the CSV using the current import flow

#### Scenario: Import ZIP archive
- **WHEN** client sends `POST /api/v1/test-suites/{testSuiteId}/test-cases/import` with a `.zip` file
- **THEN** system SHALL:
  1. Extract and parse `test-cases.csv` from the archive
  2. For each FILE column value (relative path), find the corresponding file in the archive's `files/` directory
  3. Sanitize the original filename by replacing any characters outside the allowed set (alphanumeric, `-`, `_`, `.`, ` `, `(`, `)`) with `_`, then generate a unique DIAL filename (e.g., `{rowIndex}_{fieldName}_{sanitizedFilename}`) to avoid collisions in the flat suite folder
  4. Upload each file to DIAL storage at `{efBucket}/suites/{suiteId}/{uniqueFilename}` via `DialFileClient`
  5. Map the DIAL file path (`files/@ef/suites/{suiteId}/{uniqueFilename}`) into the test case's `data` field
  6. Create test cases with the resolved data

#### Scenario: Import ZIP with missing file
- **WHEN** a CSV row references a file path (e.g., `files/1/doc/report.pdf`) that does not exist in the ZIP archive
- **THEN** system SHALL produce a validation warning for that test case and set the FILE field value to null

#### Scenario: Import CSV for suite with FILE fields
- **WHEN** client imports a CSV file for a suite whose schema has FILE fields
- **THEN** system SHALL treat FILE columns as string values (DIAL file paths if provided, or empty for null); no file extraction is performed
- **AND** FILE field validation may produce warnings if the DIAL file paths have invalid format or disallowed prefix

### Requirement: Import preview (CSV or ZIP)
The import preview endpoint SHALL support both CSV and ZIP formats with the same detection logic as the import endpoint.

#### Scenario: Preview CSV file
- **WHEN** client sends `POST /api/v1/test-suites/{testSuiteId}/test-cases/import/preview` with a CSV file
- **THEN** system SHALL return the preview (current behavior)

#### Scenario: Preview ZIP archive
- **WHEN** client sends `POST /api/v1/test-suites/{testSuiteId}/test-cases/import/preview` with a ZIP file
- **THEN** system SHALL extract and preview the `test-cases.csv` within the archive
- **AND** FILE columns SHALL show the relative paths from the CSV (not DIAL file paths, since files are not yet uploaded during preview)
