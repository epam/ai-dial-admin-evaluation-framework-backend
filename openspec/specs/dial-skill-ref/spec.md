# DIAL Skill Reference

## Purpose

Defines the `SKILL` schema type and the `|skill` request-template hint: a string-backed reference to an existing DIAL Core skill (`skills/{bucket}/{path-to-skill}`). Covers the reference format and canonical form (`SkillRefResolver`), validation warnings for test-case values and constant bindings, and the rule that skills are never files (no download, ZIP packing, clone copy or ownership checks). Runtime placeholder resolution lives in `request-template`; try-out, MCP, CSV/ZIP and coercion behaviour live in their respective specs.

Status: **Implemented**

## Requirements

### Requirement: SKILL schema type is a string-backed DIAL skill reference
The `SchemaFieldType` enum SHALL include `SKILL`. A field or response column of type `SKILL` SHALL carry a **string** value that references an existing DIAL Core skill — a directory resource — in the form `skills/{bucket}/{path-to-skill}`. Everywhere the system is type-agnostic (JSON-schema type mapping, query-field type mapping, CSV cell handling, response-column reconciliation) `SKILL` SHALL behave exactly like `STRING`. `SKILL` SHALL NOT be treated as a file: it is never a multipart part, never downloaded or materialised as binary, never packed into a ZIP archive, and never copied between suites/datasets.

The system SHALL only **reference** existing skills. It SHALL NOT create, upload, copy, delete or otherwise manage skills, and SHALL NOT call DIAL Core skill routes. DIAL Core SHALL remain authoritative for skill existence, access rights and request-time sharing; the system SHALL NOT check existence or access.

Status: **Implemented**
Implementation notes: `SchemaFieldType.SKILL` (runner-core `runner/dto/SchemaFieldType.java`); string-like handling in `ResponseColumnTypeReconciler`, `SchemaValidationService.jsonSchemaType`, `query/service/SchemaFieldTypeMapper`, `SchemaTypeCoercer`; never routed to `DialFileRefResolver` / `DialFileClient` (see `ResourceRefPlaceholderResolver`). Tests: `SkillFieldFunctionalTests` (`PostgresFunctionalTests$SkillFieldTests`).

#### Scenario: SKILL accepted as a schema field type
- **WHEN** a client saves a test-suite or dataset `testCaseSchema` containing a field `{"name": "skill", "type": "SKILL"}`
- **THEN** the system SHALL accept and persist the schema and return the field with `type: "SKILL"`

#### Scenario: SKILL value is persisted verbatim
- **WHEN** a test case stores `"skills/my-bucket/summariser"` in a `SKILL` field
- **THEN** reading the test case back SHALL return exactly `"skills/my-bucket/summariser"`

#### Scenario: SKILL is never downloaded
- **WHEN** a run, try-out or export processes a test case with a `SKILL` value
- **THEN** the system SHALL NOT call `DialFileClient` download/upload and SHALL NOT call any DIAL Core skill route

### Requirement: Skill reference format and canonical form
A skill reference is **well-formed** when, after trimming surrounding whitespace and at most one trailing `/`, it (a) starts with the case-sensitive prefix `skills/`, (b) has a non-empty `{bucket}` segment followed by at least one non-empty path segment, and (c) contains no segment equal to `.` or `..`, no backslash and no control character. No further restriction is placed on segment characters. The **canonical reference** of a well-formed value is that trimmed value, `skills/{bucket}/{path-to-skill}`. A single injectable component (`SkillRefResolver`, runner-core `client.dialcore`) SHALL own both checks so validation and runtime resolution cannot drift; it SHALL NOT delegate to `DialFileRefResolver`.

Status: **Implemented**
Implementation notes: `runner/client/dialcore/SkillRefResolver.java` (`validate`, `toCanonicalRef`), a `@Component` picked up by `EvaluationRunnerAutoConfiguration`'s component scan. Tests: `SkillRefResolverTest`.

#### Scenario: Well-formed reference
- **WHEN** the value `skills/my-bucket/team/summariser` is validated
- **THEN** it SHALL be well-formed and its canonical form SHALL be `skills/my-bucket/team/summariser`

#### Scenario: Surrounding whitespace and one trailing slash are normalised
- **WHEN** the value `  skills/my-bucket/summariser/  ` is canonicalised
- **THEN** the canonical form SHALL be `skills/my-bucket/summariser`

#### Scenario: Missing or wrong prefix is malformed
- **WHEN** the value is `my-bucket/summariser`, `Skills/my-bucket/summariser`, `files/my-bucket/summariser` or `/skills/my-bucket/summariser`
- **THEN** it SHALL be malformed

#### Scenario: Bucket-only reference is malformed
- **WHEN** the value is `skills/my-bucket` or `skills/my-bucket/`
- **THEN** it SHALL be malformed

#### Scenario: Empty, dot and backslash segments are malformed
- **WHEN** the value is `skills/b//x`, `skills/b/./x`, `skills/b/../x` or contains a `\`
- **THEN** it SHALL be malformed

#### Scenario: Double trailing slash is malformed
- **WHEN** the value is `skills/b/x//`
- **THEN** it SHALL be malformed (only one trailing `/` is trimmed, leaving an empty final segment)

#### Scenario: Control characters are malformed
- **WHEN** the value contains a control character such as a tab or newline inside a segment (e.g. `skills/b/x\ty`)
- **THEN** it SHALL be malformed (control characters are only tolerated as surrounding whitespace that is trimmed)

#### Scenario: Malformed value is left unchanged by canonicalisation
- **WHEN** a malformed value is passed to canonicalisation
- **THEN** it SHALL be returned unchanged (DIAL Core rejects it at request time)

### Requirement: Skill value validation warnings
A non-empty `SKILL` value in test-case data SHALL be validated by the skill reference format check. A malformed value SHALL produce a validation warning with code `TYPE` and message `SKILL field '<name>': <error>`, following the existing validation-warning model (never an HTTP 400 for a test-case write). A `null` value in a non-required field SHALL produce no warning; a `null` or blank-string value in a required field SHALL produce the standard required-field warning. A constant binding (`constantValue`) for a template variable whose declared type is `SKILL` SHALL be validated the same way and produce a `TYPE` warning `Constant binding for '|skill' variable '<name>': <error>` when malformed. The system SHALL NOT perform `@ef`/`public` ownership checks or existence checks for skill values. A default value on a skill placeholder (`${{s|skill:skills/b/x/}}`) and a value supplied in a variables try-out call are canonicalised at run time by the same resolution seam but are NOT format-validated (DIAL Core remains authoritative and rejects a malformed value at request time).

Status: **Implemented**
Implementation notes: `TestCaseValidationService.validateSkillFields` (+ `isBlankReference`, `isTypeCompatible`) and the `|skill` branch of `BindingValidator.validate`, both using `SkillRefResolver.validate`; no `FileRefValidator` call. Tests: `TestCaseValidationServiceSkillTest`, `SuiteValidationServiceTest` (`McpBindingValidation`), `SkillFieldFunctionalTests`.

#### Scenario: Malformed SKILL value warns
- **WHEN** a test case stores `"my-bucket/summariser"` in a `SKILL` field named `skill`
- **THEN** the test case SHALL be saved and its validation warnings SHALL contain a `TYPE` warning starting with `SKILL field 'skill':`

#### Scenario: Well-formed SKILL value does not warn
- **WHEN** a test case stores `"skills/my-bucket/summariser"` in a `SKILL` field
- **THEN** no validation warning SHALL be produced for that field

#### Scenario: Non-string SKILL value is a type mismatch
- **WHEN** a test case stores the number `42` in a `SKILL` field
- **THEN** the standard type-mismatch `TYPE` warning SHALL be produced, as for a `STRING` field

#### Scenario: Required SKILL field with blank value
- **WHEN** a required `SKILL` field has the value `"   "`
- **THEN** the standard "Required field '<name>' has no value in data" warning SHALL be produced

#### Scenario: Malformed constant binding warns
- **WHEN** a template contains `${{skill|skill}}` and the binding for `skill` has `constantValue = "bad/value"`
- **THEN** saving the suite SHALL produce a `TYPE` warning for that binding and SHALL NOT be rejected with HTTP 400

#### Scenario: No ownership check for skills
- **WHEN** a `SKILL` value or constant binding names a bucket different from any suite or dataset bucket
- **THEN** no ownership warning SHALL be produced
