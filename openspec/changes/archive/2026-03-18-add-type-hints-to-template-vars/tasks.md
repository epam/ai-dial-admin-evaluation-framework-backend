## 1. Placeholder Parser (`TemplateVariableExtractor`)

- [x] 1.1 Update `PLACEHOLDER_PATTERN` regex in `TemplateVariableExtractor` to `\$\{\{([^:|}]+)(?:\|([^:}]+))?(?::([^}]*))?\}\}` (3 groups: name, type-hint, default)
- [x] 1.2 Update `extractFromString()` to read group 2 as type-hint and group 3 as default (was groups 1 and 2); parse type-hint case-insensitively against `SchemaFieldType` values
- [x] 1.3 Add `declaredType: SchemaFieldType` (nullable) field to `ExtractedVariable` inner class; populate from parsed type-hint (null when absent or unrecognised)
- [x] 1.4 Emit a suite-level soft validation warning when the type-hint token is present but does not match any `SchemaFieldType` value (unrecognised type hint)
- [x] 1.5 Wire unknown-type-hint warnings from `TemplateVariableExtractor` into suite validation pipeline (update `TestSuiteValidator` or equivalent to collect warnings during save and merge into suite's `validationWarnings`)

## 2. DTO Changes (`TemplateVariableDto`)

- [x] 2.1 Add `declaredType: SchemaFieldType` (nullable) field to `TemplateVariableDto`; add `@Schema` annotation describing it as the explicit type from the placeholder syntax
- [x] 2.2 Rename `inferredType` → `effectiveType` in `TemplateVariableDto`; update `@Schema` description to reflect the resolved priority chain (declared → endpointRef → binding+testCaseSchema → STRING fallback)
- [x] 2.3 Add `@JsonAlias("inferredType")` on `TemplateVariableDto.effectiveType` for backward compatibility during one release cycle

## 3. Type Inference (`TemplateVariableService`)

- [x] 3.1 Propagate `ExtractedVariable.declaredType` into the `TemplateVariableDto.declaredType` field in `TemplateVariableService`
- [x] 3.2 Update `inferType()` (or equivalent) to use `declaredType` as the highest-priority source when resolving `effectiveType`; chain: declared → endpointRef → binding+testCaseSchema → STRING fallback
- [x] 3.3 Update all `TemplateVariableDto.builder()` call sites in `TemplateVariableService` to use `effectiveType(...)` instead of `inferredType(...)`

## 4. Tests

- [x] 4.1 Unit tests for `TemplateVariableExtractor` — new `${{name|type}}` and `${{name|type:default}}` syntax; case-insensitive type matching; defaults containing `|` and `:`; unrecognised type hint warning; existing `${{name}}` and `${{name:default}}` syntax unchanged
- [x] 4.2 Unit tests for `TemplateVariableService` type inference — `declaredType` wins over binding-inferred type; binding-inferred type used when no declared type; STRING fallback when neither
- [x] 4.3 Functional tests for `GET /api/v1/test-suites/{id}/template-variables` — verify `declaredType` and `effectiveType` fields in response for `${{var|file}}`, `${{var|number:0.7}}`, and plain `${{var}}`

## 5. OpenAPI / Docs

- [x] 5.1 Update `@Schema` annotations on `TemplateVariableDto.declaredType` and `TemplateVariableDto.effectiveType` with example values
- [x] 5.2 Update OpenAPI request/response examples for template-variables endpoints to include `declaredType` and `effectiveType` fields; rename `inferredType` → `effectiveType` in existing example files; add an example showing `${{doc|file}}` placeholder
- [x] 5.3 Update `openspec/specs/README.md` per Spec Index Maintenance Policy (done: `request-template` and `response-columns` summaries reflect type-hint and FILE column additions)
