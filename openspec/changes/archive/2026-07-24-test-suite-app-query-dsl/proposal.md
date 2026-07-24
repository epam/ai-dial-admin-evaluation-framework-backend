## Why

The `test_suites` QueryDSL entity exposes `deployment_ref` and `mcp_deployment_ref` as opaque `OBJECT`-typed JSONB blobs, making it impossible to filter or project on sub-fields such as deployment name or ID via structured queries. Clients need to query suites by application name (e.g. find all suites targeting a specific model deployment) — a common need for dashboards and evaluation analytics.

## What Changes

- `deployment_ref::id`, `deployment_ref::name`, `deployment_ref::version` added as flat `STRING`-typed queryable fields on the `test_suites` QueryDSL entity (backed by `deployment_ref ->> 'id'` etc.).
- `mcp_deployment_ref::id`, `mcp_deployment_ref::name`, `mcp_deployment_ref::type` added as flat `STRING`-typed queryable fields on the `test_suites` QueryDSL entity (backed by `mcp_deployment_ref ->> 'id'` etc.).
- The existing opaque `deployment_ref` (OBJECT) and `mcp_deployment_ref` (OBJECT) bindings are **retained** — no breaking change.
- `JsonbFieldResolver` gains two new prefix dispatch families: `deployment_ref::` and `mcp_deployment_ref::`, each resolving to a `jsonbAtAsText` path expression over the respective JSONB column.
- `TestSuitesSchemaProvider.baseSchema()` is extended with the 6 new virtual `QuerySchemaFieldDto` entries so the schema-discovery endpoint advertises them.
- `PostgresTestSuiteEntityResolver` static bindings map is extended with the 6 corresponding `QueryFieldBinding` entries.

No DB schema changes, no Flyway migrations, no jOOQ regeneration, no config changes.

## Capabilities

### New Capabilities

None — this change extends existing infrastructure rather than introducing a new top-level capability.

### Modified Capabilities

- `structured-query-model`: The `test_suites` entity now exposes JSONB sub-field paths (`deployment_ref::name`, etc.) as filterable/projectable `STRING` fields; `JsonbFieldResolver` gains two new prefix families. Wire contract (envelope, filter grammar, expression grammar) is unchanged.
- `query-schema-discovery`: The `test_suites` base schema response (from `GET /api/v1/queries/entities/test_suites/schema`) now includes the 6 new virtual field entries that were not previously advertised.

## Impact

**Production code (3 classes):**
- `experimental.query.service.translate.JsonbFieldResolver` — add `deployment_ref::` and `mcp_deployment_ref::` dispatch branches using existing `textPath()` helper.
- `experimental.query.service.TestSuitesSchemaProvider` — append 6 `QuerySchemaFieldDto` entries to `baseSchema`.
- `experimental.query.service.repository.PostgresTestSuiteEntityResolver` — inject `JsonPathAccessor`; augment static bindings with 6 `QueryFieldBinding` entries at construction time.

**Test code (4 files):**
- `TestSuitesSchemaProviderTest` — assert new fields present with correct type and source.
- `PostgresTestSuiteEntityResolverTest` (new) — unit-test bindings map contains the 6 new entries.
- `TestSuiteStructuredQueryFunctionalTests` — add `filtersByDeploymentRefName()` test.
- `StructuredQueryExecuteFunctionalTests` — add HTTP-level filter-by-deployment-ref-name test.

**No impact on:** DB schema, jOOQ generated sources, REST `?filter=` classic filter, `test_cases` entity, any other QueryDSL entity, security model.
