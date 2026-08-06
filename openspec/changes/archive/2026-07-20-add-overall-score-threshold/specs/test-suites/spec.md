## ADDED Requirements

### Requirement: Per-suite `overallScoreThreshold` on the suite API
The suite create and update request bodies SHALL accept an optional `overallScoreThreshold` field — a numeric value (same type as the computed run-level `overall` metric score result) that a client can compare a run's `overall` score against. The system SHALL persist it verbatim to `test_suites.overall_score_threshold` (`DOUBLE PRECISION`) and SHALL return it on the suite read (`GET`) and in create/update responses. When omitted or `null`, the column SHALL be left/stored as NULL. `overallScoreThreshold` SHALL NOT affect suite validity (`isValid`/`validationWarnings`); suite validity remains configuration-only. The system SHALL reject a value outside the inclusive range `0.0`–`1.0` with HTTP 400 `VALIDATION_ERROR` at write time and SHALL NOT persist it. The system SHALL NOT perform any comparison against a run's computed `overall` score — evaluating the threshold against a run's result is a client-side concern.
Status: **Implemented**

#### Scenario: Set overallScoreThreshold on update and read it back
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with an `overallScoreThreshold` numeric value
- **THEN** system SHALL respond HTTP 200 with the updated suite whose `overallScoreThreshold` equals the submitted value, and a subsequent `GET /api/v1/test-suites/{id}` SHALL return the same `overallScoreThreshold`

#### Scenario: Set overallScoreThreshold on create
- **WHEN** client calls `POST /api/v1/test-suites` with a valid body including an `overallScoreThreshold` value
- **THEN** system SHALL create the suite persisting `overall_score_threshold` and return it in the response body

#### Scenario: Omitted overallScoreThreshold leaves the column null
- **WHEN** client creates or updates a suite without an `overallScoreThreshold` field
- **THEN** system SHALL store `overall_score_threshold` as NULL and the suite response SHALL omit `overallScoreThreshold` (or return it as null)

#### Scenario: overallScoreThreshold does not affect suite validity
- **WHEN** client sets `overallScoreThreshold` on an otherwise valid suite
- **THEN** the suite's `isValid` and `validationWarnings` SHALL be unchanged by the presence or value of `overallScoreThreshold`

#### Scenario: Clone inherits the source threshold
- **WHEN** a suite carrying an `overallScoreThreshold` is cloned
- **THEN** the cloned suite SHALL inherit the same `overallScoreThreshold` (as with `overallScore`)

#### Scenario: Value outside 0.0-1.0 is rejected
- **WHEN** client submits `overallScoreThreshold` less than `0.0` or greater than `1.0` on create or update
- **THEN** system SHALL respond HTTP 400 `VALIDATION_ERROR` and SHALL NOT persist the suite/value

#### Scenario: Boundary values 0.0 and 1.0 are accepted
- **WHEN** client submits `overallScoreThreshold` equal to `0.0` or `1.0`
- **THEN** system SHALL accept and persist the value

## Implementation Notes
- `overallScoreThreshold` (per-suite): DTO fields `TestSuiteRequestDto.overallScoreThreshold` / `TestSuiteResponseDto.overallScoreThreshold` (`Double`) — a plain scalar, not JSONB, unlike `overallScore`/`testCaseFilter`. Model field: `TestSuite.overallScoreThreshold` (`Double`). Mapping in `TestSuiteMapper` `toEntity` / `update` / `toDto` / `toRequestDto` / `toCloneEntity` via direct getter/setter (no `JsonbMapper` conversion needed). Record mapping in `TestSuiteRecordMapper` maps the jOOQ column straight to `Double`. Repository: `PostgresTestSuiteRepository` sets `TEST_SUITES.OVERALL_SCORE_THRESHOLD` alongside `TEST_SUITES.OVERALL_SCORE` in the create, update, and clone-create statements. New column: `V1.25__AddOverallScoreThresholdToTestSuites.sql` (`overall_score_threshold DOUBLE PRECISION`), then `./gradlew generateJooq`. Range validation via `@DecimalMin`/`@DecimalMax` on `TestSuiteRequestDto.overallScoreThreshold`, with bound literals and message in `RunnerValidationConstants` (`MIN_OVERALL_SCORE_THRESHOLD` = `"0.0"`, `MAX_OVERALL_SCORE_THRESHOLD` = `"1.0"`, `OVERALL_SCORE_THRESHOLD_RANGE_MESSAGE`).
- Not included in `SuiteSnapshotDto`/`SuiteSnapshotBuilder` — the field reflects the suite's current configured value; it is not captured per-run.
