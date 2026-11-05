## MODIFIED Requirements

### Requirement: CSV bulk upload with schema detection
The service SHALL allow bulk uploading TestCases via CSV under the dataset endpoint `POST /api/v1/datasets/{datasetId}/test-cases/import`. All columns map to the unified `data` map. Column names match the dataset's `testCaseSchema` field names. Schema auto-detection, persistence, and replacement behavior depend on the `importMode` parameter. In OVERRIDE mode, schema is always replaced on the dataset from CSV. In APPEND mode, schema is only auto-detected when the dataset schema is empty. In MERGE mode, new CSV columns are merged into the existing dataset schema. The reserved CSV columns are `testCaseName` and `turnIndex` (both excluded from `data` and from schema auto-detection); `turnIndex` groups and orders the turns of a multi-turn case (see the `multi-turn-test-case` spec). The previously-recognized `enabled` column is no longer parsed (TestCase has no `enabled` field).

Cell values for ARRAY- and OBJECT-typed fields SHALL be stored as structured JSON values (not strings) when the schema specifies those types; when there is no schema or the field has no schema type, valid JSON arrays/objects SHALL be parsed and stored structurally. `CsvCellParser` SHALL NOT treat `"1"` and `"0"` as boolean literals; only `"true"`/`"false"` (case-insensitive). Integer parsing SHALL use `Long.parseLong()`. Inline coercion SHALL match the schema type when known; post-persist fixup SHALL run after schema auto-detection / merge / OVERRIDE replacement.
Status: **Planned**

#### Scenario: Import maps all columns to data
- **WHEN** CSV header has column names matching `testCaseSchema` fields
- **THEN** system SHALL map all columns except the reserved `testCaseName` and `turnIndex` to `data` (the `enabled` column header is no longer reserved and is treated like any other data column — falling under the schema rules of the chosen importMode)

#### Scenario: Header `enabled` is no longer reserved
- **WHEN** CSV contains a column named `enabled`
- **THEN** system SHALL treat it like any other data column under the chosen importMode (OVERRIDE/APPEND-empty/MERGE auto-detect it; APPEND-non-empty discards it if not in schema); the column SHALL NOT be promoted to a TestCase boolean field

#### Scenario: Auto-detect schema from CSV (no existing schema)
- **WHEN** the dataset's `testCaseSchema` is empty and CSV is imported (any mode)
- **THEN** system SHALL auto-detect field definitions from CSV columns: all headers except the reserved `testCaseName` and `turnIndex` become `FieldDefinitionDto` entries with `required: false` and `description: null`; schema field order follows CSV column order (left to right)

#### Scenario: Auto-detected schema is persisted on the dataset
- **WHEN** CSV import commits and schema auto-detection or merge occurs
- **THEN** system SHALL persist the new or merged schema to the **Dataset's** `testCaseSchema` through the dataset update rules (see "Import persists the schema through the dataset update rules"), bump dataset `version`, and run the dataset-rooted `RevalidationTask` to coerce and revalidate the test cases against the new schema; when the resulting schema equals the current one (ignoring field `id`s), the system SHALL NOT persist it, SHALL NOT bump `version` and SHALL NOT run a task

#### Scenario: Schema-change side-effects from import
- **WHEN** an import in OVERRIDE or MERGE mode results in a schema change
- **THEN** the response SHALL include both the import result and a `RevalidationTaskDto` (HTTP 202 path) covering the auto-coercion of post-import data and the suite-side fan-out; alternatively the import response carries only the import counts and the client polls `/datasets/{id}/revalidation-tasks` to observe progress — exact API shape is left to implementation but the behavior of triggering a task is required

#### Scenario: Post-persist fixup uses dataset schema
- **WHEN** CSV is imported with an empty schema (or OVERRIDE mode) and the dataset's schema is auto-detected
- **THEN** the fixup pass SHALL coerce values for columns with newly determined types using `SchemaTypeCoercer`, re-validate each test case against the **dataset's** schema, and batch-update changed rows

#### Scenario: Coercion failure preserves original value
- **WHEN** a CSV cell value cannot be coerced to the declared dataset-schema type
- **THEN** system SHALL store the original parsed value unchanged; downstream validation surfaces a TYPE warning

#### Scenario: FILE-typed column coerced to string
- **WHEN** a CSV cell value is in a column declared as `FILE` in the dataset schema and the parsed value is not already a String
- **THEN** system SHALL coerce the value to String via `String.valueOf(value)`

#### Scenario: Column name not in schema is discarded (APPEND mode only)
- **WHEN** client imports with `importMode=APPEND`, the dataset `testCaseSchema` is non-empty, and a CSV column name does not match any schema field
- **THEN** system SHALL discard that column's values and NOT store them in `data`; no validation warning is added for the unknown column itself
- **Note:** This filtering applies only to `APPEND` mode with a non-empty dataset schema. In `OVERRIDE` mode all CSV data columns are stored (schema is replaced). In `MERGE` mode all CSV data columns are stored (all columns end up in the merged schema — either existing or newly added).

#### Scenario: Auto-detect type inference
- **WHEN** system auto-detects schema from CSV
- **THEN** system SHALL scan all row values per column and infer type: all non-empty values parse as JSON objects → OBJECT; JSON arrays → ARRAY; literal `true`/`false` (case-insensitive, NOT `1`/`0`) → BOOLEAN; whole numbers (including `1`/`0`) → INTEGER; decimal numbers → NUMBER; well-formed skill references (`skills/{bucket}/{path}`, per the `dial-skill-ref` format rule) → SKILL; otherwise → STRING. A column whose non-blank cells infer different types SHALL widen: INTEGER + NUMBER → NUMBER; any other mix (including SKILL with any non-SKILL type) → STRING; blank cells are ignored

#### Scenario: Auto-detected schema in preview
- **WHEN** client calls the CSV import preview endpoint and `testCaseSchema` would be auto-detected or schema would be replaced/merged
- **THEN** the preview response SHALL include `autoDetectedSchema` reflecting what would change

#### Scenario: CSV with only reserved columns
- **WHEN** CSV has only `testCaseName` with no data columns, and the dataset's `testCaseSchema` is empty
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

### Requirement: SKILL columns in CSV import and export
CSV export of test cases whose schema contains `SKILL` fields (and no `FILE` fields) SHALL produce a plain CSV (never a ZIP); each `SKILL` cell SHALL be written verbatim. CSV import into an existing `SKILL` column SHALL keep each cell verbatim as a string (permissive coercion identical to `STRING`; no path rewriting).

During CSV schema detection (wherever a column's type is inferred from cell content: OVERRIDE import, APPEND into an empty schema, the MERGE new-column delta, ZIP import without a manifest, ZIP import with a manifest for the CSV columns the manifest does not list, and the corresponding preview `autoDetectedSchema`) a column SHALL be inferred as `SKILL` when it has at least one non-blank cell and every non-blank cell is a well-formed skill reference per the `dial-skill-ref` format rule (`SkillRefResolver.validate`). A column mixing well-formed skill references with any other non-blank value SHALL widen to `STRING`; blank cells SHALL NOT affect the inferred type, and a column that is blank on every row SHALL default to `STRING`, as for every other type. Inference SHALL NOT retype a column whose definition the import keeps from the dataset schema (APPEND into a non-empty schema; existing columns in MERGE); in OVERRIDE every column's type is re-derived from the CSV, as for every other type, so a declared `STRING` column holding only well-formed skill references becomes `SKILL`. A manifest-declared type or the manifest-less `files/…` → `FILE` hint SHALL take precedence over the inferred `SKILL`. Consequently a plain-CSV export of a `SKILL` column with at least one non-blank value, whose non-blank values are all well-formed, SHALL re-import as a `SKILL` column with identical cell values.

Status: **Implemented**
Implementation notes: `SchemaTypeCoercer` (`case STRING, FILE, SKILL`); inference in `service/domain/csv/CsvColumnTypeInferrer` (`inferCellType` SKILL branch via runner-core `SkillRefResolver.validate`, `widen`), called from `CsvImportService.updateInferredTypes` / `updateInferredTypesForNewFields`; precedence in `CsvSchemaFieldBuilder.buildFromBindings` / `buildMergeDelta` (manifest `drivingFields`) and `resolveType` (`fileColumns` → FILE > inferred > STRING); `ZipImportColumnTypeResolver` keeps SKILL out of path rewriting. Tests: `CsvColumnTypeInferrerTest`, `CsvImportServiceSchemaTest` (SKILL inference section), `CsvImportServiceSchemaHintsTest` (SKILL vs hints precedence), `SchemaTypeCoercerTest`, `CsvSchemaFieldBuilderTest`, `ZipExportServiceTest`, `SkillFieldFunctionalTests` (CSV export, import, inference, mixed column, preview, plain-CSV round trip).

#### Scenario: Export with only SKILL fields is CSV
- **WHEN** a suite has a `SKILL` field and no `FILE` field and the client exports test cases
- **THEN** the response SHALL be a CSV, and the `SKILL` cells SHALL equal the stored strings

#### Scenario: Import keeps SKILL cells verbatim
- **WHEN** a CSV is imported into a dataset whose schema has a `SKILL` field and a cell is `skills/my-bucket/summariser`
- **THEN** the stored value SHALL be exactly `skills/my-bucket/summariser`

#### Scenario: SKILL inferred from well-formed skill references
- **WHEN** a CSV whose `skillField` column holds `skills/b/x` and `skills/b/team/y/` (and blank cells) is imported into an empty dataset with schema detection and no hints
- **THEN** the persisted schema field `skillField` SHALL have type `SKILL`, the stored values SHALL equal the cells verbatim, and no validation warning SHALL be produced for that field

#### Scenario: Mixed skill and non-skill column widens to STRING
- **WHEN** a CSV column holds `skills/b/x` in one row and `hello` in another and is imported with schema detection
- **THEN** the inferred column type SHALL be `STRING`

#### Scenario: Malformed skill reference is not inferred as SKILL
- **WHEN** every non-blank cell of a CSV column is `skills/b` (bucket only), `Skills/b/x` or `files/b/x`, and the CSV is imported with schema detection
- **THEN** the inferred column type SHALL be `STRING`

#### Scenario: All-blank column is not inferred as SKILL
- **WHEN** a CSV column is blank on every row and the CSV is imported into an empty dataset with schema detection and no hints
- **THEN** the inferred column type SHALL be `STRING`

#### Scenario: Plain CSV round trip preserves SKILL
- **WHEN** a dataset with a `SKILL` column (well-formed values), an `INTEGER` column and no `FILE` column is exported as CSV and that CSV is imported into a new dataset in OVERRIDE mode
- **THEN** the new dataset's schema SHALL have the `SKILL` column with type `SKILL` and the `INTEGER` column with type `INTEGER`, and the `SKILL` cell values SHALL equal the exported strings

#### Scenario: Declared column is not retyped
- **WHEN** a CSV whose `note` column holds only `skills/b/x` values is imported in APPEND mode into a dataset whose schema declares `note` as `STRING`
- **THEN** the field `note` SHALL remain `STRING`

#### Scenario: OVERRIDE re-derives a declared STRING column as SKILL
- **WHEN** a CSV whose `note` column holds only `skills/b/x` values is imported in OVERRIDE mode into a dataset whose schema declares `note` as `STRING`
- **THEN** the field `note` SHALL become `SKILL`

#### Scenario: Preview reports inferred SKILL
- **WHEN** the client calls the import preview endpoint for a CSV whose column holds only well-formed skill references and the schema would be auto-detected
- **THEN** `autoDetectedSchema` SHALL list that column with type `SKILL`
