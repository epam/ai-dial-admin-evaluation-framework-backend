## MODIFIED Requirements

### Requirement: Type System
The system SHALL support the following types for test case schema fields:

| Type | Java Mapping | JSON Representation | CSV Auto-Inference |
|------|--------------|---------------------|-------------------|
| `STRING` | `String` | `"value"` | Yes (default) |
| `INTEGER` | `Long` | `123` | Yes |
| `NUMBER` | `Double` | `123.45` | Yes |
| `BOOLEAN` | `Boolean` | `true`/`false` | Yes |
| `OBJECT` | `Map<String, Object>` | `{...}` | No (requires schema) |
| `ARRAY` | `List<Object>` | `[...]` | No (requires schema) |
| `FILE` | `String` (blob UUID) | `"uuid-string"` | No (requires schema) |

#### Scenario: FILE type in testCaseSchema
- **WHEN** client creates a test suite with `testCaseSchema` containing a field with `type: FILE`
- **THEN** system SHALL accept the schema; the field's data value in test cases SHALL be a blob UUID string referencing a file uploaded to the same test suite

#### Scenario: FILE field validation
- **WHEN** a test case has a field declared as `FILE` in `testCaseSchema`
- **AND** the data value is a non-null string
- **THEN** validation SHALL check that the referenced blob UUID exists and belongs to the same test suite; if not, a validation warning SHALL be produced

#### Scenario: FILE field with null value
- **WHEN** a test case has a `FILE` field with null value and the field is not required
- **THEN** validation SHALL pass without warning (no file attached)

#### Scenario: Required FILE field with null value
- **WHEN** a test case has a `FILE` field with null value and the field is required
- **THEN** validation SHALL produce a warning: "Required field '<name>' has no value in data"

### Requirement: Export test cases (CSV or ZIP)
The system SHALL export test cases in a format appropriate to the suite's schema. If the suite's `testCaseSchema` contains no `FILE` type fields, export SHALL produce a CSV file (current behavior). If the suite's `testCaseSchema` contains one or more `FILE` type fields, export SHALL produce a ZIP archive.

#### Scenario: Export without FILE fields (CSV, unchanged)
- **WHEN** client calls `GET /api/v1/test-suites/{testSuiteId}/test-cases/export`
- **AND** the suite's `testCaseSchema` has no FILE type fields
- **THEN** system SHALL return `Content-Type: text/csv` with test case data as CSV (current behavior)

#### Scenario: Export with FILE fields (ZIP)
- **WHEN** client calls `GET /api/v1/test-suites/{testSuiteId}/test-cases/export`
- **AND** the suite's `testCaseSchema` has one or more FILE type fields
- **THEN** system SHALL return `Content-Type: application/zip` with `Content-Disposition: attachment; filename="test-cases-{suiteId}.zip"`
- **AND** the ZIP SHALL contain:
  - `test-cases.csv` — CSV file where FILE columns contain relative paths (e.g., `files/{rowIndex}/{fieldName}/{filename}`) where `rowIndex` is the 1-based CSV row number and `fieldName` is the schema field name, ensuring uniqueness
  - `files/` directory — containing the actual file bytes organized by row index and field name
- **AND** the ZIP SHALL be streamed directly to the response (no full in-memory buffering)

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
  3. Upload each file to `BlobStorage` associated with the test suite
  4. Map the blob UUID into the test case's `data` field
  5. Create test cases with the resolved data

#### Scenario: Import ZIP with missing file
- **WHEN** a CSV row references a file path (e.g., `files/case-1/doc.pdf`) that does not exist in the ZIP archive
- **THEN** system SHALL produce a validation warning for that test case and set the FILE field value to null

#### Scenario: Import CSV for suite with FILE fields
- **WHEN** client imports a CSV file for a suite whose schema has FILE fields
- **THEN** system SHALL treat FILE columns as string values (blob UUIDs if provided, or empty for null); no file extraction is performed
- **AND** FILE field validation may produce warnings if the blob UUIDs do not reference existing blobs

### Requirement: Import preview (CSV or ZIP)
The import preview endpoint SHALL support both CSV and ZIP formats with the same detection logic as the import endpoint.

#### Scenario: Preview CSV file
- **WHEN** client sends `POST /api/v1/test-suites/{testSuiteId}/test-cases/import/preview` with a CSV file
- **THEN** system SHALL return the preview (current behavior)

#### Scenario: Preview ZIP archive
- **WHEN** client sends `POST /api/v1/test-suites/{testSuiteId}/test-cases/import/preview` with a ZIP file
- **THEN** system SHALL extract and preview the `test-cases.csv` within the archive
- **AND** FILE columns SHALL show the relative paths from the CSV (not blob UUIDs, since files are not yet uploaded during preview)
