## Why

When a test suite has many metric definitions backed by different metric declarations, users need to filter the list to see only definitions of a specific declaration (e.g. "show me all my Accuracy configurations"). Today the only available filter is `name` (the TSMD's own name), which doesn't help when the user is thinking in terms of the underlying metric declaration. Additionally, the response DTO only carries `metricDeclarationId` (a UUID), so users must perform a separate lookup to know which declaration a definition belongs to.

## What Changes

- Add a `metricDeclarationName` filter field to the TSMD list endpoint, supporting `EQ`, `NE`, and `CONTAINS` operators. This requires a JOIN from `test_suite_metric_definitions` to `metric_declarations` in the repository query — the first cross-table filter in the project.
- Add `metricDeclarationName` (String) to the TSMD response DTO so the declaration's human-readable name is returned inline with every TSMD response (list, get-by-id, create, update).
- Update `OpenApiQueryParamCustomizer` registry to document the new filter field.

## Capabilities

### New Capabilities
_(none)_

### Modified Capabilities
- `test-suite-metric-definitions`: Add `metricDeclarationName` to the response DTO shape and add a new filter field for the list endpoint that resolves against the joined `metric_declarations.name` column.
- `entity-filtering`: Add `test-suite-metric-definitions` to the list of endpoints covered by this spec (currently mentions only TestSuites, TestCases, MetricDeclarations).

## Impact

- **API**: TSMD response DTO gains a new `metricDeclarationName` field (additive, non-breaking). TSMD list endpoint gains a new `metricDeclarationName` filter key.
- **Repository layer**: `PostgresTestSuiteMetricDefinitionRepository` SELECT queries must JOIN `metric_declarations` and use table aliases. This is the first JOIN in the repository layer — establishes a precedent for cross-entity filtering.
- **Data model**: `TestSuiteMetricDefinition` DB model gains a transient `metricDeclarationName` field populated from the JOIN.
- **RowMapper**: Must extract the additional `metric_declaration_name` column from the result set.
- **MapStruct mapper**: Maps the new field from entity to response DTO.
- **Filter whitelist**: `FilterWhitelists.METRIC_DEFINITIONS` gains a `metricDeclarationName` entry mapped to `md.name` (table-qualified column from the JOIN).
- **Sort whitelist**: Existing sort entries for this entity need table-qualifying if they would become ambiguous after the JOIN (e.g. `name` -> `tsmd.name`).
- **OpenAPI**: New filter field documented via `OpenApiQueryParamCustomizer`. Response DTO `@Schema` updated.
- **Tests**: Functional tests for filtering by `metricDeclarationName` and verifying the new field in responses.
- **No DB migration needed** — no schema change, the JOIN uses existing FK columns and the existing index on `metric_declaration_id`.
- **No configuration changes**.
