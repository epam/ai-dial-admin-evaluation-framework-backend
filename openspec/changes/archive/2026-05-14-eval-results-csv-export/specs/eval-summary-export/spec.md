## ADDED Requirements

### Requirement: EvalSummary CSV export endpoint
The service SHALL expose `POST /api/v1/analytics/eval-summaries/export.csv` that streams a CSV download of `EvalSummary` rows for a single `(runId, computationId)` pair. The endpoint SHALL be `POST` (not `GET`) so the column-subset payload is delivered in the request body, avoiding URL-length limits. The response Content-Type SHALL be `text/csv; charset=UTF-8`.
Status: **Planned**

#### Scenario: Successful export returns CSV with full default column set
- **WHEN** a client POSTs `/api/v1/analytics/eval-summaries/export.csv` with body `{ "runId": "<uuid>" }` and no `columns`
- **THEN** the response status SHALL be `200`, Content-Type `text/csv; charset=UTF-8`, body SHALL contain a header row followed by one row per `EvalSummary` for the resolved `(runId, latestComputationId)` pair

#### Scenario: Content-Disposition header advertises the filename
- **WHEN** a client successfully invokes the export endpoint
- **THEN** the response SHALL include `Content-Disposition: attachment; filename="eval-summary-<runId>-<computationId>.csv"`

### Requirement: Request body schema for export
The endpoint SHALL accept an `EvalSummaryExportRequestDto` JSON body with the following fields: `runId` (UUID, required), `computation` (string, optional; UUID or `"latest"`, default `"latest"`), `columns` (string array, optional, ordered, default empty), `filter` (string array, optional, default empty), `delimiter` (string, optional, single ASCII character, default `","`). The DTO SHALL NOT carry a `detailed` field; inclusion of `requestBody`/`responseBody` is governed solely by their presence in `columns` (see "Request and response bodies via explicit columns"). The `filter` array SHALL be size-capped at `ValidationConstants.MAX_LIST_FILTER_PARAMS`; each element SHALL be taken verbatim (no comma-splitting is applied to body-supplied filter entries, unlike URL query-param parsing).
Status: **Planned**

#### Scenario: Missing runId
- **WHEN** the request body omits `runId` or sets it to null
- **THEN** the service SHALL return `HTTP 400` with error code `VALIDATION_ERROR`

#### Scenario: Computation defaults to latest
- **WHEN** the request body omits `computation`
- **THEN** the service SHALL resolve the latest `computation_id` for the run by ordering `run_metric_snapshots.computed_at_ms` descending

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
The column set SHALL be derived exclusively from the run's frozen `test_suite_runs.suite_snapshot` and the resolved computation's `run_metric_snapshots`. The service SHALL NOT read the live `TestSuite` or live `TestSuiteMetricDefinition` rows to construct columns.
Status: **Planned**

#### Scenario: Live suite changes do not affect old-run exports
- **WHEN** a run was executed at time T with schema S, and at time T+1 the suite has been edited to schema S′
- **AND** the client exports the run at time T+1
- **THEN** the CSV column set SHALL reflect S, not S′

#### Scenario: Live metric definitions changes do not affect old-computation exports
- **WHEN** a metric definition's `output_schema` was X at the time of computation C, and is later edited to X′
- **AND** the client exports `(runId, C)` after the edit
- **THEN** the flattened metric columns SHALL reflect X (read from `run_metric_snapshots.output_schema`), not X′

### Requirement: Inlined testCaseData columns
For each `FieldDefinitionDto` in `SuiteSnapshotDto.testCaseSchema` (preserving snapshot order), the CSV SHALL include one column named `data.<fieldName>`. The cell value SHALL be `testCaseData[fieldName]` rendered per the cell-serialization rules.
Status: **Planned**

#### Scenario: Schema field with primitive value
- **WHEN** the snapshot schema contains a STRING field `prompt` and a row has `testCaseData.prompt = "What is 2+2?"`
- **THEN** the row's `data.prompt` cell SHALL be `What is 2+2?`

#### Scenario: Schema field with FILE type
- **WHEN** the snapshot schema contains a FILE field `attachment` and a row has `testCaseData.attachment = "@ef/suites/abc/foo.pdf"`
- **THEN** the row's `data.attachment` cell SHALL be `@ef/suites/abc/foo.pdf` (raw DIAL ref, not materialized in V1)

#### Scenario: Schema field with nested JSON value
- **WHEN** the snapshot schema contains an OBJECT field `meta` and a row has `testCaseData.meta = {"k": 1}`
- **THEN** the row's `data.meta` cell SHALL be the compact JSON string `{"k":1}`

### Requirement: Inlined extractedColumns columns
For each `ResponseColumnDefinitionDto` in `SuiteSnapshotDto.responseColumns` (preserving snapshot order), the CSV SHALL include one column named `response.<columnName>`. The cell value SHALL be `extractedColumns[columnName]` rendered per the cell-serialization rules. When extraction failed for a column (recorded in `extractionWarnings`), the cell SHALL be empty.
Status: **Planned**

#### Scenario: Successful extraction
- **WHEN** the snapshot response columns include `answer` and a row has `extractedColumns.answer = "42"`
- **THEN** the row's `response.answer` cell SHALL be `42`

#### Scenario: Failed extraction
- **WHEN** the snapshot response columns include `answer` and a row has `extractedColumns.answer = null` and a warning for `answer` in `extractionWarnings`
- **THEN** the row's `response.answer` cell SHALL be empty and the row's `extractionWarnings` cell SHALL contain the warning JSON

#### Scenario: FILE-typed response column
- **WHEN** the snapshot response columns include a FILE column `attachment` and a row has `extractedColumns.attachment = "@ef/.../file.png"`
- **THEN** the row's `response.attachment` cell SHALL be `@ef/.../file.png` (raw DIAL ref, not materialized in V1)

### Requirement: Flattened metric value columns
For each `RunMetricSnapshot` of the resolved computation, the CSV SHALL include one column per field declared in the snapshot's `output_schema.properties`, named `<metricName>.<fieldName>`. The cell value SHALL be the numeric value at `metricValues.<metricName>.<fieldName>`. When the metric or field has no value for a row (failed evaluation), the cell SHALL be empty. Snapshot order SHALL be preserved.
Status: **Planned**

#### Scenario: Metric with two fields
- **WHEN** a `RunMetricSnapshot` named `Accuracy` has `output_schema.properties = {exact_match: {…}, score: {…}}` and a row has `metricValues.Accuracy = {exact_match: 1.0, score: 0.95}`
- **THEN** the row SHALL have columns `Accuracy.exact_match` = `1.0` and `Accuracy.score` = `0.95`

#### Scenario: Metric failure leaves cells empty
- **WHEN** a row's `metricValues.Accuracy` is null (evaluation failed) and `metricInfos.Accuracy` contains an error
- **THEN** the row's `Accuracy.exact_match` and `Accuracy.score` cells SHALL be empty and the row's `metricInfos` cell SHALL contain the error JSON

### Requirement: JSON-blob columns
The CSV SHALL include columns `metricInfos` and `extractionWarnings` as single cells holding compact JSON strings. Empty/null payloads SHALL render as empty cells. Keys present in `extractedColumns` that are not covered by the inlined `response.*` columns SHALL be dropped — the run-frozen column-set rule guarantees this set is empty in well-formed runs, so no overflow column is emitted.

**Producer-shape note**: `metricInfos` is persisted as a JSON **object** keyed by metric name (e.g. `{"Accuracy": {...}, "F1": {...}}`); its empty form is `{}`. `extractionWarnings` is persisted as a JSON **array** of `ExtractionWarningDto` entries (produced by `ResponseColumnExtractor.extract` via `ValidationWarningsSerializer.serializeExtractionWarnings`; the empty form returned for runs with no extraction failures is `"[]"`); its empty form is `[]`. Both empty forms render as empty cells on the CSV path and are preserved as `{}` / `[]` respectively on the preview path.
Status: **Planned**

#### Scenario: metricInfos cell is compact JSON
- **WHEN** a row has `metricInfos = {"Accuracy": {"score": {"reason": "matches"}}}`
- **THEN** the row's `metricInfos` cell SHALL be the compact JSON string `{"Accuracy":{"score":{"reason":"matches"}}}`

#### Scenario: Empty metricInfos
- **WHEN** a row has `metricInfos = null` or `{}`
- **THEN** the row's `metricInfos` cell SHALL be empty

### Requirement: Identity and execution columns
The CSV SHALL include the following columns in this order before the inlined columns: `id`, `testSuiteId`, `testSuiteRunId`, `testCaseRunResultId`, `testCaseId`, `testCaseName`, `runIndex`, `computationId`, `createdAt`, `computedAt`, `executionStatus`, `execDurationMs`, `responseStatusCode`.
Status: **Planned**

#### Scenario: Identity columns present in default export
- **WHEN** any successful export is invoked
- **THEN** all listed identity and execution columns SHALL appear in the header row before any `data.*`, `response.*`, `<metric>.<field>`, or JSON-blob columns

### Requirement: Request and response bodies via explicit columns
`requestBody` and `responseBody` SHALL be excluded from the **default** column set (the set emitted when the request's `columns` array is empty or omitted). They SHALL be included in the CSV **only** when the caller explicitly names them in `columns`. When at least one body column is named, the repository projection SHALL be the LEFT JOIN to `test_case_run_results`; otherwise the projection SHALL be the non-JOIN list projection.

Discovery of body columns happens through the preview endpoint, whose headers manifest always contains both body columns regardless of caller input.
Status: **Planned**

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
Status: **Planned**

#### Scenario: Subset preserves user order
- **WHEN** a client POSTs with `columns: ["testCaseName", "data.prompt", "Accuracy.score", "responseBody"]`
- **THEN** the CSV header row SHALL be `testCaseName,data.prompt,Accuracy.score,responseBody`
- **AND** the `responseBody` cells SHALL be populated from the JOIN

#### Scenario: Unknown column name
- **WHEN** a client POSTs with `columns: ["data.unknownField", "Accuracy.score"]` and `data.unknownField` is not in the run's column manifest
- **THEN** the service SHALL return `HTTP 400` with error code `VALIDATION_ERROR` and an error body listing `data.unknownField`

### Requirement: Filter and computation behave as on the list endpoint
The `filter` and `computation` fields SHALL behave identically to the existing `GET /api/v1/analytics/eval-summaries` list endpoint. `filter` strings SHALL be parsed with the existing filter parser and validated against `FilterWhitelists.EVAL_SUMMARIES`. `computation` SHALL accept either a UUID or the literal string `"latest"`.
Status: **Planned**

#### Scenario: Filter narrows the export
- **WHEN** a client POSTs with `filter: ["executionStatus:eq:SUCCESS"]`
- **THEN** the CSV SHALL contain only rows where `executionStatus = SUCCESS`

#### Scenario: Invalid filter token
- **WHEN** a `filter` entry references a field that is not in `FilterWhitelists.EVAL_SUMMARIES`
- **THEN** the service SHALL return `HTTP 400` with error code `VALIDATION_ERROR`

#### Scenario: Explicit computation UUID
- **WHEN** the request specifies `computation: "<uuid>"` and that UUID exists for the run
- **THEN** the export SHALL use that specific computation's snapshots and eval-summary rows

#### Scenario: Unknown computation UUID returns 404
- **WHEN** the request specifies `computation: "<uuid>"` that is a syntactically well-formed UUID but no `run_metric_snapshots` row exists for that `(runId, computationId)` pair (or `computation: "latest"` is supplied but the run has no snapshots at all)
- **THEN** the service SHALL return `HTTP 404` with error code `NOT_FOUND`

#### Scenario: Malformed computation string returns 400
- **WHEN** the request specifies `computation` as a non-UUID, non-`"latest"` string (e.g. `"abc"`, `"123"`)
- **THEN** the service SHALL return `HTTP 400` with error code `VALIDATION_ERROR`

### Requirement: Legacy run snapshot handling
Runs whose `suite_snapshot` is null SHALL have a transient snapshot synthesized from the live `TestSuite` via `SuiteSnapshotBuilder.build(liveSuite)` (mirroring the baseline policy in AGENTS.md → "Suite Run Snapshot Phase" → "Legacy runs"). Only when the live suite is also missing SHALL the service reject the request with `HTTP 422` (code `SNAPSHOT_SUITE_MISSING`). Runs whose `suite_snapshot.snapshotVersion` is not understood by the service SHALL be rejected with `HTTP 422` (code `UNSUPPORTED_SNAPSHOT_VERSION`).
Status: **Planned**

#### Scenario: Null snapshot with live suite present
- **WHEN** the client invokes either the export or the preview endpoint for a legacy run (`suite_snapshot IS NULL`) whose live `TestSuite` still exists
- **THEN** the service SHALL synthesize a transient snapshot via `SuiteSnapshotBuilder.build(liveSuite)` and proceed with the export

#### Scenario: Null snapshot with live suite missing
- **WHEN** the client invokes either endpoint for a legacy run (`suite_snapshot IS NULL`) whose live `TestSuite` has been deleted
- **THEN** the service SHALL return `HTTP 422` with error code `SNAPSHOT_SUITE_MISSING`

#### Scenario: Unsupported snapshot version
- **WHEN** `suite_snapshot.snapshotVersion` differs from `SuiteSnapshotDto.CURRENT_VERSION`
- **THEN** the service SHALL return `HTTP 422` with error code `UNSUPPORTED_SNAPSHOT_VERSION`

### Requirement: Preview endpoint
The service SHALL expose `GET /api/v1/analytics/eval-summaries/export/preview` that returns the full column manifest plus at most ten sample rows as JSON, used to drive client-side column selection before the full export. The response Content-Type SHALL be `application/json` and the response SHALL NOT set a `Content-Disposition` header (the preview is intended to be consumed inline by the UI, not downloaded as a file).

The response body SHALL be a JSON top-level array whose first element is the **headers array** (an array of column-name strings in the same order the CSV export would emit them) and whose subsequent elements (zero to ten) are **data row arrays** of identical length to the headers array, positionally aligned to it.

Each data-row cell SHALL be the column's parsed value rendered with its natural JSON type — numbers as JSON numbers (including metric scores, timestamps in epoch ms, and `responseStatusCode`), booleans as JSON booleans, `null` source values as JSON `null`, UUIDs and DIAL file references and enum names as JSON strings, and `Map`/`List`/`JsonNode` values (e.g. `metricInfos`, `extractionWarnings`, nested `data.*`/`response.*` objects) as nested JSON objects/arrays (NOT JSON-stringified). Empty maps and empty lists SHALL be preserved as `{}` and `[]` respectively (NOT collapsed to `null` — the CSV path's empty-cell collapsing rule is specific to CSV and does NOT apply here).
Status: **Planned**

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

#### Scenario: Preview shares legacy-run handling
- **WHEN** the client invokes the preview endpoint for a legacy run (`suite_snapshot IS NULL`)
- **THEN** the service SHALL apply the same legacy-run snapshot handling as the export endpoint: synthesize a transient snapshot via `SuiteSnapshotBuilder.build(liveSuite)` when the live suite exists, or return `HTTP 422` (`SNAPSHOT_SUITE_MISSING`) when it does not

#### Scenario: Preview rejects invalid filter token
- **WHEN** the client passes `filter=<token>` where `<token>` references a field that is not in `FilterWhitelists.EVAL_SUMMARIES` (or is otherwise unparseable by the filter parser)
- **THEN** the service SHALL return `HTTP 400` with error code `VALIDATION_ERROR`

#### Scenario: Preview returns 404 when run not found
- **WHEN** the client invokes the preview endpoint with a `runId` that does not exist
- **THEN** the service SHALL return `HTTP 404`

#### Scenario: Preview returns 422 on legacy snapshot with missing live suite
- **WHEN** the client invokes the preview endpoint for a legacy run (`suite_snapshot IS NULL`) whose live `TestSuite` has been deleted
- **THEN** the service SHALL return `HTTP 422` with error code `SNAPSHOT_SUITE_MISSING`

#### Scenario: Preview returns 422 on unsupported snapshot version
- **WHEN** the client invokes the preview endpoint for a run whose `suite_snapshot.snapshotVersion` is not understood by the service
- **THEN** the service SHALL return `HTTP 422` with error code `UNSUPPORTED_SNAPSHOT_VERSION`

### Requirement: CSV cell serialization rules
These rules apply to the **CSV export endpoint only**; the preview endpoint preserves typed JSON values per the Preview endpoint requirement above. The service SHALL serialize CSV cells as follows: `null` → empty string; an **empty** `Map`, `List`, or empty Jackson container (`ObjectNode`/`ArrayNode` with no entries) → empty string; `String`, `Number`, `Boolean` → `toString()`; a non-empty `Map`, `List`, or non-empty Jackson `JsonNode` → compact JSON string via the shared `ObjectMapper`. DIAL file references SHALL be emitted as their raw string form (no resolution or download in V1).
Status: **Planned**

#### Scenario: List value serialization
- **WHEN** a cell value is the Java `List.of("a", "b")`
- **THEN** the rendered cell SHALL be `["a","b"]`

#### Scenario: Null vs empty string
- **WHEN** a cell value is `null`
- **THEN** the rendered cell SHALL be the empty string (CSV-quoted as needed)

### Requirement: Run state guard (terminal-only)
Both the export endpoint and the preview endpoint SHALL reject requests targeting a `TestSuiteRun` whose `status` is not terminal (`PENDING` or `RUNNING`) with `HTTP 409 Conflict` and error code `RUN_NOT_TERMINAL`. Terminal statuses (`COMPLETED`, `FAILED`, `CANCELLED`) are the only ones allowed because cursor pagination over `test_case_eval_summaries` requires a stable snapshot of the underlying table; concurrent inserts during a non-terminal run would produce skipped or duplicated rows in the response. The check SHALL use `RunStatus.isTerminal(run.getStatus())` after loading the `TestSuiteRun` and before any column-planning or repository read.
Status: **Planned**

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
Status: **Planned**

#### Scenario: Swagger UI shows endpoints
- **WHEN** a developer opens `/swagger-ui.html`
- **THEN** the `Eval Summaries` tag (the actual `@Tag(name = "Eval Summaries")` on `EvalSummaryController`) SHALL list `POST /export.csv` (request body example, `text/csv` response) and `GET /export/preview` (JSON response example) with their respective examples

## Implementation notes

To be implemented under the following packages:

- `com.epam.aidial.evaluation.web.controller.EvalSummaryController` — two new methods.
- `com.epam.aidial.evaluation.service.domain.analytics.EvalSummaryExportService` — orchestration + streaming.
- `com.epam.aidial.evaluation.service.domain.analytics.EvalSummaryExportColumnPlanner` — pure column-derivation component.
- `com.epam.aidial.evaluation.service.domain.analytics.EvalSummaryExportColumnSelector` — column-subset resolution + validation.
- `com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryExportRequestDto` — request body.
- `com.epam.aidial.evaluation.data.db.analytics.repository.PostgresEvalSummaryRepository` — new JOIN-on-`test_case_run_results` cursor variant.
- `com.epam.aidial.evaluation.constants.ValidationConstants` — `MAX_EXPORT_COLUMNS`.

CSV writing reuses Apache Commons CSV `CSVPrinter` (already on the classpath) following the patterns established in `CsvExportService`.
