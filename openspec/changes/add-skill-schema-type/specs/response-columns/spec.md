# Response Columns — delta for `add-skill-schema-type`

## ADDED Requirements

### Requirement: SKILL type in response column definitions
The `SKILL` value of `SchemaFieldType` SHALL be a supported `ResponseColumnDefinitionDto.type`. A `SKILL` response column indicates that the JSONata expression extracts a DIAL skill reference (`skills/{bucket}/{path}`) from the response. Storage and reconciliation SHALL be identical to `STRING` (`ResponseColumnTypeReconciler` handles `SKILL` like `STRING`); the extracted value is stored as a string, is NOT format-validated, and `SKILL` is NOT auto-inferred from the extracted value. Clients MAY use the type as a rendering hint. Extraction failures behave as for every other type.

Status: **Implemented**
Implementation notes: `ResponseColumnTypeReconciler.reconcile` (`case STRING, FILE, SKILL`). Tests: `ResponseColumnTypeReconcilerTest` (`skillBehavesLikeString`).

#### Scenario: SKILL response column accepted
- **WHEN** a client saves a suite with a response column `{"name": "used_skill", "expression": "skill.id", "type": "SKILL"}`
- **THEN** the column SHALL be persisted and returned with `type: "SKILL"`

#### Scenario: SKILL column value stored as string
- **WHEN** a result is written and a `SKILL` column's expression resolves to `"skills/b/x"`
- **THEN** `extracted_columns["used_skill"]` SHALL be the string `"skills/b/x"` with no extraction warning

#### Scenario: Non-string result is reconciled like STRING
- **WHEN** a `SKILL` column's expression resolves to the number `5`
- **THEN** the reconciliation outcome SHALL be identical to that of a `STRING` column with the same result
