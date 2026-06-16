# Design: Add meta-model validations

## Context

The backend accepts TestSuite and TestCase payloads with underspecified or invalid meta-model data: fact fields without `name`/`type`, parameters without `in`, and JSON Schema objects (requestBodySchema, responseBodySchema, parameters[].schema) with invalid `type` values. Schema validation today compiles schemas with networknt but does not validate the schema document itself against the JSON Schema meta-schema, so invalid types (e.g. `"abc"`) slip through. Test case validation warnings are returned as a flat list of strings (e.g. `"$: required property 'model' not found"`), so the client cannot tell whether a warning refers to parameters or facts or match it to a grid cell. List inputs (factFields, filter, sort) have no explicit caps. Constraints: JDBC-only, existing networknt json-schema-validator 1.5.9, Bean Validation in use; no new endpoints; FE needs parseable, grid-bindable warnings.

## Goals / Non-Goals

**Goals:**

- Reject invalid or underspecified meta-model data at request time (HTTP 400) with clear messages.
- Validate requestBodySchema, responseBodySchema, and parameters[].schema against JSON Schema Draft-07 rules (invalid `type`, etc.); require fact field name/type and parameter `in`; add DTO and query-param size/format constraints.
- Return structured test case validation warnings (source, path, property, message, optional code) so the FE can bind to parameters vs facts and match to grid cells.
- Cap list sizes (factFields, filter, sort) and validate delimiter as single character where used.

**Non-Goals:**

- OpenAPI Schema Object–specific validation beyond JSON Schema Draft-07; full recursive meta-schema for `$ref`-heavy schemas if networknt does not support it out of the box.
- Changing request/response shapes other than validationWarnings; new endpoints; migration of existing stored data (validation only affects new/updated payloads).

## Decisions

### 1. Schema validation: bundled meta-schema

**Decision:** Validate user-provided schemas (requestBodySchema, responseBodySchema, parameters[].schema) against the **JSON Schema Draft-07 meta-schema** using networknt. The meta-schema is **bundled as a classpath resource** (`schemas/json-schema-draft-07.json`) to ensure the service works without internet access. At startup, `SchemaValidationService` loads the meta-schema and caches the compiled `JsonSchema` instance. User schemas are validated via `metaSchema.validate(userSchemaNode)`.

**Strict mode:** The bundled meta-schema uses `additionalProperties: false` (unlike the standard Draft-07 meta-schema which allows extensions). This rejects unknown/typo keywords like `properties2` instead of silently ignoring them.

**$ref handling:** Schemas containing `$ref` keyword SHALL be **rejected** with HTTP 400 in v1. Complex schema composition via `$ref` is deferred to a future TODO.

**Rationale:** Meta-schema validation rejects all invalid keywords in one place. Bundling the meta-schema avoids network dependency and ensures consistent behavior. Rejecting `$ref` simplifies v1 implementation and avoids URI resolution complexity.

**Where:** `SchemaValidationService`: `loadMetaSchema()` at `@PostConstruct`, `validateAgainstMetaSchema()` from `getSchemaValidationError()`. Applied to endpointRef.requestBodySchema, endpointRef.responseBodySchema, and each endpointRef.parameters[i].schema.

### 2. Fact fields and parameters: optional factFields (init), optional description, Bean Validation

**Decision:** Treat `testCasesDefinition.factFields` as **optional**: when missing or null, **initialize** to empty list in request normalization (no error). When present, enforce obligatory fields per entry: name non-blank, type non-null. **Do not** require `description`—it is optional; when null or absent on a fact field entry, handle without error (no validation failure). Enforce name/type and parameter `in` via **Bean Validation**: add `@Valid` on `TestCasesDefinitionDto.factFields` and on `EndpointContractDto.parameters` so nested `SchemaFieldDto` and `ParameterDefinitionDto` are validated (triggers existing `@NotBlank`/`@NotNull` constraints). Keep `@NotBlank` on SchemaFieldDto.name, `@NotNull` on SchemaFieldDto.type; **do not** add `@NotNull` on SchemaFieldDto.description. Add `@Size(max=2000)` on description only when present (null allowed). Optionally add an explicit loop in TestSuiteService that rejects any fact field with blank name or null type with a clear message for defense in depth.

**Note:** `SchemaFieldDto` already has `@NotBlank` on `name` and `@NotNull` on `type`; `ParameterDefinitionDto` already has `@NotNull` on `in`. The key change is adding `@Valid` on the parent list fields to **trigger** nested validation. Bean Validation automatically returns HTTP 400 for invalid enum values (e.g. invalid `in` string).

**Rationale:** factFields optional + init matches existing normalizeRequest behavior and avoids rejecting requests that omit the field. description optional keeps the API flexible; validation and persistence must accept null/absent description.

### 3. Structured validation warnings: replace list of strings (no migration)

**Decision:** Change `TestCaseResponseDto.validationWarnings` from `List<String>` to **list of structured objects** (e.g. `List<ValidationWarningDto>`). Each object has: `source` (`"parameters"` | `"facts"`), `path` (JSONPath-like string from validator), `property` (top-level property name when applicable), `message` (human-readable), and optional `code` (e.g. `required`, `type`). **Replace** the existing field; do not add a parallel field. **BREAKING change without migration:** early phase, no external clients today; no migration path or backward compatibility. **Do not backfill** existing test case rows: leave `validationWarnings` empty (or null) for current rows; only new/updated validations produce structured warnings.

**Rationale:** Single source of truth for warnings; FE can bind to source and property for grid highlighting. No clients yet, so a clean break is acceptable; avoiding backfill keeps implementation simple.

**Implementation:** Validation already runs parameters and facts separately; networknt `ValidationMessage` exposes path and message. When building the combined result, tag each warning with source (`parameters` | `facts`), derive `property` from path, pass through message, and optionally map message type to a stable `code`. New DTO: `ValidationWarningDto` (source, path, property, message, code). DB: store warnings as jsonb array of objects; Flyway migration changes column type from `text[]` to `jsonb` and **prunes existing rows** (sets `validation_warnings` to empty array)—no concurrent read/write concerns during deployment, no rollback migration needed.

**Property extraction from path:**
- `$` → `property: null` (root-level error)
- `$.model` → `property: "model"`
- `$.items[0]` → `property: "items"` (array container)
- `$.a.b.c` → `property: "c"` (leaf property)
- `$[0]` → `property: null` (root array index)

**Warning code enumeration** (optional `code` field, stable identifiers for FE):
- `REQUIRED` – missing required property
- `TYPE` – type mismatch
- `FORMAT` – format validation failed
- `PATTERN` – pattern validation failed
- `ENUM` – value not in allowed enum
- `ADDITIONAL` – additional properties not allowed
- `UNKNOWN` – fallback for unmapped validation errors

### 4. DTO and query-param constraints

**Decision:** Add `@Size` (and where needed `@Pattern`) on nested request DTOs: SchemaFieldDto (name max 255, description max 2000), ParameterDefinitionDto (name max 255), EndpointContractDto (relativeUrl pattern `^/[^\\s]*$`, operationId max 255), DeploymentReferenceDto (id, name max 255; version max 50). Cap `TestCasesDefinitionDto.factFields` with `@Size(max = 128)` (default). In controllers: cap `filter` and `sort` list size at **32** (default); validate CSV `delimiter` as single character (e.g. length 1 or reject with 400). Use existing `resolvePage`/`resolveSize` for page/size; optionally add `@Min(0)` and `@Range` on params for clarity.

**Rationale:** Aligns with DB column sizes and prevents abuse; single-char delimiter matches actual usage (only first character used).

### 5. List size limits (factFields, filter, sort)

**Decision:** factFields: `@Size(max = 128)` on TestCasesDefinitionDto (default). filter/sort: **separate limits** of **32 each** per request in controller (default); return HTTP 400 when exceeded. Exact values can be moved to configuration (e.g. pagination/validation props) if needed.

**CSV delimiter:** Default is comma (`,`). Validate as single **ASCII** character (length 1, no multi-byte Unicode for v1); return HTTP 400 when invalid.

**Rationale:** 128 fact fields and 32 filter/sort params are sufficient for early phase; limits can be increased via config later if needed. ASCII-only delimiter simplifies parsing and avoids edge cases with multi-byte characters.

## Risks / Trade-offs

- **[Risk] validationWarnings response shape:** Response shape changes from `List<String>` to list of objects.  
  **Mitigation:** Early phase, no external clients today; no migration path. Document in API docs and release notes.

- **[Risk] networknt meta-schema not available or brittle:** Meta-schema validation might fail for edge cases.  
  **Mitigation:** Meta-schema is bundled as classpath resource (`schemas/json-schema-draft-07.json`), eliminating network dependency. Loaded once at startup; startup fails fast if resource missing.

- **[Trade-off] Storing structured warnings in DB:** validation_warnings changes from `text[]` to `jsonb`. Flyway migration prunes existing data (sets to empty array). No concurrent read/write concerns expected during deployment.  
  **Mitigation:** Simple migration; existing data pruned; no rollback needed.

## Migration Plan

1. **Backend:** Implement schema validation (meta-schema via networknt with bundled Draft-07 schema; reject `$ref` schemas), `@Valid` on nested lists, DTO/query constraints (factFields max 128, filter max 32, sort max 32), and structured warnings (ValidationWarningDto, mapping, persistence). Deploy; no client migration (no external clients).
2. **DB:** Flyway migration changes validation_warnings from `text[]` to `jsonb` and prunes existing data (sets to empty array).
3. **Rollback:** Not required—no external clients, data can be recreated.

## Open Questions

- Exact config keys and defaults for factFields max (128) and filter/sort max (32) if moved to application config.

## Future Work (TODO)

- Support complex schemas with `$ref` keyword (currently rejected with HTTP 400).
