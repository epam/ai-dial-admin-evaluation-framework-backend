## MODIFIED Requirements

### Requirement: Export test cases (CSV or ZIP)
The service SHALL export test cases of a Dataset in a format appropriate to the dataset's schema and the `materializeFiles` parameter. If the dataset's `testCaseSchema` contains no `FILE` type fields, export SHALL produce a CSV file. If the schema contains `FILE` type fields, the `materializeFiles` parameter controls the export format.

Column order SHALL be by schema order: fixed columns `testCaseName` and `turnIndex` first, then data columns in the order fields appear in the dataset's `testCaseSchema`. The previously-supported `includeEnabled` query parameter is removed (TestCase has no `enabled` field; per-suite exclude lists belong to suites, not the dataset). ARRAY and OBJECT values SHALL be serialized as JSON strings.

The ZIP archive format, its manifest, file materialization and export failure behaviour are defined by the `test-case-zip-archive` capability.
Status: **Planned**

#### Scenario: Export without FILE fields (CSV)
- **WHEN** client calls `GET /api/v1/datasets/{datasetId}/test-cases/export.csv`
- **AND** the dataset's `testCaseSchema` has no FILE type fields
- **THEN** system SHALL return `Content-Type: text/csv` with test case data as CSV

#### Scenario: CSV columns reflect dataset schema
- **WHEN** system exports CSV
- **THEN** header SHALL be: `testCaseName`, `turnIndex`, then dataset `testCaseSchema` fields in schema order; no `enabled` column

#### Scenario: Export with custom delimiter
- **WHEN** client calls `GET .../datasets/{datasetId}/test-cases/export.csv?delimiter=;`
- **THEN** system SHALL use semicolon as delimiter

#### Scenario: Export with FILE fields and materializeFiles=true (ZIP)
- **WHEN** client calls `GET /api/v1/datasets/{datasetId}/test-cases/export?materializeFiles=true`
- **AND** the dataset's `testCaseSchema` has one or more FILE type fields
- **THEN** system SHALL return `Content-Type: application/zip` with `Content-Disposition: attachment; filename="test-cases-{datasetId}.zip"`
- **AND** the archive SHALL be fully built before the response starts, so a failure yields an error status instead of a truncated archive (it is no longer streamed while being built)
- **AND** the archive SHALL follow the `test-case-zip-archive` layout: `test-cases.csv` with one row per turn, `manifest.json`, and one `files/{n}/{filename}` entry per distinct EF-owned file reference, with `public/…` references kept verbatim in the CSV

#### Scenario: Export with FILE fields and materializeFiles=false
- **WHEN** client calls `GET /api/v1/datasets/{datasetId}/test-cases/export?materializeFiles=false`
- **THEN** system SHALL return CSV with FILE columns containing the stored file references verbatim (e.g. `@ef/datasets/{datasetId}/{filename}`, legacy `@ef/suites/{suiteId}/{filename}`, `public/…`)

#### Scenario: Export with FILE fields default materializeFiles
- **WHEN** client calls `GET /api/v1/datasets/{datasetId}/test-cases/export` without `materializeFiles`
- **AND** the dataset's `testCaseSchema` has one or more FILE type fields
- **THEN** system SHALL default `materializeFiles` to `true` and produce a ZIP

#### Scenario: Export ARRAY values as JSON
- **WHEN** system exports test cases with ARRAY-type fields to CSV
- **THEN** the CSV cell SHALL contain the valid JSON string (e.g., `["item1","item2"]`)

#### Scenario: Export OBJECT values as JSON
- **WHEN** system exports test cases with OBJECT-type fields to CSV
- **THEN** the CSV cell SHALL contain the valid JSON string (e.g., `{"key":"value"}`)

#### Scenario: includeEnabled query param is rejected or ignored
- **WHEN** client sends `includeEnabled=true` (or any value) on the export URL
- **THEN** system SHALL ignore the parameter (or reject it with HTTP 400 if strict-validation is enabled); no `enabled` column appears in the export under any setting

#### Scenario: Export with FILE field but null value
- **WHEN** a test case has a FILE field with null value (no file attached)
- **THEN** the CSV column for that field SHALL be empty; no file entry in the ZIP for that test case's field

#### Scenario: Export null ARRAY/OBJECT values
- **WHEN** system exports a test case where an ARRAY or OBJECT field has a null value
- **THEN** the CSV cell SHALL be empty

#### Scenario: Export primitive values unchanged
- **WHEN** system exports test cases with STRING, INTEGER, NUMBER, or BOOLEAN fields
- **THEN** the CSV cell values SHALL use their natural string representation

### Requirement: Import test cases (CSV or ZIP)
The import endpoint SHALL accept both CSV files and ZIP archives. A file SHALL be treated as a ZIP archive when any of these holds:
- its filename ends with `.zip`;
- its content type is `application/zip` or `application/x-zip-compressed`;
- its content starts with the ZIP signature bytes `PK\x03\x04`.

Otherwise it SHALL be treated as CSV. ZIP import behaviour (archive layout, manifest, file placement and overwrite, cell rewriting, limits and failure handling) is defined by the `test-case-zip-archive` capability.

Status: **Implemented** (CSV); **Planned** (ZIP, content-based detection)

#### Scenario: Import CSV file (unchanged)
- **WHEN** client sends `POST /api/v1/datasets/{datasetId}/test-cases/import` with a `.csv` file
- **AND** the suite's `testCaseSchema` has no FILE type fields
- **THEN** system SHALL process the CSV using the current import flow

#### Scenario: Import ZIP archive
- **WHEN** client sends `POST /api/v1/datasets/{datasetId}/test-cases/import` with a `.zip` file
- **THEN** system SHALL import it per the `test-case-zip-archive` capability: every referenced archive file SHALL be uploaded to `{efBucket}/datasets/{datasetId}/{filename}`, and the corresponding FILE values SHALL hold the dataset file reference

#### Scenario: ZIP detected by content
- **WHEN** client uploads a ZIP archive named `export.bin` with content type `application/octet-stream`
- **THEN** system SHALL detect it as ZIP from its signature bytes and import it as a ZIP archive

#### Scenario: Import ZIP with missing file
- **WHEN** a CSV row references a file path (e.g., `files/1/doc/report.pdf`) that does not exist in the ZIP archive
- **THEN** system SHALL store the FILE field value blank (treated as absent by validation) and report a warning carrying that row's number and column name

#### Scenario: Import CSV for suite with FILE fields
- **WHEN** client imports a CSV file for a suite whose schema has FILE fields
- **THEN** system SHALL treat FILE columns as string values (DIAL file paths if provided, or empty for null); no file extraction is performed
- **AND** FILE field validation may produce warnings if the DIAL file paths have invalid format or disallowed prefix

### Requirement: Import preview (CSV or ZIP)
The import preview endpoint SHALL support both CSV and ZIP formats with the same detection logic as the import endpoint.

The preview response SHALL report both `totalRows` — the number of CSV data rows parsed — and `totalTestCases` — the number of test cases those rows assemble into. The two differ only when the CSV contains multi-turn cases, whose turn rows assemble into one case each; for a single-turn CSV they are equal. Both describe the CSV as submitted and SHALL NOT be reduced by rows a conflict strategy would skip.

`sampleRows` SHALL contain assembled test cases (bounded by the sample limit), not raw CSV rows. A sample for a multi-turn case SHALL carry its `multiTurnData` turn array and its shared `data`; a sample for a single-turn case SHALL carry a flat `data` with no turn array.

For a ZIP archive, preview SHALL match import as defined by the `test-case-zip-archive` capability, without writing any file.

Status: **Implemented** (CSV); **Planned** (ZIP)

#### Scenario: Preview CSV file
- **WHEN** client sends `POST /api/v1/datasets/{datasetId}/test-cases/import/preview` with a CSV file
- **THEN** system SHALL return the preview (current behavior)

#### Scenario: Preview ZIP archive
- **WHEN** client sends `POST /api/v1/datasets/{datasetId}/test-cases/import/preview` with a ZIP file
- **THEN** system SHALL extract and preview the `test-cases.csv` within the archive
- **AND** FILE columns SHALL show the dataset file references import would store (`@ef/datasets/{datasetId}/{filename}` per the configured bucket alias), not the archive's relative paths, and no file SHALL be uploaded

#### Scenario: Preview reports test case count alongside row count
- **WHEN** client previews a CSV whose rows include a multi-turn case of N turns
- **THEN** `totalRows` SHALL count every CSV data row and `totalTestCases` SHALL count the N turn rows as one test case

#### Scenario: Multi-turn sample carries the validity import would produce
- **WHEN** client previews a CSV containing a multi-turn case
- **THEN** the sample's validity and warnings SHALL be those the import would compute for the assembled case — schema validation of its shared and per-turn data, merged with any multi-turn conflict — not a default or a per-row verdict

#### Scenario: Single-turn CSV preview is unchanged apart from the new count
- **WHEN** client previews a CSV containing no `turnIndex` values
- **THEN** `totalTestCases` SHALL equal `totalRows`, each sample row SHALL carry a flat `data` with no turn array, and no other previously reported field SHALL change value
