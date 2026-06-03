## Why

The `GET /api/v1/test-suite-runs` list endpoint currently supports filters only on `testSuiteId`, `status`, `testRunName`, and `createdAt`. Clients need to filter runs by their own `id` (e.g., to check membership in a known set) and by time-range on `startedAt`/`completedAt` (e.g., to find runs that started or finished within a given window), which is not possible today.

## What Changes

- Add `id` filter field to `TEST_SUITE_RUNS` filter whitelist — UUID type, supports `eq` and `in` operators (same as `testSuiteId`).
- Add `startedAt` filter field to `TEST_SUITE_RUNS` filter whitelist — LONG type (epoch ms), supports `gt`, `gte`, `lt`, `lte` operators (same as `createdAt`).
- Add `completedAt` filter field to `TEST_SUITE_RUNS` filter whitelist — LONG type (epoch ms), supports `gt`, `gte`, `lt`, `lte` operators (same as `createdAt`).
- Add functional test coverage for the three new filter fields.
- Update `test-suite-runs` delta spec with new filter scenarios.

## Capabilities

### New Capabilities

_(none)_

### Modified Capabilities

- `test-suite-runs`: Adding three new filter fields (`id`, `startedAt`, `completedAt`) to the list endpoint's filter whitelist. The requirements (supported fields and operators) are changing.

## Impact

- **Code**: `FilterWhitelists.java` — one file, three new map entries in `TEST_SUITE_RUNS`.
- **API**: `GET /api/v1/test-suite-runs` gains three new filterable fields. Fully backwards-compatible; existing queries unaffected.
- **OpenAPI docs**: Auto-updated via `OpenApiQueryParamCustomizer` reading `FilterWhitelists.TEST_SUITE_RUNS` — no customizer change needed.
- **Tests**: `TestSuiteRunFunctionalTests` — new test cases for each new filter field.
- **No DB migrations**, **no new config properties**, **no new packages**.
