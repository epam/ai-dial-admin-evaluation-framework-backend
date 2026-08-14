## MODIFIED Requirements

### Requirement: Column header family-separator convention
Column names derived from the run's `suite_snapshot` testCaseSchema, the snapshot's responseColumns, or the resolved computation's `RunMetricSnapshot`s SHALL use the double-colon sequence `::` as the family-separator between the family name (`data`, `response`, `metric`, `metricInfo`, `metricError`) and the embedded identifier(s). The `::` sequence SHALL be the only family-separator emitted by the export; neither a single colon `:` nor the dot character SHALL be used for this role. The canonical separator constant SHALL be defined as `EvalSummaryExportColumnConstants.COLUMN_SEPARATOR = "::"` and used by all column-name composition sites.

Identity/execution columns (`id`, `testSuiteId`, `testSuiteRunId`, `testCaseRunResultId`, `testCaseId`, `testCaseName`, `runIndex`, `computationId`, `createdAt`, `computedAt`, `executionStatus`, `execDurationMs`, `avgMetricEvalDurationMs`, `responseStatusCode`), the JSON-blob column (`extractionWarnings`), and the body columns (`requestBody`, `responseBody`) SHALL NOT embed a family-separator; they retain their camelCase names because they are not derived from snapshot/metric identifiers.
Status: **Implemented**

#### Scenario: Snapshot field names with embedded dots are preserved
- **WHEN** the snapshot has a testCaseSchema field `meta.tags` and a metric `bert.score` with field `precision`
- **THEN** the header SHALL contain `data::meta.tags` and `metric::bert.score::precision` (the dots inside the snapshot/metric identifiers are preserved verbatim; only the family-separator slot uses `::`)

#### Scenario: Identity and JSON-blob columns retain camelCase names
- **WHEN** any successful export is invoked
- **THEN** the header SHALL contain `testCaseName`, `responseStatusCode`, and `extractionWarnings` exactly as written (no `::` family-separator in their names)

### Requirement: Identity and execution columns
The CSV SHALL include the following columns in this order before the inlined columns: `id`, `testSuiteId`, `testSuiteRunId`, `testCaseRunResultId`, `testCaseId`, `testCaseName`, `runIndex`, `computationId`, `createdAt`, `computedAt`, `executionStatus`, `execDurationMs`, `avgMetricEvalDurationMs`, `responseStatusCode`. These names use camelCase without a family-separator prefix — they are not derived from snapshot/metric data and therefore do not participate in the `<family>::<name>` convention.
Status: **Implemented**

#### Scenario: Identity columns present in default export
- **WHEN** any successful export is invoked
- **THEN** all listed identity and execution columns SHALL appear in the header row before any `data::*`, `response::*`, `metric::*`, `metricInfo::*`, `metricError::*`, or `extractionWarnings` columns

#### Scenario: Average metric evaluation latency column present
- **WHEN** any successful export is invoked
- **THEN** the header row SHALL contain an `avgMetricEvalDurationMs` column immediately after `execDurationMs`, with cell values taken from `EvalSummary.avgMetricEvalDurationMs`
