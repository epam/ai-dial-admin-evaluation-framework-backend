## ADDED Requirements

### Requirement: `|file` hint bound to a non-FILE schema column produces a TYPE warning

The shared `BindingValidator` SHALL emit a `ValidationWarningCode.TYPE` warning when a template variable carries an explicit `|file` type hint and its binding has a non-blank `dataField` that names a field present in the test case schema with a type other than `FILE`. The warning SHALL set `fieldName` to the variable name, `path` to the binding path (`$.inputBindings`, or `$.additionalRequests[i].inputBindings`), and a message naming the variable, the column and its type, and instructing to bind a `FILE` column or remove the hint. The check SHALL apply to both DEPLOYMENT and MCP_TOOL suites and SHALL NOT fire for `constantValue` bindings, for a `dataField` absent from the schema (already reported as `UNKNOWN`), or for an empty/`null` schema. Because suite validity is `warnings.isEmpty()`, such a suite is reported invalid until corrected.

Status: Planned

A hintless placeholder bound to a `FILE` column SHALL produce no warning (it is resolved by schema type at run time), and an explicit non-file hint on a `FILE` column SHALL produce no warning.

Multipart `FILE` form-part validation (skip for placeholder values) is unchanged.

#### Scenario: `|file` bound to a STRING column warns
- **WHEN** a request template contains `${{attachment|file}}`
- **AND** a binding maps `attachment` to `dataField: "title"` where `title` has `type: STRING`
- **THEN** validation SHALL produce a warning with `code = TYPE`, `fieldName = "attachment"`, `path = "$.inputBindings"` and a message mentioning field `title` and type `STRING`
- **AND** the suite validation result SHALL be `valid = false`

#### Scenario: `|file` bound to a FILE column produces no warning
- **WHEN** a request template contains `${{attachment|file}}`
- **AND** `attachment` is bound to a `FILE` schema field
- **THEN** no mismatch warning SHALL be produced

#### Scenario: Hintless placeholder bound to a FILE column produces no warning
- **WHEN** a request template contains `${{file}}` bound to a `FILE` schema field
- **THEN** validation SHALL produce no warning for that variable and the suite SHALL be valid (no other problems)

#### Scenario: Mismatch not reported for constantValue binding
- **WHEN** a request template contains `${{doc|file}}` and its binding has only `constantValue`
- **THEN** no hint/column mismatch warning SHALL be produced (constant file refs are validated by the typed constant-binding rules)

#### Scenario: Unknown dataField reports only UNKNOWN
- **WHEN** `${{doc|file}}` is bound to `dataField: "missing"` not present in the schema
- **THEN** exactly the existing `UNKNOWN` warning SHALL be produced and no mismatch warning

#### Scenario: Additional request path prefix
- **WHEN** the mismatch occurs in `additionalRequests[1]`
- **THEN** the warning `path` SHALL be `$.additionalRequests[1].inputBindings`

#### Scenario: MCP suite mismatch warns
- **WHEN** an MCP_TOOL suite has `argumentTemplate.arguments = {"document": "${{doc|file}}"}`
- **AND** `doc` is bound to a `STRING` schema field
- **THEN** validation SHALL produce the same `TYPE` mismatch warning

#### Implementation notes
- `BindingValidator.validate` (root app, `service.domain`) — new check in the template→binding loop; column type via `FilePlaceholderDetector.boundFieldType`. No change to `SuiteValidationService` validity calculation.
