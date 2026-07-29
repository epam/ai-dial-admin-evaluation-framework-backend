## ADDED Requirements

### Requirement: Export of a run without metrics
The export and preview endpoints SHALL support runs whose suite had no enabled+valid TSMDs, i.e. runs that have eval summaries but no `run_metric_snapshots` rows. For such a run the column manifest SHALL contain the identity, timestamp, execution, `data::<field>`, `response::<column>`, and JSON-blob/body column families only, and SHALL omit every `metric::*`, `metricInfo::*`, and `metricError::*` column. The absence of metric snapshots SHALL NOT be treated as a missing computation.
Status: **Planned**

#### Scenario: Metric-free column manifest
- **WHEN** a client exports a run that has eval summaries and no `run_metric_snapshots` rows
- **THEN** the CSV SHALL contain one row per eval summary, with the `data::*` columns derived from the run snapshot's `testCaseSchema` and the `response::*` columns from its `responseColumns`, and SHALL contain no column whose name starts with `metric::`, `metricInfo::`, or `metricError::`

#### Scenario: Preview of a metric-free run
- **WHEN** a client calls the preview endpoint for such a run
- **THEN** it SHALL return HTTP 200 with a metric-free headers array — the full manifest per the "Preview headers array is the full manifest" scenario of the "Preview endpoint" requirement, so including `requestBody` and `responseBody` even though the default CSV header omits them — followed by the previewed data rows, and containing no `metric::*`, `metricInfo::*`, or `metricError::*` entry

#### Scenario: Explicit columns on a metric-free run
- **WHEN** a client requests an explicit `columns` subset containing a `metric::*` column for such a run
- **THEN** the service SHALL reject the request with `HTTP 400` and error code `VALIDATION_ERROR`, because that column is not in the planner-derived manifest — the same rule as any unknown column

## MODIFIED Requirements

### Requirement: Request body schema for export
The endpoint SHALL accept an `EvalSummaryExportRequestDto` JSON body with the following fields: `runId` (UUID, required), `computation` (string, optional; UUID or `"latest"`, default `"latest"`), `columns` (string array, optional, ordered, default empty), `filter` (string array, optional, default empty), `delimiter` (string, optional, single ASCII character, default `","`). The DTO SHALL NOT carry a `detailed` field; inclusion of `requestBody`/`responseBody` is governed solely by their presence in `columns` (see "Request and response bodies via explicit columns"). The `filter` array SHALL be size-capped at `ValidationConstants.MAX_LIST_FILTER_PARAMS`; each element SHALL be taken verbatim (no comma-splitting is applied to body-supplied filter entries, unlike URL query-param parsing).
Status: **Planned**

#### Scenario: Missing runId
- **WHEN** the request body omits `runId` or sets it to null
- **THEN** the service SHALL return `HTTP 400` with error code `VALIDATION_ERROR`

#### Scenario: Computation defaults to latest
- **WHEN** the request body omits `computation`
- **THEN** the service SHALL resolve the latest `computation_id` for the run by ordering `test_case_eval_summaries.computed_at_ms` descending

#### Scenario: Empty or omitted delimiter defaults to comma
- **WHEN** `delimiter` is null, omitted from the request body, or the empty string
- **THEN** the service SHALL use `,` (U+002C COMMA) as the CSV delimiter (matching the existing `TestCaseController.parseDelimiter` semantic; no error is raised)

#### Scenario: Invalid delimiter
- **WHEN** `delimiter` is longer than one character, or contains a non-ASCII character
- **THEN** the service SHALL return `HTTP 400` with error code `VALIDATION_ERROR`

#### Scenario: Columns subset size limit
- **WHEN** `columns` has more than `ValidationConstants.MAX_EXPORT_COLUMNS` entries
- **THEN** the service SHALL return `HTTP 400` with error code `VALIDATION_ERROR`

#### Scenario: Planner output column count limit
- **WHEN** the column planner's output (derived from the run's frozen snapshot + resolved `RunMetricSnapshot`s) contains more than `ValidationConstants.MAX_EXPORT_COLUMNS` descriptors
- **THEN** the service SHALL fail the request with `HTTP 400` and error code `VALIDATION_ERROR`, and the error message SHALL include both the offending column count and the cap value. This check SHALL execute **after** planning and **before** selector subsetting, so a request that supplies a small `columns` array against an over-wide run cannot bypass the cap.

#### Scenario: Filter array size limit
- **WHEN** `filter` has more than `ValidationConstants.MAX_LIST_FILTER_PARAMS` entries
- **THEN** the service SHALL return `HTTP 400` with error code `VALIDATION_ERROR`

#### Scenario: Filter elements are taken verbatim (no comma-splitting)
- **WHEN** the request body supplies `filter: ["data.tags:eq:foo,bar"]`
- **THEN** the service SHALL treat the single element `data.tags:eq:foo,bar` as one filter token (the comma is part of the value), distinct from the comma-splitting behavior of URL query-param parsing

### Requirement: Filter and computation behave as on the list endpoint
The `filter` and `computation` fields SHALL behave identically to the existing `GET /api/v1/analytics/eval-summaries` list endpoint. `filter` strings SHALL be parsed with the existing filter parser and validated against `FilterWhitelists.EVAL_SUMMARIES`. `computation` SHALL accept either a UUID or the literal string `"latest"`. Whether a computation exists SHALL be decided by the presence of eval-summary rows for that `(runId, computationId)` pair, never by the presence of `run_metric_snapshots` rows.
Status: **Planned**

#### Scenario: Filter narrows the export
- **WHEN** a client POSTs with `filter: ["executionStatus:eq:SUCCESS"]`
- **THEN** the CSV SHALL contain only rows where `executionStatus = SUCCESS`

#### Scenario: Invalid filter token
- **WHEN** a `filter` entry references a field that is not in `FilterWhitelists.EVAL_SUMMARIES`
- **THEN** the service SHALL return `HTTP 400` with error code `VALIDATION_ERROR`

#### Scenario: Explicit computation UUID
- **WHEN** the request specifies `computation: "<uuid>"` and that UUID exists for the run
- **THEN** the export SHALL use that specific computation's eval-summary rows, and its `run_metric_snapshots` rows when the computation has any

#### Scenario: Unknown computation UUID returns 404
- **WHEN** the request specifies `computation: "<uuid>"` that is a syntactically well-formed UUID but no eval-summary row exists for that `(runId, computationId)` pair (or `computation: "latest"` is supplied but the run has no eval summaries at all)
- **THEN** the service SHALL return `HTTP 404` with error code `NOT_FOUND`

#### Scenario: Computation without metric snapshots is not a 404
- **WHEN** the request specifies a `computation` (explicit UUID or `"latest"`) that has eval-summary rows but no `run_metric_snapshots` rows
- **THEN** the service SHALL return `HTTP 200` and export those rows with a metric-free column manifest

#### Scenario: Malformed computation string returns 400
- **WHEN** the request specifies `computation` as a non-UUID, non-`"latest"` string (e.g. `"abc"`, `"123"`)
- **THEN** the service SHALL return `HTTP 400` with error code `VALIDATION_ERROR`

### Requirement: Column set is derived from run snapshot
The column set SHALL be derived exclusively from the run's frozen `test_suite_runs.suite_snapshot` and the resolved computation's `run_metric_snapshots`. The service SHALL NOT read the live `TestSuite` or live `TestSuiteMetricDefinition` rows to construct columns. When the resolved computation has no `run_metric_snapshots` rows, the metric-derived column families are simply absent.
Status: **Planned**

#### Scenario: Live suite changes do not affect old-run exports
- **WHEN** a run was executed at time T with schema S, and at time T+1 the suite has been edited to schema S′
- **AND** the client exports the run at time T+1
- **THEN** the CSV column set SHALL reflect S, not S′

#### Scenario: Live metric definitions changes do not affect old-computation exports
- **WHEN** a metric definition's `output_schema` was X at the time of computation C, and is later edited to X′
- **AND** the client exports `(runId, C)` after the edit
- **THEN** the flattened metric columns SHALL reflect X (read from `run_metric_snapshots.output_schema`), not X′

#### Scenario: Adding metrics later does not retro-fit old exports
- **WHEN** a run was executed with no TSMDs and the suite later gains TSMDs
- **THEN** exporting that earlier run SHALL still yield the metric-free manifest, because its computation has no `run_metric_snapshots` rows

## Implementation notes

- `com.epam.aidial.evaluation.service.domain.analytics.EvalSummaryExportService` — the explicit-computation not-found guard is re-pointed from "no `RunMetricSnapshot` rows" to "no eval summaries for `(runId, computationId)`", via `EvalSummaryRepository.existsByRunIdAndComputationId` (jOOQ `fetchExists`, no rows fetched). The `"latest"` path keeps its existing `computationResolver.resolve(...).orElseThrow(EntityNotFoundException…)` 404, which after the resolver switch is itself an eval-summary answer. The repository is already injected, so no new dependency.
- `com.epam.aidial.evaluation.service.domain.analytics.EvalSummaryExportColumnPlanner` — unchanged; it already skips the whole per-metric block for an empty snapshot list.
- `com.epam.aidial.evaluation.service.domain.analytics.EvalSummaryExportColumnSelector` — unchanged; unknown-column validation already produces the 400 for a `metric::*` request against a metric-free manifest.
