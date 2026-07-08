## ADDED Requirements

### Requirement: Metric definition carries an optional execution condition
A TSMD SHALL support an optional `condition` string (max 2000 characters) across its create and update
request DTOs, its response DTO, the domain model, and the `test_suite_metric_definitions` table. It
SHALL be persisted, returned on read, and default to null (metric always runs).
Status: **Planned**

#### Scenario: Create a metric definition with a condition
- **WHEN** a POST creates a TSMD with `condition = "$exists(response.answer)"`
- **THEN** the TSMD SHALL be created and a subsequent GET SHALL return the same `condition`

#### Scenario: Create a metric definition without a condition
- **WHEN** a POST creates a TSMD with no `condition`
- **THEN** the TSMD SHALL be created with a null condition and behave exactly as before

### Requirement: Malformed condition is rejected with 400
On TSMD create or update, a non-blank `condition` SHALL be validated eagerly and the request SHALL be
rejected with HTTP 400 (`VALIDATION_ERROR`) when the condition is a syntactically invalid JSONata
expression, or a bare `name()` call whose function name is not registered. The condition SHALL NOT
contribute to the metric's soft `is_valid` flag and SHALL NOT be stored as a validation warning.
Status: **Planned**

#### Scenario: Invalid JSONata syntax rejected
- **WHEN** a POST or PUT sets `condition` to a syntactically invalid JSONata expression
- **THEN** the request SHALL fail with HTTP 400 and the TSMD SHALL NOT be created or updated

#### Scenario: Unknown custom function rejected
- **WHEN** a POST or PUT sets `condition` to a bare `name()` whose name is not a registered custom
  function
- **THEN** the request SHALL fail with HTTP 400

#### Scenario: Valid condition accepted
- **WHEN** a POST or PUT sets `condition` to a valid JSONata expression
- **THEN** the request SHALL succeed and `is_valid` SHALL be computed exactly as it would be without
  the condition
