## ADDED Requirements

### Requirement: Output schema validation in TSMD soft validation
The `MetricDefinitionValidationService` SHALL validate the metric declaration version's `output_schema` during TSMD creation, update, and revalidation. Validation SHALL use `OutputSchemaFieldExtractor.extractFieldNames(outputSchema)` — if the returned list is empty, the output schema is invalid. This covers null/blank input, missing `"properties"` key, non-object `"properties"`, empty `"properties"`, and malformed JSON. If validation fails, the TSMD SHALL be marked `is_valid = false` with a warning code `INVALID_OUTPUT_SCHEMA`.
Status: **Planned**

#### Scenario: Valid output schema — properties with at least one field
- **WHEN** a TSMD is created or updated and the metric declaration version's output schema contains `{"properties": {"score": {...}}}` (one or more keys)
- **THEN** the output schema validation SHALL pass — no `INVALID_OUTPUT_SCHEMA` warning SHALL be produced. Other validation checks (UNRESOLVED_REFERENCE, REQUIRED, ADDITIONAL) still apply independently.

#### Scenario: Invalid — output schema is null or empty string
- **WHEN** a TSMD is created and the metric declaration version's `output_schema` is null, blank, or `"{}"`
- **THEN** system SHALL set `is_valid = false`, add a warning with `code = INVALID_OUTPUT_SCHEMA` and a message indicating the output schema is missing or empty

#### Scenario: Invalid — output schema has no properties key
- **WHEN** a TSMD is created and the metric declaration version's output schema is valid JSON but does not contain a `"properties"` key
- **THEN** system SHALL set `is_valid = false`, add a warning with `code = INVALID_OUTPUT_SCHEMA` and a message indicating the output schema has no properties

#### Scenario: Invalid — properties is empty object
- **WHEN** a TSMD is created and the metric declaration version's output schema has `{"properties": {}}`
- **THEN** system SHALL set `is_valid = false`, add a warning with `code = INVALID_OUTPUT_SCHEMA` and a message indicating the output schema has no output fields

#### Scenario: Invalid — properties is not an object
- **WHEN** a TSMD is created and the metric declaration version's output schema has `"properties"` as a non-object value (e.g., string, array)
- **THEN** system SHALL set `is_valid = false`, add a warning with `code = INVALID_OUTPUT_SCHEMA`

#### Scenario: Malformed JSON in output schema
- **WHEN** a TSMD is created and the metric declaration version's `output_schema` contains invalid JSON
- **THEN** system SHALL set `is_valid = false`, add a warning with `code = INVALID_OUTPUT_SCHEMA` and a message indicating the schema is malformed

#### Scenario: Revalidation catches newly invalid output schemas
- **WHEN** `POST /api/v1/test-suites/{id}/revalidation` is triggered and a TSMD references a metric declaration version whose output schema was updated to an invalid state (e.g., properties removed during re-sync)
- **THEN** the TSMD SHALL be updated to `is_valid = false` with an `INVALID_OUTPUT_SCHEMA` warning

#### Scenario: Output schema validation independent of binding checks
- **WHEN** a TSMD has valid bindings but an invalid output schema
- **THEN** the TSMD SHALL be marked `is_valid = false` due to the output schema check, regardless of binding validity

### Requirement: INVALID_OUTPUT_SCHEMA validation warning code
The `ValidationWarningCode` enum SHALL include a value `INVALID_OUTPUT_SCHEMA` with the description "Metric output schema is missing, empty, or malformed".
Status: **Planned**

#### Scenario: Code appears in TSMD validation warnings
- **WHEN** a TSMD references a metric with an invalid output schema
- **THEN** the validation warning in `validationWarnings` SHALL have `code = "INVALID_OUTPUT_SCHEMA"`
