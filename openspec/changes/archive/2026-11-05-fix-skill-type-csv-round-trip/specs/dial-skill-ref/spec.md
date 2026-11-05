## MODIFIED Requirements

### Requirement: SKILL schema type is a string-backed DIAL skill reference
The `SchemaFieldType` enum SHALL include `SKILL`. A field or response column of type `SKILL` SHALL carry a **string** value that references an existing DIAL Core skill — a directory resource — in the form `skills/{bucket}/{path-to-skill}`. Everywhere the system is type-agnostic (JSON-schema type mapping, query-field type mapping, CSV cell handling, response-column reconciliation) `SKILL` SHALL behave exactly like `STRING`. CSV **schema detection** is the one place a `SKILL` type is derived from content: a column with at least one non-blank cell whose every non-blank cell is a well-formed skill reference (see "Skill reference format and canonical form") SHALL be inferred as `SKILL` (rules in `test-cases`, "SKILL columns in CSV import and export"). `SKILL` SHALL NOT be treated as a file: it is never a multipart part, never downloaded or materialised as binary, never packed into a ZIP archive, and never copied between suites/datasets.

The system SHALL only **reference** existing skills. It SHALL NOT create, upload, copy, delete or otherwise manage skills, and SHALL NOT call DIAL Core skill routes. DIAL Core SHALL remain authoritative for skill existence, access rights and request-time sharing; the system SHALL NOT check existence or access.

Status: **Implemented**
Implementation notes: `SchemaFieldType.SKILL` (runner-core `runner/dto/SchemaFieldType.java`); string-like handling in `ResponseColumnTypeReconciler`, `SchemaValidationService.jsonSchemaType`, `query/service/SchemaFieldTypeMapper`, `SchemaTypeCoercer`; never routed to `DialFileRefResolver` / `DialFileClient` (see `ResourceRefPlaceholderResolver`); CSV schema-detection inference in `service/domain/csv/CsvColumnTypeInferrer` using `SkillRefResolver.validate`. Tests: `SkillFieldFunctionalTests` (`PostgresFunctionalTests$SkillFieldTests`), `CsvColumnTypeInferrerTest`.

#### Scenario: SKILL accepted as a schema field type
- **WHEN** a client saves a test-suite or dataset `testCaseSchema` containing a field `{"name": "skill", "type": "SKILL"}`
- **THEN** the system SHALL accept and persist the schema and return the field with `type: "SKILL"`

#### Scenario: SKILL value is persisted verbatim
- **WHEN** a test case stores `"skills/my-bucket/summariser"` in a `SKILL` field
- **THEN** reading the test case back SHALL return exactly `"skills/my-bucket/summariser"`

#### Scenario: SKILL is never downloaded
- **WHEN** a run, try-out or export processes a test case with a `SKILL` value
- **THEN** the system SHALL NOT call `DialFileClient` download/upload and SHALL NOT call any DIAL Core skill route

#### Scenario: CSV schema detection uses the skill reference format rule
- **WHEN** a CSV column's non-blank cells are all well-formed skill references and the column's type is auto-detected on import
- **THEN** the detected type SHALL be `SKILL`, and a column containing a malformed reference such as `skills/my-bucket` SHALL NOT be detected as `SKILL`
