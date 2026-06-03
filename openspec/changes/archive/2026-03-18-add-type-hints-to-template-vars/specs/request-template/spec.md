## MODIFIED Requirements

### Requirement: Template variable syntax
The service SHALL support the following placeholder syntax within `requestTemplate` fields:
- `${{variable}}` — a required variable (no type hint, no default)
- `${{variable:default}}` — an optional variable with a default value (the raw string after the colon)
- `${{variable|type}}` — a required variable with an explicit type hint
- `${{variable|type:default}}` — an optional variable with an explicit type hint and a default value

Variable names SHALL match `[a-zA-Z0-9_]+` (alphanumeric and underscore only). The `|` character SHALL NOT appear in a variable name; its first occurrence after the name is always the type-hint separator.

The type hint SHALL be a case-insensitive `SchemaFieldType` keyword: `string`, `integer`, `number`, `boolean`, `object`, `array`, `file`. Default values (after `:`) are unrestricted — they may contain `|`, `:`, `::`, or any character other than `}}`.

The placeholder syntax SHALL be recognised in: `urlTemplate` string, `queryParams[*].value` strings, `headers[*].value` strings, and any string value at any depth in `body`.

#### Scenario: Parse variable with type hint
- **WHEN** template contains `"${{doc|file}}"`
- **THEN** system SHALL extract variable `doc` with `declaredType: FILE`, `hasDefault: false`

#### Scenario: Parse variable with type hint and default
- **WHEN** template contains `"${{ctx|file:files/public/default-context.txt}}"`
- **THEN** system SHALL extract variable `ctx` with `declaredType: FILE`, `hasDefault: true`, `defaultValue: "files/public/default-context.txt"`

#### Scenario: Default value may contain pipe character
- **WHEN** template contains `"${{q|string:opt-a|opt-b}}"`
- **THEN** system SHALL extract variable `q` with `declaredType: STRING`, `hasDefault: true`, `defaultValue: "opt-a|opt-b"`

#### Scenario: Default value may contain double-colon
- **WHEN** template contains `"${{query|string:SELECT id::uuid FROM t}}"`
- **THEN** system SHALL extract variable `query` with `declaredType: STRING`, `hasDefault: true`, `defaultValue: "SELECT id::uuid FROM t"`

#### Scenario: Existing syntax unchanged — simple variable
- **WHEN** template contains `"${{prompt}}"`
- **THEN** system SHALL extract variable `prompt` with `declaredType: null`, `hasDefault: false` (unchanged behaviour)

#### Scenario: Existing syntax unchanged — variable with default
- **WHEN** template contains `"${{model:gpt-4}}"`
- **THEN** system SHALL extract variable `model` with `declaredType: null`, `hasDefault: true`, `defaultValue: "gpt-4"` (unchanged behaviour)

#### Scenario: Unknown type hint produces soft validation warning
- **WHEN** template contains `"${{doc|unknowntype}}"`
- **THEN** system SHALL extract the variable with `declaredType: null` and add a suite-level soft validation warning indicating the unrecognised type hint
- **AND** system SHALL NOT reject the request with HTTP 400

#### Scenario: Type hint is case-insensitive
- **WHEN** template contains `"${{doc|FILE}}"` or `"${{doc|File}}"` or `"${{doc|file}}"`
- **THEN** system SHALL normalise to `SchemaFieldType.FILE` in all cases

### Requirement: TemplateVariableDto structure
The template variables convenience API (`GET /api/v1/test-suites/{id}/template-variables` and `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/template-variables`) SHALL return `TemplateVariableDto` entries with the following fields: `name`, `sources`, `hasDefault`, `defaultValue`, `binding`, `declaredType`, `effectiveType`, `resolvedValue`.

`declaredType` (SchemaFieldType, nullable): The type explicitly declared in the placeholder syntax via `|type`. Null when no type hint is present in the placeholder.

`effectiveType` (SchemaFieldType, non-null): The fully resolved type for this variable, determined by the following priority chain:
1. `declaredType` from placeholder syntax (highest priority)
2. `endpointRef` schema type (requestBodySchema or parameter definition)
3. `testCaseSchema` field type via the binding's `dataField`
4. `STRING` fallback (lowest priority)

The legacy `inferredType` field is replaced by `effectiveType`. The JSON property name SHALL be `effectiveType`.

#### Scenario: TemplateVariableDto with declared FILE type
- **WHEN** template contains `"${{doc|file}}"` and no binding exists
- **THEN** the entry SHALL have `declaredType: "FILE"`, `effectiveType: "FILE"`

#### Scenario: TemplateVariableDto — declared type overrides binding inference
- **WHEN** template contains `"${{doc|file}}"` and a binding maps `doc` → `dataField: "title"` where `title` has `type: STRING` in testCaseSchema
- **THEN** the entry SHALL have `declaredType: "FILE"`, `effectiveType: "FILE"` (declared wins over endpointRef and binding)

#### Scenario: TemplateVariableDto — binding inference used when no declared type
- **WHEN** template contains `"${{doc}}"` and a binding maps `doc` → `dataField: "input_doc"` where `input_doc` has `type: FILE` in testCaseSchema
- **THEN** the entry SHALL have `declaredType: null`, `effectiveType: "FILE"` (from binding)

#### Scenario: TemplateVariableDto — STRING fallback when no declared type and no binding
- **WHEN** template contains `"${{prompt}}"` and no binding exists
- **THEN** the entry SHALL have `declaredType: null`, `effectiveType: "STRING"`

#### Scenario: TemplateVariableDto — no declared type and constant-value binding
- **WHEN** template contains `"${{model}}"`, no endpointRef schema entry exists for `model`, and a binding maps `model` → `constantValue: "gpt-4"`
- **THEN** the entry SHALL have `declaredType: null`, `effectiveType: "STRING"` (constant-value bindings have no dataField, so testCaseSchema type inference is not applicable; falls through to STRING)

#### Scenario: Variable source tracking (unchanged)
- **WHEN** `${{model}}` appears in both `body` and `queryParams`
- **THEN** system SHALL return a single entry with `sources: [BODY, QUERY]`
