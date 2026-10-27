# MCP Tool Invocation — delta for `add-skill-schema-type`

## ADDED Requirements

### Requirement: MCP argument resolution canonicalises skill placeholders
`McpRequestResolver` SHALL resolve a full-value argument placeholder that is a skill placeholder (explicit `|skill` hint, case-insensitive, or no hint and a binding to a `SKILL` schema field — see `request-template`, "Runtime skill reference resolution for full-value placeholders") to its canonical skill reference `skills/{bucket}/{path}` when the resolved value is a `String`. The decision SHALL use the same shared component as the HTTP body paths. Embedded placeholders SHALL stay verbatim; a malformed value SHALL pass through unchanged; `|file` resolution SHALL be unchanged.

Status: **Implemented**
Implementation notes: `McpRequestResolver.resolveStringValue` → `resolveResourceRef` → `ResourceRefPlaceholderResolver.resolve` (shared with the HTTP body paths). Tests: `McpRequestResolverTest` (`SkillTypeHintResolution`).

#### Scenario: Explicit |skill argument
- **WHEN** `argumentTemplate.arguments = {"skill": "${{s|skill}}"}` and `s` resolves to `"skills/b/x/"`
- **THEN** the MCP tool call arguments SHALL contain `"skill": "skills/b/x"`

#### Scenario: Hintless argument bound to a SKILL column
- **WHEN** `${{s}}` has no hint and is bound to a `SKILL` schema field with value `"skills/b/x"`
- **THEN** the argument SHALL be `"skills/b/x"`

#### Scenario: Embedded placeholder verbatim
- **WHEN** an argument string is `"load ${{s|skill}}"` and `s` is `"skills/b/x/"`
- **THEN** the argument SHALL be `"load skills/b/x/"`
