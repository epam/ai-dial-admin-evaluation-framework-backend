## 1. JsonbMapper and DTO Changes

- [x] 1.1 Add `mapJsonSchema(Map<String, Object>)` → `String` and `mapJsonSchema(String)` → `Map<String, Object>` methods to `JsonbMapper`, following the existing fail-fast error handling pattern. Return `null` for null/blank input on deserialization; return `null` for null input on serialization.
- [x] 1.2 Change `configSchema`, `inputSchema`, `outputSchema` fields in `MetricDeclarationVersionResponseDto` from `String` to `Map<String, Object>`. Update `@Schema` annotations accordingly (remove `example` if present, update `description`).
- [x] 1.3 Convert `MetricDeclarationVersionMapper` from a MapStruct interface to a concrete `@Component` class that injects `JsonbMapper` and manually maps fields, calling `jsonbMapper.mapJsonSchema(...)` for each schema field. Add `@LogExecution` annotation.

## 2. OpenAPI Examples

- [x] 2.1 Update `api-v1-metric-declarations-id-latest-GET-response-200-full.json` and `api-v1-metric-declarations-id-latest-GET-response-200-minimal.json` to represent `configSchema`, `inputSchema`, `outputSchema` as JSON objects instead of JSON strings.

## 3. Tests

- [x] 3.1 Update functional tests for `GET /api/v1/metric-declarations/{id}/latest` to assert that schema fields in the response are JSON objects (maps), not strings. Verify null schemas remain null.

## 4. Documentation

- [x] 4.1 Update `metrics-system` spec implementation notes to reflect that schema fields are now returned as JSON objects in the DTO, converted via `JsonbMapper`.
- [x] 4.2 Update AGENTS.md to add a best-practice entry: JSONB-backed JSON schema fields (e.g. config_schema, input_schema, output_schema) MUST be exposed as `Map<String, Object>` in response DTOs, not as raw `String`. Reference the `JsonbMapper` pattern.
