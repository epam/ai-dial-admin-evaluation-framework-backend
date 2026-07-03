## MODIFIED Requirements

### Requirement: Column header family-separator convention
Column names derived from the run's `suite_snapshot` testCaseSchema, the snapshot's responseColumns, or the resolved computation's `RunMetricSnapshot`s SHALL use the double-colon sequence `::` as the family-separator between the family name (`data`, `response`, `metric`, `metricInfo`, `metricError`) and the embedded identifier(s). The `::` sequence SHALL be the only family-separator emitted by the export; neither a single colon `:` nor the dot character SHALL be used for this role. The canonical separator constant SHALL be defined as `EvalSummaryExportColumnConstants.COLUMN_SEPARATOR = "::"` and used by all column-name composition sites.

Identity/execution columns (`id`, `testSuiteId`, `testSuiteRunId`, `testCaseRunResultId`, `testCaseId`, `testCaseName`, `runIndex`, `computationId`, `createdAt`, `computedAt`, `executionStatus`, `execDurationMs`, `responseStatusCode`), the JSON-blob column (`extractionWarnings`), and the body columns (`requestBody`, `responseBody`) SHALL NOT embed a family-separator; they retain their camelCase names because they are not derived from snapshot/metric identifiers.
Status: **Implemented**

#### Scenario: Snapshot field names with embedded dots are preserved
- **WHEN** the snapshot has a testCaseSchema field `meta.tags` and a metric `bert.score` with field `precision`
- **THEN** the header SHALL contain `data::meta.tags` and `metric::bert.score::precision` (the dots inside the snapshot/metric identifiers are preserved verbatim; only the family-separator slot uses `::`)

#### Scenario: Identity and JSON-blob columns retain camelCase names
- **WHEN** any successful export is invoked
- **THEN** the header SHALL contain `testCaseName`, `responseStatusCode`, and `extractionWarnings` exactly as written (no `::` family-separator in their names)

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
For each `ResponseColumnDefinitionDto` in `SuiteSnapshotDto.responseColumns` (preserving snapshot order), the CSV SHALL include one column named `response::<columnName>` (with `::` as the family-separator). The cell value SHALL be `extractedColumns[columnName]` rendered per the cell-serialization rules. When extraction failed for a column (recorded in `extractionWarnings`), the cell SHALL be empty.
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
The CSV SHALL include the following columns in this order before the inlined columns: `id`, `testSuiteId`, `testSuiteRunId`, `testCaseRunResultId`, `testCaseId`, `testCaseName`, `runIndex`, `computationId`, `createdAt`, `computedAt`, `executionStatus`, `execDurationMs`, `responseStatusCode`. These names use camelCase without a family-separator prefix — they are not derived from snapshot/metric data and therefore do not participate in the `<family>::<name>` convention.
Status: **Implemented**

#### Scenario: Identity columns present in default export
- **WHEN** any successful export is invoked
- **THEN** all listed identity and execution columns SHALL appear in the header row before any `data::*`, `response::*`, `metric::*`, `metricInfo::*`, `metricError::*`, or `extractionWarnings` columns

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
