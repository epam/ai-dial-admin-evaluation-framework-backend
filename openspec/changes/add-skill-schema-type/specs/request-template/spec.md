# Request Template — delta for `add-skill-schema-type`

## MODIFIED Requirements

### Requirement: Template variable syntax
The service SHALL support the following placeholder syntax within `requestTemplate` fields:
- `${{variable}}` — a required variable (no type hint, no default)
- `${{variable:default}}` — an optional variable with a default value (the raw string after the colon)
- `${{variable|type}}` — a required variable with an explicit type hint
- `${{variable|type:default}}` — an optional variable with an explicit type hint and a default value

Variable names SHALL match `[a-zA-Z0-9_]+` (alphanumeric and underscore only). The `|` character SHALL NOT appear in a variable name; its first occurrence after the name is always the type-hint separator.

The type hint SHALL be a case-insensitive `SchemaFieldType` keyword: `string`, `integer`, `number`, `boolean`, `object`, `array`, `file`, `skill`. Default values (after `:`) are unrestricted — they may contain `|`, `:`, `::`, or any character other than `}}`.

The placeholder syntax SHALL be recognised in: `urlTemplate` string, `queryParams[*].value` strings, `headers[*].value` strings, and any string value at any depth in `body`.

#### Scenario: Parse simple variable
- **WHEN** template contains `"${{prompt}}"`
- **THEN** system SHALL extract variable `prompt` with `declaredType: null`, `hasDefault: false`

#### Scenario: Parse variable with default
- **WHEN** template contains `"${{model:gpt-4}}"`
- **THEN** system SHALL extract variable `model` with `declaredType: null`, `hasDefault: true`, `defaultValue: "gpt-4"`

#### Scenario: Parse variable with type hint
- **WHEN** template contains `"${{doc|file}}"`
- **THEN** system SHALL extract variable `doc` with `declaredType: FILE`, `hasDefault: false`

#### Scenario: Parse variable with skill type hint
- **WHEN** template contains `"${{selectedSkill|skill}}"`
- **THEN** system SHALL extract variable `selectedSkill` with `declaredType: SKILL`, `hasDefault: false`

#### Scenario: Parse variable with type hint and default
- **WHEN** template contains `"${{ctx|file:files/public/default-context.txt}}"`
- **THEN** system SHALL extract variable `ctx` with `declaredType: FILE`, `hasDefault: true`, `defaultValue: "files/public/default-context.txt"`

#### Scenario: Default value may contain pipe character
- **WHEN** template contains `"${{q|string:opt-a|opt-b}}"`
- **THEN** system SHALL extract variable `q` with `declaredType: STRING`, `hasDefault: true`, `defaultValue: "opt-a|opt-b"`

#### Scenario: Default value may contain double-colon
- **WHEN** template contains `"${{query|string:SELECT id::uuid FROM t}}"`
- **THEN** system SHALL extract variable `query` with `declaredType: STRING`, `hasDefault: true`, `defaultValue: "SELECT id::uuid FROM t"`

#### Scenario: Unknown type hint produces soft validation warning
- **WHEN** template contains `"${{doc|unknowntype}}"`
- **THEN** system SHALL extract the variable with `declaredType: null` and add a suite-level soft validation warning indicating the unrecognised type hint
- **AND** system SHALL NOT reject the request with HTTP 400

#### Scenario: Type hint is case-insensitive
- **WHEN** template contains `"${{doc|FILE}}"` or `"${{doc|File}}"` or `"${{doc|file}}"`
- **THEN** system SHALL normalise to `SchemaFieldType.FILE` in all cases

#### Scenario: Skill type hint is case-insensitive
- **WHEN** template contains `"${{s|SKILL}}"` or `"${{s|Skill}}"` or `"${{s|skill}}"`
- **THEN** system SHALL normalise to `SchemaFieldType.SKILL` in all cases

#### Scenario: Multiple variables in one string
- **WHEN** a template value contains `"Hello ${{name}}, your score is ${{score:0}}"`
- **THEN** system SHALL extract both `name` (no default) and `score` (default `"0"`)

#### Scenario: Duplicate variables across template sections
- **WHEN** the same variable name `${{model}}` appears in both `body` and `queryParams`
- **THEN** system SHALL track the variable once with multiple sources (e.g., `sources: [BODY, QUERY]`)

#### Scenario: Same variable appears multiple times within one section
- **WHEN** `${{model}}` appears at multiple paths within `body` (e.g. in two nested objects)
- **THEN** system SHALL track the variable once with source BODY; all occurrences are resolved with the same value

## ADDED Requirements

### Requirement: Runtime skill reference resolution for full-value placeholders
When a placeholder is a **full-value** placeholder (the entire string is exactly one `${{...}}`) and is a *skill placeholder*, and its resolved value is a `String`, the resolved value SHALL be replaced by its canonical skill reference `skills/{bucket}/{path-to-skill}` (see `dial-skill-ref`). A placeholder is a skill placeholder when it carries an explicit `|skill` hint (case-insensitive), or when it has **no** type hint and its binding's `dataField` names a schema field of type `SKILL`. An explicit hint of any other type (e.g. `|string`, `|file`) SHALL take precedence over the bound column's type. This SHALL apply identically to: structured JSON `content` bodies, URL-encoded bodies, `jsonataContent` bodies (typed placeholder values) and MCP tool `arguments`. A non-String resolved value SHALL be passed through unchanged. A malformed String value SHALL be passed through unchanged (DIAL Core rejects it). Placeholders **embedded** in a longer string SHALL be interpolated verbatim with no canonicalisation. A skill placeholder SHALL NOT trigger `DialFileRefResolver` conversion and SHALL NOT be eligible for multipart file-part resolution. The `FILE` resolution behaviour SHALL be unchanged. The decision SHALL be made in one shared component used by all four paths.

Status: **Implemented**
Implementation notes: `runner/service/ResourceRefPlaceholderResolver.java` (single seam), `FilePlaceholderDetector.isSkillReference`, called from `TemplateContentResolver.resolveObject` (JSON / URL-encoded), `JsonataSourcePreprocessor.resolveTypedValue` (JSONata) and `McpRequestResolver`. Tests: `ResourceRefPlaceholderResolverTest`, `FilePlaceholderDetectorTest`, `TemplateContentResolverTest`, `JsonataSourcePreprocessorTest`, `McpRequestResolverTest`, `SkillFieldFunctionalTests` (run).

#### Scenario: Explicit hint in JSON body
- **WHEN** a JSON body contains a message with `"custom_content": {"skills": [{"url": "${{s|skill}}"}]}` and `s` resolves to `"skills/b/summariser/"`
- **THEN** the request body SHALL contain `"custom_content": {"skills": [{"url": "skills/b/summariser"}]}` — the placeholder is resolved at any depth inside objects and lists

#### Scenario: Hint is case-insensitive at runtime
- **WHEN** the body uses `${{s|SKILL}}`
- **THEN** the value SHALL be canonicalised exactly as for `${{s|skill}}`

#### Scenario: Hintless placeholder bound to a SKILL column
- **WHEN** `${{s}}` has no hint and its binding's `dataField` names a `SKILL` schema field whose value is `" skills/b/x "`
- **THEN** the resolved value SHALL be `"skills/b/x"`

#### Scenario: Explicit non-skill hint wins over the column type
- **WHEN** `${{s|string}}` is bound to a `SKILL` column whose value is `" skills/b/x "`
- **THEN** the resolved value SHALL be the verbatim string `" skills/b/x "`

#### Scenario: Explicit skill hint on a STRING column opts in without a schema change
- **WHEN** `${{s|skill}}` is bound to a `STRING` schema field whose value is `" skills/b/x "`
- **THEN** the resolved value SHALL be `"skills/b/x"` (the explicit hint decides; the column type is not consulted and no schema change is required)

#### Scenario: Embedded placeholder is verbatim
- **WHEN** a body string is `"use ${{s|skill}} now"` and `s` resolves to `"skills/b/x/"`
- **THEN** the resolved string SHALL be `"use skills/b/x/ now"`

#### Scenario: JSONata body
- **WHEN** a `jsonataContent` body references a full-value `${{s|skill}}` placeholder resolving to `"skills/b/x/"`
- **THEN** the evaluated body SHALL contain the string `"skills/b/x"`

#### Scenario: URL-encoded body
- **WHEN** a URL-encoded body value is a full-value `${{s|skill}}` placeholder resolving to `"skills/b/x/"`
- **THEN** the encoded form value SHALL be `skills/b/x`

#### Scenario: FILE behaviour unchanged
- **WHEN** a full-value `${{f|file}}` placeholder resolves to `"@ef/suites/s1/a.pdf"`
- **THEN** the result SHALL be `"files/@ef/suites/s1/a.pdf"` exactly as before this change

#### Scenario: Malformed value passes through
- **WHEN** `${{s|skill}}` resolves to `"not-a-skill"`
- **THEN** the request body SHALL contain `"not-a-skill"` unchanged and the run SHALL NOT fail locally

#### Scenario: Blank value passes through
- **WHEN** `${{s|skill}}` resolves to `""` or whitespace-only `"   "` (blank is malformed per `dial-skill-ref`)
- **THEN** the request body SHALL contain the value unchanged (an empty `url` that DIAL Core rejects) and the run SHALL NOT fail locally; making the column required (so a blank value is flagged at authoring time) is the template author's responsibility

### Requirement: Template variables report SKILL types
`declaredType` and `effectiveType` of a template variable (variables APIs and try-it-out variable discovery) MAY be `SKILL`. `effectiveType` SHALL follow the existing precedence (declared hint, then endpoint type, then bound schema field type, default `STRING`), so a hintless variable bound to a `SKILL` column SHALL report `effectiveType: SKILL`. A `constantValue`-only binding (no `dataField`) is treated as a skill only with an explicit `|skill` hint, as for `FILE`.

Status: **Implemented**
Implementation notes: hint parsing is `TemplateVariableExtractor.parseTypeHint` (`SchemaFieldType.valueOf`, so `skill` needs no extra code); effective type via `TemplateVariableService`; `TemplateVariableDto` OpenAPI text and `skill` examples. Tests: `TemplateVariableExtractorTest`, `TemplateVariableServiceTest`, `TemplateVariableFunctionalTests` (OpenAPI example), `SkillFieldFunctionalTests`.

#### Scenario: Declared SKILL type
- **WHEN** a template contains `${{s|skill}}`
- **THEN** the variable SHALL be reported with `declaredType: SKILL` and `effectiveType: SKILL`

#### Scenario: Effective type from the bound column
- **WHEN** a hintless variable is bound to a `SKILL` schema field
- **THEN** it SHALL be reported with `declaredType: null` and `effectiveType: SKILL`
