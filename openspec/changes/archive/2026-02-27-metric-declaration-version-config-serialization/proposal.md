## Why

MetricDeclarationVersion schema fields (`configSchema`, `inputSchema`, `outputSchema`) are currently serialized as raw JSON strings in the REST API response, while TestCase and TestSuite already return their JSONB-backed fields as structured JSON objects. This inconsistency forces the frontend client to parse the string before using it, and breaks the uniform contract this service provides. Aligning MetricDeclarationVersion with the established pattern ensures a consistent API experience for all JSON schema fields.

## What Changes

- **BREAKING**: `configSchema`, `inputSchema`, and `outputSchema` in `MetricDeclarationVersionResponseDto` change from `String` to `Map<String, Object>`, so API consumers will receive JSON objects instead of JSON strings.
- The `MetricDeclarationVersionMapper` will use `JsonbMapper` (or equivalent) to convert between the `String` model and `Map<String, Object>` DTO, following the same pattern as `TestCaseMapper` and `TestSuiteMapper`.
- The DB model and repository layers remain unchanged — schemas are still stored as JSONB and read as `String` internally.
- OpenAPI examples for the metric-declaration-version endpoint will be updated to show object-typed schemas.
- Project documentation (AGENTS.md, relevant specs) will be updated to codify the rule: JSONB-backed JSON schema fields MUST be exposed as structured objects in DTOs, not as raw strings.

## Capabilities

### New Capabilities

_(none)_

### Modified Capabilities

- `metrics-system`: The "Get latest metric declaration version" endpoint response contract changes — `configSchema`, `inputSchema`, and `outputSchema` become JSON objects instead of JSON strings.

## Impact

- **API (breaking)**: Clients consuming `GET /api/v1/metric-declarations/{id}/latest` will receive schema fields as JSON objects. Clients that currently JSON-parse the string values will need to stop doing so.
- **Code**: Changes scoped to `MetricDeclarationVersionResponseDto`, `MetricDeclarationVersionMapper`, and `JsonbMapper`. DB model, RowMapper, and repository are unaffected.
- **Tests**: Functional tests asserting on the response shape will need updating to expect `Map<String, Object>` instead of `String`.
- **Documentation**: AGENTS.md best-practices section, `metrics-system` spec, and OpenAPI examples updated.
