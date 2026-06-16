## Why

API consumers (FE, other BE, BA) must reverse-engineer allowed filter fields, operators, sort fields, pagination limits, and enum values from trial-and-error or source code. The whitelists (`FilterWhitelists`, `SortWhitelists`) and configuration (`PaginationProperties`) already contain this information, but it never reaches the OpenAPI spec. Hand-written `@Parameter` descriptions are incomplete, inconsistent across controllers, and drift from the source of truth.

## What Changes

- **Auto-generated filter documentation**: An `OpenApiCustomizer` reads `FilterWhitelists` at startup and injects a field-operator matrix (with types and examples) into the `filter` parameter description for each list endpoint.
- **Auto-generated sort documentation**: Same customizer reads `SortWhitelists` and injects sortable fields + default sort into the `sort` parameter description.
- **Auto-generated pagination documentation**: Reads `PaginationProperties` and injects actual defaults/limits into `page` and `size` parameter descriptions.
- **Enriched special parameter descriptions**: Improved `@Parameter` descriptions for `includeTotalCount`, `includeWarnings`, `delimiter`, `importMode`, `conflictStrategy`, `cursor`, `includeEnabled` with enum values, defaults, and behavioral notes.
- **Minimal fallback descriptions on controllers**: Current verbose hand-written filter/sort/page/size descriptions stripped to minimal fallbacks — the customizer provides the rich content.
- **Parameter examples**: `@Parameter` `example` attribute added where practical so Swagger UI "Try it out" pre-fills useful values.

## Capabilities

### New Capabilities
- `openapi-query-param-docs`: Auto-generation of rich OpenAPI query parameter documentation from existing whitelists and configuration. Covers filter field-operator matrices, sort field lists, pagination defaults/limits, and special parameter descriptions.

### Modified Capabilities
<!-- No spec-level requirement changes. Filtering, sorting, pagination behavior unchanged. -->

## Impact

- **Code**: New `OpenApiQueryParamCustomizer` component in `.configuration` package. Modified `@Parameter` annotations in 5 controllers (TestSuite, TestCase, MetricDeclaration, TestSuiteRun, AnalyticsResult). Optional `@Schema` additions to `CsvImportMode`, `CsvConflictStrategy` enums.
- **APIs**: No behavioral changes. Only OpenAPI spec metadata enriched.
- **Dependencies**: No new dependencies. Uses existing springdoc `OpenApiCustomizer` extension point.
- **Risk**: Low. Documentation-only change; no runtime behavior affected.
