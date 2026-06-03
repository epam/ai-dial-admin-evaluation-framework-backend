## Why

When a client wants to update a test suite metric definition, it needs the metric definition's current bindings **and** the metric declaration version's schemas (config, input, output) to render a form or validate changes. Today this requires two separate API calls: one for the metric definition (`GET .../metric-definitions/{id}`) and one for the declaration version (`GET /api/v1/metric-declarations/{declarationId}/latest` or a version-specific lookup). An aggregated endpoint eliminates this round-trip and gives the client a single, complete view.

## What Changes

- **New read-only endpoint** `GET /api/v1/test-suites/{testSuiteId}/metric-definitions/{id}/aggregated` that returns the test suite metric definition joined with its referenced metric declaration and metric declaration version.
- **New response DTO** `AggregatedMetricDefinitionResponseDto` combining fields from `TestSuiteMetricDefinitionResponseDto`, `MetricDeclarationResponseDto`, and `MetricDeclarationVersionResponseDto` (schemas exposed as `Map<String, Object>`).
- **New repository query** joining `test_suite_metric_definitions`, `metric_declarations`, and `metric_declaration_versions` in a single SQL statement.
- No database schema changes. No new tables, columns, or migrations.
- No breaking changes to existing endpoints.

## Capabilities

### New Capabilities

- `aggregated-metric-definition`: Read-only endpoint that returns a test suite metric definition enriched with the full metric declaration and metric declaration version details (schemas, description, schema version) in a single response.

### Modified Capabilities

_(none — existing endpoints and their contracts remain unchanged)_

## Impact

- **API**: One new GET endpoint under the existing `test-suites/{testSuiteId}/metric-definitions` resource path. OpenAPI annotations and examples required.
- **Code**:
  - New `AggregatedMetricDefinitionResponseDto` in `service.domain.dto`
  - New repository method (and model/row-mapper if the join result differs from existing models) in `data.db`
  - New service method in `TestSuiteMetricDefinitionService`
  - New controller method in `TestSuiteMetricDefinitionController`
  - New mapper logic (or extended existing mapper) for the aggregated DTO
- **Database**: No migration — uses existing tables via JOIN.
- **Configuration**: No new config properties.
- **Testing**: Functional tests for the new endpoint (success, 404 cases for missing suite / missing definition / orphaned declaration or version).
