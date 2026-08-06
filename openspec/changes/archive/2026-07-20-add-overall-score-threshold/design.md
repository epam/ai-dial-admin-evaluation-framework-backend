## Context

`test_suites.overall_score` already stores a typed, JSONB-backed `OverallScoreDefinition` (`mean` / `weighted_mean` / `custom_function`) describing *how* the run-level `overall` metric score is computed. The computed result itself is persisted per run in `metric_score_result.value` (`DOUBLE PRECISION`, mapped to `Double` in `MetricScoreResult`). The frontend wants to compare a run's computed `overall` value against a suite-level threshold, fetching the suite and the run independently and comparing client-side. This design covers adding that threshold as a plain scalar field on the suite — no computation, comparison, or snapshot logic.

## Goals / Non-Goals

**Goals:**
- Persist an optional `overallScoreThreshold : Double` on `test_suites`, exposed via the existing suite request/response DTOs.
- Match the type of the actual computed overall-score result (`Double` / `DOUBLE PRECISION`), so the frontend can compare the two values without conversion.
- Thread the field through every existing code path that already handles `overallScore` (create, update, clone, validation-replay, jOOQ record mapping), keeping the two fields symmetric in the codebase.

**Non-Goals:**
- No pass/fail computation or comparison logic in the backend.
- No inclusion in `SuiteSnapshotDto` / `SuiteSnapshotBuilder` — the frontend does not need the threshold "as it was when a given run executed," only the suite's current value, so no run-time snapshot capture is needed.
- No pass/fail computation, comparison, or run-snapshot capture in the backend beyond the plain 0.0–1.0 range check on the stored value.

## Decisions

- **Plain scalar column, not JSONB.** Unlike `overallScore` (a discriminated config object needing `OverallScoreDefinition` + `JsonbMapper` serialization), a threshold is a single number. Storing it as `DOUBLE PRECISION` and mapping directly to `Double` avoids introducing a wrapper type or JSON (de)serialization for no benefit. Alternative considered: reuse the `OverallScore`-style JSONB pattern for consistency — rejected as unnecessary indirection for a single scalar.
- **Type is `Double`, not `BigDecimal`.** The value being compared against — `MetricScoreResult.value` — is `Double`. Using the same type end-to-end avoids a conversion step at the point of comparison (even though comparison happens client-side, keeping the API contract type-aligned with the result avoids the frontend having to reconcile a `BigDecimal` string-like JSON number against a `Double`-typed run value). `WeightedMetric.weight` in the same DTO family uses `BigDecimal`, but that field is an input to a computation (needs arbitrary precision for weighting math); `overallScoreThreshold` is a comparison target for an already-computed `Double`, so `Double` is the closer match.
- **No snapshot capture.** Confirmed with stakeholder: frontend performs `GET suite` + `GET run` and compares client-side, so the threshold does not need to be frozen per-run. This keeps the change additive-only to `TestSuite`/DTOs/migration/mapper/repository, with zero changes to run-creation or snapshot code.
- **Range validated to `0.0`–`1.0` inclusive.** Unlike `overallScore` (which is unconstrained), `overallScoreThreshold` is validated at the DTO level via `@DecimalMin("0.0")`/`@DecimalMax("1.0")` on `TestSuiteRequestDto`, with the bound literals and error message centralized in `RunnerValidationConstants` (`MIN_OVERALL_SCORE_THRESHOLD`, `MAX_OVERALL_SCORE_THRESHOLD`, `OVERALL_SCORE_THRESHOLD_RANGE_MESSAGE`) per the "no magic numbers in validation annotations" convention. A suite's `overall` score is expected to be a normalized fraction in this domain, so the threshold that gets compared against it is constrained to the same range; out-of-range values are rejected with HTTP 400 `VALIDATION_ERROR` at write time rather than persisted.
- **Reuse existing mapper/repository call sites.** `TestSuiteMapper` (`toDto`, `toEntity`, `update`, `toCloneEntity`, `toRequestDto`) and `PostgresTestSuiteRepository`'s three `.set(TEST_SUITES.OVERALL_SCORE, ...)` call sites (create, update, clone-create) already exist as the seam for suite-level scalar/JSONB fields — extending each with one more line keeps the field's plumbing consistent with `overallScore` and `testCaseFilter` rather than introducing a parallel code path.

## Risks / Trade-offs

- [Threshold and computed score could drift out of sync if the suite's threshold changes between two runs shown side-by-side in the UI] → Accepted: this is the explicit, agreed behavior (no snapshotting) since the frontend wants the *current* threshold, not a historical one.
- [Boundary values (`0.0`/`1.0`) could be mishandled by an off-by-one in the `@DecimalMin`/`@DecimalMax` bounds] → Mitigated by explicit functional test coverage asserting both boundaries are accepted and values just outside them are rejected with HTTP 400.
- [jOOQ regeneration is a manual, must-remember step after the migration] → Mitigated by `JooqSchemaDriftTest`, which already fails the build if generated sources are out of sync with migrations.
