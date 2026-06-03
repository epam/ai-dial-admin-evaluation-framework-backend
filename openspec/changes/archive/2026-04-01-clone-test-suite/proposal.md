## Why

Users need the ability to duplicate an existing test suite (including its test cases, metric definitions, and files) to create variations without manual re-entry. This is common when setting up A/B comparisons, iterating on suite configurations, or creating templates from proven setups. Currently the only path is to create a new suite from scratch and re-import everything.

## What Changes

- **New `POST /api/v1/test-suites/{sourceId}/clone` endpoint** that creates a deep copy of a test suite:
  - Copies suite-level configuration (deployment/MCP refs, schemas, templates, bindings, response columns)
  - Accepts a required `name` and optional patch-style field overrides (description, deploymentRef, etc.)
  - Paginates through source test cases and batch-inserts copies with new UUIDs into the cloned suite
  - Paginates through source TSMDs and batch-inserts copies with new UUIDs
  - Copies associated DIAL files from `{bucket}/suites/{sourceId}/` to `{bucket}/suites/{newId}/`, skipping missing files gracefully
  - Rewrites all file references (`@ef/suites/{sourceId}/...` → `@ef/suites/{newId}/...`) in suite-level JSONB fields and test case data via string replacement
  - Always triggers async revalidation after clone (reuses existing `RevalidationService`)
  - Returns `201 Created` with the new suite and revalidation task
  - Test suite runs are **not** copied
- **New `TestSuiteCloneRequestDto`** — dedicated request DTO with only `name` required; all other fields optional (null = inherit from source)
- **Configurable clone batch size** — reuses existing revalidation batch size property for paginated test case/TSMD copying
- **New major classes**: `TestSuiteCloneService` (orchestrates file copy + DB transaction + revalidation trigger)

## Capabilities

### New Capabilities
- `test-suite-clone`: Deep-copy endpoint for test suites — file copying, paginated entity cloning, file reference rewriting, post-clone revalidation

### Modified Capabilities
- `test-suites`: New clone endpoint added to the test suite API surface; no changes to existing endpoints or behavior

## Impact

- **API**: New endpoint `POST /api/v1/test-suites/{sourceId}/clone`; response reuses `TestSuiteUpdateResultDto`
- **Code**: New service (`TestSuiteCloneService`), new DTO (`TestSuiteCloneRequestDto`), mapper additions, controller method addition. Existing `TestCaseRepository` and `TestSuiteMetricDefinitionRepository` may need new batch-insert methods
- **Database**: No schema changes (clone inserts into existing tables with new UUIDs)
- **DIAL File Storage**: Leverages existing `DialFileClient` for file list/download/upload during copy; best-effort cleanup on transaction failure
- **Configuration**: Reuses `revalidation.batch-size` for paginated copying; no new config properties
- **OpenAPI**: New endpoint needs examples and `OpenApiQueryParamCustomizer` is not needed (no list endpoint)
- **Docs**: `docs/configuration.md` unchanged (no new properties)
