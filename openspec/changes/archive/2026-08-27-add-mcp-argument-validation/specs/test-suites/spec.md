## ADDED Requirements

### Requirement: MCP argument coverage against the tool input schema

MCP suite soft validation SHALL check `argumentTemplate.arguments` against `toolRef.inputSchema`, in
addition to the existing template-variable / binding / dataset-schema cross-checks.

Every property named in `inputSchema.required` SHALL be **satisfied**. A required argument is
satisfied when `argumentTemplate.arguments` contains an entry under that name whose **effective value**
is present and non-blank, where the effective value is resolved as follows:

- a constant value — used as-is; a `null`, empty, or whitespace-only string is **not** satisfied, and a
  non-string constant (number, boolean, object, array) is always satisfied;
- a `${{variable}}` placeholder — resolved through the suite's `inputBindings`, using the same
  precedence the run-time resolver applies: a binding carrying a `dataField` is satisfied; a binding
  carrying only a `constantValue` follows the constant rule above, and that constant wins even when
  the placeholder also declares an inline default; with no binding, a placeholder that declares no
  default is left to the existing unbound-variable check and SHALL NOT additionally produce an
  argument-coverage warning, while a placeholder that declares a default is satisfied only when that
  default is non-blank.

Each unsatisfied required argument SHALL produce one validation warning with `code = REQUIRED`,
`fieldName` = the argument name, and `path = "$.argumentTemplate.arguments"`, which makes the suite
`isValid = false`.

An argument name that is not declared in `inputSchema.properties` SHALL NOT produce a warning: JSON
Schema permits additional properties by default, and `toolRef.inputSchema` is a client-supplied
snapshot rather than a live read of the tool, so a stale snapshot must never invalidate a suite the
tool would accept.

The check SHALL degrade gracefully: when `toolRef` is absent, `toolRef.inputSchema` is absent, or the
schema declares no `properties`, the check SHALL produce no warnings.

The check SHALL apply to the top level of `inputSchema` only; `required` declared inside a nested
object property is out of scope and SHALL NOT be evaluated.

Because suite validity is recomputed on suite create/update, on clone, and on dataset-rooted
revalidation, the check SHALL apply on all three paths.

Status: **Implemented**

Implementation notes: `service.domain.McpArgumentValidator` (effective-value resolution, invoked from
`SuiteValidationService.validateMcpSuite`), `service.domain.JsonSchemaPropertyExtractor` (shared
`properties`/`required` reading, also used by `MetricDefinitionValidationService`), and
`TemplateVariableExtractor.parsePlaceholder` (single full-value placeholder parsing).

#### Scenario: Required argument saved with an empty constant
- **WHEN** an MCP_TOOL suite has `toolRef.inputSchema.required = ["repoName"]` and
  `argumentTemplate.arguments = {"repoName": ""}`
- **THEN** `isValid` SHALL be `false` and `validationWarnings` SHALL include a `REQUIRED` warning with
  `fieldName = "repoName"` and `path = "$.argumentTemplate.arguments"`

#### Scenario: Required argument missing from the template
- **WHEN** an MCP_TOOL suite has `toolRef.inputSchema.required = ["repoName"]` and
  `argumentTemplate.arguments` contains no `repoName` entry
- **THEN** `isValid` SHALL be `false` and `validationWarnings` SHALL include a `REQUIRED` warning with
  `fieldName = "repoName"`

#### Scenario: Required argument set to null
- **WHEN** an MCP_TOOL suite has `toolRef.inputSchema.required = ["repoName"]` and
  `argumentTemplate.arguments = {"repoName": null}`
- **THEN** `isValid` SHALL be `false` and `validationWarnings` SHALL include a `REQUIRED` warning with
  `fieldName = "repoName"`

#### Scenario: Required argument set to whitespace only
- **WHEN** an MCP_TOOL suite has `toolRef.inputSchema.required = ["repoName"]` and
  `argumentTemplate.arguments = {"repoName": "   "}`
- **THEN** `isValid` SHALL be `false` and `validationWarnings` SHALL include a `REQUIRED` warning with
  `fieldName = "repoName"`

#### Scenario: Required argument bound to a blank constant
- **WHEN** an MCP_TOOL suite has `toolRef.inputSchema.required = ["repoName"]`,
  `argumentTemplate.arguments = {"repoName": "${{repo}}"}`, and an `inputBindings` entry for `repo`
  whose `constantValue` is `""`
- **THEN** `isValid` SHALL be `false` and `validationWarnings` SHALL include a `REQUIRED` warning with
  `fieldName = "repoName"`

#### Scenario: Required argument bound to a data field
- **WHEN** an MCP_TOOL suite has `toolRef.inputSchema.required = ["repoName"]`,
  `argumentTemplate.arguments = {"repoName": "${{repo}}"}`, and an `inputBindings` entry for `repo`
  whose `dataField` names a field present in the dataset's `testCaseSchema`
- **THEN** no argument-coverage warning SHALL be produced for `repoName`

#### Scenario: Optional argument left empty
- **WHEN** an MCP_TOOL suite declares `branch` in `inputSchema.properties` but not in
  `inputSchema.required`, and `argumentTemplate.arguments = {"repoName": "dial", "branch": ""}`
- **THEN** no warning SHALL be produced for `branch`

#### Scenario: Argument not declared by the tool
- **WHEN** `argumentTemplate.arguments` contains an entry whose name is absent from
  `inputSchema.properties`
- **THEN** no argument-coverage warning SHALL be produced for that argument

#### Scenario: Required argument whose placeholder carries a blank default
- **WHEN** an MCP_TOOL suite has `toolRef.inputSchema.required = ["repoName"]`,
  `argumentTemplate.arguments = {"repoName": "${{repo:}}"}`, and no binding for `repo`
- **THEN** `isValid` SHALL be `false` and `validationWarnings` SHALL include a `REQUIRED` warning with
  `fieldName = "repoName"`

#### Scenario: Required argument whose placeholder carries a usable default
- **WHEN** an MCP_TOOL suite has `toolRef.inputSchema.required = ["repoName"]`,
  `argumentTemplate.arguments = {"repoName": "${{repo:main}}"}`, and no binding for `repo`
- **THEN** no argument-coverage warning SHALL be produced for `repoName`

#### Scenario: Tool schema absent or without properties
- **WHEN** an MCP_TOOL suite has no `toolRef`, a `toolRef` without `inputSchema`, or an `inputSchema`
  declaring no `properties`
- **THEN** the argument-coverage check SHALL contribute no warnings and `isValid` SHALL be determined
  by the other MCP checks alone

#### Scenario: Nested required properties are not evaluated
- **WHEN** `inputSchema.properties.filters` is an object schema declaring its own `required` list, and
  the corresponding argument value supplies an object missing one of those nested properties
- **THEN** no argument-coverage warning SHALL be produced for the nested property

#### Scenario: Coverage re-checked on clone and revalidation
- **WHEN** an MCP_TOOL suite with an unsatisfied required argument is cloned, or its bound dataset's
  `testCaseSchema` is updated and the dataset-rooted revalidation task refreshes the suite
- **THEN** the resulting suite's `isValid` SHALL be `false` with the same `REQUIRED` warning
