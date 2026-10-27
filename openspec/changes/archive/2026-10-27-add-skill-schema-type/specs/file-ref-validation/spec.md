# File Ref Validation — delta for `add-skill-schema-type`

## ADDED Requirements

### Requirement: Skill references are outside file reference validation
`FileRefValidator` and all file-ownership validation (suite/dataset `@ef` ownership, `public` prefix whitelist, dataset-shaped constant-binding rejection) SHALL apply only to `FILE`-typed fields, `FILE` form parts and `|file` constant bindings. `SKILL`-typed fields and `|skill` constant bindings SHALL NOT be passed to `FileRefValidator`; they are validated solely by the skill reference format check (`dial-skill-ref`). A `|file` constant binding SHALL NOT accept a skill-shaped value as valid, and a `|skill` constant binding SHALL NOT accept a file-shaped value as valid.

Status: **Implemented**
Implementation notes: `FileRefValidator` is still called only for FILE fields (`TestCaseValidationService.validateFileFields`) and `|file` bindings (`BindingValidator`); SKILL goes through `SkillRefResolver` only. Tests: `FileRefValidatorTest` (skill-shaped value is an invalid file ref), `TestCaseValidationServiceSkillTest` (no ownership call), `SuiteValidationServiceTest`.

#### Scenario: Skill value is not subject to ownership checks
- **WHEN** a `SKILL` field holds `"skills/other-bucket/x"` in a test case of any suite
- **THEN** no file-ownership warning SHALL be produced

#### Scenario: File-shaped value in a skill binding warns
- **WHEN** a template contains `${{s|skill}}` and the binding has `constantValue = "@ef/suites/s1/a.pdf"`
- **THEN** a `TYPE` warning for the skill format SHALL be produced

#### Scenario: Skill-shaped value in a file binding warns
- **WHEN** a template contains `${{f|file}}` and the binding has `constantValue = "skills/b/x"`
- **THEN** the existing file-reference format warning SHALL be produced
