## MODIFIED Requirements

### Requirement: Identity and execution columns
The CSV SHALL include the following columns in this order before the inlined columns: `id`, `testSuiteId`, `testSuiteRunId`, `testCaseRunResultId`, `testCaseId`, `testCaseName`, `runIndex`, `multiTurnId`, `turnIndex`, `totalTurns`, `computationId`, `createdAt`, `computedAt`, `executionStatus`, `execDurationMs`, `responseStatusCode`. These names use camelCase without a family-separator prefix — they are not derived from snapshot/metric data and therefore do not participate in the `<family>::<name>` convention. `multiTurnId` is placed immediately before `turnIndex`, mirroring the test-case CSV column order (`testCaseName`, `multiTurnId`, `turnIndex`); its cell is empty for single-turn rows. `turnIndex`/`totalTurns` are `0`/`1` for single-turn results.
Status: **Implemented**

#### Scenario: Identity columns present in default export
- **WHEN** any successful export is invoked
- **THEN** all listed identity and execution columns — including `multiTurnId` immediately before `turnIndex`, and `turnIndex`/`totalTurns` immediately after it — SHALL appear in the header row before any `data::*`, `response::*`, `metric::*`, `metricInfo::*`, `metricError::*`, or `extractionWarnings` columns

#### Scenario: Turn columns populated per row
- **WHEN** a multi-turn of 3 turns is exported
- **THEN** the three rows SHALL carry `turnIndex` `0`,`1`,`2` and `totalTurns` `3`; a single-turn suite's row SHALL carry `turnIndex` `0` and `totalTurns` `1`

#### Scenario: multiTurnId column populated per row
- **WHEN** a multi-turn of 3 turns is exported alongside a single-turn row
- **THEN** the three multi-turn rows SHALL carry the same non-empty `multiTurnId` cell (the source multi-turn's id), and the single-turn row SHALL carry an empty `multiTurnId` cell
