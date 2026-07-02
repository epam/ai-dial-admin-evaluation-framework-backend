## MODIFIED Requirements

### Requirement: Inlined extractedColumns columns
For each `ResponseColumnDefinitionDto` in `SuiteSnapshotDto.responseColumns` (preserving snapshot order), the CSV SHALL include one column named `response::<columnName>` (with `::` as the family-separator). The cell value SHALL be `extractedColumns[columnName]` rendered per the cell-serialization rules. For a multi-step result the value is a per-column array, which the cell-serialization rules render as a compact JSON string (e.g. `["Paris","Tokio"]`); for a single-step result it is a scalar. When extraction failed for a column (recorded in `extractionWarnings`), the cell SHALL be empty.
Status: **Implemented**

#### Scenario: Successful extraction
- **WHEN** the snapshot response columns include `answer` and a row has `extractedColumns.answer = "42"`
- **THEN** the row's `response::answer` cell SHALL be `42`

#### Scenario: Multi-step array-valued extraction
- **WHEN** the snapshot response columns include `answer` and a multi-step row has `extractedColumns.answer = ["Paris","Tokio"]`
- **THEN** the row's `response::answer` cell SHALL be the compact JSON string `["Paris","Tokio"]`

#### Scenario: Failed extraction
- **WHEN** the snapshot response columns include `answer` and a row has `extractedColumns.answer = null` and a warning for `answer` in `extractionWarnings`
- **THEN** the row's `response::answer` cell SHALL be empty and the row's `extractionWarnings` cell SHALL contain the warning JSON

#### Scenario: FILE-typed response column
- **WHEN** the snapshot response columns include a FILE column `attachment` and a row has `extractedColumns.attachment = "@ef/.../file.png"`
- **THEN** the row's `response::attachment` cell SHALL be `@ef/.../file.png` (raw DIAL ref, not materialized in V1)
