## ADDED Requirements

### Requirement: Runtime file resolution agrees with the effective FILE type of a template variable

A template variable whose `TemplateVariableDto.effectiveType` is `FILE` because of its binding's `testCaseSchema` column (declared type absent) SHALL be treated as a file reference at run time and in try-it-out/preview resolution, identically to a variable declared `|file`. The system SHALL NOT report a variable as `FILE` in `TemplateVariableDto` while sending its raw `@ef/...` value to the deployment or MCP tool. This applies to full-value placeholders only; interpolated placeholders are unchanged.

Status: Planned

#### Scenario: Effective FILE type from the binding is honoured at run time
- **WHEN** a suite's template contains `"${{doc}}"` and a binding maps `doc` → `dataField: "input_doc"` where `input_doc` has `type: FILE` in the schema (so the variable reports `declaredType: null`, `effectiveType: "FILE"`)
- **AND** a run resolves a test case whose `input_doc` is `@ef/datasets/abc/report.pdf`
- **THEN** the resolved request SHALL contain `files/{realBucket}/datasets/abc/report.pdf` for that placeholder

#### Scenario: Try-out with variables requires an explicit hint
- **WHEN** try-out is invoked with `variables = {0: {"doc": "@ef/datasets/abc/report.pdf"}}` (variables become `constantValue` bindings with no column)
- **AND** the template placeholder is `${{doc}}` (no hint)
- **THEN** the value SHALL be passed through unconverted
- **AND** with `${{doc|file}}` it SHALL be converted to a DIAL ref

#### Scenario: Declared hint still wins over the binding column type
- **WHEN** a template contains `"${{doc|string}}"` and `doc` is bound to a `FILE` schema field
- **THEN** the variable's resolved value SHALL NOT be converted to a DIAL ref

#### Scenario: Validation and resolution agree for a hintless FILE-bound placeholder
- **WHEN** a suite has `${{file}}` bound to a `FILE` column and no other problem
- **THEN** suite validation SHALL report no warning for it
- **AND** a run SHALL send the DIAL ref, not the raw `@ef/...` value

#### Implementation notes
- Runtime: `FilePlaceholderDetector` (see `dial-file-ref` spec). Effective-type inference: `TemplateVariableService` (unchanged).
