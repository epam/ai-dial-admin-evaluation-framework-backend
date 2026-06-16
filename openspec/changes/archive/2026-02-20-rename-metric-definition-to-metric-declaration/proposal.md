# Rename MetricDefinition to MetricDeclaration

## Tasks placeholder
See tasks.md when created.

## Why

The design docs (entity-relationship-model, metrics-system spec) distinguish **MetricDeclaration** (catalog: what a metric is—inputs, outputs, configuration) from **TestSuiteMetricDefinition** (application of a metric in a test suite). The current codebase uses "MetricDefinition" for the catalog entity, which is a naming mismatch and will confuse future work on TestSuiteMetricDefinition. Renaming now aligns implementation with design and avoids ambiguity before adding more metrics functionality.

## What Changes

- Rename all API, service, repository, model, and DTO components from "MetricDefinition" to "MetricDeclaration" so the catalog entity matches design terminology.
- **BREAKING**: API path changes from `/api/v1/metric-definitions` to `/api/v1/metric-declarations`; response type names change (e.g. `MetricDefinitionResponseDto` → `MetricDeclarationResponseDto`).
- **BREAKING**: Database table renamed from `metric_definitions` to `metric_declarations` via a new Flyway migration (meta DB).
- Update OpenAPI examples, docs (database-schema, specs), and functional/smoke tests to use the new path and names.
- No new endpoints or behavior; same list/get-by-id semantics under the new names.

## Capabilities

### New Capabilities

None. This is a naming/refactor change only.

### Modified Capabilities

- **metrics-system**: Stub requirement "List metric definitions (stub)" and scenario URLs/descriptions updated to "metric declarations" and `GET /api/v1/metric-declarations` (and by-id).
- **entity-filtering**: Reference to list endpoints updated from "MetricDefinitions" to "MetricDeclarations".
- **test-cases**: Requirement "MetricDefinitions read-only stub" and controller list updated to "MetricDeclarations" and `MetricDeclarationController`; scenario URLs updated to `/api/v1/metric-declarations`.

## Impact

- **Code**: All Java types and files named MetricDefinition (model, repository, row mapper, service, controller, DTO, mapper) renamed to MetricDeclaration; FilterWhitelists/SortWhitelists constants updated.
- **API**: Path `/api/v1/metric-definitions` → `/api/v1/metric-declarations`; OpenAPI tag and descriptions; example JSON filenames under `openapi/examples/` (pathKey becomes `api-v1-metric-declarations`).
- **Database**: New meta Flyway migration renames table `metric_definitions` to `metric_declarations`; repository SQL updated.
- **Tests**: Functional test class and Postgres suite inner class renamed; all test URLs and DTO references updated; smoke tests updated.
- **Docs**: `docs/database-schema.md` (table name and description); `openspec/specs/README.md` if it mentions "metric definitions" in the metrics summary.
- **Consumers**: Any client calling `/api/v1/metric-definitions` or using old DTO type names must switch to the new path and types.
