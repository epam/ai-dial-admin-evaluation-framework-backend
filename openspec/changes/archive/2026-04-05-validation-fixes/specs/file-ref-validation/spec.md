<!-- Delta note: The requirement "File ref validation at test case save time delegates to FileRefValidator"
already exists in the baseline spec with 2 scenarios. This delta ADDS blank-string handling to that
requirement AND adds a new requirement for the data-vs-binding blank-string check.
Modified requirement new scenarios: "Optional FILE field with null value — no warning" (regression guard),
"Optional FILE field with blank string value — no warning",
"Optional FILE field with whitespace-only string value — no warning",
"Required FILE field with null value — warning produced" (regression guard),
"Required FILE field with blank string value — warning produced",
"Required FILE field with whitespace-only string value — warning produced",
"Required FILE field with valid file ref — no warning" (regression guard).
New requirement: "Blank-string FILE detection in data-vs-binding check" — all scenarios are new.
The requirement description texts are reproduced in full per delta-spec convention. -->

## MODIFIED Requirements

### Requirement: File ref validation at test case save time delegates to FileRefValidator

Status: Planned

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

Status: Planned

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
