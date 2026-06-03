## ADDED Requirements

### Requirement: Reconcile JSONata extraction result against declared column type

When a JSONata expression is evaluated for a response column, the system SHALL reconcile the result against the column's declared `type` before persistence. Reconciliation either produces a value matching the declared type (with safe coercions applied silently) or records a type-mismatch failure via the existing extraction-warning mechanism.

Status: **Planned**

**Implementation notes:** Reconciliation is performed by `service.domain.ResponseColumnTypeReconciler` (new). On unsafe mismatch it throws `service.domain.exception.TypeMismatchException`, which is caught by the existing per-column handler in `service.domain.ResponseColumnExtractor.extract()` and emitted as an `ExtractionWarningDto`. See `openspec/changes/enforce-response-column-types/design.md` for the full coercion table.

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

Status: **Planned**

**Implementation notes:** `TypeMismatchException` constructs the message; `ExtractionWarningDto.error` carries it verbatim into stored data, the run-result API, and CSV exports.

#### Scenario: Mismatch without value preview
- **WHEN** a STRING column receives an array result
- **THEN** the warning's `error` SHALL be exactly `Type mismatch: expected STRING, got ARRAY`

#### Scenario: Mismatch with value preview
- **WHEN** an INTEGER column receives a non-parseable string `"abc"`
- **THEN** the warning's `error` SHALL include the actual value (truncated to at most 80 characters when needed) — for example `Type mismatch: expected INTEGER, got STRING ("abc") — not parseable as integer`

## MODIFIED Requirements

### Requirement: Evaluate expressions at result write time

When a test case run result is stored, the system SHALL evaluate all of the suite's response column expressions against the result's `responseBody`, reconcile each result against the column's declared `type`, and persist the reconciled values.

Status: **Implemented** (extraction); reconciliation is **Planned** under this change.

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
