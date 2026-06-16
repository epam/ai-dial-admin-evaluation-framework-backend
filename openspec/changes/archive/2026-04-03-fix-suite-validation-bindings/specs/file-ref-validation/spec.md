## MODIFIED Requirements

### Requirement: File ref validation at suite save time — FormPartDto

Status: Implemented

The system SHALL validate `FormPartDto.value` as a file reference when `FormPartDto.type == FILE` during test suite creation and update, **but only when the value is a literal file reference, not a template variable placeholder**. Validation SHALL occur in `SuiteValidationService` and SHALL produce a validation warning (not a hard error) for invalid literal values, consistent with the existing validation warning model.

When a FILE form part's value is a template variable placeholder (matches `${{...}}` syntax — e.g., `${{contract_file}}`, `${{attachment|file}}`, `${{doc:@ef/default.pdf}}`), the system SHALL skip `FileRefValidator` validation for that value. The actual file reference will be resolved at runtime via the binding chain. Constant-value bindings for `|file`-typed template variables are validated separately (see "File ref validation at suite save time — typed constant bindings" requirement).

The placeholder detection SHALL use the same `PLACEHOLDER_PATTERN` regex used by `TemplateVariableExtractor` for consistency. A value is considered a placeholder when the **entire string** matches the pattern (full-value placeholder), not when it merely contains a placeholder substring.

#### Scenario: Valid file ref in FormPartDto constant value accepted
- **WHEN** a multipart request template contains a `FormPartDto` with `type = FILE` and `value = "@ef/suites/{suiteId}/report.pdf"`
- **AND** the test suite is saved
- **THEN** no validation warning SHALL be produced for that form part

#### Scenario: Invalid file ref in FormPartDto constant value warns
- **WHEN** a multipart request template contains a `FormPartDto` with `type = FILE` and `value = "public/.."` (path traversal)
- **AND** the test suite is saved
- **THEN** a validation warning SHALL be produced indicating the file reference format is invalid

#### Scenario: Non-FILE FormPartDto value not validated as file ref
- **WHEN** a multipart request template contains a `FormPartDto` with `type = TEXT` and any `value`
- **THEN** no file reference format validation SHALL be applied to that value

#### Scenario: FILE form part with template variable placeholder skips file ref validation
- **WHEN** a multipart request template contains a `FormPartDto` with `type = FILE` and `value = "${{contract_file}}"`
- **AND** the test suite is saved
- **THEN** no file reference validation warning SHALL be produced for that form part
- **AND** the suite's `isValid` SHALL NOT be set to `false` because of this form part

#### Scenario: FILE form part with typed placeholder skips file ref validation
- **WHEN** a multipart request template contains a `FormPartDto` with `type = FILE` and `value = "${{attachment|file}}"`
- **AND** the test suite is saved
- **THEN** no file reference validation warning SHALL be produced for that form part

#### Scenario: FILE form part with non-file typed placeholder skips file ref validation
- **WHEN** a multipart request template contains a `FormPartDto` with `type = FILE` and `value = "${{contract_file|string}}"`
- **AND** the test suite is saved
- **THEN** no file reference validation warning SHALL be produced for that form part (skip is based on placeholder detection, not on `|file` type hint)

#### Scenario: FILE form part with placeholder with default skips file ref validation
- **WHEN** a multipart request template contains a `FormPartDto` with `type = FILE` and `value = "${{doc:@ef/suites/abc/default.pdf}}"`
- **AND** the test suite is saved
- **THEN** no file reference validation warning SHALL be produced for that form part

### Requirement: File ref validation at suite save time — typed constant bindings

Status: Implemented

The system SHALL validate `InputBindingDto.constantValue` as a file reference when the bound template variable carries a `|file` type hint (e.g., `${{attachment|file}}`). Validation SHALL occur in `SuiteValidationService` during binding validation pass and SHALL produce a validation warning for invalid values.

**This validation SHALL apply to both DEPLOYMENT and MCP_TOOL suite types.** The shared binding validation helper extracts `declaredType` from template variables; when `declaredType == FILE` and the binding has `constantValue`, the value is validated via `FileRefValidator`.

#### Scenario: Valid file ref in constant binding for |file template variable accepted
- **WHEN** a request template contains `${{dataset|file}}`
- **AND** the binding for `dataset` has `constantValue = "public/shared/input.csv"`
- **AND** the test suite is saved
- **THEN** no validation warning SHALL be produced for that binding

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

## Implementation Notes

- Placeholder detection: use `TemplateVariableExtractor.isPlaceholder(String)` in `SuiteValidationService` before calling `fileRefValidator.validate()` for FILE form parts
- The pattern must match the **full string** (use `Matcher.matches()`, not `Matcher.find()`) to distinguish full-value placeholders from strings that happen to contain `${{` substrings
- `|file` constant binding validation is in the shared `BindingValidator.validate()` component — applies to both DEPLOYMENT and MCP_TOOL suite types
