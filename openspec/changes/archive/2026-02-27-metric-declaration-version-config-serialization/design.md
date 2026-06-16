## Context

MetricDeclarationVersion has three JSONB columns (`config_schema`, `input_schema`, `output_schema`) that store JSON schema objects. The current implementation keeps these as `String` throughout the entire stack — model, mapper, DTO, and API response. This means the REST API returns JSON schemas as escaped strings (e.g. `"configSchema": "{\"type\":\"object\"}"`) instead of inline JSON objects.

TestCase and TestSuite already follow a well-established pattern for JSONB fields: the DB model uses `String`, the mapper layer converts via `JsonbMapper` / `ValidationWarningsSerializer`, and DTOs expose structured types (`Map<String, Object>`, typed DTOs, etc.). This design aligns MetricDeclarationVersion with that pattern.

## Goals / Non-Goals

**Goals:**
- Change `configSchema`, `inputSchema`, `outputSchema` in `MetricDeclarationVersionResponseDto` from `String` to `Map<String, Object>` so the API returns proper JSON objects.
- Reuse `JsonbMapper` for bidirectional String ↔ Map conversion, consistent with the existing codebase pattern.
- Update `MetricDeclarationVersionMapper` to perform the conversion via `JsonbMapper`.
- Update OpenAPI examples and documentation to reflect the new contract.
- Codify the convention in project docs so future JSONB-backed schema fields follow the same pattern.

**Non-Goals:**
- Changing the DB model (`MetricDeclarationVersion`) — `String` fields mapped from JSONB are the established data-layer pattern and remain unchanged.
- Changing the RowMapper or repository — they already correctly read/write JSONB as `String`.
- Changing the `MetricProviderSyncService` internals — it writes `String` to the model, which is correct.
- Adding request DTOs for MetricDeclarationVersion — versions are created via sync, not user input.

## Decisions

### Decision 1: Use `Map<String, Object>` for schema DTO fields

**Choice**: `Map<String, Object>` over `JsonNode` or custom schema DTOs.

**Rationale**: This is the exact pattern used by `TestCaseRequestDto` / `TestCaseResponseDto` for their `data` field. `Map<String, Object>` is naturally serializable by Jackson as a JSON object without custom serializers, and it matches the existing `ValidationWarningsSerializer.deserializeMap` / `serializeMap` utility already in the codebase. Using `JsonNode` would couple the DTO to Jackson internals; custom DTOs aren't needed because JSON schemas have arbitrary structure.

### Decision 2: Add `mapJsonSchema` / `mapJsonSchema(String)` methods to `JsonbMapper`

**Choice**: Extend `JsonbMapper` with reusable `Map<String, Object>` conversion methods.

**Rationale**: `JsonbMapper` is the centralized component for JSONB String ↔ structured-type conversions. Adding schema-specific methods there keeps the pattern consistent with `mapFieldDefinitions`, `mapInputBindings`, etc. The alternative — using `ValidationWarningsSerializer.deserializeMap` directly — would also work, but `JsonbMapper` is the canonical place for JSONB mapping in MapStruct mappers. The new methods will follow the same fail-fast (serialize) / fail-fast (deserialize) error handling as the existing methods.

### Decision 3: Convert MetricDeclarationVersionMapper from MapStruct auto-mapping to manual construction

**Choice**: Convert to a manual `@Component` mapper (like `TestCaseMapper`) that injects `JsonbMapper`.

**Rationale**: MapStruct cannot auto-map `String` → `Map<String, Object>` without a custom method binding. The simplest approach is to make the mapper a concrete `@Component` that calls `JsonbMapper` for each schema field, consistent with how `TestCaseMapper` handles its JSONB conversions. This avoids MapStruct `@Mapping` complexity with `qualifiedByName` and expressions.

## Risks / Trade-offs

- **Breaking API change** → Clients already parsing JSON strings from the response will receive objects instead. Mitigation: The frontend team is the primary consumer and requested this change. Coordinate deployment timing.
- **Null schema handling** → A schema could be `null` or `"{}"` in the DB. Mitigation: `JsonbMapper.mapJsonSchema(String)` will return `null` for null/blank input and an empty map for `"{}"`, consistent with existing deserialization patterns. Clients already handle nullable schema fields.
