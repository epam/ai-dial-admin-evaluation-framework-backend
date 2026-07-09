## MODIFIED Requirements

### Requirement: Inlined extractedColumns columns
For each `ResponseColumnDefinitionDto` in `SuiteSnapshotDto.responseColumns` (preserving snapshot order), the CSV SHALL include one column named `response::<columnName>` (with `::` as the family-separator). The cell value SHALL be `extractedColumns[columnName]` rendered per the cell-serialization rules. Because every result row is now a single turn, `extractedColumns` is always an object of **scalars** — the cell is the scalar value (a multi-turn conversation is exported as one CSV row per turn, each with its own scalar cell). When extraction failed for a column (recorded in `extractionWarnings`), the cell SHALL be empty.
Status: **Planned**

#### Scenario: Successful extraction
- **WHEN** the snapshot response columns include `answer` and a row has `extractedColumns.answer = "42"`
- **THEN** the row's `response::answer` cell SHALL be `42`

#### Scenario: Multi-turn conversation exports one row per turn
- **WHEN** a 3-turn conversation has response column `answer` with per-turn values `"Paris"`, `"Berlin"`, `"Tokio"`
- **THEN** the export SHALL contain three rows for that conversation with `response::answer` cells `Paris`, `Berlin`, `Tokio` and `turnIndex` `0`,`1`,`2`

#### Scenario: Failed extraction
- **WHEN** the snapshot response columns include `answer` and a row has `extractedColumns.answer = null` and a warning for `answer` in `extractionWarnings`
- **THEN** the row's `response::answer` cell SHALL be empty and the row's `extractionWarnings` cell SHALL contain the warning JSON

#### Scenario: FILE-typed response column
- **WHEN** the snapshot response columns include a FILE column `attachment` and a row has `extractedColumns.attachment = "@ef/.../file.png"`
- **THEN** the row's `response::attachment` cell SHALL be `@ef/.../file.png` (raw DIAL ref)

### Requirement: Identity and execution columns
The CSV SHALL include the following columns in this order before the inlined columns: `id`, `testSuiteId`, `testSuiteRunId`, `testCaseRunResultId`, `testCaseId`, `testCaseName`, `runIndex`, `turnIndex`, `totalTurns`, `computationId`, `createdAt`, `computedAt`, `executionStatus`, `execDurationMs`, `responseStatusCode`. These names use camelCase without a family-separator prefix — they are not derived from snapshot/metric data and therefore do not participate in the `<family>::<name>` convention. `turnIndex`/`totalTurns` are `0`/`1` for single-turn results.
Status: **Planned**

#### Scenario: Identity columns present in default export
- **WHEN** any successful export is invoked
- **THEN** all listed identity and execution columns — including `turnIndex` and `totalTurns` immediately after `runIndex` — SHALL appear in the header row before any `data::*`, `response::*`, `metric::*`, `metricInfo::*`, `metricError::*`, or `extractionWarnings` columns

#### Scenario: Turn columns populated per row
- **WHEN** a multi-turn conversation of 3 turns is exported
- **THEN** the three rows SHALL carry `turnIndex` `0`,`1`,`2` and `totalTurns` `3`; a single-turn suite's row SHALL carry `turnIndex` `0` and `totalTurns` `1`
