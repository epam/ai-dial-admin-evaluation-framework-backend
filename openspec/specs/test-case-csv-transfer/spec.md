# Test-Case CSV Transfer

## Purpose

CSV import/export, permissive parsing/coercion, schema inference, mode/conflict handling, multi-turn assembly, provisional preview and post-row fixup. [test-cases](../test-cases/spec.md) owns CRUD; [datasets](../datasets/spec.md) owns schema identity/version/lifecycle; [test-case-zip-archive](../test-case-zip-archive/spec.md) owns archive/manifest/file preparation and compensation; [test-case-revalidation](../test-case-revalidation/spec.md) owns strict processing after schema persistence.

Status: **Implemented**

## Requirements

### Requirement: CSV conflict strategies and result counts

Status: **Implemented**

Case-insensitive `testCaseName` uniqueness within a Dataset is defined by [test-cases](../test-cases/spec.md#requirement-unique-testcasename-within-dataset) ("Unique testCaseName within Dataset"). CSV import `conflictStrategy` (FAIL/SKIP/OVERRIDE) SHALL govern both cross-import collisions and within-CSV duplicates.

The `CsvImportResultDto` SHALL expose `totalRows` as raw CSV row count and validity counts from assembled-case processing before final fixup/revalidation, including cases skipped by the conflict strategy. Those counts SHALL NOT be recomputed from final persisted validity. `skippedCount`/`overriddenCount` SHALL count assembled-case persistence operations, be populated for the applicable strategy (including zero), and otherwise be null/omitted.

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| CSV import within-CSV duplicates handled by conflictStrategy | CSV import with the CSV containing two rows with the same `testCaseName` (case-insensitive) | SHALL handle the duplicate per `conflictStrategy`: FAIL → HTTP 409; SKIP → first occurrence preserved, subsequent skipped; OVERRIDE → last occurrence wins via upsert |
| FAIL strategy rejects import on name collision | import with any `importMode` and `conflictStrategy=FAIL` (or omitted) and a `testCaseName` collision occurs (a CSV row name matching an existing test case in APPEND/MERGE modes, or a within-CSV duplicate in any mode) | SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION`, and message identifying the first colliding name; no rows SHALL be imported (transaction rolled back by DB constraint) |
| SKIP strategy skips colliding rows | import with any `importMode` and `conflictStrategy=SKIP` and `testCaseName` collisions occur (cross-import collisions in APPEND/MERGE, or within-CSV duplicates in any mode) | SHALL skip those rows (using `INSERT ... ON CONFLICT DO NOTHING`), import the remaining rows, and return result with `skippedCount` set to the total number of skipped assembled cases |
| OVERRIDE strategy replaces colliding rows | import with any `importMode` and `conflictStrategy=OVERRIDE` and `testCaseName` collisions occur (cross-import collisions in APPEND/MERGE, or within-CSV duplicates in any mode) | SHALL replace the matching rows with the CSV rows (using `INSERT ... ON CONFLICT DO UPDATE`); result SHALL include `overriddenCount` set to the number of replaced assembled cases (Replacement is a full data substitution — the existing row's `data` is entirely replaced with the CSV row's data. Fields defined in the schema but absent from the CSV row are NOT preserved. Field-level merge of existing and imported row data is out of scope.) |
| Conflict strategy applies in OVERRIDE import mode for within-CSV duplicates | import with `importMode=OVERRIDE` (which deletes all existing test cases first) and the CSV file contains two or more rows with the same `testCaseName` (case-insensitive) | SHALL handle those within-CSV duplicates according to `conflictStrategy`: FAIL → HTTP 409 on the second occurrence; SKIP → first occurrence wins, `skippedCount` is set; OVERRIDE → last occurrence wins via upsert, `overriddenCount` is set |
| OVERRIDE import mode + SKIP strategy returns skippedCount for within-CSV duplicates | import with `importMode=OVERRIDE` and `conflictStrategy=SKIP` and the CSV contains rows with duplicate `testCaseName` values | SHALL import the first occurrence of each duplicate name, skip subsequent duplicates, and return `skippedCount` equal to the number of skipped within-CSV duplicate rows; `overriddenCount` SHALL be null |
| OVERRIDE import mode + OVERRIDE strategy returns overriddenCount for within-CSV duplicates | import with `importMode=OVERRIDE` and `conflictStrategy=OVERRIDE` and the CSV contains rows with duplicate `testCaseName` values | SHALL store the last occurrence of each duplicate name (via upsert) and return `overriddenCount` equal to the number of replaced within-CSV duplicate rows; `skippedCount` SHALL be null |
| Default conflict strategy | import without `conflictStrategy` parameter | SHALL behave as `FAIL` |
| Preview shows conflict-specific context | preview with `importMode=APPEND` (or `MERGE`) and CSV row names collide with existing test case names | SHALL include warnings indicating which names collide and what action the current `conflictStrategy` would take (skip / override / fail) |
| Within-CSV duplicates follow conflictStrategy | import with any `importMode` and the CSV contains multiple rows with the same `testCaseName` (case-insensitive) | SHALL handle them per `conflictStrategy`: FAIL → HTTP 409 on the first within-CSV duplicate encountered; SKIP → first occurrence is imported, subsequent duplicates are silently skipped (skippedCount incremented); OVERRIDE → last occurrence wins via upsert (overriddenCount incremented for each replacement) |
| Preview annotates within-CSV duplicates with strategy-appropriate warnings | the preview endpoint with a CSV that contains duplicate `testCaseName` values | preview response SHALL annotate duplicate rows with strategy-appropriate warnings (FAIL: "would cause import failure"; SKIP: "would be skipped"; OVERRIDE: "would replace earlier row"); no HTTP 409 is returned from the preview endpoint itself |
| Turn rows of one case are not a name collision | client imports or previews a CSV whose consecutive rows share a `testCaseName` and carry distinct integer `turnIndex` values | SHALL treat them as one test case name occurrence — no duplicate warning on import or preview, no HTTP 409 under `FAIL`, and no `skippedCount`/`overriddenCount` increment under `SKIP`/`OVERRIDE` |
| Same-named single-turn rows still collide | client imports or previews a CSV with two adjacent rows carrying the same `testCaseName` and a blank or non-integer `turnIndex` | the second row SHALL be treated as a within-CSV duplicate exactly as before, per the chosen `conflictStrategy` |
| Result includes skippedCount for SKIP strategy | import completes with `conflictStrategy=SKIP` and some rows were skipped | the result SHALL include `skippedCount` with the number of skipped assembled cases; `overriddenCount` SHALL be null |
| Result includes overriddenCount for OVERRIDE strategy | import completes with `conflictStrategy=OVERRIDE` and some existing test cases were replaced | the result SHALL include `overriddenCount` with the number of replaced test cases; `skippedCount` SHALL be null |
| Extended counts are null when not applicable | import completes with `conflictStrategy=FAIL` (any import mode) | `skippedCount` and `overriddenCount` SHALL be null (omitted from JSON) |

### Requirement: CSV export and flat multi-turn round trip

Status: **Implemented**

The service SHALL export test cases of a Dataset in a format appropriate to the dataset's schema and the `materializeFiles` parameter. If the dataset's `testCaseSchema` contains no `FILE` type fields, export SHALL produce a CSV file. If the schema contains `FILE` type fields, the `materializeFiles` parameter controls the export format.

Column order SHALL be by schema order: fixed columns `testCaseName` and `turnIndex` first, then data columns in the order fields appear in the dataset's `testCaseSchema`. ARRAY and OBJECT values SHALL be serialized as JSON strings.

The ZIP archive format, its manifest, file materialization and export failure behaviour are defined by the `test-case-zip-archive` capability.

CSV import/export SHALL remain flat: a multi-turn case is represented as one row per turn. A reserved `turnIndex` header groups and orders turns; it and `testCaseName` are excluded from `data` and from schema auto-detection. Per-turn columns vary per row. Shared columns SHALL be repeated on every turn row of a case; on import the columns whose **resolved scope is shared** (declared shared in the dataset's current schema, or defaulted shared by scope resolution) MUST be identical across a case's turn rows, and a mismatch SHALL be reported as a conflict warning that invalidates the case. Columns whose resolved scope is per-turn — declared or inferred from the file's turn structure — are NOT subject to this identity rule. Single-turn cases export one row with a blank `turnIndex`.

ZIP export and import SHALL use the same flat representation: a ZIP archive's `test-cases.csv` carries one row per turn with `turnIndex`, exactly like CSV export, and ZIP import assembles turn rows into multi-turn cases exactly like CSV import (see the `test-case-zip-archive` capability). Every guarantee of this requirement SHALL hold for ZIP archives too.

The round trip SHALL be **repeatable**: importing an exported CSV back into a dataset, and then importing an export of the result again, SHALL yield the same test cases each time — the second and every subsequent import SHALL produce the same `data` and `multiTurnData` as the first.

This guarantee holds under two conditions. First, the dataset's `testCaseSchema` declares every field the case carries — export derives its column set from the schema, so a key held by a case but absent from the schema is omitted from the CSV and cannot survive any round trip. Second, re-importing the same names is a defined write: `importMode=OVERRIDE` with any `conflictStrategy`, or `APPEND`/`MERGE` with `conflictStrategy=OVERRIDE`. `APPEND`/`MERGE` with `FAIL` (HTTP 409) or `SKIP` (nothing written) is correct collision handling, not a round-trip defect, and is excluded.

Additionally, importing an exported multi-turn CSV into a **different dataset whose `testCaseSchema` is empty** SHALL reproduce the source's turn structure: every turn survives with its per-turn values, and re-exporting the destination SHALL yield CSV content identical to the source export. The destination schema MAY differ from the source schema in scope (columns the source declared shared are inferred per-turn at the destination), and a value the source case omitted MAY materialize as an empty string at the destination (a CSV cannot distinguish an absent value from a blank one); each turn's merged effective view SHALL otherwise equal the source case's.

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| Export without FILE fields (CSV) | `GET /api/v1/datasets/{datasetId}/test-cases/export.csv`; the dataset's `testCaseSchema` has no FILE type fields | SHALL return `Content-Type: text/csv` with test case data as CSV |
| CSV columns reflect dataset schema | system exports CSV | header SHALL be: `testCaseName`, `turnIndex`, then dataset `testCaseSchema` fields in schema order |
| Export with custom delimiter | `GET .../datasets/{datasetId}/test-cases/export.csv?delimiter=;` | SHALL use semicolon as delimiter |
| Export with FILE fields and materializeFiles=true (ZIP) | `GET /api/v1/datasets/{datasetId}/test-cases/export.csv?materializeFiles=true`; the dataset's `testCaseSchema` has one or more FILE type fields | SHALL return `Content-Type: application/zip` with `Content-Disposition: attachment; filename="test-cases-{datasetId}.zip"`; the archive SHALL be fully built before the response starts, so a failure yields an error status instead of a truncated archive (it is no longer streamed while being built); the archive SHALL follow the `test-case-zip-archive` layout: `test-cases.csv` with one row per turn, `manifest.json`, and one `files/{n}/{filename}` entry per distinct EF-owned file reference, with `public/…` references kept verbatim in the CSV |
| Export with FILE fields and materializeFiles=false | `GET /api/v1/datasets/{datasetId}/test-cases/export.csv?materializeFiles=false` | SHALL return CSV with FILE columns containing the stored file references verbatim (e.g. `@ef/datasets/{datasetId}/{filename}`, legacy `@ef/suites/{suiteId}/{filename}`, `public/…`) |
| Export with FILE fields default materializeFiles | `GET /api/v1/datasets/{datasetId}/test-cases/export.csv` without `materializeFiles`; the dataset's `testCaseSchema` has one or more FILE type fields | SHALL default `materializeFiles` to `true` and produce a ZIP |
| Export ARRAY values as JSON | system exports test cases with ARRAY-type fields to CSV | the CSV cell SHALL contain the valid JSON string (e.g., `["item1","item2"]`) |
| Export OBJECT values as JSON | system exports test cases with OBJECT-type fields to CSV | the CSV cell SHALL contain the valid JSON string (e.g., `{"key":"value"}`) |
| Export with FILE field but null value | a test case has a FILE field with null value (no file attached) | the CSV column for that field SHALL be empty; no file entry in the ZIP for that test case's field |
| Export null ARRAY/OBJECT values | system exports a test case where an ARRAY or OBJECT field has a null value | the CSV cell SHALL be empty |
| Export primitive values unchanged | system exports test cases with STRING, INTEGER, NUMBER, or BOOLEAN fields | the CSV cell values SHALL use their natural string representation |
| Export multiplies turns to rows | a multi-turn case with N turns is exported | it produces N contiguous rows sharing `testCaseName`, with `turnIndex` `0..N-1` in order; shared columns carry the same value on every row; single-turn cases export one row with a blank `turnIndex` |
| Import assembles a contiguous run into one case | consecutive import rows share a `testCaseName` and at least one carries a `turnIndex` that parses to an integer | they are assembled into one multi-turn test case whose `multiTurnData` is the per-turn columns sorted by `turnIndex`, and whose shared `data` is taken from the (identical) shared columns |
| Conflicting shared columns are a conflict | two turn rows of the same case carry different values for a column whose resolved scope is shared | a conflict warning is reported and the case is invalidated |
| Non-contiguous multi-turn name is a conflict | a second multi-turn run reuses an earlier multi-turn run's `testCaseName`, or a `turnIndex` is duplicated within a run | a row/conflict error is reported; a single-turn row sharing a name with any other row (in either order) is instead an ordinary within-CSV duplicate handled per `conflictStrategy` |
| Re-importing an exported CSV twice is stable | a dataset containing a multi-turn case is exported, that CSV is imported back into the dataset with `importMode=OVERRIDE`, the result is exported again, and that second CSV is imported with `importMode=OVERRIDE` | after both imports the case SHALL carry the same number of turns with the same per-turn values as the original, and its shared `data` SHALL be unchanged; no per-turn value SHALL be promoted into the shared `data` map, and no turn map SHALL become empty |
| Exported multi-turn CSV round-trips into a schema-less dataset | a multi-turn dataset is exported, the CSV is imported into a second dataset whose `testCaseSchema` is empty, and the second dataset is exported | the second dataset's cases SHALL carry the same turn counts as the source and per-turn merged effective views equal to the source's up to blank materialization, every imported case SHALL be valid with no shared-column conflict, and the two exported CSVs SHALL be identical |
| ZIP export and re-import keeps turns | a dataset containing a multi-turn case is exported as ZIP and the archive is imported back with `importMode=OVERRIDE` | the case SHALL carry the same number of turns with the same per-turn values and the same shared `data` as before the export |

### Requirement: CSV parsing, inference and post-row fixup

Status: **Implemented**

CSV import SHALL parse non-reserved columns and assemble their values into shared/per-turn maps using "CSV schema rebuild preserves per-field scope". Reserved `testCaseName`/`turnIndex` are excluded from data and inference; schema field matching and persistence follow "CSV import modes and schema derivation". ARRAY/OBJECT fields and valid untyped JSON containers are stored structurally. Integer heuristics use Long, and `1`/`0` are numeric, not Boolean; literal true/false is case-insensitive.

Known parse-time target types use permissive `SchemaTypeCoercer`; unknown types retain their heuristic-parsed values initially. Failed conversion preserves that value for [content validation](../test-case-validation/spec.md). STRING/FILE/SKILL SHALL preserve Strings and use `String.valueOf` for every other parsed value; null values or unknown target types remain unchanged; composite parse failures and numeric heuristic boundaries follow the cases below. Manifest-driven STRING fields use raw text as specified by [ZIP import](../test-case-zip-archive/spec.md), not heuristic normalization.

During import/preview streaming, validation uses provisional mode-specific field membership and unresolved inferred types. After row persistence, import SHALL derive the final effective schema, then re-read cases in batches and fix newly determined column types before schema persistence. MERGE fixup affects new columns only; APPEND with a non-empty schema needs no fixup. Changed cases SHALL be revalidated and batch-updated. Fixup SHALL process shared data and each turn, validate scopes as multi-turn, preserve durable source conflicts and persist the turn array. An unreadable stored turn array SHALL be skipped unchanged with a warning; it must never be rewritten as single-turn.

Schema persistence then invokes [dataset update](../datasets/spec.md) whenever a schema is derived, except when an equal non-empty schema (ignoring field ids) is reproduced — so empty-to-empty derivation still updates the dataset; synchronous [strict revalidation](../test-case-revalidation/spec.md) runs only when the stored schema actually changes. Preview never receives these final fixup/revalidation passes; its samples remain provisional.

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| Import partitions data columns by resolved scope | CSV header has column names matching `testCaseSchema` fields | SHALL treat all columns except `testCaseName` and `turnIndex` as data columns under the chosen import mode, partitioning assembled multi-turn values into shared `data` and per-turn maps |
| Header `enabled` is an ordinary data column | CSV contains a column named `enabled` | SHALL treat it like any other data column under the chosen importMode (OVERRIDE/APPEND-empty/MERGE auto-detect it; APPEND-non-empty discards it if not in schema); the column SHALL NOT be promoted to a TestCase boolean field |
| Auto-detect schema from CSV (no existing schema) | the dataset's `testCaseSchema` is empty and CSV is imported (any mode) | SHALL derive field definitions from non-reserved columns with `required:false` and `description:null`; schema order SHALL follow CSV columns and scopes SHALL follow `multi-turn-test-case` |
| Auto-detected schema is persisted on the dataset | CSV import commits and schema auto-detection or merge occurs | SHALL persist eligible schemas through dataset update rules. Equal non-empty schemas ignoring field IDs SHALL skip the update/version bump. Empty-to-empty derivation SHALL still invoke dataset update and bump its version, without revalidation. Non-empty MERGE SHALL follow the inference/manifest gate in "CSV import modes and schema derivation"; changed schemas SHALL run dataset-rooted revalidation. |
| Schema-change side-effects from import | a CSV or ZIP import in any mode results in a dataset schema change | the import SHALL return HTTP 200 with import counts, and exactly one dataset-rooted revalidation task SHALL be recorded; revalidation SHALL run synchronously after the imported rows are written and coerced, refreshing test-case validity and referencing suite configuration before the import returns; the persisted task SHALL be available through `/api/v1/datasets/{datasetId}/revalidation-tasks` and its detail endpoint |
| Post-persist fixup uses dataset schema | CSV is imported with an empty schema (or OVERRIDE mode) and the dataset's schema is auto-detected | the fixup pass SHALL use `SchemaTypeCoercer` and the final derived schema to coerce and revalidate stored rows, then batch-update changed rows before schema persistence |
| Failed permissive conversion | a parsed cell cannot be converted to its declared type (for example `"hello"` to INTEGER) | the original parsed value SHALL remain unchanged and downstream validation SHALL report the TYPE mismatch |
| Reference/string conversion | a cell's known type is FILE | permissive coercion SHALL preserve Strings and convert other parsed values via `String.valueOf`, as for STRING and SKILL |
| Column name not in schema is discarded (APPEND mode only) | client imports with `importMode=APPEND`, the dataset `testCaseSchema` is non-empty, and a CSV column name does not match any schema field | SHALL discard that column's values and NOT store them in `data`; no validation warning is added for the unknown column itself (This filtering applies only to `APPEND` mode with a non-empty dataset schema. In `OVERRIDE` mode all CSV data columns are stored (schema is replaced). In `MERGE` mode CSV data columns are stored; persistence of new schema columns remains subject to the inference/manifest gate, including the all-blank plain-CSV exception.) |
| Auto-detect type inference | system auto-detects schema from CSV | SHALL scan all row values per column and infer type: all non-empty values parse as JSON objects → OBJECT; JSON arrays → ARRAY; literal `true`/`false` (case-insensitive, NOT `1`/`0`) → BOOLEAN; whole numbers (including `1`/`0`) → INTEGER; decimal numbers → NUMBER; well-formed skill references (`skills/{bucket}/{path}`, per the `dial-skill-ref` format rule) → SKILL; otherwise → STRING. A column whose non-blank cells infer different types SHALL widen: INTEGER + NUMBER → NUMBER; any other mix (including SKILL with any non-SKILL type) → STRING; blank cells are ignored |
| Auto-detected schema in preview | the CSV import preview endpoint and `testCaseSchema` would be auto-detected or schema would be replaced/merged | the preview response SHALL include `autoDetectedSchema` reflecting what would change |
| CSV with only reserved columns | CSV has only `testCaseName` with no data columns, and the dataset's `testCaseSchema` is empty | schema stays empty (no fields to auto-detect) |
| JSON array cell stored as array when schema is empty | a CSV cell value is a valid JSON array (e.g. `["a","b"]`) and there is no existing schema (OVERRIDE mode or empty schema in APPEND mode) | SHALL parse the cell value and store it in `data` as a JSON array, not as a raw string |
| JSON array cell stored as array when schema has no type for that field | a CSV cell value is a valid JSON array and the column is a new field being added in MERGE mode (no existing schema type for this field) | SHALL parse the cell value and store it as a JSON array; the auto-detected schema type for that field SHALL be ARRAY |
| JSON object cell stored as object when no schema type known | a CSV cell value is a valid JSON object (e.g. `{"key":"value"}`) and there is no schema type known for that column (empty schema or new MERGE column) | SHALL parse and store it as a JSON object; auto-detected schema type SHALL be OBJECT |
| Invalid JSON in array-looking cell falls back to string | a CSV cell value starts with `[` or `{` but is not valid JSON | SHALL store the raw cell string value unchanged; the auto-detected schema type SHALL be STRING |
| Preview sample rows reflect coerced values | the CSV import preview endpoint and the dataset has an existing `testCaseSchema` with declared field types | the preview sample rows SHALL reflect coerced values (e.g., a STRING-typed column with CSV value `1865` will show `"1865"` in preview, not `1865`) (`parseRow()` is shared between preview and import. Schema type auto-detection (inference) in preview is unaffected — only per-cell value coercion applies when a schema type is known.) |
| Long → STRING | Parsed Long `1865`; target STRING | Store JSON string `"1865"`, not numeric `1865` |
| Double → STRING | Parsed Double `3.14`; target STRING | Store `"3.14"` |
| Boolean → STRING | Parsed Boolean true; target STRING | Store `"true"` |
| Numeric 1/0 → STRING | Cells `1`/`0` parsed as Long; target STRING | Store `"1"`/`"0"` via `String.valueOf`, without Boolean conversion |
| Integer string → INTEGER | Parseable integer string; target INTEGER | Store the Long from `Long.parseLong` |
| 64-bit integer boundary | Cell `3000000000`; target INTEGER | Store Long `3000000000`; values up to Long.MAX_VALUE are supported |
| Whole Double → INTEGER | Parsed Double `3.0`; target INTEGER | Store Long `3` |
| Fractional Double → INTEGER | Parsed Double `3.14`; target INTEGER | Preserve `3.14` and report TYPE; never truncate to `3` |
| Negative integer identity | Parsed Long `-42`; target INTEGER | Store Long `-42` unchanged |
| Numeric 1/0 → INTEGER | Cells `1`/`0` parsed as Long; target INTEGER | Store Long `1`/`0` unchanged |
| Boolean → INTEGER | Parsed Boolean true/false; target INTEGER | Store Long `1`/`0` |
| Boolean → NUMBER | Parsed Boolean true/false; target NUMBER | Store Double `1.0`/`0.0` |
| Numeric string → NUMBER | Parseable numeric string; target NUMBER | Store the Double from `Double.parseDouble` |
| Long → NUMBER | Parsed Long `42`; target NUMBER | Store Double `42.0` |
| Long → BOOLEAN | Parsed Long; target BOOLEAN | Nonzero becomes true; zero becomes false |
| Boolean string → BOOLEAN | String `true`/`false` (case-insensitive); target BOOLEAN | Store the corresponding Boolean |
| Double → BOOLEAN refused | Parsed Double, including `3.14` or `1.0`; target BOOLEAN | Keep the Double and report TYPE |
| Blank scalar conversion refused | Blank cell parsed as `""`; target INTEGER/NUMBER/BOOLEAN | Keep `""` and report TYPE; STRING keeps it unchanged |
| No inline coercion when schema type is unknown | a column has no declared schema type during parseRow (null in fieldTypes map — e.g., empty schema in APPEND mode) | SHALL store the heuristic-parsed value initially; the post-persist fixup pass will coerce after schema is finalized |
| Post-persist fixup corrects types after schema auto-detection | CSV is imported with an empty schema (or OVERRIDE mode) and schema is auto-detected from CSV content | after rows are stored and the final schema is derived, but before schema persistence, SHALL re-read dataset test cases in batches, coerce values for columns with newly determined types using `SchemaTypeCoercer`, re-validate each test case, and batch-update any rows whose data changed (This ensures type correctness even for rows stored before the final schema type was known (e.g., a column widened from INTEGER to STRING during import).) |
| Post-persist fixup for MERGE mode new columns | CSV is imported in MERGE mode and new columns are added to the existing schema | the fixup pass SHALL coerce values only for the newly added columns; existing columns (already in schema before import) are not re-coerced |
| No fixup when schema is unchanged | CSV is imported in APPEND mode with an existing non-empty schema | SHALL NOT run the fixup pass (inline coercion in parseRow already handled all columns) |
| Blank declared composite JSON cells | a CSV cell is blank and its parse-time type is OBJECT or ARRAY | it SHALL parse as an empty object or empty array respectively, not JSON null |
| Malformed declared composite JSON cell | a cell cannot be parsed as JSON for a declared OBJECT or ARRAY field | the parsed fallback value SHALL be retained and an UNKNOWN JSON-parse warning SHALL invalidate the assembled case |
| Numeric heuristic boundaries | untyped cells use exponent, leading-plus, or leading-dot numeric notation | heuristic parsing/inference SHALL leave them as strings; a known target type MAY subsequently coerce them; an integer-looking cell outside Long range SHALL fail import/preview with HTTP 400 rather than fall back to STRING |
| CSV import uses permissive coercion rules | client imports CSV containing `"1"` for a BOOLEAN-typed field | CSV parsing SHALL produce Long `1`, and `SchemaTypeCoercer` SHALL convert it to Boolean `true` before validation (`SchemaChangeCoercer` is stricter than `SchemaTypeCoercer`; the two are sibling components in the `service.domain.csv` package.) |
| CSV import with coercion produces no type warnings | a CSV is imported and the SchemaTypeCoercer successfully coerces values to match schema types | validation SHALL NOT emit TYPE warnings for those fields (types already match after coercion) |
| Coercion failure produces type mismatch warning | a CSV cell value is `"hello"` (parsed as String) and the schema declares `INTEGER` | SHALL store the original String value `"hello"` unchanged (coercion failure); validation SHALL emit a `TYPE` warning for the field (`isValid=false`) (Coercion failure and type mismatch warning are two sides of the same coin — the coercer returns the value unchanged, then the validation layer detects the mismatch.) |
| Mixed-type auto-detected column widens to STRING without warnings | CSV is imported with empty schema and a column has values `42`, `99`, `hello` across rows | auto-detection SHALL widen the column type to `STRING` (INTEGER + STRING → STRING); the post-persist fixup pass SHALL coerce the integer values `42` and `99` to strings `"42"` and `"99"`; all test cases SHALL have `isValid=true` for that field (all values are strings matching STRING type) |
| Schema-declared INTEGER column with non-integer value during APPEND | CSV is imported with `importMode=APPEND`, existing schema declares a field as `INTEGER`, and one CSV row has value `"hello"` for that field | SHALL attempt coercion (`Long.parseLong("hello")` fails), store `"hello"` as String; validation SHALL produce a `TYPE` warning for that test case (`isValid=false`); other rows with valid integer values SHALL have `isValid=true` for that field |
| CSV export-import round-trip preserves typed values | test cases with non-null, non-blank INTEGER, NUMBER, BOOLEAN, and STRING values are exported and re-imported with OVERRIDE, and inference reproduces their original field types | the re-imported data values SHALL be type-equivalent to the originals after coercion (no type drift across export-import cycles) (CSV export writes all values as strings. Re-import parses heuristically, then coercion restores the original types from the schema.) |
| CSV import OVERRIDE mode validates against target schema | client imports CSV with `importMode=OVERRIDE` and the dataset has an existing schema with different fields than the CSV | SHALL validate each row against the target schema derived from CSV headers (not the pre-import dataset schema); imported test cases SHALL have `valid=true` when data matches the CSV-derived schema |
| CSV import OVERRIDE mode with empty dataset schema validates correctly | client imports CSV with `importMode=OVERRIDE` and the dataset's `testCaseSchema` is empty | SHALL validate each row against the target schema derived from CSV headers; imported test cases SHALL NOT have "Unknown data field" warnings for columns present in the CSV |
| CSV import MERGE mode with new columns validates against merged schema | client imports CSV with `importMode=MERGE` and the CSV has columns not in the existing dataset schema | SHALL validate each row against the merged schema (existing fields + new CSV columns); new columns SHALL NOT produce "Unknown data field" warnings |
| CSV import APPEND mode with empty schema validates against target schema | client imports CSV with `importMode=APPEND` and the dataset's `testCaseSchema` is empty | SHALL validate each row against the target schema derived from CSV headers; imported test cases SHALL have `valid=true` when data matches the CSV-derived schema |
| CSV preview validates sample rows against target schema | the CSV import preview endpoint with any mode where the schema would change | SHALL validate samples against the provisional mode-specific schema, including candidate columns, and report final inference in `autoDetectedSchema` without re-coercing or revalidating the samples |
| Per-turn values are coerced to the new type | an import determines a per-turn field's type and a stored multi-turn case holds values for that field needing coercion | the values inside each turn SHALL be coerced to the new type and the updated turn array SHALL be persisted |
| Multi-turn case is re-validated as multi-turn | the fixup pass re-validates a case that carries a turn array | validity and warnings SHALL be computed from the case's shared data against the schema's shared fields and each turn against the schema's per-turn fields, not from the shared data against the whole schema |
| Unreadable turn array is skipped, never overwritten | the fixup pass encounters a case whose stored turn array is present but cannot be read | the case SHALL be left unchanged in the database and a warning SHALL be logged; the case SHALL NOT be rewritten as a single-turn case |
| Single-turn cases are unaffected | the fixup pass processes a case with no turn array | it SHALL behave exactly as before — coercing and re-validating the shared `data` only |
| Fixup does not erase an import conflict | the fixup pass re-validates a case that carries an import-derived conflict warning | that warning SHALL be carried forward and the case SHALL stay invalid, per the durable-conflict-warning requirement |

### Requirement: Import test cases (CSV or ZIP)

Status: **Implemented**

The import endpoint SHALL accept both CSV files and ZIP archives. A file SHALL be treated as a ZIP archive when any of these holds:
- its filename ends with `.zip`;
- its content type is `application/zip` or `application/x-zip-compressed`;
- its content starts with the ZIP signature bytes `PK\x03\x04`.

Otherwise it SHALL be treated as CSV. The import and preview endpoints SHALL respond with `application/json`. ZIP import behaviour (archive layout, manifest, file placement and overwrite, cell rewriting, limits and failure handling) is defined by the `test-case-zip-archive` capability.

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| Import CSV file (unchanged) | client sends `POST /api/v1/datasets/{datasetId}/test-cases/import` with a `.csv` file; the dataset's `testCaseSchema` has no FILE type fields | SHALL process the CSV using the current import flow |
| Import ZIP archive | client sends `POST /api/v1/datasets/{datasetId}/test-cases/import` with a `.zip` file | SHALL import it per the `test-case-zip-archive` capability: every archive file referenced by a participating resolved FILE column SHALL be uploaded to `{efBucket}/datasets/{datasetId}/{filename}`, and the corresponding FILE values SHALL hold the dataset file reference |
| ZIP detected by content | client uploads a ZIP archive named `export.bin` with content type `application/octet-stream` | SHALL detect it as ZIP from its signature bytes and import it as a ZIP archive |
| Import ZIP with missing file | a CSV row references a file path (e.g., `files/1/doc/report.pdf`) that does not exist in the ZIP archive | SHALL store the FILE field value blank (treated as absent by validation) and report a warning carrying that row's number and column name |
| Import CSV for dataset with FILE fields | client imports a CSV file for a dataset whose schema has FILE fields | SHALL treat FILE columns as string values (DIAL file paths if provided, or empty for null); no file extraction is performed; FILE field validation may produce warnings if the DIAL file paths have invalid format or disallowed prefix |

### Requirement: Read-only import preview

Status: **Implemented**

The import preview endpoint SHALL support both CSV and ZIP formats with the same detection logic as the import endpoint.

The preview response SHALL report both `totalRows` — the number of CSV data rows parsed — and `totalTestCases` — the number of test cases those rows assemble into. The two differ only when the CSV contains multi-turn cases, whose turn rows assemble into one case each; for a single-turn CSV they are equal. Both describe the CSV as submitted and SHALL NOT be reduced by rows a conflict strategy would skip.

`sampleRows` SHALL contain assembled test cases (bounded by the sample limit), not raw CSV rows. Samples and warnings SHALL be computed during streaming against a provisional validation schema and parse-time field types; final `autoDetectedSchema` SHALL be derived after the scan. Samples SHALL not be re-coerced or revalidated afterward, so their values and validity MAY differ from final post-fixup imported state. A sample for a multi-turn case SHALL carry its `multiTurnData` turn array and its shared `data`; a sample for a single-turn case SHALL carry a flat `data` with no turn array.

For a ZIP archive, preview SHALL match import as defined by the `test-case-zip-archive` capability, without writing any file.

CSV import preview SHALL group CSV rows into test cases using the same rules as import, and report per assembled test case rather than per CSV row. Turn rows of one multi-turn case SHALL NOT be reported as duplicate names of each other.

A run is a maximal group of consecutive rows sharing a `testCaseName`. A run in which some row's `turnIndex` parses to an integer assembles into **one** multi-turn test case. A run of K rows whose `turnIndex` is blank or not an integer (treated as blank) remains **K separate** test cases. Duplicate-name and existing-name collision detection SHALL therefore key once per multi-turn run and once per row of a single-turn run.

Preview SHALL report the multi-turn conflicts import would report for the same CSV — duplicate `turnIndex` within a case, a multi-turn `testCaseName` reappearing non-contiguously, and shared columns differing across a case's turn rows — anchored to the run's first CSV row number, matching import.

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| Preview CSV file | client sends `POST /api/v1/datasets/{datasetId}/test-cases/import/preview` with a CSV file | SHALL return the preview (current behavior) |
| Preview ZIP archive | client sends `POST /api/v1/datasets/{datasetId}/test-cases/import/preview` with a ZIP file | SHALL extract and preview the `test-cases.csv` within the archive; FILE columns SHALL show the dataset file references import would store (`@ef/datasets/{datasetId}/{filename}` per the configured bucket alias), not the archive's relative paths, and no file SHALL be uploaded |
| Preview reports test case count alongside row count | client previews a CSV whose rows include a multi-turn case of N turns | `totalRows` SHALL count every CSV data row and `totalTestCases` SHALL count the N turn rows as one test case |
| Multi-turn sample uses provisional assembled-case validation | client previews a CSV containing a multi-turn case | the sample SHALL be validated as an assembled case using the provisional schema, with shared/per-turn data and source conflicts; its validity and coerced types SHALL not be guaranteed to match post-fixup import results |
| Single-turn CSV preview counts rows as cases | client previews a CSV containing no `turnIndex` values | `totalTestCases` SHALL equal `totalRows`, each sample row SHALL carry a flat `data` with no turn array |
| Preview does not receive final inferred-type fixup | an OVERRIDE CSV contains only numeric cells for a column currently declared STRING | preview SHALL show parse-time string samples while reporting the inferred numeric type in `autoDetectedSchema`; imported values MAY become numeric during fixup, without that fixup being reflected in preview samples |
| Turn rows are not flagged as duplicate names | a client previews a CSV whose rows are the turns of one multi-turn case (same `testCaseName`, distinct `turnIndex` values) | the preview SHALL NOT emit a within-CSV duplicate-name warning for any of those rows |
| Multi-turn sample rows carry their turns | a client previews a CSV containing a multi-turn case | the corresponding sample row SHALL be the assembled case, carrying its `multiTurnData` turn array and its shared `data`, not one sample per turn row |
| Adjacent same-named single-turn rows stay separate cases | a client previews a CSV with two adjacent rows carrying the same `testCaseName` and a blank `turnIndex` | the preview SHALL still emit a within-CSV duplicate-name warning for the second row, and SHALL count them as two test cases |
| Preview predicts multi-turn conflicts | a client previews a CSV in which one case's turn rows repeat a `turnIndex`, or disagree on a shared column value, or a multi-turn `testCaseName` reappears after another case | the preview SHALL report the same conflict import would report, anchored to the first CSV row number of the affected run |

### Requirement: CSV import modes and schema derivation

Status: **Implemented**

The import and preview endpoints SHALL accept `importMode=OVERRIDE|APPEND|MERGE`, defaulting to OVERRIDE; invalid values SHALL return HTTP 400. Schema derivation SHALL follow this matrix:

| Mode | Empty schema | Non-empty schema |
|---|---|---|
| OVERRIDE | Infer CSV membership/types | Replace membership/types from CSV |
| APPEND | Infer CSV membership/types | Keep stored schema; discard undeclared CSV columns |
| MERGE | Infer CSV membership/types | Keep existing order/definitions; append eligible new columns |

Field scope follows "CSV schema rebuild preserves per-field scope". Manifest definitions follow [ZIP import schema from the manifest](../test-case-zip-archive/spec.md). Plain CSV OVERRIDE rebuilds every field from the CSV: a surviving field keeps its declared `perTurn` scope (and its `id`), but is rebuilt with `required: false` and without `displayName` or `description`; its type is re-derived from the CSV.

Persistence SHALL follow "Import persists the schema through the dataset update rules": equal non-empty schemas skip update/version bump; empty-to-empty derivation invokes dataset update and increments version without a revalidation task. Non-empty MERGE attempts persistence only when a new column produces inference or a manifest supplies driving fields. If only new plain-CSV columns are entirely blank, they SHALL NOT persist, although preview MAY report their delta. Another inferred new column enables the builder to include those blank columns. With no persisted delta the schema/version remain unchanged. Preview SHALL report full detection/replacement for OVERRIDE or empty schemas and only the new-field delta for non-empty MERGE.

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| Import with explicit OVERRIDE mode | `POST .../test-cases/import?importMode=OVERRIDE` with a CSV file | SHALL replace the dataset's test cases with the imported cases, derive the target dataset schema from CSV, and persist it through the dataset update rules; the dataset version SHALL increase when schema persistence invokes dataset update, including the empty-to-empty schema case |
| Import with APPEND mode | `POST .../test-cases/import?importMode=APPEND` with a CSV file | SHALL NOT delete existing test cases; imported rows SHALL be appended to the dataset |
| Import with MERGE mode | `POST .../test-cases/import?importMode=MERGE` with a CSV file | SHALL NOT delete existing test cases; imported rows SHALL be appended; SHALL merge CSV schema with the existing dataset schema |
| Import with default mode (parameter omitted) | `POST .../test-cases/import` without `importMode` parameter | SHALL behave as `OVERRIDE` |
| Preview with import mode | `POST .../test-cases/import/preview` with any `importMode` value | SHALL include mode-specific information in the preview response |
| Invalid import mode value | import or preview with `importMode=INVALID_VALUE` | SHALL respond with HTTP 400 |
| OVERRIDE replaces existing schema | import with `importMode=OVERRIDE` and the dataset has an existing `testCaseSchema` | SHALL derive the replacement schema from CSV and persist it through the dataset update rules, incrementing the dataset version only if the schema changes |
| OVERRIDE with empty schema auto-detects | import with `importMode=OVERRIDE` and the dataset's `testCaseSchema` is empty | SHALL auto-detect schema from CSV and persist it through the dataset update rules, incrementing the dataset version even when the current and derived schemas are both empty |
| OVERRIDE preview shows replacement schema | preview with `importMode=OVERRIDE` | the preview response SHALL include `autoDetectedSchema` regardless of whether the dataset already has a schema |
| OVERRIDE replacement keeps field scope | import with `importMode=OVERRIDE` and an existing schema field is marked `perTurn: true` | the replacement schema SHALL still mark that field `perTurn: true`, while its type is re-derived from the CSV as usual, `required` becomes `false`, and `displayName`/`description` are dropped |
| Empty-to-empty schema derivation still increments version | an OVERRIDE import into an empty dataset schema contains only reserved columns | rows SHALL be imported and dataset update SHALL increment the version without recording a revalidation task |
| APPEND with empty schema triggers auto-detection | import with `importMode=APPEND` and the dataset's `testCaseSchema` is empty | SHALL auto-detect field definitions from CSV columns, persist the schema to the dataset through the dataset update rules, and increment the dataset version unless an equal non-empty schema skips the update |
| APPEND with existing schema preserves it | import with `importMode=APPEND` and the dataset's `testCaseSchema` is non-empty | SHALL validate CSV against the existing schema; SHALL NOT modify the schema or bump the version; unknown CSV columns are silently discarded (not stored in `data`) |
| APPEND preview with empty schema shows auto-detected schema | preview with `importMode=APPEND` and the dataset's `testCaseSchema` is empty | the preview response SHALL include `autoDetectedSchema` |
| MERGE with empty schema auto-detects entirely | import with `importMode=MERGE` and the dataset's `testCaseSchema` is empty | SHALL auto-detect all field definitions from CSV, persist, and bump version |
| MERGE adds new columns to existing schema | import with `importMode=MERGE`, the dataset schema has fields `[A, B]`, and the CSV has columns `[A, B, C, D]` with at least one non-blank new-column value | SHALL keep `A` and `B` unchanged and, when the inference/manifest gate is met, append `C` and `D`, persist the merged schema, and bump the dataset version |
| MERGE with no new columns leaves schema unchanged | import with `importMode=MERGE` and all CSV data columns already exist in the dataset schema | SHALL validate CSV against existing schema, persist rows; schema SHALL NOT be modified; dataset version SHALL NOT be bumped |
| MERGE preserves existing field definitions | import with `importMode=MERGE` and a CSV column name matches an existing schema field | SHALL use the existing field definition (type, required flag) for validation; SHALL NOT override the existing field's type with the CSV-inferred type |
| MERGE preview shows new fields only | preview with `importMode=MERGE` and CSV has columns not in the existing schema | the preview response SHALL include `autoDetectedSchema` showing only the new fields that would be added (the delta, not the full merged schema) |
| Only blank new plain-CSV columns do not persist a schema delta | a plain CSV is MERGEd into a non-empty schema and every value in every new column is blank | no schema update SHALL be persisted for those columns, although preview MAY report their inferred delta |

### Requirement: Import persists the schema through the dataset update rules

Status: **Implemented**

A version field SHALL be present on `Dataset` for optimistic concurrency control. Dataset PUTs SHALL require `If-Match`. CSV/ZIP import SHALL accept an optional `If-Match` checked against the owning dataset's version. A supplied stale dataset version SHALL produce HTTP 409 `VERSION_CONFLICT` without committing row or schema changes; ZIP import SHALL compensate its file writes on failure. Suite PUTs SHALL use the suite's version independently.

Test-case create, update, patch, batch-update, batch-patch, bulk-patch, delete, and deleteAll operations SHALL NOT recalculate or update referencing suites' `isValid` / `validationWarnings`. CSV/ZIP imports that leave the dataset schema unchanged SHALL likewise leave suite validity unchanged. An import that changes the dataset schema SHALL refresh referencing suite configuration through dataset-rooted revalidation, not because of test-case presence or content. Suite validity SHALL remain config-only; test-case presence SHALL be enforced at run creation (see `test-suite-runs`).

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| Import rejects a supplied stale dataset version | a CSV or ZIP import supplies `If-Match` with a stale dataset version | SHALL return HTTP 409 `VERSION_CONFLICT`, preserve the dataset schema and test cases, and leave ZIP file storage unchanged after compensation |
| OVERRIDE keeps ids of surviving columns | the dataset schema is `[{id: A, name: "prompt"}, {id: B, name: "legacy"}]` and a client imports a CSV with `importMode=OVERRIDE` and columns `prompt, answer` | the persisted schema SHALL contain `prompt` with `id: A` and `answer` with a newly generated `id`, and SHALL NOT contain `legacy` |
| MERGE keeps existing ids and adds new ones | the dataset schema is `[{id: A, name: "prompt"}]` and a client imports a CSV with `importMode=MERGE` and columns `prompt, answer` | the persisted schema SHALL be `prompt` with `id: A` followed by `answer` with a newly generated `id` |
| OVERRIDE removing a field from a bound PUBLIC dataset is rejected | a client imports with `importMode=OVERRIDE` into a `PUBLIC` dataset referenced by suites, dropping one current column | the import SHALL respond with HTTP 409 `DATASET_FIELD_REMOVAL_FORBIDDEN`; neither the schema, dataset version nor test cases SHALL change |
| Revalidation sees coerced rows | an import persists a changed schema | exactly one revalidation task SHALL be recorded for the import, and it SHALL evaluate the imported rows after their values were coerced to the persisted schema's types |
| Re-import with an unchanged schema runs no revalidation | a client imports with `importMode=OVERRIDE` a CSV whose columns and inferred types reproduce the dataset's current schema exactly | the rows SHALL be replaced and no revalidation task SHALL be recorded |
| PUT based on the pre-import dataset is rejected | an import that persists a changed schema commits, and a dataset PUT carrying the dataset's pre-import version arrives while, or after, it runs | the PUT SHALL be rejected with HTTP 409 `VERSION_CONFLICT` and SHALL change nothing; the imported rows and schema SHALL stay intact |
| Import does not revert a committed rename | a dataset PUT renaming `column2` to `column3` commits, and an import with `importMode=MERGE` into the same dataset then runs | the import SHALL see `column3` (not `column2`) as the current field, and the persisted schema SHALL keep `column3` with its original `id` |
| MERGE column differing only in case is rejected | the dataset schema contains `prompt` and a client imports a CSV with `importMode=MERGE` and a column `Prompt` | SHALL return HTTP 400 `VALIDATION_ERROR` naming the duplicate field, and SHALL NOT persist any row or schema change |
| Import with unchanged schema leaves suite validity unchanged | an import adds or replaces test cases without changing the dataset schema | referencing suites' `isValid` and `validationWarnings` SHALL remain unchanged, and no dataset revalidation task SHALL be recorded |
| Schema-changing import refreshes suite configuration | an import changes the schema of a dataset referenced by suites | dataset-rooted revalidation SHALL refresh those suites' configuration validity before import returns; suite validity SHALL remain independent of the number of test cases or their validity |

### Requirement: CSV import auto-name generation uses 1-based padded row index

Status: **Implemented**

When a CSV row has a blank or missing `testCaseName` column during import (any mode), the system SHALL auto-generate the name as `"Row " + zeroPaddedIndex` where the index is 1-based (first data row = 1) and zero-padded to `digits(csv.import.max-rows)` width (e.g., 100000 → 6 digits → "Row 000001").

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| First data row gets name "Row 000001" | client imports a CSV with blank `testCaseName` for the first data row and `csv.import.max-rows` is 100000 | SHALL assign the name "Row 000001" |
| Auto-generated names sort correctly as strings | client imports a CSV with 10+ rows with blank `testCaseName` | string-sorting the auto-generated names SHALL produce the same order as numeric sorting (e.g., "Row 000002" < "Row 000010") |
| Preview shows padded auto-generated names | the CSV preview endpoint with blank `testCaseName` values | the preview response SHALL show the same padded naming pattern as the actual import |
| Padding width derived from maxRows config | `csv.import.max-rows` config value has N digits | the zero-padding width SHALL be N (e.g., max-rows=100000 → 6-digit padding) |

### Requirement: SKILL columns in CSV import and export

Status: **Implemented**

CSV export of test cases whose schema contains `SKILL` fields (and no `FILE` fields) SHALL produce a plain CSV (never a ZIP); each `SKILL` cell SHALL be written verbatim. CSV import into an existing `SKILL` column SHALL keep each cell verbatim as a string (permissive coercion identical to `STRING`; no path rewriting).

During CSV schema detection (wherever a column's type is inferred from cell content: OVERRIDE import, APPEND into an empty schema, the MERGE new-column delta, ZIP import without a manifest, ZIP import with a manifest for the CSV columns the manifest does not list, and the corresponding preview `autoDetectedSchema`) a column SHALL be inferred as `SKILL` when it has at least one non-blank cell and every non-blank cell is a well-formed skill reference per the `dial-skill-ref` format rule (`SkillRefResolver.validate`). A column mixing well-formed skill references with any other non-blank value SHALL widen to `STRING`; blank cells SHALL NOT affect the inferred type, and a column that is blank on every row SHALL default to `STRING`, as for every other type. Inference SHALL NOT retype a column whose definition the import keeps from the dataset schema (APPEND into a non-empty schema; existing columns in MERGE); in OVERRIDE every column's type is re-derived from the CSV, as for every other type, so a declared `STRING` column holding only well-formed skill references becomes `SKILL`. A manifest-declared type or the manifest-less `files/…` → `FILE` hint SHALL take precedence over the inferred `SKILL`. Consequently a plain-CSV export of a `SKILL` column with at least one non-blank value, whose non-blank values are all well-formed, SHALL re-import as a `SKILL` column with identical cell values.

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| Export with only SKILL fields is CSV | a dataset has a `SKILL` field and no `FILE` field and the client exports test cases | the response SHALL be a CSV, and the `SKILL` cells SHALL equal the stored strings |
| Import keeps SKILL cells verbatim | a CSV is imported into a dataset whose schema has a `SKILL` field and a cell is `skills/my-bucket/summariser` | the stored value SHALL be exactly `skills/my-bucket/summariser` |
| SKILL inferred from well-formed skill references | a CSV whose `skillField` column holds `skills/b/x` and `skills/b/team/y/` (and blank cells) is imported into an empty dataset with schema detection and no hints | the persisted schema field `skillField` SHALL have type `SKILL`, the stored values SHALL equal the cells verbatim, and no validation warning SHALL be produced for that field |
| Mixed skill and non-skill column widens to STRING | a CSV column holds `skills/b/x` in one row and `hello` in another and is imported with schema detection | the inferred column type SHALL be `STRING` |
| Malformed skill reference is not inferred as SKILL | every non-blank cell of a CSV column is `skills/b` (bucket only), `Skills/b/x` or `files/b/x`, and the CSV is imported with schema detection | the inferred column type SHALL be `STRING` |
| All-blank column is not inferred as SKILL | a CSV column is blank on every row and the CSV is imported into an empty dataset with schema detection and no hints | the inferred column type SHALL be `STRING` |
| Plain CSV round trip preserves SKILL | a dataset with a `SKILL` column (well-formed values), an `INTEGER` column and no `FILE` column is exported as CSV and that CSV is imported into a new dataset in OVERRIDE mode | the new dataset's schema SHALL have the `SKILL` column with type `SKILL` and the `INTEGER` column with type `INTEGER`, and the `SKILL` cell values SHALL equal the exported strings |
| Declared column is not retyped | a CSV whose `note` column holds only `skills/b/x` values is imported in APPEND mode into a dataset whose schema declares `note` as `STRING` | the field `note` SHALL remain `STRING` |
| OVERRIDE re-derives a declared STRING column as SKILL | a CSV whose `note` column holds only `skills/b/x` values is imported in OVERRIDE mode into a dataset whose schema declares `note` as `STRING` | the field `note` SHALL become `SKILL` |
| Preview reports inferred SKILL | the the import preview endpoint for a CSV whose column holds only well-formed skill references and the schema would be auto-detected | `autoDetectedSchema` SHALL list that column with type `SKILL` |

### Requirement: CSV schema rebuild preserves per-field scope

Status: **Implemented**

When a CSV import rebuilds or updates the dataset's `testCaseSchema` (any mode that persists a schema), the system SHALL resolve each derived field's `perTurn` scope in this precedence order:

0. **Manifest-declared** (ZIP import only) — the import is a ZIP archive whose `manifest.json` declares the field, and the mode lets the manifest decide that field's definition (see the `test-case-zip-archive` capability, "ZIP import schema from the manifest"): its `perTurn` value SHALL be taken from the manifest verbatim, including an absent value (shared). Tiers 1–3 below apply only when tier 0 does not.

1. **Declared** — the field name exists in the dataset's current schema: its `perTurn` value is preserved verbatim, including an absent/false value (declared shared). The declared test is the field's *presence* in the current schema, not the presence of a non-null `perTurn` value — a declared field whose `perTurn` is absent is declared shared and SHALL NOT be re-scoped by inference.
2. **Inferred from the file's turn structure** — the field is undeclared and the CSV contains at least one multi-turn case (a contiguous same-`testCaseName` run where some row's `turnIndex` parses to an integer): the derived field SHALL carry `perTurn: true`. The gate is file-level: one multi-turn case makes every undeclared column per-turn.
3. **Default shared** — the field is undeclared and the CSV contains no multi-turn case: `perTurn` SHALL be absent (shared).

This resolution SHALL apply consistently everywhere a schema is derived from CSV columns during import or preview: the schema imported rows are validated against, the schema persisted to the dataset, the schema used to re-validate rows after persistence, and the `autoDetectedSchema` reported by preview. Preview SHALL report exactly the scope import would persist for the same CSV, and the shared/per-turn partitioning of an imported multi-turn case's values SHALL agree with the persisted scope.

Scope inference SHALL require no additional read of the CSV: a single streaming pass over the rows SHALL suffice for both scope resolution and case assembly.

Deriving a schema from CSV columns SHALL NOT mutate the dataset's current schema field definitions, and export SHALL NOT change: shared values remain repeated on every turn row, so an export → import → export round trip through a schema-less dataset yields byte-identical CSV content.

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| OVERRIDE import preserves perTurn on the persisted schema | a dataset has a schema field marked `perTurn: true` and a CSV containing that column is imported with `importMode=OVERRIDE` | the persisted `testCaseSchema` SHALL still mark that field `perTurn: true` |
| Preview reports perTurn in autoDetectedSchema | a client previews a CSV with `importMode=OVERRIDE` against a dataset whose schema marks a field `perTurn: true` | the `autoDetectedSchema` in the preview response SHALL mark that field `perTurn: true`, so a client writing it back does not strip the scope |
| Declared-shared field is never re-scoped by inference | a dataset's current schema declares a field with `perTurn` absent (shared) and a multi-turn CSV carries that column with values differing across a case's turn rows | the derived schema SHALL keep the field shared, and the turn-row disagreement SHALL be reported as a shared-column conflict that invalidates the case |
| New CSV column defaults to shared | a CSV containing no multi-turn case carries a data column with no same-named field in the dataset's current schema | the derived field definition SHALL omit `perTurn` (shared scope) |
| Empty dataset schema has nothing to preserve | a CSV with no multi-turn case is imported into a dataset whose `testCaseSchema` is empty | no field is declared, the file-level gate does not fire, and the persisted schema SHALL omit `perTurn` on every field — identical to the pre-inference behavior |
| Multi-turn CSV into an empty dataset infers per-turn scope | a CSV containing a multi-turn case is imported into a dataset whose `testCaseSchema` is empty | every data column SHALL be persisted with `perTurn: true`, the case SHALL retain all its turns in `multiTurnData`, and no shared-column conflict SHALL be reported |
| Mixed CSV marks every undeclared column per-turn | a CSV containing both multi-turn and single-turn cases is imported into a dataset whose `testCaseSchema` is empty | the persisted schema SHALL mark every field `perTurn: true`, the multi-turn cases SHALL retain their turns, and the single-turn cases SHALL keep their values in `data` and remain valid |
| Preview reports the scope import would persist | a client previews a multi-turn CSV against a dataset whose `testCaseSchema` is empty | the `autoDetectedSchema` SHALL mark every data column `perTurn: true`, the multi-turn sample rows SHALL carry those columns in their `multiTurnData` turn maps, and no shared-column conflict SHALL be reported |
| Manifest scope wins over file-level inference | a ZIP containing a multi-turn case is imported into a dataset whose `testCaseSchema` is empty, and its manifest declares column `context` shared and column `prompt` `perTurn: true` | the persisted schema SHALL mark `prompt` `perTurn: true` and omit `perTurn` on `context`, and the file-level gate SHALL NOT mark `context` per-turn |

## Implementation notes

- [CsvImportService.java](../../../src/main/java/com/epam/aidial/evaluation/service/domain/CsvImportService.java) and [CsvExportService.java](../../../src/main/java/com/epam/aidial/evaluation/service/domain/CsvExportService.java): streaming assembly, preview, schema persistence and batched fixup/export.
- [CsvCellParser.java](../../../src/main/java/com/epam/aidial/evaluation/service/domain/csv/CsvCellParser.java), [SchemaTypeCoercer.java](../../../src/main/java/com/epam/aidial/evaluation/service/domain/csv/SchemaTypeCoercer.java), [CsvColumnTypeInferrer.java](../../../src/main/java/com/epam/aidial/evaluation/service/domain/csv/CsvColumnTypeInferrer.java): parsing/permissive conversion/inference.
- [TestCaseController.java](../../../src/main/java/com/epam/aidial/evaluation/web/controller/TestCaseController.java): delimiter/mode parameters and CSV/ZIP detection.
