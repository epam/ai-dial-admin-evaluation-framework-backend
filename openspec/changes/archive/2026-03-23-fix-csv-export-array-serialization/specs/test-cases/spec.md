## MODIFIED Requirements

### Requirement: Export test cases (CSV or ZIP)
The service SHALL export test cases in a format appropriate to the suite's schema and the `materializeFiles` parameter. If the suite's `testCaseSchema` contains no `FILE` type fields, export SHALL produce a CSV file regardless of the parameter. If the suite's `testCaseSchema` contains `FILE` type fields, the `materializeFiles` parameter controls the export format. Column order SHALL be **by schema order**: fixed columns first (e.g. `testCaseName`, optionally `enabled`), then data columns in the order fields appear in `testCaseSchema`.

ARRAY and OBJECT values SHALL be serialized as valid JSON strings in CSV cells (e.g., `["value1","value2"]` for arrays, `{"key":"value"}` for objects). The system SHALL NOT use Java `toString()` representation for structured types.

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

#### Scenario: Export ARRAY values as JSON
- **WHEN** system exports test cases with ARRAY-type fields to CSV
- **AND** a test case has an ARRAY field with value `["item1", "item2"]`
- **THEN** the CSV cell SHALL contain the valid JSON string `["item1","item2"]`
- **AND** reimporting this CSV SHALL preserve the value as a JSON array (not a string)

#### Scenario: Export OBJECT values as JSON
- **WHEN** system exports test cases with OBJECT-type fields to CSV
- **AND** a test case has an OBJECT field with value `{"key": "value"}`
- **THEN** the CSV cell SHALL contain the valid JSON string `{"key":"value"}`
- **AND** reimporting this CSV SHALL preserve the value as a JSON object (not a string)

#### Scenario: Export null ARRAY/OBJECT values
- **WHEN** system exports a test case where an ARRAY or OBJECT field has a null value
- **THEN** the CSV cell SHALL be empty (same as current behavior for null values)

#### Scenario: Export primitive values unchanged
- **WHEN** system exports test cases with STRING, INTEGER, NUMBER, or BOOLEAN fields
- **THEN** the CSV cell values SHALL use their natural string representation (unchanged from current behavior)

#### Scenario: Export ARRAY with delimiter-containing elements
- **WHEN** system exports test cases with an ARRAY field containing elements with commas (e.g., `["hello, world", "foo"]`)
- **THEN** the CSV cell SHALL contain valid JSON `["hello, world","foo"]`
- **AND** the CSV library SHALL properly quote the cell so the comma inside the JSON is not interpreted as a field delimiter
- **AND** reimporting this CSV SHALL preserve the array with the original element values

#### Scenario: Export empty ARRAY and OBJECT values
- **WHEN** system exports a test case where an ARRAY field has value `[]` or an OBJECT field has value `{}`
- **THEN** the CSV cell SHALL contain `[]` or `{}` respectively (not empty string)
- **AND** reimporting this CSV SHALL preserve the value as an empty array or empty object

#### Scenario: Export nested structures
- **WHEN** system exports a test case with an ARRAY field containing nested objects (e.g., `[{"name":"test","scores":[1,2]}]`)
- **THEN** the CSV cell SHALL contain the valid JSON representation of the nested structure
- **AND** reimporting this CSV SHALL preserve the full nested structure

#### Scenario: Export mixed-type ARRAY values
- **WHEN** system exports a test case with an ARRAY field containing mixed types (e.g., `[1, "two", true, null]`)
- **THEN** the CSV cell SHALL contain valid JSON `[1,"two",true,null]`
- **AND** reimporting this CSV SHALL preserve all element types

#### Scenario: Export ARRAY with special characters in elements
- **WHEN** system exports a test case with an ARRAY field containing elements with quotes, newlines, or Unicode (e.g., `["she said \"hi\"", "line1\nline2", "café"]`)
- **THEN** the CSV cell SHALL contain valid JSON with properly escaped characters
- **AND** reimporting this CSV SHALL preserve the original element values including special characters

#### Scenario: Export already-corrupted string-encoded arrays
- **WHEN** system exports a test case where an ARRAY field was previously stored as a string (e.g., `test_case_data` contains `{"col": "[\"a\",\"b\"]"}` due to the prior bug)
- **THEN** the CSV cell SHALL output the string value as-is (the value is a `String` in the deserialized map, not a `List`)
- **AND** the system SHALL NOT double-encode the value (i.e., it SHALL NOT produce `"[\"a\",\"b\"]"` with extra escaping)

#### Scenario: Export mixed column types in same suite
- **WHEN** system exports test cases from a suite with STRING, INTEGER, ARRAY, and OBJECT columns
- **THEN** each column type SHALL serialize according to its type rules: primitives use natural string representation, ARRAY/OBJECT use JSON serialization
- **AND** reimporting this CSV SHALL preserve all column types correctly

## Implementation Notes
- Modified: `CsvExportService.cellValue()` — use `objectMapper.writeValueAsString()` for `List`/`Map` values
- Modified: `ZipExportService.cellValue()` — same fix
