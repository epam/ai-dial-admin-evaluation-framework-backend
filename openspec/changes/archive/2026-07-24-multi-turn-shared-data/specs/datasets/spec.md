## MODIFIED Requirements

### Requirement: Dataset testCaseSchema structure and validation
The system SHALL validate the `testCaseSchema` on every dataset create/update: schema is a list of `FieldDefinitionDto` entries where each entry's `name` is non-blank, unique within the schema (case-insensitive), at most 255 characters, and matches the identifier pattern that prohibits the `:` character; `type` is one of `STRING`, `INTEGER`, `NUMBER`, `BOOLEAN`, `OBJECT`, `ARRAY`, `FILE`; `displayName` is at most 255 characters; `description` is at most 2000 characters; `required` is a boolean; `perTurn` is a boolean (default `false`) that marks the field's **scope** — `true` = per-turn (the field's value may vary between turns of a multi-turn case and lives in each `multiTurnData[i]` map), `false`/absent = shared (test-case-level, constant across turns, lives in the `data` map). Scope is a schema-level declaration and applies uniformly to every test case in the dataset. A missing `perTurn` SHALL be treated as `false`, so schemas authored before this field are unchanged.
Status: **Planned**

#### Scenario: Empty schema accepted
- **WHEN** client creates a dataset with `testCaseSchema: []`
- **THEN** the request SHALL succeed and the dataset stores an empty schema

#### Scenario: Duplicate field name (case-insensitive)
- **WHEN** client sends a `testCaseSchema` with two fields named `"prompt"` and `"Prompt"`
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`

#### Scenario: Field name contains colon
- **WHEN** client sends a field with `name: "foo:bar"`
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR` because `:` is reserved as the filter operator separator

#### Scenario: Unknown field type
- **WHEN** client sends a field with `type: "TIMESTAMP"`
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`

#### Scenario: Field exceeds max length
- **WHEN** client sends a field with `name` longer than 255 characters or `description` longer than 2000 characters
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`

#### Scenario: perTurn defaults to shared when absent
- **WHEN** client sends a field with no `perTurn` attribute
- **THEN** the field SHALL be persisted and treated as shared (`perTurn=false`), and existing pre-change schemas SHALL behave identically to before

#### Scenario: perTurn marks a field per-turn
- **WHEN** client sends a field with `perTurn: true`
- **THEN** the request SHALL succeed and that field's values SHALL be expected in each turn's `multiTurnData[i]` map (not in the shared `data` map) for multi-turn cases in this dataset
