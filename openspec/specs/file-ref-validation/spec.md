# File Reference Validation

## Purpose
This spec describes centralized file reference format and ownership validation via `FileRefValidator`. Covers short-format rules, `@ef` ownership scoping per suite, and delegation from `SuiteValidationService` and `TestCaseValidationService`.

Status: **Implemented**

## Requirements

### Requirement: FileRefValidator component

Status: Implemented

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

### Requirement: File ref validation at test case save time delegates to FileRefValidator

Status: Implemented

`TestCaseValidationService` SHALL delegate file reference format and ownership validation to `FileRefValidator`. The inline validation logic (`files/` prefix check, prefix whitelist check) SHALL be removed from `TestCaseValidationService` and replaced by a call to `FileRefValidator`.

A **blank string** (`""` or whitespace-only) in a FILE-typed schema field SHALL be treated as "not provided" — equivalent to `null` — and SHALL NOT be passed to `FileRefValidator`. The required-field check governs whether absence is valid; the file-ref format check governs whether a non-blank value is a valid reference.

For **required** FILE-typed schema fields, the required-field check SHALL catch both `null` values and blank strings (`""` or whitespace-only), and SHALL produce a "field is missing" warning in both cases.

#### Scenario: Valid short-format file ref in FILE schema field accepted
- **WHEN** a test case has a FILE-typed schema field with value `@ef/suites/{suiteId}/data.csv`
- **THEN** no validation warning SHALL be produced for that field

#### Scenario: Old-format file ref in FILE schema field warns
- **WHEN** a test case has a FILE-typed schema field with value `files/@ef/suites/{suiteId}/data.csv` (old format)
- **THEN** a validation warning SHALL be produced (the `files/` prefix is not part of the allowed format)

#### Scenario: Optional FILE field with null value — no warning
- **WHEN** a test case has an optional FILE-typed schema field and the data map does not contain that field (null / absent)
- **THEN** no validation warning SHALL be produced

#### Scenario: Optional FILE field with blank string value — no warning
- **WHEN** a test case has an optional FILE-typed schema field and the data map contains `""` (empty string) for that field
- **THEN** no validation warning SHALL be produced (blank string is treated as "not provided")

#### Scenario: Optional FILE field with whitespace-only string value — no warning
- **WHEN** a test case has an optional FILE-typed schema field and the data map contains `"   "` (whitespace-only string) for that field
- **THEN** no validation warning SHALL be produced (`String.isBlank()` treats whitespace-only as not provided)

#### Scenario: Required FILE field with null value — warning produced
- **WHEN** a test case has a required FILE-typed schema field and the data map does not contain that field
- **THEN** a "field is missing from data" validation warning SHALL be produced with code `REQUIRED`

#### Scenario: Required FILE field with blank string value — warning produced
- **WHEN** a test case has a required FILE-typed schema field and the data map contains `""` (empty string) for that field
- **THEN** a "field is missing from data" validation warning SHALL be produced with code `REQUIRED` (blank string is treated as absent)

#### Scenario: Required FILE field with whitespace-only string value — warning produced
- **WHEN** a test case has a required FILE-typed schema field and the data map contains `"   "` (whitespace-only string) for that field
- **THEN** a "field is missing from data" validation warning SHALL be produced with code `REQUIRED` (`String.isBlank()` treats whitespace-only as absent)

#### Scenario: Required FILE field with valid file ref — no warning
- **WHEN** a test case has a required FILE-typed schema field with a valid `@ef/suites/{suiteId}/file.csv` value
- **THEN** no validation warning SHALL be produced

### Requirement: Blank-string FILE detection in data-vs-binding check

Status: Implemented

When a template variable is bound to a FILE-typed data field, the data-vs-binding check in `TestCaseValidationService` SHALL treat a blank string (`""` or whitespace-only) in that data field as absent — equivalent to `null` — for the purposes of required-variable enforcement. The check SHALL remain type-specific: blank strings in non-FILE (e.g., STRING) data fields SHALL continue to be treated as present values and SHALL NOT trigger a required warning.

Implementation: build a `Map<String, SchemaFieldType>` from the schema before the data-vs-binding loop; extend the null condition for FILE-typed fields to `value == null || (fieldType == FILE && value instanceof String s && s.isBlank())`.

#### Scenario: Required binding + FILE field + blank string — warning produced
- **WHEN** a template variable with no default is bound to a FILE-typed data field whose value in the test case data map is `""` (empty string)
- **THEN** a "Required field is empty in data" validation warning SHALL be produced with code `REQUIRED`

#### Scenario: Required binding + FILE field + whitespace-only string — warning produced
- **WHEN** a template variable with no default is bound to a FILE-typed data field whose value in the test case data map is `"   "` (whitespace-only)
- **THEN** a "Required field is empty in data" validation warning SHALL be produced with code `REQUIRED`

#### Scenario: Required binding + FILE field + null — warning produced (regression guard)
- **WHEN** a template variable with no default is bound to a FILE-typed data field that is absent from the test case data map
- **THEN** a "Required field is empty in data" validation warning SHALL be produced with code `REQUIRED`

#### Scenario: Required binding + STRING field + blank string — no warning
- **WHEN** a template variable with no default is bound to a STRING-typed data field whose value is `""`
- **THEN** no required warning SHALL be produced (blank string is a legitimate STRING value)

#### Scenario: Optional binding (has default) + FILE field + blank string — no warning
- **WHEN** a template variable with a default value is bound to a FILE-typed data field whose value in the test case data map is `""`
- **THEN** no required warning SHALL be produced (the template default covers the missing value)

## Implementation Notes

- `FileRefValidator` — `com.epam.aidial.evaluation.service.domain.FileRefValidator`
- `SuiteValidationService` — `com.epam.aidial.evaluation.service.domain.SuiteValidationService`
- `TestCaseValidationService` — `com.epam.aidial.evaluation.service.domain.TestCaseValidationService`
- Placeholder detection: use `TemplateVariableExtractor.isPlaceholder(String)` in `SuiteValidationService` before calling `fileRefValidator.validate()` for FILE form parts
- The pattern must match the **full string** (use `Matcher.matches()`, not `Matcher.find()`) to distinguish full-value placeholders from strings that happen to contain `${{` substrings
- `|file` constant binding validation is in the shared `BindingValidator.validate()` component — applies to both DEPLOYMENT and MCP_TOOL suite types
