# Test Cases (delta: meta-model validations)

## ADDED Requirements

### Requirement: Structured validation warnings in TestCase responses
When a TestCase has validation warnings (e.g. schema validation failures for parameters or facts), the service SHALL return them as a list of **structured objects** (not plain strings). Each object SHALL include: `source` (parameters | facts), `path` (JSONPath-like), `property` (top-level property name when applicable), `message` (human-readable), and optionally `code` (stable identifier). This applies to all responses that include validation warnings: create, get, update, list (when includeWarnings=true), and CSV import/preview.

#### Scenario: Create returns structured warnings when invalid
- **WHEN** client creates a TestCase that fails schema validation (e.g. missing required parameter or fact)
- **THEN** response SHALL include `validationWarnings` as a list of objects each with `source`, `path`, `property`, `message`, and optionally `code`

#### Scenario: Get returns structured warnings when invalid
- **WHEN** client calls GET for a TestCase with includeWarnings=true and the TestCase has validation warnings
- **THEN** response SHALL include `validationWarnings` as a list of objects with source, path, property, message

#### Scenario: Warnings indicate parameters vs facts
- **WHEN** a TestCase has both parameter and fact validation failures
- **THEN** each warning object SHALL have `source` equal to `"parameters"` or `"facts"` so the client can bind to the correct part of the grid

#### Scenario: Property enables grid cell matching
- **WHEN** a warning refers to a top-level property (e.g. required property 'model' not found)
- **THEN** the warning object SHALL include `property` (e.g. `"model"`) so the FE can highlight the corresponding column/cell

#### Scenario: CSV import preview returns structured warnings
- **WHEN** client calls CSV import preview and sample rows have validation failures
- **THEN** sample row objects SHALL include `validationWarnings` as a list of structured objects (source, path, property, message)

#### Scenario: validationWarnings type (structured objects)
- **WHEN** client requests a TestCase response that includes validation warnings
- **THEN** `validationWarnings` SHALL be an array of objects (source, path, property, message, code)

#### Scenario: Existing rows have empty validationWarnings (migration prunes data)
- **WHEN** client requests a TestCase that existed before the structured-warnings migration
- **THEN** `validationWarnings` SHALL be an empty list (migration prunes existing data to empty array)
