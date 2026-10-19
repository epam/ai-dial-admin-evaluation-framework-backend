## MODIFIED Requirements

### Requirement: File ref validation at suite save time — typed constant bindings

Status: **Implemented**

The system SHALL validate `InputBindingDto.constantValue` as a file reference when the bound template variable carries a `|file` type hint (e.g., `${{attachment|file}}`). Validation SHALL occur in `SuiteValidationService` during binding validation pass and SHALL produce a validation warning for invalid values.

**This validation SHALL apply to all suite types (DEPLOYMENT, ROUTE and MCP_TOOL).** The shared binding validation helper extracts `declaredType` from template variables; when `declaredType == FILE` and the binding has `constantValue`, the value is validated via `FileRefValidator.validateSuiteOwnership(constantValue, suiteId)`. Dataset-shaped constant binding values SHALL produce a warning indicating that suite-level fields require suite-shaped refs.

#### Scenario: Valid file ref in constant binding for |file template variable accepted
- **WHEN** a request template contains `${{dataset|file}}`
- **AND** the binding for `dataset` has `constantValue = "public/shared/input.csv"`
- **AND** the test suite is saved
- **THEN** no validation warning SHALL be produced for that binding

#### Scenario: Dataset-shaped file ref in constant binding warns
- **WHEN** a request template contains `${{attachment|file}}`
- **AND** the binding for `attachment` has `constantValue = "@ef/datasets/{anyDatasetId}/doc.pdf"`
- **AND** the test suite is saved
- **THEN** a validation warning SHALL be produced indicating that suite-level fields require suite-shaped refs

#### Scenario: Invalid file ref in constant binding for |file template variable warns
- **WHEN** a request template contains `${{attachment|file}}`
- **AND** the binding for `attachment` has `constantValue = "files/@ef/suites/abc/old-format.pdf"` (old format with `files/` prefix)
- **AND** the test suite is saved
- **THEN** a validation warning SHALL be produced indicating the file reference format is invalid

#### Scenario: Constant binding for non-file template variable not validated as file ref
- **WHEN** a binding has `constantValue` and the template variable has `|string` or no type hint
- **THEN** no file reference format validation SHALL be applied to that constant value

#### Scenario: MCP suite — valid |file constant binding accepted
- **WHEN** an MCP_TOOL suite has `argumentTemplate.arguments = {"document": "${{doc|file}}"}`
- **AND** the binding for `doc` has `constantValue = "@ef/suites/{suiteId}/contract.pdf"`
- **AND** the test suite is saved
- **THEN** no validation warning SHALL be produced for that binding

#### Scenario: MCP suite — invalid |file constant binding warns
- **WHEN** an MCP_TOOL suite has `argumentTemplate.arguments = {"document": "${{doc|file}}"}`
- **AND** the binding for `doc` has `constantValue = "invalid-prefix/path"`
- **AND** the test suite is saved
- **THEN** a validation warning SHALL be produced with code `TYPE` indicating the file reference format is invalid

## Implementation notes

- The shared binding validation helper (`BindingValidator`) is reused; a ROUTE suite uses the HTTP (`requestTemplate`) path, same as DEPLOYMENT.
- Tests: no dedicated ROUTE test.
