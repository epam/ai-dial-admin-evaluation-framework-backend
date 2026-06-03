## Context

The existing `GET /api/v1/test-suites/{testSuiteId}/metric-definitions/{id}` endpoint returns a `TestSuiteMetricDefinitionResponseDto` that already JOINs `metric_declarations` to include `metricDeclarationName`. However, it does not include the metric declaration's full details (provider, description) or the referenced version's schemas (configSchema, inputSchema, outputSchema, schemaVersion).

Clients editing a metric definition need all three pieces — the definition itself (bindings), the declaration (context), and the version (schemas) — to render a form. Today this requires 2-3 API calls.

## Goals / Non-Goals

**Goals:**
- Provide a single endpoint that returns the metric definition enriched with its referenced metric declaration and metric declaration version.
- Reuse existing response DTOs (`MetricDeclarationResponseDto`, `MetricDeclarationVersionResponseDto`) as nested objects to avoid field duplication.
- Follow existing repository patterns (3-way JOIN, dedicated row mapper, dedicated model).

**Non-Goals:**
- Changing the existing `GET /{id}` endpoint response shape.
- Supporting aggregated list queries (only the single-resource detail view is in scope).
- Caching or denormalizing data; the endpoint resolves via a live SQL JOIN.

## Decisions

### D1: Response DTO structure — nested composition

The aggregated response DTO will embed the metric definition fields at the top level and nest `metricDeclaration` and `metricDeclarationVersion` as child objects using existing response DTOs.

```
AggregatedMetricDefinitionResponseDto
├── id, testSuiteId, name, configBindings, inputBindings, createdAt, updatedAt
├── metricDeclaration: MetricDeclarationResponseDto
│   └── id, providerId, name, description, createdAt
└── metricDeclarationVersion: MetricDeclarationVersionResponseDto
    └── id, metricDeclarationId, schemaVersion, configSchema, inputSchema, outputSchema, description, createdAt
```

**Rationale**: Reusing existing DTOs ensures consistency with standalone endpoints and avoids field duplication. Clients already know the shape of these objects. The top-level `metricDeclarationId`, `metricDeclarationVersionId`, and `metricDeclarationName` fields from the base response can be omitted since they are redundant with the nested objects — but keeping them preserves backward-compatible field naming for clients migrating from `GET /{id}`.

**Alternative considered**: Flat DTO with prefixed field names (e.g., `declarationName`, `versionConfigSchema`). Rejected because it creates a non-standard shape and doesn't leverage existing DTOs.

### D2: Data layer — new model + row mapper for the 3-way JOIN

Introduce `AggregatedMetricDefinition` model in `data.db.model` carrying all columns from the 3-way JOIN. A new `AggregatedMetricDefinitionRowMapper` will map the result set.

The existing `TestSuiteMetricDefinition` model will NOT be extended — it is already a well-scoped carrier for the 2-table JOIN. The aggregated model exists solely for the 3-way JOIN result.

**Fields in `AggregatedMetricDefinition`**:
- All `TestSuiteMetricDefinition` fields (definition + declaration name)
- `MetricDeclaration` fields: `declarationProviderId`, `declarationDescription`, `declarationCreatedAt`
- `MetricDeclarationVersion` fields: `versionId`, `versionSchemaVersion`, `versionConfigSchema`, `versionInputSchema`, `versionOutputSchema`, `versionDescription`, `versionCreatedAt`

### D3: Repository — new method on existing repository interface

Add `findAggregatedByIdAndTestSuiteId(UUID id, UUID testSuiteId)` to `TestSuiteMetricDefinitionRepository`. The Postgres implementation will execute a single SQL query with a 3-way JOIN:

```sql
SELECT md.id, md.test_suite_id, md.metric_declaration_id, md.metric_declaration_version_id,
       md.name, md.config_bindings, md.input_bindings, md.created_at_ms, md.updated_at_ms,
       decl.name AS metric_declaration_name, decl.provider_id, decl.description AS declaration_description,
       decl.created_at_ms AS declaration_created_at_ms,
       ver.id AS version_id, ver.schema_version, ver.config_schema, ver.input_schema,
       ver.output_schema, ver.description AS version_description, ver.created_at_ms AS version_created_at_ms
FROM test_suite_metric_definitions md
JOIN metric_declarations decl ON md.metric_declaration_id = decl.id
JOIN metric_declaration_versions ver ON md.metric_declaration_version_id = ver.id
WHERE md.id = :id AND md.test_suite_id = :testSuiteId
```

No new indexes needed — existing PKs and FKs handle this efficiently.

### D4: Service layer — new read-only method

Add `getAggregatedById(UUID testSuiteId, UUID id)` to `TestSuiteMetricDefinitionService` with `@Transactional(value = "metaTransactionManager", readOnly = true)`. Returns `AggregatedMetricDefinitionResponseDto`. Throws `EntityNotFoundException` if not found.

### D5: Mapper — new method in existing mapper

Add `toAggregatedDto(AggregatedMetricDefinition)` to `TestSuiteMetricDefinitionMapper`. This method reuses `JsonbMapper` for bindings and schema conversion, and composes the nested DTOs inline.

### D6: Controller endpoint path

`GET /api/v1/test-suites/{testSuiteId}/metric-definitions/{id}/aggregated`

Using a sub-resource path (`/{id}/aggregated`) rather than a query parameter (`?view=aggregated`) because:
- It follows REST conventions for alternate representations of a resource
- It keeps the existing `GET /{id}` endpoint unchanged
- It's explicit and discoverable in OpenAPI docs

## Risks / Trade-offs

- **[Risk] Orphaned version reference** → If a metric declaration version is deleted while still referenced by a definition, the JOIN would return no rows. Mitigation: FK constraint on `metric_declaration_version_id` prevents deletion; the endpoint returns 404 which is correct behavior.
- **[Trade-off] Additional model class** → A new `AggregatedMetricDefinition` model adds a class, but this is preferable to overloading the existing model with nullable transient fields for every possible JOIN shape.
- **[Risk] JSONB deserialization overhead** → The version schemas (configSchema, inputSchema, outputSchema) are JSONB columns. For a single-row fetch this is negligible. No optimization needed.
