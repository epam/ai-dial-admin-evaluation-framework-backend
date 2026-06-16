## MODIFIED Requirements

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
