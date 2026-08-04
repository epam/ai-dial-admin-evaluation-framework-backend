# Response Columns

## Purpose

Defines the storage, validation, and evaluation of user-defined response column definitions scoped to a TestSuite. Each column specifies a JSONata expression to extract a named value from a test result's response body. Column definitions are stored as JSONB on the TestSuite (like `testCaseSchema`). At run time, expressions are evaluated against each result's `responseBody` and extracted values are persisted in the analytics DB for downstream sort/filter/analytics.

Status: **Implemented**

## Key Terms

- **ResponseColumn**: A named column definition attached to a TestSuite that describes how to extract a display value from a test result's response body using a JSONata expression.
- **name**: Stable identifier for the column. Used as key in `extracted_columns`, CSV headers, and FE references. Must be unique within the suite's response columns array.
- **displayName**: Optional human-friendly UI label. FE falls back to `name` if not set.
- **SchemaFieldType**: Shared type enum (`STRING`, `INTEGER`, `NUMBER`, `BOOLEAN`, `OBJECT`, `ARRAY`, `FILE`) used for both testCaseSchema fields and response columns. `FILE` is a display hint indicating the value is a DIAL file reference path.

## Data Shape

**ResponseColumn definition** (element in `test_suites.response_columns` JSONB array):
```json
{
  "name": "answer",
  "displayName": "Response Content",
  "expression": "choices[0].message.content",
  "type": "STRING"
}
```

**Minimal definition** (displayName and type optional):
```json
{
  "name": "token_count",
  "expression": "usage.total_tokens"
}
```

Defaults when omitted: `displayName` = null (FE uses `name`), `type` = `STRING`.

**TestSuite with response columns** (partial, showing new field):
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "My Suite",
  "testCaseSchema": [
    {"name": "question", "type": "STRING", "required": true}
  ],
  "responseColumns": [
    {"name": "answer", "displayName": "Answer", "expression": "choices[0].message.content", "type": "STRING"},
    {"name": "token_count", "expression": "usage.total_tokens", "type": "NUMBER"}
  ]
}
```

**Extracted values** (stored in `test_case_run_results.extracted_columns` JSONB):
```json
{
  "answer": "Paris is the capital of France.",
  "token_count": 25
}
```

**Extraction warnings** (stored in `test_case_run_results.extraction_warnings` JSONB):
```json
[
  {
    "column": "token_count",
    "expression": "usage.total_tokens",
    "error": "Path 'usage' not found in response body"
  }
]
```

## Requirements

### Requirement: Store response column definitions on TestSuite

Response column definitions SHALL be stored as a JSONB array on the `test_suites` table, managed via existing suite create/update endpoints.

Status: **Implemented**

#### Scenario: Create suite with response columns
- **WHEN** client calls `POST /api/v1/test-suites` with `responseColumns` in the request body
- **THEN** system SHALL persist the definitions and return them in the response

#### Scenario: Update suite response columns
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with updated `responseColumns`
- **THEN** system SHALL replace the existing definitions with the new array

#### Scenario: Suite with no response columns
- **WHEN** `responseColumns` is omitted or null on create/update
- **THEN** system SHALL default to an empty array `[]`

#### Scenario: Get suite returns response columns
- **WHEN** client calls `GET /api/v1/test-suites/{id}`
- **THEN** response SHALL include `responseColumns` array (empty if none defined)

### Requirement: Validate JSONata expressions on save

The service SHALL validate each response column's `expression` as syntactically valid JSONata when the suite is created or updated. Invalid expressions SHALL be rejected with HTTP 400.

Status: **Implemented**

#### Scenario: Valid expression
- **WHEN** suite is saved with `expression: "choices[0].message.content"`
- **THEN** system SHALL accept and persist the column

#### Scenario: Invalid expression
- **WHEN** suite is saved with `expression: "choices[0.message.content"` (syntax error)
- **THEN** system SHALL return HTTP 400 with a validation error message that includes both the array index and the column `name` (e.g. `responseColumns[0] ('answer').expression: Invalid JSONata expression: ...`)

#### Scenario: Duplicate column names
- **WHEN** suite is saved with two columns having the same `name`
- **THEN** system SHALL return HTTP 400

### Requirement: Response column name MUST NOT contain `::` (double colon)
The service SHALL reject TestSuite create and update requests in which any `responseColumns[i].name` contains the `::` (double-colon) sequence. The `::` sequence is reserved as the column-family separator in the evaluation summary CSV export; a single colon `:` is permitted in the name. Validation applies uniformly to `POST /api/v1/test-suites` and `PUT /api/v1/test-suites/{id}`. Pre-existing rows with `::`-bearing column names are NOT migrated; any subsequent update of such a suite SHALL fail validation until the column is renamed.

#### Scenario: Create rejected when response column name contains a double colon
- **WHEN** client calls `POST /api/v1/test-suites` with a `responseColumns` entry whose `name` is `"with::colon"`
- **THEN** system SHALL respond with HTTP 400, error code `VALIDATION_ERROR`, and a field-bound message identifying the offending entry

#### Scenario: Update rejected when response column name contains a double colon
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with a `responseColumns` entry whose `name` is `"with::colon"`
- **THEN** system SHALL respond with HTTP 400, error code `VALIDATION_ERROR`, and a field-bound message identifying the offending entry

#### Scenario: Create accepted when a response column name contains a single colon
- **WHEN** client calls `POST /api/v1/test-suites` with a `responseColumns` entry whose `name` is `"with:colon"` (single colon, no `::` sequence)
- **THEN** system SHALL persist the suite and respond with HTTP 201

### Requirement: Evaluate expressions at result write time

When a test case run result is stored, the system SHALL evaluate all of the suite's response column expressions against the result's `responseBody`, reconcile each result against the column's declared `type`, and persist the reconciled values.

Status: **Implemented**

#### Scenario: Successful extraction
- **WHEN** a result is written with a non-null `responseBody` and the suite has response columns
- **THEN** `extracted_columns` SHALL contain a key for each column `name` with the reconciled value
- **AND** `extraction_warnings` SHALL be empty for all columns whose JSONata result was reconcilable

#### Scenario: Expression evaluation failure
- **WHEN** a JSONata expression fails to evaluate (invalid expression, runtime error in the JSONata engine, etc.)
- **THEN** `extracted_columns[columnName]` SHALL be `null`
- **AND** `extraction_warnings` SHALL contain an entry with `column`, `expression`, and `error`

#### Scenario: Type-mismatch failure
- **WHEN** a JSONata expression evaluates successfully but the result cannot be reconciled to the column's declared `type` under the safe-coercion rules
- **THEN** `extracted_columns[columnName]` SHALL be `null`
- **AND** `extraction_warnings` SHALL contain an entry with `column`, `expression`, and `error` (formatted per the type-mismatch warning message format)

#### Scenario: Null response body
- **WHEN** `responseBody` is null (e.g., timeout, connection error)
- **THEN** all `extracted_columns` values SHALL be `null`
- **AND** `extraction_warnings` SHALL contain entries for each column

#### Scenario: No response columns defined
- **WHEN** a result is written and the suite has no response columns
- **THEN** `extracted_columns` SHALL be `{}` and `extraction_warnings` SHALL be `[]`

### Requirement: Reconcile JSONata extraction result against declared column type

When a JSONata expression is evaluated for a response column, the system SHALL reconcile the result against the column's declared `type` before persistence. Reconciliation either produces a value matching the declared type (with safe coercions applied silently) or records a type-mismatch failure via the existing extraction-warning mechanism.

Status: **Implemented**

**Implementation notes:** Reconciliation is performed by `service.domain.ResponseColumnTypeReconciler`. On unsafe mismatch it throws `service.domain.exception.TypeMismatchException`, which is caught by the existing per-column handler in `service.domain.ResponseColumnExtractor.extract()` and emitted as an `ExtractionWarningDto`. See `openspec/changes/enforce-response-column-types/design.md` for the full coercion table.

#### Scenario: ARRAY column, JSONata returns single match
- **WHEN** a column with `type: ARRAY` evaluates a JSONata expression that returns a single non-null scalar (JSONata's documented sequence-flattening behaviour)
- **THEN** `extracted_columns[columnName]` SHALL contain a JSON array with that single value as its only element
- **AND** `extraction_warnings` SHALL NOT contain an entry for that column

#### Scenario: ARRAY column, JSONata returns multiple matches
- **WHEN** a column with `type: ARRAY` evaluates an expression that returns two or more matches
- **THEN** `extracted_columns[columnName]` SHALL contain a JSON array with the matches in order
- **AND** `extraction_warnings` SHALL NOT contain an entry for that column

#### Scenario: ARRAY column, JSONata returns no matches
- **WHEN** a column with `type: ARRAY` evaluates an expression that returns zero matches (`undefined` in JSONata terms)
- **THEN** `extracted_columns[columnName]` SHALL be `null`
- **AND** `extraction_warnings` SHALL NOT contain an entry for that column

#### Scenario: STRING column, JSONata returns array
- **WHEN** a column with `type: STRING` evaluates an expression that returns a JSON array
- **THEN** `extracted_columns[columnName]` SHALL be `null`
- **AND** `extraction_warnings` SHALL contain an entry with `column`, `expression`, and `error` describing the type mismatch (`expected STRING, got ARRAY`)

#### Scenario: STRING column, JSONata returns object
- **WHEN** a column with `type: STRING` evaluates an expression that returns a JSON object
- **THEN** `extracted_columns[columnName]` SHALL be `null`
- **AND** `extraction_warnings` SHALL contain an entry indicating `expected STRING, got OBJECT`

#### Scenario: STRING column, JSONata returns number or boolean
- **WHEN** a column with `type: STRING` evaluates an expression that returns a number or boolean
- **THEN** `extracted_columns[columnName]` SHALL contain the value as a JSON string (silent coercion via `String.valueOf`)
- **AND** `extraction_warnings` SHALL NOT contain an entry for that column

#### Scenario: NUMBER column, JSONata returns parseable string
- **WHEN** a column with `type: NUMBER` evaluates an expression that returns a string parseable as a JSON number
- **THEN** `extracted_columns[columnName]` SHALL contain the parsed numeric value
- **AND** `extraction_warnings` SHALL NOT contain an entry for that column

#### Scenario: NUMBER column, JSONata returns non-parseable string
- **WHEN** a column with `type: NUMBER` evaluates an expression that returns a string that is not parseable as a number
- **THEN** `extracted_columns[columnName]` SHALL be `null`
- **AND** `extraction_warnings` SHALL contain an entry indicating the parse failure

#### Scenario: INTEGER column, JSONata returns whole-valued number
- **WHEN** a column with `type: INTEGER` evaluates an expression that returns a `Long`, `Integer`, or whole-valued `Double`
- **THEN** `extracted_columns[columnName]` SHALL contain the integer value
- **AND** `extraction_warnings` SHALL NOT contain an entry for that column

#### Scenario: INTEGER column, JSONata returns fractional number
- **WHEN** a column with `type: INTEGER` evaluates an expression that returns a fractional `Double`
- **THEN** `extracted_columns[columnName]` SHALL be `null`
- **AND** `extraction_warnings` SHALL contain an entry indicating `expected INTEGER, got NUMBER`

#### Scenario: BOOLEAN column, JSONata returns "true" or "false" string
- **WHEN** a column with `type: BOOLEAN` evaluates an expression that returns the string `"true"` or `"false"` (case-insensitive)
- **THEN** `extracted_columns[columnName]` SHALL contain the parsed boolean value
- **AND** `extraction_warnings` SHALL NOT contain an entry for that column

#### Scenario: OBJECT column, JSONata returns scalar
- **WHEN** a column with `type: OBJECT` evaluates an expression that returns a scalar (string, number, boolean) or array
- **THEN** `extracted_columns[columnName]` SHALL be `null`
- **AND** `extraction_warnings` SHALL contain an entry indicating the type mismatch

#### Scenario: FILE column behaves like STRING
- **WHEN** a column with `type: FILE` is reconciled
- **THEN** the result SHALL be reconciled using the same rules as `type: STRING` (FILE is a display hint over a string-typed file reference; multi-file scenarios are expressed via `type: ARRAY`)
- **AND** a single JSONata match SHALL NOT be auto-wrapped into a list — users requiring multi-file metric inputs MUST declare the column as `type: ARRAY` rather than `type: FILE`

#### Scenario: Declared type is null
- **WHEN** a column has no declared `type` (legacy or malformed data)
- **THEN** the JSONata result SHALL be persisted as-is without reconciliation, preserving today's behaviour for unspecified columns
- **AND** `extraction_warnings` SHALL NOT contain an entry for that column on the basis of type alone

### Requirement: Type-mismatch warning message format

When reconciliation fails and an extraction warning is recorded, the warning's `error` field SHALL identify the declared type, the actual JSON type observed, and (when feasible) a truncated representation of the offending value.

Status: **Implemented**

**Implementation notes:** `TypeMismatchException` constructs the message; `ExtractionWarningDto.error` carries it verbatim into stored data, the run-result API, and CSV exports.

#### Scenario: Mismatch without value preview
- **WHEN** a STRING column receives an array result
- **THEN** the warning's `error` SHALL be exactly `Type mismatch: expected STRING, got ARRAY`

#### Scenario: Mismatch with value preview
- **WHEN** an INTEGER column receives a non-parseable string `"abc"`
- **THEN** the warning's `error` SHALL include the actual value (truncated to at most 80 characters when needed) — for example `Type mismatch: expected INTEGER, got STRING ("abc") — not parseable as integer`

### Requirement: displayName on testCaseSchema fields

`FieldDefinitionDto` SHALL support an optional `displayName` field for UI label purposes.

Status: **Implemented**

#### Scenario: Field with displayName
- **WHEN** testCaseSchema includes `{"name": "q", "displayName": "Question", "type": "STRING"}`
- **THEN** system SHALL persist and return the displayName

#### Scenario: Field without displayName
- **WHEN** testCaseSchema includes `{"name": "q", "type": "STRING"}` (no displayName)
- **THEN** system SHALL persist it as-is; FE uses `name` as display label

### Requirement: FILE type in response column definitions

The `FILE` value of `SchemaFieldType` SHALL be an officially supported type for `ResponseColumnDefinitionDto.type`. A response column with `type: FILE` indicates that the JSONata expression extracts a DIAL file reference path (e.g., `"files/@myapp/results/output.pdf"`) from the response body.

Semantics in this phase: **display hint only**. The extracted value is stored as a string in `extracted_columns` (identical to `STRING` storage). Clients (FE) SHALL use `type: FILE` to render the extracted value as a clickable/downloadable link rather than plain text. No backend extraction, validation, or download behaviour changes.

The default type (when omitted or null in the DTO) remains `STRING`. `FILE` type is **not** auto-inferred from the extracted value — it must be explicitly set on the column definition.

Status: **Implemented**

#### Scenario: Response column with FILE type accepted on create
- **WHEN** client calls `POST /api/v1/test-suites` with a response column `{"name": "result_file", "expression": "output.path", "type": "FILE"}`
- **THEN** system SHALL persist the column definition and return it in the response with `type: "FILE"`

#### Scenario: FILE type column value stored as string
- **WHEN** a test case run result is written and the suite has a `FILE`-typed response column whose JSONata expression resolves to `"files/@myapp/results/out.pdf"`
- **THEN** `extracted_columns["result_file"]` SHALL be stored as the string `"files/@myapp/results/out.pdf"` (identical to STRING storage)

#### Scenario: FILE type is a display hint — no backend validation of value format
- **WHEN** a `FILE`-typed response column's JSONata expression resolves to a value that does not match the DIAL file path format
- **THEN** system SHALL store the value as-is without emitting an extraction warning for format mismatch
- **AND** the extraction proceeds normally (warnings are only emitted for JSONata evaluation failures, not for value format)

#### Scenario: FILE type column with extraction failure
- **WHEN** a `FILE`-typed response column's JSONata expression fails to evaluate (path not found, etc.)
- **THEN** `extracted_columns["result_file"]` SHALL be `null` and `extraction_warnings` SHALL contain an entry with `column`, `expression`, and `error` — identical to the behaviour for all other types

### Requirement: MCP response extraction paths

Response column JSONata expressions SHALL work against the serialized MCP response envelope structure. The system SHALL NOT impose any format restrictions on JSONata expressions — users may target any path in the serialized response JSON.
Status: **Implemented**

#### Scenario: Extract isError flag
- **WHEN** a response column has `expression: "isError"` and the MCP response has `isError = true`
- **THEN** `extracted_columns["error_flag"]` SHALL be `true`

#### Scenario: Extract first text content block
- **WHEN** a response column has `expression: "content[0].text"` and the MCP response has `content: [{"type": "text", "text": "Hello"}]`
- **THEN** `extracted_columns["response_text"]` SHALL be `"Hello"`

#### Scenario: Extract from structuredContent
- **WHEN** a response column has `expression: "structuredContent.score"` and the MCP response has `structuredContent: {"score": 0.95}`
- **THEN** `extracted_columns["score"]` SHALL be `0.95`

#### Scenario: Count content blocks
- **WHEN** a response column has `expression: "$count(content)"` and the MCP response has 3 content blocks
- **THEN** `extracted_columns["block_count"]` SHALL be `3`

#### Scenario: Extract from specific content type
- **WHEN** a response column has `expression: "content[type='text'][0].text"`
- **THEN** the extraction SHALL return the text of the first text-type content block (skipping image/audio blocks)

#### Scenario: structuredContent absent
- **WHEN** a response column targets `structuredContent.field` but the MCP response has no `structuredContent`
- **THEN** `extracted_columns["field"]` SHALL be `null` and `extraction_warnings` SHALL contain a path-not-found warning

### Requirement: SSE response body envelope for JSONata extraction

When a test suite targets a non-OpenAI SSE endpoint, the evaluation engine stores the response body as a `{"events": [...]}` envelope. JSONata expressions SHALL target this envelope structure.

Status: **Implemented**

**SSE envelope shape** (stored in `response_body` / `eval_summaries.response_body`):
```json
{
  "events": [
    { "event": "message", "data": { "status": "processing" } },
    { "event": "result",  "data": { "output": "Paris", "confidence": 0.95 } }
  ]
}
```

Each element in `events` has:
- `event` — the SSE event type name (e.g., `"message"`, `"result"`, or any named event)
- `data` — the parsed JSON payload, or a raw string if the payload was not valid JSON

**OpenAI mode (unchanged)**: When the SSE stream uses OpenAI chat-completions format (`choices[].delta.content`), the engine assembles a standard non-streaming response. JSONata expressions for OpenAI streams use the same paths as before (e.g., `choices[0].message.content`).

#### Scenario: Extract last result from non-OpenAI SSE stream
- **WHEN** response body is `{"events":[{"event":"start","data":{}},{"event":"result","data":{"output":"Paris"}}]}`
- **AND** response column has `expression: "events[-1].data.output"`
- **THEN** `extracted_columns["answer"]` SHALL be `"Paris"`

#### Scenario: Extract first event's data field
- **WHEN** response body has events array and expression is `"events[0].data.status"`
- **THEN** extraction returns the `status` field of the first event's data

#### Scenario: Count events in SSE stream
- **WHEN** expression is `"$count(events)"`
- **THEN** extraction returns the total number of SSE events in the stream

#### Scenario: Filter by event type
- **WHEN** expression is `"events[event='result'][0].data.score"`
- **THEN** extraction returns the `score` field from the first event whose type is `"result"`

#### Scenario: Non-JSON data payload stored as string
- **WHEN** an SSE event has a non-JSON data payload (e.g., plain text)
- **THEN** `events[i].data` SHALL be a string; JSONata string functions (e.g., `$string()`, `$contains()`) may be used to access it

### Requirement: MCP response columns work with existing validation

The existing JSONata expression validation (syntax check on suite save) SHALL work for MCP-targeting expressions — JSONata syntax is format-agnostic.
Status: **Implemented**

#### Scenario: Valid MCP-targeting expression accepted
- **WHEN** suite is saved with `expression: "content[0].text"`
- **THEN** system SHALL accept and persist the column (JSONata syntax is valid regardless of expected response shape)

#### Scenario: Complex MCP expression accepted
- **WHEN** suite is saved with `expression: "$count(content[type='text'])"` or `expression: "structuredContent.results[score > 0.8]"`
- **THEN** system SHALL accept (valid JSONata syntax)

### Requirement: Request/response frame for response column extraction

When evaluating a response column's JSONata expression, the service SHALL provide a `Frame` binding `$request` (the parsed JSON of the request body actually sent) and `$response` (the parsed JSON of the response body) in addition to the existing evaluation. The root evaluation document remains the raw response body, unchanged — `$request`/`$response` are additive frame variables; no existing response column expression's meaning changes. This applies uniformly to single-turn suites, multi-turn suites (each turn's own request/response), and MCP suites.

Status: **Implemented**

#### Scenario: Existing expression unaffected
- **WHEN** a response column has `expression: "choices[0].message.content"` (no reference to `$request`/`$response`)
- **THEN** extraction behaves exactly as before this change — evaluated against the response body as the root document

#### Scenario: Expression references $response explicitly
- **WHEN** a response column has `expression: "$response.choices[0].message.content"`
- **THEN** extraction SHALL produce the identical result as the equivalent root-document expression `"choices[0].message.content"`, since `$response` is bound to the same parsed response body

#### Scenario: Expression correlates request and response
- **WHEN** a response column has `expression: "$response.result = $request.expected"`
- **THEN** extraction SHALL evaluate the expression with both `$request` and `$response` populated from the turn's actual sent request body and received response body

#### Scenario: Multi-turn extraction uses that turn's own request/response
- **WHEN** a multi-turn case's turn k is extracted
- **THEN** `$request` and `$response` are bound to turn k's own resolved request body and received response body, not an earlier or later turn's

### Requirement: Reserved response column names

A response column's `name` SHALL NOT collide with a JSONata built-in function name (a hand-maintained `JsonataReservedNames` constants list, distinct from the query-DSL function registry used by the query DSL subsystem) or with the reserved frame variable names `request` or `response`. Suite create/update SHALL reject a colliding name with HTTP 400. This is independent of, and in addition to, the existing `::`-sequence name restriction and the existing per-suite name-uniqueness check.

Status: **Implemented**

#### Scenario: Response column name collides with a JSONata built-in function name
- **WHEN** client saves a suite with a response column named `"count"` (a JSONata built-in function name)
- **THEN** system SHALL respond with HTTP 400, error code `VALIDATION_ERROR`, identifying the offending column

#### Scenario: Response column name collides with the reserved request/response frame names
- **WHEN** client saves a suite with a response column named `"request"` or `"response"`
- **THEN** system SHALL respond with HTTP 400, error code `VALIDATION_ERROR`, identifying the offending column

#### Scenario: Non-colliding name accepted
- **WHEN** client saves a suite with a response column named `"answer"` (not a JSONata function name, not `request`/`response`)
- **THEN** system SHALL accept and persist the column, unaffected by this requirement

### Requirement: Failed-extraction frame binding uses explicit null, not undefined (F2)

When a response column's extraction genuinely fails (JSONata evaluation error or type-mismatch reconciliation failure) and that column's value is subsequently bound as a request-template frame variable for the next turn (per the `multi-turn-test-case` frame-driven turn-loop requirement), the service SHALL bind the JSONata explicit-null sentinel for that variable — not a plain Java `null` — so that a downstream expression such as `$append($historyColumn, [...])` observes null-append semantics (the prior value is treated as a present-but-null entry) rather than undefined-append semantics (as if the column had never been extracted). Binding a plain Java `null` is observably indistinguishable, at the JSONata level, from leaving the variable unbound entirely — both cause `$append`'s undefined-argument short-circuit — so a plain Java `null` binding MUST NOT be used to represent "this column's value is JSON null."

Status: **Implemented**

#### Scenario: Failed extraction feeds a real null into the next turn's frame
- **WHEN** turn k's extraction of a response column named `answer` fails (evaluation error or type mismatch), and turn k+1's request template references `$answer` inside `$append($answer, [...])`
- **THEN** turn k+1's evaluation observes null-append semantics — the result includes an explicit `null` entry for turn k's contribution, not merely the new array

#### Scenario: Unextracted (never-configured) column is genuinely unbound
- **WHEN** a response column named `context` is not configured for the suite at all
- **THEN** turn 1's reference to `$context` is unbound (undefined), producing undefined-append semantics — distinct from the "configured but failed" case above

## Validation Rules

**Response columns array:**

| Constraint          | Rules |
|---------------------|-------|
| `responseColumns`   | Max 50 elements per suite |

**Response column definition (each element):**

| Field        | Rules |
|--------------|-------|
| `name`       | Required, not blank, max 255 characters, unique within the array |
| `displayName`| Optional, max 255 characters |
| `expression` | Required, not blank, max 2000 characters, must be valid JSONata |
| `type`       | Optional; one of `SchemaFieldType` values; null in DTO is normalized to `STRING` by `TestSuiteService.normalizeRequest()` |

**testCaseSchema field (modified):**

| Field        | Rules |
|--------------|-------|
| `displayName`| Optional (new), max 255 characters |
| _(other fields unchanged)_ | |

## Implementation Notes

- **Meta DB migration** `V1.8__AddResponseColumnsToTestSuites.sql`:
  - `ALTER TABLE test_suites ADD COLUMN response_columns JSONB NOT NULL DEFAULT '[]'::jsonb;`
- **Analytics DB migration** `V1.2__AddExtractedColumnsToTestCaseRunResults.sql`:
  - `ALTER TABLE test_case_run_results ADD COLUMN extracted_columns JSONB NOT NULL DEFAULT '{}'::jsonb;`
  - `ALTER TABLE test_case_run_results ADD COLUMN extraction_warnings JSONB NOT NULL DEFAULT '[]'::jsonb;`
- **New DTO**: `ResponseColumnDefinitionDto` in `service.domain.dto` — `name`, `displayName`, `expression`, `type` (SchemaFieldType).
- **Modified DTO**: `FieldDefinitionDto` — add `displayName` (String, optional, `@Size(max=255)`).
- **Modified DTOs**: `TestSuiteRequestDto`, `TestSuiteResponseDto` — add `List<ResponseColumnDefinitionDto> responseColumns`.
- **Modified model**: `TestSuite` — add `String responseColumns` (JSONB string).
- **Modified model**: `TestCaseRunResult` — add `String extractedColumns`, `String extractionWarnings`.
- **New component**: `JsonataEvaluationService` in `service.domain` — validates expressions (parse-only), evaluates expressions against JSON data.
- **Modified service**: `TestSuiteService.validateTestSuiteSchemas()` — add JSONata validation for response columns.
- **Modified service**: Result write path — evaluate response columns after building result. Extraction is a **job-layer concern**: `MockResultsGenerator` (and future real runners) load the suite's `responseColumns` from meta DB and evaluate expressions before calling the analytics batch writer. The analytics batch write API (`POST /api/v1/analytics/results`) is a pure persistence endpoint and does NOT trigger extraction — external callers must pre-populate `extractedColumns`/`extractionWarnings` or accept empty defaults.
- **JSONB serialization**: Uses existing `JsonbMapper` pattern for responseColumns. Uses `ValidationWarningsSerializer` pattern for extraction warnings.
- **JSONata library**: `com.dashjoin:jsonata:0.9.9` — 100% reference test coverage, zero extra transitive dependencies. `DashjoinJsonataEvaluationService` is the only class that imports from `com.dashjoin.jsonata`; it also owns the `$request`/`$response` 3-arg frame-evaluate overload and the `Jsonata.NULL_VALUE` frame-binding logic for the request-template frame (F2) — a narrow, explicit exception to (not a repeal of) the "only importer" invariant, since both live in the same class.
- `constants.JsonataReservedNames` (new constants class) is the single source for both the built-in-function-name set (a hand-maintained list of JSONata built-in function names, unrelated to the query-DSL function registry — which enumerates SQL/jOOQ functions for a different subsystem) and the `request`/`response` reserved names; `TestSuiteRequestValidator` consumes it for the reserved-name 400 check.
- **Transaction**: Response columns are part of `test_suites` — uses `@Transactional("metaTransactionManager")`. Extraction happens in the analytics write path — uses `@Transactional("analyticsTransactionManager")`.

## Deferred

- Re-evaluation of historical results when expressions change.
- Eval results CSV export with extracted columns.
- Unified schema at suite level (merging testCaseSchema + responseColumns).
- Import/export of response columns as part of suite CSV import (suite JSON export includes them naturally).
