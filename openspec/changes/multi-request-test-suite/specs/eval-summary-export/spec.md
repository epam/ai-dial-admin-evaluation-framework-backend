## MODIFIED Requirements

### Requirement: Identity and execution columns
The CSV SHALL include the following columns in this order before the inlined columns: `id`, `testSuiteId`, `testSuiteRunId`, `testCaseRunResultId`, `testCaseId`, `testCaseName`, `runIndex`, `requestIndex`, `requestLabel`, `turnIndex`, `totalTurns`, `computationId`, `createdAt`, `computedAt`, `executionStatus`, `execDurationMs`, `responseStatusCode`. These names use camelCase without a family-separator prefix — they are not derived from snapshot/metric data and therefore do not participate in the `<family>::<name>` convention.

`requestIndex` / `requestLabel` identify which chain request produced the row; `turnIndex` / `totalTurns` identify the turn within a multi-turn test case. Without these, rows multiplied by a chain or by turns are distinguishable only by opaque identifiers, making an exported report un-interpretable on its own.
Status: **Planned**

#### Scenario: Identity columns present in default export
- **WHEN** any successful export is invoked
- **THEN** all listed identity and execution columns SHALL appear in the header row before any `data::*`, `response::*`, `metric::*`, `metricInfo::*`, `metricError::*`, or `extractionWarnings` columns

#### Scenario: Chain rows are distinguishable
- **WHEN** a run of a three-request chain is exported
- **THEN** each test case contributes three rows carrying `requestIndex` `0`, `1`, `2` and their respective `requestLabel` values

#### Scenario: Turn rows are distinguishable
- **WHEN** a run containing a multi-turn test case with N turns is exported
- **THEN** that case contributes N rows carrying `turnIndex` `0..N-1` and `totalTurns = N`

#### Scenario: Single-request single-turn export carries defaults
- **WHEN** a run of a single-request suite over single-turn cases is exported
- **THEN** every row carries `requestIndex = 0`, `turnIndex = 0`, `totalTurns = 1`, and the resolved default `requestLabel`

### Requirement: Inlined extractedColumns columns
For each `ResponseColumnDefinitionDto` in the run snapshot's **chain-union** response column set (preserving chain order: request 0's columns, then each subsequent chain request's columns in order), the CSV SHALL include one column named `response::<columnName>` (with `::` as the family-separator). The cell value SHALL be `extractedColumns[columnName]` rendered per the cell-serialization rules. When extraction failed for a column (recorded in `extractionWarnings`), the cell SHALL be empty. Because each row's `extractedColumns` holds only its own chain request's columns, rows of a multi-request run are **sparse**: cells for columns owned by other requests SHALL be empty.
Status: **Planned**

#### Scenario: Successful extraction
- **WHEN** the snapshot response columns include `answer` and a row has `extractedColumns.answer = "42"`
- **THEN** the row's `response::answer` cell SHALL be `42`

#### Scenario: Failed extraction
- **WHEN** the snapshot response columns include `answer` and a row has `extractedColumns.answer = null` and a warning for `answer` in `extractionWarnings`
- **THEN** the row's `response::answer` cell SHALL be empty and the row's `extractionWarnings` cell SHALL contain the warning JSON

#### Scenario: FILE-typed response column
- **WHEN** the snapshot response columns include a FILE column `attachment` and a row has `extractedColumns.attachment = "@ef/.../file.png"`
- **THEN** the row's `response::attachment` cell SHALL be `@ef/.../file.png` (raw DIAL ref, not materialized in V1)

#### Scenario: Chain union produces the column set
- **WHEN** a run's snapshot chain declares `session_id` on request 0 and `answer` on request 1
- **THEN** the header SHALL contain both `response::session_id` and `response::answer`, in chain order

#### Scenario: Chain rows are sparse
- **WHEN** the same run is exported
- **THEN** request 0's rows SHALL have a populated `response::session_id` cell and an empty `response::answer` cell, and request 1's rows the reverse

#### Scenario: Column count cap applies to the union
- **WHEN** a chain's unioned response columns push the planner's output past `ValidationConstants.MAX_EXPORT_COLUMNS`
- **THEN** the service SHALL fail the request with HTTP 400 `VALIDATION_ERROR` naming both the offending column count and the cap, as for any over-wide run

## Implementation notes

`EvalSummaryExportColumnPlanner` — four additional identity descriptors, and the `response::` family sourced from the shared chain-union response-column helper rather than the snapshot's flat `responseColumns`. The same helper backs `EvalSummariesSchemaProvider`. The added identity columns are appended within the identity block, so header-name-based consumers are unaffected; strictly positional parsers will observe the new columns.
