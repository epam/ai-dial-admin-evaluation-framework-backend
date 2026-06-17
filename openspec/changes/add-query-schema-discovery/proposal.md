## Why

The structured query DSL (see the `structured-query-model` spec) lets clients send a body-delivered
query against an entity, but a client cannot compose a valid query without first knowing which
entities are queryable and which flat field names each one exposes — including the JSONB-backed
fields whose flattening depends on a concrete instance (e.g. an `eval_summaries` row's `data:`,
`response:`, and `metric:` columns are defined by the originating test suite run's snapshot). The
exploratory work on `feat/query-dsl` already implemented this discovery surface; this change pins it
down as its own capability so it is specified independently of query execution.

## What Changes

- Add the **schema discovery API** under the experimental `/api/v1/queries` namespace (all
  **Implemented**):
  - `GET /entities` — list the queryable entities, each carrying a `complex` flag and, when complex,
    the `schemaIdField` naming the parameter that selects a concrete instance for the detailed
    schema.
  - `GET /entities/schema/{name}` — the instance-independent **base schema**: a flat field list with
    JSONB-backed fields listed as-is (`object`/`array`).
  - `GET /entities/schema/{name}/detailed` — the instance-specific **detailed schema** for a complex
    entity, replacing the flattenable JSONB fields with per-instance field families.
- Add the **provider SPI + registry**: `QueryableEntitySchemaProvider` (each entity contributes a
  `descriptor` / `baseSchema` / `detailedSchema`) and `QueryEntityRegistry` (alphabetically-ordered
  lookup root; 404 on unknown entity, 400 on a detailed request against a simple entity).
- Add `JooqTableSchemaResolver` — derives a flat base schema directly from a generated jOOQ table
  (type inference + reverse field-name→column binding), so the base schema follows the physical
  schema without hand maintenance.
- Add the two providers: `TestSuitesSchemaProvider` (simple entity, no detailed schema) and
  `EvalSummariesSchemaProvider` (complex entity whose detailed schema is derived from a test suite
  run's snapshot — `test_suite_run_id` selects the run directly, `test_suite_id` selects the suite's
  latest run, and a run with no snapshot is rejected with a validation error).

## Capabilities

### New Capabilities
- `query-schema-discovery`: Discovery of queryable entities and their flat field schemas for the
  structured query DSL — the entity catalog, the instance-independent base schema (JSONB fields
  listed as-is), the instance-specific detailed schema for complex entities (per-instance JSONB
  flattening, resolved from a test suite run snapshot), the provider SPI + registry that back them,
  and the discovery error contract (404 unknown entity, 400 detailed-on-simple, 400 malformed or
  missing instance id).

### Modified Capabilities
<!-- None. The structured-query-model spec (request object model) is untouched by this change;
     query execution is documented separately in add-structured-query-execution. -->

## Impact

- **Packages**: `com.epam.aidial.evaluation.experimental.query.service` (registry, provider SPI, the
  two providers, `JooqTableSchemaResolver`, `QueryFieldBinding`), `…experimental.query.service.dto`
  (`QueryEntityDto`, `QueryEntitySchemaDto`, `QuerySchemaFieldDto`, `QueryFieldType`), and the
  discovery controller in `…experimental.query.web` (`QuerySchemaController`). The `experimental.*`
  namespace signals the surface is subject to change.
- **APIs**: three new read-only `GET` endpoints under `/api/v1/queries`. No change to existing
  endpoints, the legacy list-query DSL, or security.
- **Data**: no DB schema changes, no Flyway migrations. The detailed schema reads existing data
  (`test_suite_runs.suite_snapshot` via `TestSuiteRunService`, and the run's analytics
  `run_metric_snapshots`); it does not write.
- **Config**: none.
- **Cross-cutting**: introduces the `QueryableEntitySchemaProvider` SPI + registry pattern — adding a
  queryable entity means registering a new provider bean. Whether this warrants an AGENTS.md "Unique
  Patterns" entry is decided at archive time.
- **Docs**: touches `openspec/specs/README.md` (new spec folder) per the Spec Index Maintenance
  Policy.
