## Context

The project uses springdoc-openapi for API documentation. Query parameter descriptions are currently hand-written in `@Parameter` annotations on each controller method. The source of truth for allowed filter fields/operators (`FilterWhitelists`), sort fields (`SortWhitelists`), and pagination limits (`PaginationProperties`) lives elsewhere — creating a drift risk where docs don't match runtime behavior.

An existing `OpenApiExampleCustomizer` already demonstrates the pattern of programmatically enriching the OpenAPI spec at startup using springdoc's `OpenApiCustomizer` interface.

## Goals / Non-Goals

**Goals:**
- Single source of truth: filter/sort/pagination docs are generated from the same constants used at runtime
- Rich Swagger UI experience: field-operator matrices, type hints, examples, enum values, defaults
- Covers all list endpoints (5 controllers) plus CSV and analytics-specific params
- Minimal maintenance: adding a new filter field to `FilterWhitelists` automatically appears in docs

**Non-Goals:**
- Changing runtime behavior of filtering, sorting, or pagination
- Auto-generating request/response body examples (existing `OpenApiExampleCustomizer` handles that)
- Generating client SDKs or external documentation beyond OpenAPI spec

## Decisions

### Decision 1: New `OpenApiQueryParamCustomizer` implementing `OpenApiCustomizer`

**Choice**: Create a new customizer class in `.configuration` package, alongside the existing `OpenApiExampleCustomizer`.

**Alternatives considered**:
- Extending `OpenApiExampleCustomizer` — rejected because responsibilities are distinct (body examples vs param docs) and the class would become too large.
- Annotation-based approach (custom `@FilteredEndpoint` annotation) — rejected as over-engineered; the path-to-whitelist mapping is small and static.

**Rationale**: Follows the existing pattern. springdoc supports multiple `OpenApiCustomizer` beans; they run in sequence. Keeps concerns separated.

### Decision 2: Static path-to-whitelist registry inside the customizer

**Choice**: A `Map<String, EndpointQueryParamConfig>` that associates OpenAPI path patterns with their `FilterSpec`, `SortSpec`, and endpoint-specific flags.

```
Registry entries:
  "/api/v1/test-suites"                                  → (TEST_SUITES filter, TEST_SUITES sort, offset pagination)
  "/api/v1/test-suites/{testSuiteId}/test-cases"         → (TEST_CASES filter, TEST_CASES sort, offset pagination)
  "/api/v1/test-suites/{testSuiteId}/test-cases/export.csv" → (TEST_CASES filter, no sort, no pagination)
  "/api/v1/metric-declarations"                          → (METRIC_DECLARATIONS filter, METRIC_DECLARATIONS sort, offset pagination)
  "/api/v1/test-suite-runs"                              → (TEST_SUITE_RUNS filter, TEST_SUITE_RUNS sort, offset pagination)
  "/api/v1/analytics/test-case-results"                  → (ANALYTICS_RESULTS filter, no sort, cursor pagination)
```

**Alternatives considered**:
- Spring-managed registry bean — unnecessary indirection for 6 static entries.
- Convention-based path-to-whitelist derivation — fragile and non-obvious mapping rules.

**Rationale**: Explicit, easy to read, easy to extend when new list endpoints are added. References existing `FilterWhitelists`/`SortWhitelists` constants directly — zero duplication.

### Decision 3: Description generation as a utility class

**Choice**: `QueryParamDescriptionGenerator` — a package-private utility class with static methods:
- `generateFilterDescription(FilterSpec)` → markdown string with field-operator table
- `generateSortDescription(SortSpec)` → markdown string with sortable fields and default
- `generatePageDescription(int defaultPage)` → string
- `generateSizeDescription(int defaultSize, int maxSize)` → string
- `generateCursorDescription()` → string

**Rationale**: Separates formatting logic from OpenAPI traversal logic. Easy to unit test in isolation.

### Decision 4: Field type display labels with hints

**Choice**: Map `FilterFieldType` to user-friendly labels:
- `STRING` → `string`
- `LONG` → `timestamp (epoch ms)` for `createdAt`/`updatedAt`/`startedAt`/`completedAt` fields; `integer` for others like `runIndex`, `responseStatusCode`, `execDurationMs`
- `BOOLEAN` → `boolean (true/false)`
- `UUID` → `uuid`
- `JSONB_STRING` → `jsonb string`

**Implementation detail**: The type hint depends on both `FilterFieldType` and the field name. `QueryParamDescriptionGenerator` will use the field name to distinguish timestamp longs from generic longs.

### Decision 5: Controller `@Parameter` descriptions become minimal fallbacks

**Choice**: Keep `@Parameter(description = "...")` on controllers with short fallback text (e.g., `"Filter conditions"`, `"Sort keys"`, `"Page number"`, `"Page size"`). The customizer overwrites descriptions at startup.

**Rationale**: If the customizer is ever removed or fails to load, controllers still have basic descriptions. Also serves as documentation for developers reading controller code.

### Decision 6: Enrich special params via improved `@Parameter` annotations directly

**Choice**: For non-whitelist params (`delimiter`, `importMode`, `conflictStrategy`, `includeTotalCount`, `includeWarnings`, `includeEnabled`), improve the `@Parameter(description = "...")` text directly in controllers rather than auto-generating.

**Rationale**: These params don't change dynamically — they're fixed enums or booleans. Auto-generation would add complexity without drift-prevention benefit. Improved static descriptions are sufficient.

### Decision 7: Add `@Schema` descriptions on enum types

**Choice**: Add `@Schema(description = "...")` on `CsvImportMode` and `CsvConflictStrategy` enum constants so Swagger UI shows enum value descriptions.

**Rationale**: Enums are self-documenting once annotated. No runtime cost.

### Decision 8: Parameter examples as nice-to-have

**Choice**: Add `example` values on `@Parameter` for filter/sort params (e.g., `filter` example: `name:contains:test`, `sort` example: `createdAt,desc`). Implemented in the customizer alongside descriptions.

**Rationale**: Pre-fills "Try it out" in Swagger UI, improving discoverability.

## Risks / Trade-offs

**[Markdown rendering in Swagger UI]** → Swagger UI renders markdown in parameter descriptions, but rendering quality varies across versions. Mitigation: Use simple markdown (bold, code, pipe tables) that renders reliably. Verify in actual Swagger UI after implementation.

**[Registry maintenance]** → Adding a new list endpoint requires adding a registry entry. Mitigation: Low frequency (new list endpoints are rare). The registry is small and co-located with the customizer — easy to spot when missed.

**[Description overwrite order]** → If multiple customizers modify the same parameter, last one wins. Mitigation: `OpenApiExampleCustomizer` only touches request body/response examples, not parameter descriptions. No conflict.

**[Long field names in timestamp heuristic]** → Using field name to distinguish timestamp longs from generic longs is a heuristic. Mitigation: The set of LONG-typed fields is small and known; the heuristic covers all current cases. If a new ambiguous field is added, the generator can be updated.
