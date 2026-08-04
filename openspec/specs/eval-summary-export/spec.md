# Eval Summary Export

## Purpose
This spec defines the CSV export and JSON preview endpoints for eval summaries. The CSV endpoint streams a downloadable report of all `EvalSummary` rows for a single `(runId, computationId)` pair; the preview endpoint returns a typed array-of-arrays JSON shape for client-side column discovery before invoking the full export.

The column manifest is derived **exclusively** from the run's frozen `test_suite_runs.suite_snapshot` and the resolved computation's `run_metric_snapshots`, so old runs export with their original schemas regardless of subsequent edits to the live `TestSuite` or `TestSuiteMetricDefinition` rows. Request and response bodies are opt-in via explicit `columns`; the default column set excludes them. Both endpoints reject non-terminal runs (`PENDING`, `RUNNING`) with `HTTP 409` because cursor pagination over `test_case_eval_summaries` requires a stable table snapshot.

Status: **Implemented**

## Requirements

### Requirement: EvalSummary CSV export endpoint
The service SHALL expose `POST /api/v1/analytics/eval-summaries/export.csv` that streams a CSV download of `EvalSummary` rows for a single `(runId, computationId)` pair. The endpoint SHALL be `POST` (not `GET`) so the column-subset payload is delivered in the request body, avoiding URL-length limits. The response Content-Type SHALL be `text/csv; charset=UTF-8`.
Status: **Implemented**

#### Scenario: Successful export returns CSV with full default column set
- **WHEN** a client POSTs `/api/v1/analytics/eval-summaries/export.csv` with body `{ "runId": "<uuid>" }` and no `columns`
- **THEN** the response status SHALL be `200`, Content-Type `text/csv; charset=UTF-8`, body SHALL contain a header row followed by one row per `EvalSummary` for the resolved `(runId, latestComputationId)` pair

#### Scenario: Content-Disposition header advertises the filename
- **WHEN** a client successfully invokes the export endpoint
- **THEN** the response SHALL include `Content-Disposition: attachment; filename="eval-summary-<runId>-<computationId>.csv"`

### Requirement: Request body schema for export
The endpoint SHALL accept an `EvalSummaryExportRequestDto` JSON body with the following fields: `runId` (UUID, required), `computation` (string, optional; UUID or `"latest"`, default `"latest"`), `columns` (string array, optional, ordered, default empty), `filter` (string array, optional, default empty), `delimiter` (string, optional, single ASCII character, default `","`). The DTO SHALL NOT carry a `detailed` field; inclusion of `requestBody`/`responseBody` is governed solely by their presence in `columns` (see "Request and response bodies via explicit columns"). The `filter` array SHALL be size-capped at `ValidationConstants.MAX_LIST_FILTER_PARAMS`; each element SHALL be taken verbatim (no comma-splitting is applied to body-supplied filter entries, unlike URL query-param parsing).
Status: **Implemented**

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

### Requirement: Column set is derived from run snapshot
The column set SHALL be derived exclusively from the run's frozen `test_suite_runs.suite_snapshot` and the resolved computation's `run_metric_snapshots`. The service SHALL NOT read the live `TestSuite` or live `TestSuiteMetricDefinition` rows to construct columns. When the resolved computation has no `run_metric_snapshots` rows, the metric-derived column families are simply absent.
Status: **Implemented**

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

### Requirement: Inlined testCaseData columns
For each `FieldDefinitionDto` in `SuiteSnapshotDto.testCaseSchema` (preserving snapshot order), the CSV SHALL include one column named `data::<fieldName>` (with `::` as the family-separator, defined by `EvalSummaryExportColumnConstants.COLUMN_SEPARATOR`). The cell value SHALL be `testCaseData[fieldName]` rendered per the cell-serialization rules.
Status: **Implemented**

#### Scenario: Schema field with primitive value
- **WHEN** the snapshot schema contains a STRING field `prompt` and a row has `testCaseData.prompt = "What is 2+2?"`
- **THEN** the row's `data::prompt` cell SHALL be `What is 2+2?`

#### Scenario: Schema field with FILE type
- **WHEN** the snapshot schema contains a FILE field `attachment` and a row has `testCaseData.attachment = "@ef/suites/abc/foo.pdf"`
- **THEN** the row's `data::attachment` cell SHALL be `@ef/suites/abc/foo.pdf` (raw DIAL ref, not materialized in V1)

#### Scenario: Schema field with nested JSON value
- **WHEN** the snapshot schema contains an OBJECT field `meta` and a row has `testCaseData.meta = {"k": 1}`
- **THEN** the row's `data::meta` cell SHALL be the compact JSON string `{"k":1}`

#### Scenario: Schema field whose name contains a dot
- **WHEN** the snapshot schema contains a STRING field named `meta.tags` and a row has `testCaseData["meta.tags"] = "x"`
- **THEN** the row's column header SHALL be `data::meta.tags` (the embedded dot is preserved unmodified because `::` is the separator)

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

### Requirement: Flattened metric value columns
For each `RunMetricSnapshot` of the resolved computation (preserving snapshot order), and for each field declared in the snapshot's `output_schema.properties` (preserving property declaration order, top-level keys only), the CSV SHALL include one column named `metric::<metricName>::<fieldName>`. The cell value SHALL be the numeric value at `metricValues.<metricName>.<fieldName>`. When the metric or field has no value for a row (failed evaluation), the cell SHALL be empty.
Status: **Implemented**

#### Scenario: Metric with two fields
- **WHEN** a `RunMetricSnapshot` named `Accuracy` has `output_schema.properties = {exact_match: {…}, score: {…}}` and a row has `metricValues.Accuracy = {exact_match: 1.0, score: 0.95}`
- **THEN** the row SHALL have columns `metric::Accuracy::exact_match` = `1.0` and `metric::Accuracy::score` = `0.95`

#### Scenario: Metric failure leaves value cells empty
- **WHEN** a row's `metricValues.Accuracy` is null (evaluation failed) and `metricInfos.Accuracy` contains an error envelope
- **THEN** the row's `metric::Accuracy::exact_match` and `metric::Accuracy::score` cells SHALL be empty (the wholesale-error envelope is routed to `metricError::Accuracy` per the per-metric error column requirement)

#### Scenario: Metric name with embedded dot
- **WHEN** a `RunMetricSnapshot` is named `bert.score` with `output_schema.properties = {precision: {…}}`
- **THEN** the row SHALL have a column `metric::bert.score::precision` (the embedded dot is preserved because `::` is the separator)

### Requirement: Flattened metric info columns
For each `RunMetricSnapshot` of the resolved computation (preserving snapshot order), and for each field declared in the snapshot's `output_schema.properties` (preserving property declaration order, top-level keys only — same iteration as the flattened metric value columns), the CSV SHALL include one column named `metricInfo::<metricName>::<fieldName>`. The cell value SHALL be the JSON payload at `metricInfos.<metricName>.<fieldName>` rendered per the cell-serialization rules, with the following routing:

- If `metricInfos[<metricName>]` is a JSON object AND at least one of its top-level keys matches a key in the metric's `output_schema.properties`, it SHALL be treated as the per-field map. Each `metricInfo::<m>::<f>` cell SHALL be `metricInfos[m][f]` (an object on per-field success, the `{type:"error", message:"..."}` envelope on per-field failure, or empty when the key is absent).
- Otherwise the per-field cells SHALL be empty and the payload SHALL route to `metricError::<metricName>` (see the per-metric error column requirement below).

Status: **Implemented**

#### Scenario: Per-field success with details
- **WHEN** a `RunMetricSnapshot` `Retrieval` has `output_schema.properties = {recall, precision, f1, mrr}` and a row has `metricInfos.Retrieval = {recall: {details: {facts_ranks: [0, 2]}}, precision: {details: {}}, f1: {details: {}}, mrr: {details: {}}}`
- **THEN** the row's `metricInfo::Retrieval::recall` cell SHALL be the compact JSON string `{"details":{"facts_ranks":[0,2]}}`
- **AND** the row's `metricInfo::Retrieval::precision`, `metricInfo::Retrieval::f1`, and `metricInfo::Retrieval::mrr` cells SHALL be `{"details":{}}`

#### Scenario: Per-field failure routes the envelope to the field's info cell
- **WHEN** `metricInfos.Retrieval = {recall: {type: "error", message: "facts missing"}, precision: {details: {}}, f1: {details: {}}, mrr: {details: {}}}`
- **THEN** the row's `metricInfo::Retrieval::recall` cell SHALL be `{"type":"error","message":"facts missing"}`
- **AND** the row's `metric::Retrieval::recall` value cell SHALL be empty
- **AND** the row's `metricError::Retrieval` cell SHALL be empty (at least one schema key — `precision`/`f1`/`mrr` — is still present, so the payload is interpreted as a per-field map)

#### Scenario: Partial per-field map keeps absent-field cells empty
- **WHEN** `metricInfos.Retrieval = {recall: {details: {}}}` (only one of four declared fields is present)
- **THEN** the row's `metricInfo::Retrieval::recall` cell SHALL be `{"details":{}}`
- **AND** the row's `metricInfo::Retrieval::precision`, `metricInfo::Retrieval::f1`, and `metricInfo::Retrieval::mrr` cells SHALL be empty
- **AND** the row's `metricError::Retrieval` cell SHALL be empty (at least one key matches the schema)

#### Scenario: Missing per-metric info entry leaves all field cells empty
- **WHEN** `metricInfos = {}` for a row whose run has `RunMetricSnapshot` `Retrieval` with declared fields `{recall, precision, f1, mrr}`
- **THEN** the row's `metricInfo::Retrieval::recall`, `metricInfo::Retrieval::precision`, `metricInfo::Retrieval::f1`, and `metricInfo::Retrieval::mrr` cells SHALL all be empty
- **AND** the row's `metricError::Retrieval` cell SHALL be empty

### Requirement: Per-metric error column
For each `RunMetricSnapshot` of the resolved computation (preserving snapshot order), the CSV SHALL include exactly one column named `metricError::<metricName>`. The column SHALL be present in the header manifest unconditionally (independent of whether any row actually exercises a wholesale-error path). The cell value SHALL be populated ONLY when `metricInfos[<metricName>]` cannot be interpreted as a per-field map per the routing rule defined on the flattened metric info columns requirement; in that case the cell SHALL contain the row's `metricInfos[<metricName>]` payload rendered as a compact JSON string. Otherwise the cell SHALL be empty.
Status: **Implemented**

#### Scenario: Wholesale metric error routes to metricError column
- **WHEN** `metricInfos.Retrieval = {type: "error", message: "metric crashed before evaluation"}` (no schema-field key — `recall`/`precision`/`f1`/`mrr` — appears in this payload)
- **THEN** the row's `metricError::Retrieval` cell SHALL be `{"type":"error","message":"metric crashed before evaluation"}`
- **AND** the row's `metricInfo::Retrieval::recall`, `metricInfo::Retrieval::precision`, `metricInfo::Retrieval::f1`, and `metricInfo::Retrieval::mrr` cells SHALL all be empty
- **AND** the row's `metric::Retrieval::recall`, `metric::Retrieval::precision`, `metric::Retrieval::f1`, and `metric::Retrieval::mrr` value cells SHALL all be empty

#### Scenario: Non-object metricInfos entry routes to metricError column
- **WHEN** `metricInfos.Retrieval = "unrecoverable failure"` (a JSON string, not an object)
- **THEN** the row's `metricError::Retrieval` cell SHALL be the compact JSON string `"unrecoverable failure"`
- **AND** all `metricInfo::Retrieval::<f>` cells SHALL be empty

#### Scenario: Empty metricError cell on successful evaluation
- **WHEN** a row's `metricInfos.Retrieval` is a per-field map covering at least one of the schema's declared keys
- **THEN** the row's `metricError::Retrieval` cell SHALL be empty

#### Scenario: metricError column always present in header
- **WHEN** the export endpoint produces the CSV header for any successful export
- **THEN** the header SHALL include one `metricError::<m>` column per `RunMetricSnapshot` of the resolved computation, regardless of whether any row in the export exercises the wholesale-error path

### Requirement: JSON-blob columns
The CSV SHALL include the `extractionWarnings` column as a single cell holding the compact JSON string of the row's `extractionWarnings` payload. Empty/null payloads SHALL render as empty cells. Keys present in `extractedColumns` that are not covered by the inlined `response::*` columns SHALL be dropped — the run-frozen column-set rule guarantees this set is empty in well-formed runs, so no overflow column is emitted.

The CSV SHALL NOT include a single `metricInfos` JSON-blob column. Per-metric detail is exposed via the `metricInfo::<m>::<f>` and `metricError::<m>` column families defined in their own requirements.

**Producer-shape note**: `extractionWarnings` is persisted as a JSON **array** of `ExtractionWarningDto` entries (produced by `ResponseColumnExtractor.extract` via `ValidationWarningsSerializer.serializeExtractionWarnings`; the empty form returned for runs with no extraction failures is `"[]"`); its empty form is `[]`. The empty form renders as an empty cell on the CSV path and is preserved as `[]` on the preview path.
Status: **Implemented**

#### Scenario: extractionWarnings cell is compact JSON
- **WHEN** a row has `extractionWarnings = [{"column":"answer","message":"missing"}]`
- **THEN** the row's `extractionWarnings` cell SHALL be the compact JSON string `[{"column":"answer","message":"missing"}]`

#### Scenario: Empty extractionWarnings
- **WHEN** a row has `extractionWarnings = null` or `[]`
- **THEN** the row's `extractionWarnings` cell SHALL be empty

#### Scenario: Legacy metricInfos blob is no longer emitted
- **WHEN** the export endpoint produces the CSV header
- **THEN** the header SHALL NOT contain a column named `metricInfos` (the JSON blob from earlier versions is replaced by the per-field `metricInfo::<m>::<f>` columns and the per-metric `metricError::<m>` columns)

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

### Requirement: Request and response bodies via explicit columns
`requestBody` and `responseBody` SHALL be excluded from the **default** column set (the set emitted when the request's `columns` array is empty or omitted). They SHALL be included in the CSV **only** when the caller explicitly names them in `columns`. When at least one body column is named, the repository projection SHALL be the LEFT JOIN to `test_case_run_results`; otherwise the projection SHALL be the non-JOIN list projection.

Discovery of body columns happens through the preview endpoint, whose headers manifest always contains both body columns regardless of caller input.
Status: **Implemented**

#### Scenario: Empty columns omits bodies
- **WHEN** a client POSTs with `columns` empty or omitted
- **THEN** the CSV header SHALL NOT include `requestBody` or `responseBody`
- **AND** the repository projection SHALL be the non-JOIN list projection

#### Scenario: Explicitly naming responseBody includes it and forces the JOIN
- **WHEN** a client POSTs with `columns: ["testCaseName", "responseBody"]`
- **THEN** the CSV header SHALL be `testCaseName,responseBody`
- **AND** the `responseBody` cell SHALL be populated from the `test_case_run_results` JOIN
- **AND** the CSV SHALL NOT include any other column (no implicit "everything plus body" expansion)

#### Scenario: Explicitly naming requestBody includes it and forces the JOIN
- **WHEN** a client POSTs with `columns: ["testCaseName", "requestBody"]`
- **THEN** the CSV header SHALL be `testCaseName,requestBody`
- **AND** the `requestBody` cell SHALL be populated from the `test_case_run_results` JOIN

#### Scenario: Full dump including bodies requires the full columns list
- **WHEN** a client wishes to export every column the run can emit (including bodies)
- **THEN** the client SHALL first invoke `GET /export/preview` to obtain the headers manifest, then POST `/export.csv` with `columns` set to the entire manifest. The API exposes no shortcut for this case.

### Requirement: Explicit columns subset
When the request supplies a non-empty `columns` array, the CSV SHALL contain exactly those columns in exactly that order. If any requested column requires the `test_case_run_results` JOIN (i.e. `requestBody` or `responseBody`), the repository SHALL use the JOIN projection.
Status: **Implemented**

#### Scenario: Subset preserves user order
- **WHEN** a client POSTs with `columns: ["testCaseName", "data::prompt", "metric::Accuracy::score", "responseBody"]`
- **THEN** the CSV header row SHALL be `testCaseName,data::prompt,metric::Accuracy::score,responseBody`
- **AND** the `responseBody` cells SHALL be populated from the JOIN

#### Scenario: Unknown column name
- **WHEN** a client POSTs with `columns: ["data::unknownField", "metric::Accuracy::score"]` and `data::unknownField` is not in the run's column manifest
- **THEN** the service SHALL return `HTTP 400` with error code `VALIDATION_ERROR` and an error body listing `data::unknownField`

### Requirement: Filter and computation behave as on the list endpoint
The `filter` and `computation` fields SHALL behave identically to the existing `GET /api/v1/analytics/eval-summaries` list endpoint. `filter` strings SHALL be parsed with the existing filter parser and validated against `FilterWhitelists.EVAL_SUMMARIES`. `computation` SHALL accept either a UUID or the literal string `"latest"`. Whether a computation exists SHALL be decided by the presence of eval-summary rows for that `(runId, computationId)` pair, never by the presence of `run_metric_snapshots` rows.
Status: **Implemented**

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

### Requirement: Export of a run without metrics
The export and preview endpoints SHALL support runs whose suite had no enabled+valid TSMDs, i.e. runs that have eval summaries but no `run_metric_snapshots` rows. For such a run the column manifest SHALL contain the identity, timestamp, execution, `data::<field>`, `response::<column>`, and JSON-blob/body column families only, and SHALL omit every `metric::*`, `metricInfo::*`, and `metricError::*` column. The absence of metric snapshots SHALL NOT be treated as a missing computation.
Status: **Implemented**

#### Scenario: Metric-free column manifest
- **WHEN** a client exports a run that has eval summaries and no `run_metric_snapshots` rows
- **THEN** the CSV SHALL contain one row per eval summary, with the `data::*` columns derived from the run snapshot's `testCaseSchema` and the `response::*` columns from its `responseColumns`, and SHALL contain no column whose name starts with `metric::`, `metricInfo::`, or `metricError::`

#### Scenario: Preview of a metric-free run
- **WHEN** a client calls the preview endpoint for such a run
- **THEN** it SHALL return HTTP 200 with a metric-free headers array — the full manifest per the "Preview headers array is the full manifest" scenario of the "Preview endpoint" requirement, so including `requestBody` and `responseBody` even though the default CSV header omits them — followed by the previewed data rows, and containing no `metric::*`, `metricInfo::*`, or `metricError::*` entry

#### Scenario: Explicit columns on a metric-free run
- **WHEN** a client requests an explicit `columns` subset containing a `metric::*` column for such a run
- **THEN** the service SHALL reject the request with `HTTP 400` and error code `VALIDATION_ERROR`, because that column is not in the planner-derived manifest — the same rule as any unknown column

### Requirement: Legacy run snapshot handling
Legacy runs — runs whose `test_suite_runs.suite_snapshot` is null or blank — are NOT supported by the export. Both the export endpoint and the preview endpoint SHALL reject such runs with `HTTP 422` and error code `SNAPSHOT_SUITE_MISSING`. The live `TestSuite` SHALL NOT be consulted as a fallback. Runs whose `suite_snapshot.snapshotVersion` is not understood by the service SHALL be rejected with `HTTP 422` and error code `UNSUPPORTED_SNAPSHOT_VERSION`.
Status: **Implemented**

#### Scenario: Legacy run is rejected
- **WHEN** the client invokes the export or the preview endpoint for a run whose `suite_snapshot` is null or blank
- **THEN** the service SHALL return `HTTP 422` with error code `SNAPSHOT_SUITE_MISSING`, regardless of whether the live `TestSuite` still exists

#### Scenario: Unsupported snapshot version
- **WHEN** `suite_snapshot.snapshotVersion` differs from `SuiteSnapshotDto.CURRENT_VERSION`
- **THEN** the service SHALL return `HTTP 422` with error code `UNSUPPORTED_SNAPSHOT_VERSION`

### Requirement: Preview endpoint
The service SHALL expose `GET /api/v1/analytics/eval-summaries/export/preview` that returns the full column manifest plus at most ten sample rows as JSON, used to drive client-side column selection before the full export. The response Content-Type SHALL be `application/json` and the response SHALL NOT set a `Content-Disposition` header (the preview is intended to be consumed inline by the UI, not downloaded as a file).

The response body SHALL be a JSON top-level array whose first element is the **headers array** (an array of column-name strings in the same order the CSV export would emit them) and whose subsequent elements (zero to ten) are **data row arrays** of identical length to the headers array, positionally aligned to it.

Each data-row cell SHALL be the column's parsed value rendered with its natural JSON type — numbers as JSON numbers (including metric scores, timestamps in epoch ms, and `responseStatusCode`), booleans as JSON booleans, `null` source values as JSON `null`, UUIDs and DIAL file references and enum names as JSON strings, and `Map`/`List`/`JsonNode` values (e.g. `metricInfos`, `extractionWarnings`, nested `data.*`/`response.*` objects) as nested JSON objects/arrays (NOT JSON-stringified). Empty maps and empty lists SHALL be preserved as `{}` and `[]` respectively (NOT collapsed to `null` — the CSV path's empty-cell collapsing rule is specific to CSV and does NOT apply here).
Status: **Implemented**

#### Scenario: Preview returns at most 10 data rows
- **WHEN** the client invokes the preview endpoint for a run with more than 10 `EvalSummary` rows
- **THEN** the response body SHALL be a JSON array whose first element is the headers array followed by at most 10 data-row arrays

#### Scenario: Preview headers array is the full manifest
- **WHEN** the client invokes the preview endpoint
- **THEN** the first element of the response array SHALL be an array of strings listing every column the export endpoint can emit, in the same order, including `requestBody` and `responseBody`

#### Scenario: Preview response is inline JSON, not a download
- **WHEN** the client successfully invokes the preview endpoint
- **THEN** the response status SHALL be `200`, Content-Type SHALL be `application/json`, and no `Content-Disposition` header SHALL be set on the response

#### Scenario: Cells preserve JSON types
- **WHEN** a row has `metricValues.Accuracy.score = 0.95`, `responseStatusCode = 200`, `metricInfos = {"Accuracy": {"score": {"reason": "matches"}}}`, and `data.unknownField = null`
- **THEN** the corresponding cells in that row's data array SHALL be the JSON number `0.95`, the JSON number `200`, the JSON object `{"Accuracy":{"score":{"reason":"matches"}}}` (NOT the string `"{\"Accuracy\":…}"`), and JSON `null` (NOT the empty string `""`)

#### Scenario: Empty container preserved in preview
- **WHEN** a row has `metricInfos = {}` (empty Jackson `ObjectNode`) and `extractionWarnings = []` (empty `ArrayNode`)
- **THEN** the corresponding preview cells SHALL be the JSON values `{}` and `[]` respectively (NOT collapsed to the empty string, NOT collapsed to `null` — the CSV path's empty-cell collapsing rule does NOT apply to the preview)

#### Scenario: Preview does not accept columns or delimiter
- **WHEN** the client passes a `columns` or `delimiter` query parameter to the preview endpoint
- **THEN** the parameter SHALL be ignored (it is not declared on the endpoint); the headers array remains the complete column set

#### Scenario: Preview rejects invalid filter token
- **WHEN** the client passes `filter=<token>` where `<token>` references a field that is not in `FilterWhitelists.EVAL_SUMMARIES` (or is otherwise unparseable by the filter parser)
- **THEN** the service SHALL return `HTTP 400` with error code `VALIDATION_ERROR`

#### Scenario: Preview returns 404 when run not found
- **WHEN** the client invokes the preview endpoint with a `runId` that does not exist
- **THEN** the service SHALL return `HTTP 404`

#### Scenario: Preview rejects legacy runs
- **WHEN** the client invokes the preview endpoint for a run whose `suite_snapshot` is null or blank
- **THEN** the service SHALL return `HTTP 422` with error code `SNAPSHOT_SUITE_MISSING` (see "Legacy run snapshot handling")

#### Scenario: Preview returns 422 on unsupported snapshot version
- **WHEN** the client invokes the preview endpoint for a run whose `suite_snapshot.snapshotVersion` is not understood by the service
- **THEN** the service SHALL return `HTTP 422` with error code `UNSUPPORTED_SNAPSHOT_VERSION`

### Requirement: CSV cell serialization rules
These rules apply to the **CSV export endpoint only**; the preview endpoint preserves typed JSON values per the Preview endpoint requirement above. The service SHALL serialize CSV cells as follows: `null` → empty string; an **empty** `Map`, `List`, or empty Jackson container (`ObjectNode`/`ArrayNode` with no entries) → empty string; `String`, `Number`, `Boolean` → `toString()`; a non-empty `Map`, `List`, or non-empty Jackson `JsonNode` → compact JSON string via the shared `ObjectMapper`. DIAL file references SHALL be emitted as their raw string form (no resolution or download in V1).
Status: **Implemented**

#### Scenario: List value serialization
- **WHEN** a cell value is the Java `List.of("a", "b")`
- **THEN** the rendered cell SHALL be `["a","b"]`

#### Scenario: Null vs empty string
- **WHEN** a cell value is `null`
- **THEN** the rendered cell SHALL be the empty string (CSV-quoted as needed)

### Requirement: Run state guard (terminal-only)
Both the export endpoint and the preview endpoint SHALL reject requests targeting a `TestSuiteRun` whose `status` is not terminal (`PENDING` or `RUNNING`) with `HTTP 409 Conflict` and error code `RUN_NOT_TERMINAL`. Terminal statuses (`COMPLETED`, `FAILED`, `CANCELLED`) are the only ones allowed because cursor pagination over `test_case_eval_summaries` requires a stable snapshot of the underlying table; concurrent inserts during a non-terminal run would produce skipped or duplicated rows in the response. The check SHALL use `RunStatus.isTerminal(run.getStatus())` after loading the `TestSuiteRun` and before any column-planning or repository read.
Status: **Implemented**

#### Scenario: Export rejects a RUNNING run
- **WHEN** a client invokes `POST /api/v1/analytics/eval-summaries/export.csv` for a `TestSuiteRun` whose `status` is `RUNNING`
- **THEN** the service SHALL return `HTTP 409` with error code `RUN_NOT_TERMINAL` and an error message that references the run's current status

#### Scenario: Export rejects a PENDING run
- **WHEN** a client invokes the export endpoint for a `TestSuiteRun` whose `status` is `PENDING`
- **THEN** the service SHALL return `HTTP 409` with error code `RUN_NOT_TERMINAL`

#### Scenario: Preview applies the same guard
- **WHEN** a client invokes `GET /api/v1/analytics/eval-summaries/export/preview` for a `TestSuiteRun` whose `status` is `RUNNING` or `PENDING`
- **THEN** the service SHALL return `HTTP 409` with error code `RUN_NOT_TERMINAL`

#### Scenario: Terminal runs are allowed
- **WHEN** a client invokes either endpoint for a `TestSuiteRun` whose `status` is `COMPLETED`, `FAILED`, or `CANCELLED`
- **THEN** the service SHALL proceed with the request (no 409 is raised by the state guard; other validation still applies)

### Requirement: OpenAPI documentation and examples
The OpenAPI document SHALL describe both the export and preview endpoints with at least one example each. The preview endpoint's `filter` query parameter SHALL be registered with `OpenApiQueryParamCustomizer` so its description is auto-generated from the filter whitelist.
Status: **Implemented**

#### Scenario: Swagger UI shows endpoints
- **WHEN** a developer opens `/swagger-ui.html`
- **THEN** the `Eval Summaries` tag (the actual `@Tag(name = "Eval Summaries")` on `EvalSummaryController`) SHALL list `POST /export.csv` (request body example, `text/csv` response) and `GET /export/preview` (JSON response example) with their respective examples

## Implementation notes

Implemented under the following packages:

- `com.epam.aidial.evaluation.web.controller.EvalSummaryController` — two new methods (`exportCsv`, `previewExport`).
- `com.epam.aidial.evaluation.service.domain.analytics.EvalSummaryExportService` — orchestration + streaming via dual-datasource `TransactionTemplate`s (per-page commits for the analytics scan). The explicit-computation not-found guard asks `EvalSummaryRepository.existsByRunIdAndComputationId` (jOOQ `fetchExists`, no rows fetched), not whether the computation has `RunMetricSnapshot` rows; the `"latest"` path keeps its `computationResolver.resolve(...).orElseThrow(EntityNotFoundException…)` 404, which is itself an eval-summary answer.
- `com.epam.aidial.evaluation.service.domain.analytics.EvalSummaryExportColumnPlanner` — pure column-derivation component (always emits `requestBody`/`responseBody` at the tail); skips the whole per-metric block for an empty snapshot list.
- `com.epam.aidial.evaluation.service.domain.analytics.EvalSummaryExportColumnSelector` — column-subset resolution + validation (strips bodies on the empty-input branch); its unknown-column validation is what rejects a `metric::*` request against a metric-free manifest.
- `com.epam.aidial.evaluation.service.domain.analytics.ComputationResolver` — `computation: String → Optional<UUID>` resolution shared with the list endpoint; `"latest"` resolves against `test_case_eval_summaries`.
- `com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryExportRequestDto` — request body.
- `com.epam.aidial.evaluation.data.db.analytics.repository.PostgresEvalSummaryRepository` — new `findAllForExport` (non-JOIN) and `findAllForExportWithBodies` (LEFT JOIN to `test_case_run_results` via subquery to avoid `test_suite_run_id` ambiguity) cursor variants sharing a common `findAllInternal`.
- `com.epam.aidial.evaluation.service.domain.csv.CsvDelimiterParser` — shared delimiter parser; `TestCaseController` delegates to it.
- `com.epam.aidial.evaluation.constants.ValidationConstants` — `MAX_EXPORT_COLUMNS`.
- `com.epam.aidial.evaluation.service.domain.exception` — `RunNotTerminalException` (new), `SnapshotSuiteMissingException` / `UnsupportedSnapshotVersionException` (extracted from `TestSuiteEvaluationJob` for cross-service reuse).
- `com.epam.aidial.evaluation.web.handler.ErrorCode` — `SNAPSHOT_SUITE_MISSING`, `UNSUPPORTED_SNAPSHOT_VERSION`, `RUN_NOT_TERMINAL`.

CSV writing reuses Apache Commons CSV `CSVPrinter` following the patterns established in `CsvExportService`.
