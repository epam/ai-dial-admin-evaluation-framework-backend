## MODIFIED Requirements

### Requirement: Preview endpoint
Status: **Implemented**

The service SHALL expose `GET /api/v1/analytics/eval-summaries/export/preview` that returns the full column manifest plus at most ten sample rows as JSON, used to drive client-side column selection before the full export. The response Content-Type SHALL be `application/json` and the response SHALL NOT set a `Content-Disposition` header (the preview is intended to be consumed inline by the UI, not downloaded as a file).

The response body SHALL be a JSON top-level array whose first element is the **headers array** (an array of column-name strings in the same order the CSV export would emit them) and whose subsequent elements (zero to ten) are **data row arrays** of identical length to the headers array, positionally aligned to it.

Non-null `createdAt` and `computedAt` cells SHALL be ISO 8601 UTC strings with exactly three fractional digits and a trailing `Z`. Null timestamp cells SHALL remain JSON `null`. Every other data-row cell SHALL be the column's parsed value rendered with its natural JSON type — numbers as JSON numbers (including metric scores, durations, and `responseStatusCode`), booleans as JSON booleans, `null` source values as JSON `null`, UUIDs and DIAL file references and enum names as JSON strings, and `Map`/`List`/`JsonNode` values (e.g. per-field metric info, `extractionWarnings`, nested data/response objects) as nested JSON objects/arrays (NOT JSON-stringified). Empty maps and empty lists SHALL be preserved as `{}` and `[]` respectively (NOT collapsed to `null`).

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
- **WHEN** a row has an Accuracy score of `0.95`, `responseStatusCode = 200`, per-field Accuracy score info `{"reason":"matches"}`, and a null value for a declared data field `optional`
- **THEN** `metric::Accuracy::score`, `responseStatusCode`, `metricInfo::Accuracy::score`, and `data::optional` cells SHALL be the JSON number `0.95`, the JSON number `200`, the JSON object `{"reason":"matches"}` (NOT a JSON-stringified object), and JSON `null` (NOT the empty string `""`)

#### Scenario: Empty container preserved in preview
- **WHEN** a row has an empty JSON object for a declared OBJECT data field `metadata` and `extractionWarnings = []` (empty JSON array)
- **THEN** the `data::metadata` and `extractionWarnings` preview cells SHALL be the JSON values `{}` and `[]` respectively (NOT collapsed to the empty string, NOT collapsed to `null`)

#### Scenario: Preview does not accept columns or delimiter
- **WHEN** the client passes a `columns` or `delimiter` query parameter to the preview endpoint
- **THEN** the parameter SHALL be ignored (it is not declared on the endpoint); the headers array remains the complete column set

#### Scenario: Preview rejects invalid filter token
- **WHEN** the client passes `filter=<token>` where `<token>` references a field that is not allowed for eval summaries (or is otherwise unparseable)
- **THEN** the service SHALL return `HTTP 400` with error code `INVALID_FILTER`

#### Scenario: Preview returns 404 when run not found
- **WHEN** the client invokes the preview endpoint with a `runId` that does not exist
- **THEN** the service SHALL return `HTTP 404`

#### Scenario: Preview rejects legacy runs
- **WHEN** the client invokes the preview endpoint for a run whose `suite_snapshot` is null or blank
- **THEN** the service SHALL return `HTTP 422` with error code `SNAPSHOT_SUITE_MISSING` (see "Legacy run snapshot handling")

#### Scenario: Preview returns 422 on unsupported snapshot version
- **WHEN** the client invokes the preview endpoint for a run whose snapshot version is not understood by the service
- **THEN** the service SHALL return `HTTP 422` with error code `UNSUPPORTED_SNAPSHOT_VERSION`

#### Scenario: Preview timestamp formatting preserves milliseconds
- **WHEN** a row's `createdAt` is `1779704532226` and `computedAt` is `1779704533226`
- **THEN** the corresponding preview cells SHALL be `"2026-05-25T10:22:12.226Z"` and `"2026-05-25T10:22:13.226Z"`

#### Scenario: Preview zero, negative, and null timestamps
- **WHEN** timestamp values are `0`, `-1`, and null
- **THEN** their preview cells SHALL be `"1970-01-01T00:00:00.000Z"`, `"1969-12-31T23:59:59.999Z"`, and JSON `null` respectively

#### Scenario: Conversion is confined to the two timestamp columns
- **WHEN** a row contains numeric durations and epoch-like numeric data, response, and metric values
- **THEN** only `createdAt` and `computedAt` SHALL become timestamp strings; the other values SHALL retain their natural JSON types and values

### Requirement: CSV timestamps use ISO UTC
Status: **Implemented**

The CSV export SHALL render non-null `createdAt` and `computedAt` epoch-millisecond values as ISO 8601 UTC timestamps with exactly three fractional digits and a trailing `Z`. Null timestamps SHALL remain empty cells. Conversion SHALL apply only to these timestamp columns, regardless of default or explicit column selection; durations and arbitrary data/response/metric values SHALL remain unchanged. Stored models and JSON responses other than export preview SHALL retain epoch milliseconds; export preview SHALL follow the "Preview endpoint" requirement. No columns SHALL be added or renamed.

#### Scenario: Timestamp formatting preserves milliseconds
- **WHEN** a selected timestamp column has value `1779704532226`
- **THEN** its CSV cell SHALL be `2026-05-25T10:22:12.226Z`

#### Scenario: Zero, negative, and null timestamps
- **WHEN** timestamp values are `0`, `-1`, and null
- **THEN** their CSV cells SHALL be `1970-01-01T00:00:00.000Z`, `1969-12-31T23:59:59.999Z`, and empty respectively

#### Scenario: Conversion is confined to CSV timestamp columns
- **WHEN** the same row is exported and previewed, with numeric duration and arbitrary data values
- **THEN** non-null `createdAt` and `computedAt` SHALL have the same ISO UTC text in CSV and preview; null timestamps SHALL be empty CSV cells and JSON `null` preview cells, and the other values SHALL retain their existing representations
