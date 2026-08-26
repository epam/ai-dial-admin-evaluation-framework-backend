## MODIFIED Requirements

### Requirement: Column header family-separator convention
Column names derived from the run's `suite_snapshot` testCaseSchema, the snapshot's responseColumns, or the resolved computation's `RunMetricSnapshot`s SHALL use the double-colon sequence `::` as the family-separator between the family name (`data`, `response`, `metric`, `metricInfo`, `metricError`) and the embedded identifier(s). The `::` sequence SHALL be the only family-separator emitted by the export; neither a single colon `:` nor the dot character SHALL be used for this role. The canonical separator constant SHALL be defined as `EvalSummaryExportColumnConstants.COLUMN_SEPARATOR = "::"` and used by all column-name composition sites.

Identity/execution columns (`id`, `testSuiteId`, `testSuiteRunId`, `testCaseRunResultId`, `testCaseId`, `testCaseName`, `runIndex`, `requestIndex`, `turnIndex`, `computationId`, `createdAt`, `computedAt`, `executionStatus`, `execDurationMs`, `responseStatusCode`), the JSON-blob column (`extractionWarnings`), and the body columns (`requestBody`, `responseBody`) SHALL NOT embed a family-separator; they retain their camelCase names because they are not derived from snapshot/metric identifiers.
Status: **Implemented**

#### Scenario: Snapshot field names with embedded dots are preserved
- **WHEN** the snapshot has a testCaseSchema field `meta.tags` and a metric `bert.score` with field `precision`
- **THEN** the header SHALL contain `data::meta.tags` and `metric::bert.score::precision` (the dots inside the snapshot/metric identifiers are preserved verbatim; only the family-separator slot uses `::`)

#### Scenario: Identity and JSON-blob columns retain camelCase names
- **WHEN** any successful export is invoked
- **THEN** the header SHALL contain `testCaseName`, `requestIndex`, `turnIndex`, `responseStatusCode`, and `extractionWarnings` exactly as written (no `::` family-separator in their names)

### Requirement: Inlined extractedColumns columns
For each `ResponseColumnDefinitionDto` in the run snapshot's **suite-wide union** of response columns — the snapshot's own `responseColumns` followed by each `additionalRequests[i].responseColumns` in chain order, preserving snapshot order within each list — the CSV SHALL include one column named `response::<columnName>` (with `::` as the family-separator). Because response-column names are globally unique across a suite's request chain, the union SHALL contain no duplicate header. The cell value SHALL be `extractedColumns[columnName]` rendered per the cell-serialization rules; because a row's `extracted_columns` is the accumulated union visible at that row, a column produced by a later request SHALL be empty on earlier requests' rows. When extraction failed for a column (recorded in `extractionWarnings`), the cell SHALL be empty.
Status: **Implemented**

#### Scenario: Successful extraction
- **WHEN** the snapshot response columns include `answer` and a row has `extractedColumns.answer = "42"`
- **THEN** the row's `response::answer` cell SHALL be `42`

#### Scenario: Failed extraction
- **WHEN** the snapshot response columns include `answer` and a row has `extractedColumns.answer = null` and a warning for `answer` in `extractionWarnings`
- **THEN** the row's `response::answer` cell SHALL be empty and the row's `extractionWarnings` cell SHALL contain the warning JSON

#### Scenario: FILE-typed response column
- **WHEN** the snapshot response columns include a FILE column `attachment` and a row has `extractedColumns.attachment = "@ef/.../file.png"`
- **THEN** the row's `response::attachment` cell SHALL be `@ef/.../file.png` (raw DIAL ref, not materialized in V1)

#### Scenario: Additional requests' columns appear in the manifest
- **WHEN** the snapshot's own `responseColumns` declare `configId` and its single additional request declares `answer`
- **THEN** the header SHALL contain both `response::configId` and `response::answer`, in that order

#### Scenario: A later request's column is empty on an earlier request's row
- **WHEN** `answer` is extracted by the chain's second request
- **THEN** the `response::answer` cell SHALL be empty on rows whose `requestIndex` is 0 and populated on rows whose `requestIndex` is 1

### Requirement: Identity and execution columns
The CSV SHALL include the following columns in this order before the inlined columns: `id`, `testSuiteId`, `testSuiteRunId`, `testCaseRunResultId`, `testCaseId`, `testCaseName`, `runIndex`, `requestIndex`, `turnIndex`, `computationId`, `createdAt`, `computedAt`, `executionStatus`, `execDurationMs`, `responseStatusCode`. These names use camelCase without a family-separator prefix — they are not derived from snapshot/metric data and therefore do not participate in the `<family>::<name>` convention.

`requestIndex` and `turnIndex` SHALL be positioned immediately after `runIndex`, in that order, so the three row-identity dimensions read repetition → request → turn. `requestIndex` SHALL carry the row's 0-based position in the suite's request chain (`0` for every row of a single-request suite); `turnIndex` SHALL carry the row's 0-based turn within its request (`0` for every row of a single-turn execution). Together with `testCaseName` and `runIndex` they SHALL uniquely identify a row within a computation, so no two exported rows of one run are indistinguishable.

Status: **Implemented**

#### Scenario: Identity columns present in default export
- **WHEN** any successful export is invoked
- **THEN** all listed identity and execution columns SHALL appear in the header row before any `data::*`, `response::*`, `metric::*`, `metricInfo::*`, `metricError::*`, or `extractionWarnings` columns

#### Scenario: Identity dimensions are ordered repetition, request, turn
- **WHEN** any successful export is invoked
- **THEN** the header SHALL contain `runIndex`, `requestIndex`, `turnIndex` as three consecutive columns in exactly that order

#### Scenario: requestIndex distinguishes chain rows
- **WHEN** a run of a 2-request chain is exported
- **THEN** each repetition SHALL yield two rows differing in their `requestIndex` cell (`0` and `1`)

#### Scenario: turnIndex distinguishes multi-turn rows
- **WHEN** a run containing a 3-turn test case is exported
- **THEN** that case's rows SHALL differ in their `turnIndex` cell (`0`, `1`, `2`), closing the pre-existing gap in which those rows were indistinguishable

#### Scenario: Both indices are zero for a single-request single-turn run
- **WHEN** a run of a suite without `additionalRequests` over single-turn test cases is exported
- **THEN** every row's `requestIndex` and `turnIndex` cells SHALL be `0`

#### Scenario: Chained multi-turn rows are uniquely identified
- **WHEN** a run of a 2-request chain whose second request is multi-turn with 2 turns is exported
- **THEN** each repetition SHALL yield rows with `(requestIndex, turnIndex)` pairs `(0, 0)`, `(1, 0)` and `(1, 1)`, all distinct
