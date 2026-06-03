## MODIFIED Requirements

### Requirement: CSV bulk upload with schema detection
The service SHALL allow bulk uploading TestCases via CSV. All columns map to the unified `data` map. Column names match `testCaseSchema` field names. Schema auto-detection, persistence, and replacement behavior depend on the `importMode` parameter. In OVERRIDE mode, schema is always replaced from CSV. In APPEND mode, schema is only auto-detected when the suite schema is empty. In MERGE mode, new CSV columns are merged into the existing schema.

Cell values for ARRAY- and OBJECT-typed fields SHALL be stored as structured JSON values (not strings), regardless of whether the field's schema type is known at parse time. When no schema entry exists for a column (`type == null`) and the raw cell value is a syntactically valid JSON array (`[...]`) or JSON object (`{...}`), the system SHALL parse the cell and store the result as a structured value. If the cell is not valid JSON, the system SHALL store the result of the normal cell parser (string/number/boolean) without error.

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
- **THEN** system SHALL scan all row values per column and infer type: all non-empty values parse as JSON objects → OBJECT; JSON arrays → ARRAY; `true`/`false` (case-insensitive) → BOOLEAN; whole numbers → INTEGER; decimal numbers → NUMBER; otherwise → STRING

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
- **WHEN** CSV cell contains a valid JSON array string (e.g. `["a","b","c"]`) and the suite `testCaseSchema` is empty at import time (OVERRIDE or APPEND+empty)
- **THEN** system SHALL store the field value as a JSON array in the test case `data`, not as the string `"[\"a\",\"b\",\"c\"]"`

#### Scenario: JSON array cell stored as array when schema has no type for that field
- **WHEN** CSV cell contains a valid JSON array string and the suite `testCaseSchema` does not include a type entry for that column (e.g. in MERGE mode for a new column)
- **THEN** system SHALL store the field value as a JSON array in the test case `data`

#### Scenario: JSON object cell stored as object when no schema type known
- **WHEN** CSV cell contains a valid JSON object string (e.g. `{"key":"value"}`) and no schema type is known for the column
- **THEN** system SHALL store the field value as a JSON object in the test case `data`

#### Scenario: Invalid JSON in array-looking cell falls back to string
- **WHEN** CSV cell starts with `[` or `{` but is not valid JSON (e.g. `[not-json]`)
- **THEN** system SHALL store the cell as a string (result of normal cell parsing) without raising an import error
