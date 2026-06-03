## ADDED Requirements

### Requirement: FileRefValidator component

Status: Planned

The system SHALL provide a `FileRefValidator` `@Component` in `service.domain` that centralizes all file reference format and ownership validation. Other services (`TestCaseValidationService`, `SuiteValidationService`) SHALL delegate to this component rather than implementing inline validation logic.

The validator SHALL apply the following rules to the **short format** file reference `{prefix}/{segment}[/{segment}...]`:
1. Value is not blank
2. Starts with an allowed prefix (`@ef` alias value from config, or `public`)
3. Has at least one path segment after the prefix (e.g., `@ef/` alone is invalid)
4. Each path segment matches `[a-zA-Z0-9\-_. ()]`
5. No path segment equals `..`
6. No leading or trailing slash

The `validate(ref, suiteId)` method performs both format and ownership validation in one call. Ownership validation is also exposed as a standalone method `validateOwnership(ref, suiteId)` for callers that need ownership-only checks. When `suiteId == null`, the ownership check is a no-op (create flow — suite UUID not yet assigned); when non-null, the `@ef` ref must start with `@ef/suites/{suiteId}/`.

#### Scenario: Valid EF file reference passes format validation
- **WHEN** `FileRefValidator.validate("@ef/suites/abc-123/data.csv", suiteId)` is called
- **THEN** it SHALL return no validation errors

#### Scenario: Valid public file reference passes format validation
- **WHEN** `FileRefValidator.validate("public/datasets/eval-data.csv", suiteId)` is called
- **THEN** it SHALL return no validation errors

#### Scenario: Missing files/ prefix is now the correct format
- **WHEN** a file reference does not start with `files/`
- **THEN** `FileRefValidator` SHALL accept it (the `files/` prefix is NOT part of the client format)

#### Scenario: Disallowed prefix rejected
- **WHEN** `FileRefValidator.validate("user-bucket/path/file.csv", suiteId)` is called
- **THEN** it SHALL return a validation error indicating the prefix is not allowed

#### Scenario: Path traversal rejected
- **WHEN** `FileRefValidator.validate("public/../etc/passwd", suiteId)` is called
- **THEN** it SHALL return a validation error indicating `..` segments are not allowed

#### Scenario: Invalid characters in path segment rejected
- **WHEN** a file reference contains a segment with characters outside `[a-zA-Z0-9\-_. ()]` (e.g., `@ef/suites/abc/fi<le>.csv`)
- **THEN** it SHALL return a validation error indicating invalid characters

#### Scenario: Empty segment rejected
- **WHEN** a file reference contains an empty segment (e.g., `public//file.csv`)
- **THEN** it SHALL return a validation error

#### Scenario: Prefix alone (no path) rejected
- **WHEN** a file reference is only a prefix with no path segments (e.g., `@ef/` or `public`)
- **THEN** it SHALL return a validation error

### Requirement: File ref validation at suite save time — FormPartDto

Status: Planned

The system SHALL validate `FormPartDto.value` as a file reference when `FormPartDto.type == FILE` during test suite creation and update. Validation SHALL occur in `SuiteValidationService` and SHALL produce a validation warning (not a hard error) for invalid values, consistent with the existing validation warning model.

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

### Requirement: File ref validation at suite save time — typed constant bindings

Status: Planned

The system SHALL validate `InputBindingDto.constantValue` as a file reference when the bound template variable carries a `|file` type hint (e.g., `${{attachment|file}}`). Validation SHALL occur in `SuiteValidationService` during binding validation pass and SHALL produce a validation warning for invalid values.

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

### Requirement: File ref validation at test case save time delegates to FileRefValidator

Status: Planned

`TestCaseValidationService` SHALL delegate file reference format and ownership validation to `FileRefValidator`. The inline validation logic (`files/` prefix check, prefix whitelist check) SHALL be removed from `TestCaseValidationService` and replaced by a call to `FileRefValidator`.

#### Scenario: Valid short-format file ref in FILE schema field accepted
- **WHEN** a test case has a FILE-typed schema field with value `@ef/suites/{suiteId}/data.csv`
- **THEN** no validation warning SHALL be produced for that field

#### Scenario: Old-format file ref in FILE schema field warns
- **WHEN** a test case has a FILE-typed schema field with value `files/@ef/suites/{suiteId}/data.csv` (old format)
- **THEN** a validation warning SHALL be produced (the `files/` prefix is not part of the allowed format)

## Implementation Notes

_(To be filled after implementation — link relevant class names and package paths here.)_
