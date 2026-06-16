## Why

Test suites currently have no way to specify which metrics should be applied to test case results. To close the loop between metric declarations (catalog of available metrics with versioned schemas) and test suite evaluation, we need a mechanism that lets users select metrics for a suite and configure how metric inputs/config parameters are sourced from test case data, response columns, or constant values. This is the "Test Suite Metric Definition" (TSMD) — a materialized application of a metric declaration within a specific test suite.

## What Changes

- **New DB table** `test_suite_metric_definitions` in the meta database with FK references to `test_suites`, `metric_declarations`, and `metric_declaration_versions`
- **New CRUD REST API** nested under test suites: `POST/GET/PUT/DELETE /api/v1/test-suites/{suiteId}/metric-definitions`
- **Paginated list endpoint** with filtering by `name` and sorting by `name`, `createdAt`
- **Parameter binding model** — polymorphic JSONB structure for `config_bindings` and `input_bindings`, supporting three source types: `TestCase` (column from test case schema), `Response` (extracted response column), and `Constant` (literal value)
- **Server-side version resolution** — when creating a TSMD, the backend resolves `metricDeclarationVersionId` to the latest version of the referenced metric declaration
- **Flyway migration** for the new table with unique constraint on `(test_suite_id, LOWER(name))`
- **Documentation updates** — database schema reference, ER model, OpenAPI examples

## Capabilities

### New Capabilities
- `test-suite-metric-definitions`: CRUD management of metric definitions within a test suite, including parameter binding model for mapping metric input/config properties to test case columns, response columns, or constant values

### Modified Capabilities

## Impact

- **New DB table**: `test_suite_metric_definitions` in meta database (Flyway migration)
- **New API surface**: 5 endpoints under `/api/v1/test-suites/{suiteId}/metric-definitions`
- **New packages/classes**: model, RowMapper, repository, service, controller, DTOs, MapStruct mapper — following existing entity patterns
- **Existing code**: no modifications to existing entities; TSMD is additive
- **Documentation**: `docs/database-schema.md`, `docs/design/entity-relationship-model.md` need updates
- **Filter/Sort whitelists**: new entries in `FilterWhitelists` and `SortWhitelists`
- **OpenAPI**: new controller with annotations, query param customizer registration
