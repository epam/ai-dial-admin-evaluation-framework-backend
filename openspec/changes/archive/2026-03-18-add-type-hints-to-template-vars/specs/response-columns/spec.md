## ADDED Requirements

### Requirement: FILE type in response column definitions
The `FILE` value of `SchemaFieldType` SHALL be an officially supported type for `ResponseColumnDefinitionDto.type`. A response column with `type: FILE` indicates that the JSONata expression extracts a DIAL file reference path (e.g., `"files/@myapp/results/output.pdf"`) from the response body.

Semantics in this phase: **display hint only**. The extracted value is stored as a string in `extracted_columns` (identical to `STRING` storage). Clients (FE) SHALL use `type: FILE` to render the extracted value as a clickable/downloadable link rather than plain text. No backend extraction, validation, or download behaviour changes.

The default type (when omitted or null in the DTO) remains `STRING`. `FILE` type is **not** auto-inferred from the extracted value — it must be explicitly set on the column definition.

#### Scenario: Response column with FILE type accepted on create
- **WHEN** client calls `POST /api/v1/test-suites` with a response column `{"name": "result_file", "expression": "output.path", "type": "FILE"}`
- **THEN** system SHALL persist the column definition and return it in the response with `type: "FILE"`

#### Scenario: FILE type column value stored as string
- **WHEN** a test case run result is written and the suite has a `FILE`-typed response column whose JSONata expression resolves to `"files/@myapp/results/out.pdf"`
- **THEN** `extracted_columns["result_file"]` SHALL be stored as the string `"files/@myapp/results/out.pdf"` (identical to STRING storage)

#### Scenario: FILE type is a display hint — no backend validation of value format
- **WHEN** a `FILE`-typed response column's JSONata expression resolves to a value that does not match the DIAL file path format
- **THEN** system SHALL store the value as-is without emitting an extraction warning for format mismatch
- **AND** the extraction proceeds normally (warnings are only emitted for JSONata evaluation failures, not for value format)

#### Scenario: FILE type column with extraction failure
- **WHEN** a `FILE`-typed response column's JSONata expression fails to evaluate (path not found, etc.)
- **THEN** `extracted_columns["result_file"]` SHALL be `null` and `extraction_warnings` SHALL contain an entry with `column`, `expression`, and `error` — identical to the behaviour for all other types
