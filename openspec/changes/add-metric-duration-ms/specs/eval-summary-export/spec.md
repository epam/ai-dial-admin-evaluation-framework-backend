## MODIFIED Requirements

### Requirement: Identity and execution columns
The CSV SHALL include the following columns in this order before the inlined columns: `id`, `testSuiteId`, `testSuiteRunId`, `testCaseRunResultId`, `testCaseId`, `testCaseName`, `runIndex`, `computationId`, `createdAt`, `computedAt`, `executionStatus`, `execDurationMs`, `metricDurationMs`, `responseStatusCode`. These names use camelCase without a family-separator prefix — they are not derived from snapshot/metric data and therefore do not participate in the `<family>::<name>` convention.
Status: **Planned**

#### Scenario: Identity columns present in default export
- **WHEN** any successful export is invoked
- **THEN** all listed identity and execution columns SHALL appear in the header row before any `data::*`, `response::*`, `metric::*`, `metricInfo::*`, `metricError::*`, or `extractionWarnings` columns

#### Scenario: metricDurationMs directly follows execDurationMs
- **WHEN** any successful export is invoked
- **THEN** the header SHALL contain `metricDurationMs` immediately after `execDurationMs` and immediately before `responseStatusCode`, so the two duration columns read together

#### Scenario: Row without metric evaluation renders an empty cell
- **WHEN** a row's `metricDurationMs` is `null` (metric evaluation never ran, or the row predates the column)
- **THEN** the row's `metricDurationMs` cell SHALL be empty, and SHALL NOT be rendered as `0`

### Requirement: Column header family-separator convention
Column names derived from the run's `suite_snapshot` testCaseSchema, the snapshot's responseColumns, or the resolved computation's `RunMetricSnapshot`s SHALL use the double-colon sequence `::` as the family-separator between the family name (`data`, `response`, `metric`, `metricInfo`, `metricError`) and the embedded identifier(s). The `::` sequence SHALL be the only family-separator emitted by the export; neither a single colon `:` nor the dot character SHALL be used for this role. The canonical separator constant SHALL be defined as `EvalSummaryExportColumnConstants.COLUMN_SEPARATOR = "::"` and used by all column-name composition sites.

Identity/execution columns (`id`, `testSuiteId`, `testSuiteRunId`, `testCaseRunResultId`, `testCaseId`, `testCaseName`, `runIndex`, `computationId`, `createdAt`, `computedAt`, `executionStatus`, `execDurationMs`, `metricDurationMs`, `responseStatusCode`), the JSON-blob column (`extractionWarnings`), and the body columns (`requestBody`, `responseBody`) SHALL NOT embed a family-separator; they retain their camelCase names because they are not derived from snapshot/metric identifiers.
Status: **Planned**

#### Scenario: Snapshot field names with embedded dots are preserved
- **WHEN** the snapshot has a testCaseSchema field `meta.tags` and a metric `bert.score` with field `precision`
- **THEN** the header SHALL contain `data::meta.tags` and `metric::bert.score::precision` (the dots inside the snapshot/metric identifiers are preserved verbatim; only the family-separator slot uses `::`)

#### Scenario: Identity and JSON-blob columns retain camelCase names
- **WHEN** any successful export is invoked
- **THEN** the header SHALL contain `testCaseName`, `metricDurationMs`, `responseStatusCode`, and `extractionWarnings` exactly as written (no `::` family-separator in their names)

#### Scenario: metricDurationMs is not a metric family column
- **WHEN** the export builds the header
- **THEN** `metricDurationMs` SHALL be emitted as a plain identity/execution column and SHALL NOT be treated as a member of the `metric::`, `metricInfo::`, or `metricError::` families, even though its name begins with `metric`

## ADDED Requirements

### Requirement: metricDurationMs is selectable as an explicit column
`metricDurationMs` SHALL behave like every other identity/execution column when the caller restricts the export via the request's `columns` array.
Status: **Planned**

#### Scenario: Named explicitly
- **WHEN** a client POSTs an export request with `columns: ["testCaseName", "metricDurationMs"]`
- **THEN** the CSV header SHALL be exactly `testCaseName,metricDurationMs` and no repository JOIN to `test_case_run_results` SHALL be required

#### Scenario: Appears in the preview headers manifest
- **WHEN** a client calls the preview endpoint for a run
- **THEN** the returned headers manifest SHALL include `metricDurationMs`, so the column is discoverable

## Implementation notes

- Column descriptor: one `plain("metricDurationMs", row -> row.getSummary().getMetricDurationMs())` entry in `service/domain/analytics/EvalSummaryExportColumnPlanner.java`, added to the execution-columns block after `execDurationMs`. Existing `plain(...)` descriptors already render `null` as an empty cell.
- The export projection (`buildExportQuery` / `buildExportWithBodiesQuery` in `PostgresEvalSummaryRepository`) must select the new column.
- Adding the column shifts `responseStatusCode` one position right. No in-repo consumer binds export columns positionally: the eval-results import (`EvalResultsCsvParser`) parses a different, run-result-shaped CSV whose reserved columns are `startedAt`/`completedAt`, and it routes unknown headers to a no-op branch.
