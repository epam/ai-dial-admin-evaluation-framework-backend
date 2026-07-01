## Why

The per-suite `overall` metric-score definition (`test_suites.overall_score`, a `StructuredQuery`
JSONB expression) is already wired end-to-end — the DB column, snapshot capture, and Phase-3
computation all honor a custom expression for **any** metric count — but there is no way to set or
read it. The `test-suites` create/update API silently ignores it, so the column stays NULL and every
run falls back to the single-metric default. This change closes that last gap, letting a client point
`overall` at one specific metric of a multi-metric suite and have it computed accordingly.

## What Changes

- Expose `overallScore` (a `Map<String, Object>` holding a `StructuredQuery` expression) on
  `TestSuiteRequestDto` and `TestSuiteResponseDto`, so it can be set via `POST`/`PUT /api/v1/test-suites`
  and returned on read.
- Add the `Map → String` write direction to `JsonbMapper` (`mapOverallScore(Map)`), mirroring the
  existing `mapJsonSchema(Map)`; the read direction already exists.
- Map `overallScore` in `TestSuiteMapper.toEntity`, `update`, and `toDto` (clone already preserves it).
- Confirm end-to-end behavior: an authored `overall` expression referencing a flattened metric column
  (`metric::<metricName>::<outputField>`, double-colon separator) resolves via `JsonbFieldResolver`
  and Phase-3 computes `overall` for that single metric — even when the suite has multiple metrics.
- No breaking changes: `overallScore` is optional and additive; existing clients and null columns keep
  the current default behavior.

## Capabilities

### New Capabilities

_None — this change exposes and completes behavior already reserved in existing specs._

### Modified Capabilities

- `test-suites`: the create/update request contract and the suite response contract gain an optional
  `overallScore` field (persisted to `test_suites.overall_score`, round-tripped on read).
- `metric-score-statistics`: removes the deferral noted in the current spec ("Setting it (suite
  create/update exposure) … are deferred; today the column stays null"). Adds a requirement/scenario
  that a custom `overall_score` set via the API and referencing a specific metric column is computed
  end-to-end for that metric regardless of metric count.

## Impact

- **Code (web/service layer only, no schema change):**
  - `service/domain/dto/TestSuiteRequestDto.java`, `service/domain/dto/TestSuiteResponseDto.java`
  - `service/domain/mapper/JsonbMapper.java`, `service/domain/mapper/TestSuiteMapper.java`
- **No migration:** the `test_suites.overall_score` column already exists
  (`V1.23__AddOverallScoreToTestSuites.sql`); no jOOQ regeneration needed.
- **No config change.**
- **API/OpenAPI:** new `@Schema` field + example on the suite request/response DTOs (must stay in sync
  per the OpenAPI-examples convention).
- **No validity impact:** suite validity remains config-only; `overall_score` is not a validity
  component (`SuiteValidationService` unchanged).
- **Tests:** a REST round-trip functional test in `TestSuiteFunctionalTests` and an end-to-end
  two-metric computation test in `MetricScoreComputationFunctionalTests` (retires that file's stale
  "custom overall cannot translate end-to-end" comment).
- **Docs:** `docs/database-schema.md` note that `overall_score` is now settable via the API.
