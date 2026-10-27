# Test Cases — delta for `add-skill-schema-type`

## ADDED Requirements

### Requirement: SKILL type in test case schemas and data
The `SKILL` value of `SchemaFieldType` SHALL be a supported `testCaseSchema` field type for test suites and datasets (type table: `SKILL` — string, value is a DIAL skill reference `skills/{bucket}/{path}`). Value validation and warnings are defined in `dial-skill-ref` (malformed non-empty value → `TYPE` warning; never an HTTP 400 for a test-case write). The type SHALL be preserved unchanged through create, update, PATCH, batch and bulk operations, CSV import/export, ZIP manifest, suite snapshot and suite/dataset clone.

Status: **Implemented**
Implementation notes: the type is carried by the JSONB schema, so persistence, clone and snapshot need no code; value validation is `TestCaseValidationService`. Tests: `SkillFieldFunctionalTests` (round trip, clone, run snapshot), `TestCaseValidationServiceSkillTest`.

#### Scenario: SKILL column survives clone
- **WHEN** a suite or dataset with a `SKILL` column and values is cloned
- **THEN** the clone SHALL have the same `SKILL` column type and the same string values, and no file copy SHALL be attempted for them

#### Scenario: SKILL column survives snapshot
- **WHEN** a run is created for a suite with a `SKILL` column
- **THEN** the run snapshot SHALL carry the schema field with `type: SKILL` and the unchanged values

### Requirement: SKILL columns in CSV import and export
CSV export of test cases whose schema contains `SKILL` fields (and no `FILE` fields) SHALL produce a plain CSV (never a ZIP); each `SKILL` cell SHALL be written verbatim. CSV import into an existing `SKILL` column SHALL keep each cell verbatim as a string (permissive coercion identical to `STRING`; no path rewriting). A `SKILL` type SHALL NOT be inferred from cell content during schema detection; a `skills/...` string in an untyped column remains `STRING` until the schema is retyped.

Status: **Implemented**
Implementation notes: `SchemaTypeCoercer` (`case STRING, FILE, SKILL`); `CsvImportService` is unchanged and never infers FILE/SKILL from cell content; `ZipImportColumnTypeResolver` keeps SKILL out of path rewriting. Tests: `SchemaTypeCoercerTest`, `CsvSchemaFieldBuilderTest`, `ZipExportServiceTest`, `SkillFieldFunctionalTests` (CSV export, import, no inference).

#### Scenario: Export with only SKILL fields is CSV
- **WHEN** a suite has a `SKILL` field and no `FILE` field and the client exports test cases
- **THEN** the response SHALL be a CSV, and the `SKILL` cells SHALL equal the stored strings

#### Scenario: Import keeps SKILL cells verbatim
- **WHEN** a CSV is imported into a dataset whose schema has a `SKILL` field and a cell is `skills/my-bucket/summariser`
- **THEN** the stored value SHALL be exactly `skills/my-bucket/summariser`

#### Scenario: No content-based SKILL inference
- **WHEN** a CSV with a `skills/b/x` cell is imported with schema detection and no hints
- **THEN** the inferred column type SHALL be `STRING`

### Requirement: SKILL coercion rules
Permissive coercion (`SchemaTypeCoercer`, CSV import) SHALL coerce any value to `SKILL` as it does to `STRING`. Strict coercion on schema-type change (`SchemaChangeCoercer`) SHALL treat a `SKILL` target like a `FILE` target: only a `String` value is kept (identity); any other value SHALL NOT be auto-coerced and SHALL be reported by revalidation. After a type change to `SKILL`, revalidation SHALL apply the skill format warning.

Status: **Implemented**
Implementation notes: `SchemaChangeCoercer` (`case FILE, SKILL -> coerceToReference`) used by `RevalidationService`; the format warning comes from `validateSkillFields`. Tests: `SchemaChangeCoercerTest`, `RevalidationCoercionFunctionalTests` (STRING→SKILL, INTEGER→SKILL).

#### Scenario: String retyped to SKILL keeps its value
- **WHEN** a field is changed from `STRING` to `SKILL` and a test case holds `"skills/b/x"`
- **THEN** the value SHALL be unchanged and no coerced-cell count SHALL be added for it

#### Scenario: Non-string value retyped to SKILL is not auto-coerced
- **WHEN** a field is changed from `INTEGER` to `SKILL` and a test case holds `42`
- **THEN** the value SHALL NOT be auto-coerced and revalidation SHALL report a type mismatch

#### Scenario: Retype to SKILL revalidates format
- **WHEN** a field is changed from `STRING` to `SKILL` and a test case holds `"not-a-skill"`
- **THEN** the value SHALL be kept and a `SKILL field` `TYPE` warning SHALL be produced
