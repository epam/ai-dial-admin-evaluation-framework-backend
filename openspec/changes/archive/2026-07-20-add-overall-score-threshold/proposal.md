## Why

The test suite already lets an owner configure *how* the run-level `overall` metric score is computed (`overallScore`, an `OverallScoreDefinition`). There is no way to declare what value that score should meet or exceed. The frontend needs a per-suite threshold it can fetch alongside a run's computed `overall` score to render pass/fail status, so the suite needs a place to store that number.

## What Changes

- Add a new optional field `overallScoreThreshold` to the test suite entity, request DTO, and response DTO.
- The field is a plain `Double`, matching the type of the computed overall-score result (`MetricScoreResult.value : Double`, backed by `metric_score_result.value DOUBLE PRECISION`) — not a JSON/config object like `overallScore`.
- Add a Flyway migration adding a nullable `overall_score_threshold DOUBLE PRECISION` column to `test_suites`, and regenerate jOOQ sources.
- Wire the field through `TestSuiteRecordMapper`, `PostgresTestSuiteRepository`, and `TestSuiteMapper` (create, update, clone, and validation-replay paths), mirroring how `overallScore` is already threaded through those same call sites.
- `overallScoreThreshold` is validated to be between `0.0` and `1.0` inclusive (`@DecimalMin`/`@DecimalMax` on `TestSuiteRequestDto`); out-of-range values are rejected with HTTP 400.
- Out of scope: `SuiteSnapshotDto`/`SuiteSnapshotBuilder` (run snapshot) and any pass/fail comparison logic. The frontend will independently fetch the suite (for the threshold) and the run (for the computed score) and compare them client-side, so no snapshot capture or server-side comparison is needed for this change.

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `test-suites`: adds an optional `overallScoreThreshold` field (request/response DTO, persisted column) alongside the existing `overallScore` definition field.

## Impact

- **Data**: new Flyway migration `V1.25__AddOverallScoreThresholdToTestSuites.sql` (nullable `DOUBLE PRECISION` column on `test_suites`); regenerated jOOQ sources under `src/main/java-generated/`.
- **API**: `TestSuiteRequestDto` and `TestSuiteResponseDto` gain `overallScoreThreshold : Double`; OpenAPI schema/examples updated accordingly. No breaking change — new optional field, defaults to `null`.
- **Code**: `TestSuite` model, `TestSuiteRecordMapper`, `PostgresTestSuiteRepository` (create/update/clone-create `.set(...)` calls), `TestSuiteMapper` (`toDto`, `toEntity`, `update`, `toCloneEntity`, `toRequestDto`).
- **Docs**: `docs/database-schema.md` — new column row + migration changelog entry.
- **No changes** to `SuiteSnapshotDto`, `SuiteSnapshotBuilder`, run evaluation/scoring logic, or any other capability.
