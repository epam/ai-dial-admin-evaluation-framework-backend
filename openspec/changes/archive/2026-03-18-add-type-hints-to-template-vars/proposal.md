## Why

DIAL applications commonly exchange files as DIAL file storage path references in JSON request and response bodies — not as binary content. The current `${{variable}}` placeholder syntax in `requestTemplate` has no way to declare that a variable expects a DIAL file reference (or any specific type), forcing clients/FE to infer type solely from bindings or fall back to STRING. This prevents the FE from rendering the correct input control (file picker vs. text box) when a binding is absent, uses a constant value, or is not yet configured. Additionally, `FILE` type is present in the `SchemaFieldType` enum for `responseColumns` but is not officially specified or documented for that use, leaving FE with no standard way to render a response column that contains a DIAL file path.

## What Changes

- **Extend template variable placeholder syntax** — add `|type` type-hint support: `${{name|type}}` and `${{name|type:default}}`. The `|` separator is unambiguous because variable names are restricted to `[a-zA-Z0-9_]+` (no `|` allowed). Defaults (after `:`) remain unrestricted. All existing `${{name}}` and `${{name:default}}` usages are unchanged.
- **Add `declaredType` and `effectiveType` to `TemplateVariableDto`** — `declaredType` reflects the type annotation from the placeholder syntax (nullable); `effectiveType` is the fully resolved type using priority: declared → endpointRef schema → binding/testCaseSchema → STRING fallback. Replaces/extends the existing `inferredType` field.
- **Formally specify `FILE` type for `responseColumns`** — `FILE` is an official, documented value for `ResponseColumnDefinitionDto.type`. Semantics: display hint for clients (FE renders as a clickable/downloadable link). No change to backend extraction or storage behavior in this phase.

## Capabilities

### New Capabilities

*(none — all changes are extensions of existing capabilities)*

### Modified Capabilities

- `request-template`: Extend placeholder syntax with `|type` annotation; update variable name validation; update type inference chain in `TemplateVariableDto` (`declaredType`, `effectiveType`).
- `response-columns`: Officially document and spec `FILE` as a valid type value for `ResponseColumnDefinitionDto`; define its display-hint semantics.

## Impact

- **Parsing**: `TemplateVariableExtractor` (or equivalent) must handle `|` separator; variable name validation updated to `[a-zA-Z0-9_]+`.
- **DTOs**: `TemplateVariableDto` — add `declaredType: SchemaFieldType` (nullable), rename/replace `inferredType` with `effectiveType: SchemaFieldType`.
- **Service**: Template variable resolution logic — add declared type as highest-priority source in type inference chain.
- **Spec docs**: `request-template` and `response-columns` specs updated.
- **No DB schema changes.** No Flyway migrations needed.
- The `TemplateVariableDto.inferredType` JSON field is renamed to `effectiveType` — a `@JsonAlias("inferredType")` will be added for one release cycle to maintain backward compatibility. All other API changes are additive. Existing `${{name}}` and `${{name:default}}` syntax unchanged. `TemplateVariableDto` gains new fields (additive).
