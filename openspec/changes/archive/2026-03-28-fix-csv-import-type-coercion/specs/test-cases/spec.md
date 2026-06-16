## MODIFIED Requirements

### Requirement: CSV bulk upload with schema detection
**Status: Planned**
The service SHALL allow bulk uploading TestCases via CSV. All columns map to the unified `data` map. Column names match `testCaseSchema` field names. Schema auto-detection, persistence, and replacement behavior depend on the `importMode` parameter. In OVERRIDE mode, schema is always replaced from CSV. In APPEND mode, schema is only auto-detected when the suite schema is empty. In MERGE mode, new CSV columns are merged into the existing schema.

Cell values for ARRAY- and OBJECT-typed fields SHALL be stored as structured JSON values (not strings) when the existing schema specifies those types. When there is no schema (empty schema) or the field has no schema type (e.g. a new column in MERGE mode), cell values that are valid JSON arrays or objects SHALL also be parsed and stored as structured values rather than raw strings.

`CsvCellParser` SHALL NOT treat `"1"` and `"0"` as boolean literals. Only literal `"true"`/`"false"` (case-insensitive) SHALL be parsed as booleans. `"1"` and `"0"` SHALL be parsed as integers. `CsvCellParser` SHALL use `Long.parseLong()` (not `Integer.parseInt()`) for integer parsing, aligning with the Type System Reference (`INTEGER → Long`) and supporting values that exceed `Integer.MAX_VALUE`. This ensures `String.valueOf()` is lossless for all parser outputs.

When a non-null schema type is known for a column, the system SHALL coerce parsed cell values to match the declared schema type before storage. Specifically:
- If schema type is `STRING`, numeric and boolean cell values SHALL be converted to string via `String.valueOf(value)`.
- If schema type is `INTEGER`, string and floating-point cell values that represent valid integers SHALL be converted to `Long` via `Long.parseLong()` or `Number.longValue()`.
- If schema type is `NUMBER`, string and integer cell values that represent valid numbers SHALL be converted to floating-point numbers.
- If schema type is `BOOLEAN`, string cell values (`"true"`, `"false"` case-insensitive) and integer cell values SHALL be converted to booleans (`!= 0` → true, `0` → false).
- If coercion fails (e.g., non-numeric string for INTEGER schema), the original parsed value SHALL be stored unchanged.

When schema is auto-detected or changed during import (OVERRIDE, APPEND with empty schema, MERGE with new columns), the system SHALL perform a post-persist fixup pass after schema is finalized: re-read all suite test cases in batches, coerce values for columns whose schema type was newly determined, re-validate, and batch-update any changed rows. This ensures type correctness even when schema is undefined at import start.

#### Scenario: Import maps all columns to data
- **WHEN** CSV header has column names matching `testCaseSchema` fields
- **THEN** system SHALL map all non-testCaseName, non-enabled columns to `data`

#### Scenario: Column name not in schema is discarded (APPEND mode only)
- **WHEN** client imports with `importMode=APPEND`, the suite `testCaseSchema` is non-empty, and a CSV column name does not match any schema field
- **THEN** system SHALL discard that column's values and NOT store them in `data`; no validation warning is added for the unknown column itself
- **Note:** This filtering applies only to `APPEND` mode with a non-empty schema. In `OVERRIDE` mode all CSV data columns are stored (schema is replaced). In `MERGE` mode all CSV data columns are stored (all columns end up in the merged schema — either existing or newly added).

#### Scenario: Auto-detect schema from CSV (no existing schema)
- **WHEN** `testCaseSchema` is empty and CSV is imported (any mode)
- **THEN** system SHALL auto-detect field definitions from CSV columns: all headers except reserved names (`testCaseName`, `enabled`) become `FieldDefinitionDto` entries with `required: false` and `description: null`; schema field order follows CSV column order (left to right)

#### Scenario: Auto-detect type inference
- **WHEN** system auto-detects schema from CSV
- **THEN** system SHALL scan all row values per column and infer type: all non-empty values parse as JSON objects → OBJECT; JSON arrays → ARRAY; literal `true`/`false` (case-insensitive, NOT `1`/`0`) → BOOLEAN; whole numbers (including `1`/`0`) → INTEGER; decimal numbers → NUMBER; otherwise → STRING

#### Scenario: Auto-detected schema is persisted
- **WHEN** CSV import commits and schema auto-detection or merge occurs
- **THEN** system SHALL persist the new or merged schema to the TestSuite's `testCaseSchema` and bump `version`; no `inputBindings` are auto-created

#### Scenario: Auto-detected schema in preview
- **WHEN** client calls the CSV import preview endpoint and `testCaseSchema` would be auto-detected or schema would be replaced/merged
- **THEN** the preview response SHALL include `autoDetectedSchema` reflecting what would change

#### Scenario: CSV with only reserved columns
- **WHEN** CSV has only `testCaseName` (and optionally `enabled`) with no data columns, and `testCaseSchema` is empty
- **THEN** schema stays empty (no fields to auto-detect)

#### Scenario: JSON array cell stored as array when schema is empty
- **WHEN** a CSV cell value is a valid JSON array (e.g. `["a","b"]`) and there is no existing schema (OVERRIDE mode or empty schema in APPEND mode)
- **THEN** system SHALL parse the cell value and store it in `data` as a JSON array, not as a raw string

#### Scenario: JSON array cell stored as array when schema has no type for that field
- **WHEN** a CSV cell value is a valid JSON array and the column is a new field being added in MERGE mode (no existing schema type for this field)
- **THEN** system SHALL parse the cell value and store it as a JSON array; the auto-detected schema type for that field SHALL be ARRAY

#### Scenario: JSON object cell stored as object when no schema type known
- **WHEN** a CSV cell value is a valid JSON object (e.g. `{"key":"value"}`) and there is no schema type known for that column (empty schema or new MERGE column)
- **THEN** system SHALL parse and store it as a JSON object; auto-detected schema type SHALL be OBJECT

#### Scenario: Invalid JSON in array-looking cell falls back to string
- **WHEN** a CSV cell value starts with `[` or `{` but is not valid JSON
- **THEN** system SHALL store the raw cell string value unchanged; the auto-detected schema type SHALL be STRING

#### Scenario: Preview sample rows reflect coerced values
- **WHEN** client calls the CSV import preview endpoint and the suite has an existing `testCaseSchema` with declared field types
- **THEN** the preview sample rows SHALL reflect coerced values (e.g., a STRING-typed column with CSV value `1865` will show `"1865"` in preview, not `1865`)
- **Note:** `parseRow()` is shared between preview and import. Schema type auto-detection (inference) in preview is unaffected — only per-cell value coercion applies when a schema type is known.

#### Scenario: Numeric cell coerced to string when schema type is STRING
- **WHEN** a CSV cell value is `1865` (parsed as Long by heuristic) and the schema declares the column type as `STRING`
- **THEN** system SHALL coerce the value to String `"1865"` before storage; JSONB SHALL contain `"1865"` (JSON string), not `1865` (JSON number)

#### Scenario: Decimal cell coerced to string when schema type is STRING
- **WHEN** a CSV cell value is `3.14` (parsed as Double by heuristic) and the schema declares the column type as `STRING`
- **THEN** system SHALL coerce the value to String `"3.14"` before storage

#### Scenario: Boolean-like cell coerced to string when schema type is STRING
- **WHEN** a CSV cell value is `true` (parsed as Boolean by heuristic) and the schema declares the column type as `STRING`
- **THEN** system SHALL coerce the value to String `"true"` before storage

#### Scenario: Numeric "1" and "0" coerced to string when schema type is STRING
- **WHEN** a CSV cell value is `1` or `0` (parsed as Long after CsvCellParser fix) and the schema declares the column type as `STRING`
- **THEN** system SHALL coerce the value to String `"1"` or `"0"` before storage via `String.valueOf(value)`
- **Note:** After the CsvCellParser fix, `1`/`0` are no longer parsed as booleans — they are `Long(1)` / `Long(0)`. `String.valueOf(Long(1))` = `"1"`, which is lossless.

#### Scenario: String value coerced to Long when schema type is INTEGER
- **WHEN** a CSV cell value is a valid integer string (e.g., parsed as String in an edge case, or a string from the JSONB round-trip) and the schema declares `INTEGER`
- **THEN** system SHALL attempt `Long.parseLong()` and store the Long value

#### Scenario: Large integer string coerced to Long when schema type is INTEGER
- **WHEN** a CSV cell value is `3000000000` (exceeds `Integer.MAX_VALUE`, parsed as Long by heuristic) and the schema declares `INTEGER`
- **THEN** system SHALL store as `Long(3000000000)` (no-op — already correct type)
- **Note:** This scenario would silently fail with `Integer.parseInt()`. Using `Long.parseLong()` ensures values up to `Long.MAX_VALUE` are handled correctly.

#### Scenario: Whole double value coerced to Long when schema type is INTEGER
- **WHEN** a CSV cell value is `3.0` (parsed as Double, no fractional part) and the schema declares `INTEGER`
- **THEN** system SHALL coerce to Long `3`

#### Scenario: Fractional double value not coerced to integer (coercion failure)
- **WHEN** a CSV cell value is `3.14` (parsed as Double, has fractional part) and the schema declares `INTEGER`
- **THEN** system SHALL NOT truncate to Long `3`; the original Double value SHALL be stored unchanged and downstream validation will catch the type mismatch
- **Note:** Silent truncation of `3.14` to `3` would lose data. Only whole-number Doubles (where `value % 1 == 0`) are coerced to Long.

#### Scenario: Negative integer coerced correctly when schema type is INTEGER
- **WHEN** a CSV cell value is `-42` (parsed as Long by heuristic) and the schema declares `INTEGER`
- **THEN** system SHALL store as Long `-42` (no-op — already correct type)

#### Scenario: CSV "1"/"0" stored as Long when schema type is INTEGER
- **WHEN** a CSV cell value is `1` or `0` (parsed as Long after CsvCellParser fix) and the schema declares the column type as `INTEGER`
- **THEN** system SHALL store as Long `1` / Long `0` (no-op — already correct type)
- **Note:** After the CsvCellParser fix, `1`/`0` are parsed as `Long`, matching the `INTEGER` schema type directly.

#### Scenario: Boolean value coerced to Long when schema type is INTEGER
- **WHEN** a data value is `Boolean(true)` or `Boolean(false)` (e.g., from literal `"true"`/`"false"` in CSV) and the schema declares `INTEGER`
- **THEN** system SHALL coerce `true` to Long `1` and `false` to Long `0`

#### Scenario: Boolean value coerced to number when schema type is NUMBER
- **WHEN** a data value is `Boolean(true)` or `Boolean(false)` (e.g., from literal `"true"`/`"false"` in CSV) and the schema declares `NUMBER`
- **THEN** system SHALL coerce `true` to Double `1.0` and `false` to Double `0.0`

#### Scenario: String value coerced to number when schema type is NUMBER
- **WHEN** a CSV cell value is a valid numeric string and the schema declares `NUMBER`
- **THEN** system SHALL attempt `Double.parseDouble()` and store the number value

#### Scenario: Integer value promoted to double when schema type is NUMBER
- **WHEN** a CSV cell value is `42` (parsed as Long) and the schema declares `NUMBER`
- **THEN** system SHALL coerce to Double `42.0`

#### Scenario: Integer coerced to boolean when schema type is BOOLEAN
- **WHEN** a CSV cell value is `1` or `0` (parsed as Long after CsvCellParser fix) and the schema declares `BOOLEAN`
- **THEN** system SHALL coerce Long to Boolean: `!= 0` → `true`, `0` → `false`

#### Scenario: String coerced to boolean when schema type is BOOLEAN
- **WHEN** a CSV cell value is `"true"` or `"false"` (as String, not caught by heuristic — edge case) and the schema declares `BOOLEAN`
- **THEN** system SHALL coerce to the corresponding Boolean value

#### Scenario: Double value not coerced to boolean (coercion failure)
- **WHEN** a CSV cell value is a Double (e.g., `3.14` or `1.0`) and the schema declares `BOOLEAN`
- **THEN** system SHALL NOT coerce Double to Boolean; the original Double value SHALL be stored unchanged and downstream validation will catch the type mismatch
- **Note:** Unlike `INTEGER ← Double` which coerces whole-number Doubles, `BOOLEAN ← Double` always fails because floating-point truthiness is ambiguous. Integer-to-boolean coercion (`!= 0`) is well-defined; Double-to-boolean is not.

#### Scenario: Empty string not coerced to non-STRING types (coercion failure)
- **WHEN** a CSV cell is blank (parsed as empty string `""` by CsvCellParser) and the schema declares a non-STRING type (`INTEGER`, `NUMBER`, `BOOLEAN`)
- **THEN** system SHALL NOT coerce the empty string; the original empty string SHALL be stored unchanged and downstream validation will catch the type mismatch
- **Note:** `Long.parseLong("")`, `Double.parseDouble("")`, and boolean parsing all fail for empty strings. For STRING-typed columns, the empty string is stored as-is (no-op).

#### Scenario: Coercion failure preserves original value
- **WHEN** a CSV cell value cannot be coerced to the declared schema type (e.g., value is `"hello"` but schema declares `INTEGER`)
- **THEN** system SHALL store the original parsed value unchanged; downstream validation will catch the type mismatch

#### Scenario: No inline coercion when schema type is unknown
- **WHEN** a column has no declared schema type during parseRow (null in fieldTypes map — e.g., empty schema in APPEND mode)
- **THEN** system SHALL store the heuristic-parsed value initially; the post-persist fixup pass will coerce after schema is finalized

#### Scenario: Post-persist fixup corrects types after schema auto-detection
- **WHEN** CSV is imported with an empty schema (or OVERRIDE mode) and schema is auto-detected from CSV content
- **THEN** after schema is persisted, the system SHALL re-read all suite test cases in batches, coerce values for columns with newly determined types using `SchemaTypeCoercer`, re-validate each test case, and batch-update any rows whose data changed
- **Note:** This ensures type correctness even for rows stored before the final schema type was known (e.g., a column widened from INTEGER to STRING during import).

#### Scenario: Post-persist fixup for MERGE mode new columns
- **WHEN** CSV is imported in MERGE mode and new columns are added to the existing schema
- **THEN** the fixup pass SHALL coerce values only for the newly added columns; existing columns (already in schema before import) are not re-coerced

#### Scenario: No fixup when schema is unchanged
- **WHEN** CSV is imported in APPEND mode with an existing non-empty schema
- **THEN** the system SHALL NOT run the fixup pass (inline coercion in parseRow already handled all columns)

#### Scenario: FILE-typed column coerced to string
- **WHEN** a CSV cell value is in a column declared as `FILE` type and the parsed value is not already a String (e.g., Long or Boolean)
- **THEN** system SHALL coerce the value to String via `String.valueOf(value)` (file references are always strings, same coercion as STRING type)
- **Note:** If the parsed value is already a String, it is stored as-is (no-op).

### Requirement: Validate TestCases against schema, template, and bindings (Soft Validation)
**Status: Planned**
The system SHALL validate test case data against the effective template, bindings, and schema. Validation is soft — test cases are always saved, but `isValid` is set to `false` and `validationWarnings` are populated when issues are found. Validation runs on all write paths (create, update, patch, batch operations, CSV import).

In addition to existing checks (REQUIRED, ADDITIONAL, UNKNOWN, FILE format), the system SHALL check that each data field's value type is compatible with the declared schema field type. When a mismatch is detected, the system SHALL emit a `TYPE` validation warning.

**Note:** The baseline spec already defines a "Type mismatch in data" scenario (set valid=false and add a warning). This change expands that scenario with detailed type compatibility rules, specific per-type scenarios, and interaction with CSV coercion. The baseline scenario was previously unimplemented.

#### Scenario: Type mismatch in data
- **WHEN** a `data` value does not match the `testCaseSchema` field's declared `type`
- **THEN** system SHALL set valid=false and add a validation warning with `fieldName` and expected type

#### Scenario: Type mismatch warning for STRING field with numeric value
- **WHEN** a test case data field contains a numeric value (Integer, Long, or Double) and the schema declares the field type as `STRING`
- **THEN** system SHALL emit a `TYPE` validation warning with message indicating the expected and actual types
- **AND** the test case SHALL still be saved with `isValid=false`

#### Scenario: Type mismatch warning for STRING field with boolean value
- **WHEN** a test case data field contains a Boolean value and the schema declares the field type as `STRING`
- **THEN** system SHALL emit a `TYPE` validation warning

#### Scenario: Type mismatch warning for INTEGER field with string value
- **WHEN** a test case data field contains a String value and the schema declares the field type as `INTEGER`
- **THEN** system SHALL emit a `TYPE` validation warning

#### Scenario: Type mismatch warning for BOOLEAN field with non-boolean value
- **WHEN** a test case data field contains a String, Double, Integer, or Long value and the schema declares the field type as `BOOLEAN`
- **THEN** system SHALL emit a `TYPE` validation warning
- **Note:** On the CSV import path, `BOOLEAN ← Long` is coerced successfully before validation runs, so Long values in BOOLEAN columns will not produce a TYPE warning after CSV import. On the API path (POST/PUT/PATCH), no coercion runs — Integer, Long, and Double values in BOOLEAN columns will produce TYPE warnings.

#### Scenario: NUMBER field accepts integer values without warning
- **WHEN** a test case data field contains an Integer or Long value and the schema declares the field type as `NUMBER`
- **THEN** system SHALL NOT emit a TYPE warning (integers are valid numbers)

#### Scenario: No type warning for null values
- **WHEN** a test case data field is null and the schema declares any type
- **THEN** system SHALL NOT emit a TYPE warning for null (nulls are handled by the existing REQUIRED check)

#### Scenario: No type warning when schema type is unknown
- **WHEN** a data field has no corresponding schema field definition
- **THEN** system SHALL NOT emit a TYPE warning (the existing ADDITIONAL/UNKNOWN check handles this)

#### Scenario: CSV import with coercion produces no type warnings
- **WHEN** a CSV is imported and the SchemaTypeCoercer successfully coerces values to match schema types
- **THEN** validation SHALL NOT emit TYPE warnings for those fields (types already match after coercion)

#### Scenario: API create with type mismatch produces warning
- **WHEN** a client creates a test case via POST API with `{"data": {"answer": 1865}}` and schema declares `answer` as `STRING`
- **THEN** system SHALL save the test case with `isValid=false` and a TYPE warning for field `answer`

#### Scenario: Coercion failure produces type mismatch warning
- **WHEN** a CSV cell value is `"hello"` (parsed as String) and the schema declares `INTEGER`
- **THEN** system SHALL store the original String value `"hello"` unchanged (coercion failure)
- **AND** validation SHALL emit a `TYPE` warning for the field (`isValid=false`)
- **Note:** Coercion failure and type mismatch warning are two sides of the same coin — the coercer returns the value unchanged, then the validation layer detects the mismatch.

#### Scenario: Mixed-type auto-detected column widens to STRING without warnings
- **WHEN** CSV is imported with empty schema and a column has values `42`, `99`, `hello` across rows
- **THEN** auto-detection SHALL widen the column type to `STRING` (INTEGER + STRING → STRING)
- **AND** the post-persist fixup pass SHALL coerce the integer values `42` and `99` to strings `"42"` and `"99"`
- **AND** all test cases SHALL have `isValid=true` for that field (all values are strings matching STRING type)

#### Scenario: Schema-declared INTEGER column with non-integer value during APPEND
- **WHEN** CSV is imported with `importMode=APPEND`, existing schema declares a field as `INTEGER`, and one CSV row has value `"hello"` for that field
- **THEN** system SHALL attempt coercion (`Long.parseLong("hello")` fails), store `"hello"` as String
- **AND** validation SHALL produce a `TYPE` warning for that test case (`isValid=false`)
- **AND** other rows with valid integer values SHALL have `isValid=true` for that field

#### Scenario: CSV export-import round-trip preserves typed values
- **WHEN** test cases with INTEGER, NUMBER, BOOLEAN, and STRING fields are exported to CSV and re-imported into the same suite (OVERRIDE mode) with the same schema
- **THEN** the re-imported data values SHALL be type-equivalent to the originals after coercion (no type drift across export-import cycles)
- **Note:** CSV export writes all values as strings. Re-import parses heuristically, then coercion restores the original types from the schema.
