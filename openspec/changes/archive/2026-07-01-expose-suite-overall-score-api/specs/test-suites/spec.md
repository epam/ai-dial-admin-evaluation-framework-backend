## ADDED Requirements

### Requirement: Per-suite `overallScore` on the suite API
The suite create and update request bodies SHALL accept an optional `overallScore` field — a JSON object holding a structured-query `StructuredQuery` expression that defines the run-level `overall` metric score for the suite. The system SHALL persist it verbatim to `test_suites.overall_score` (JSONB) and SHALL return it, as a JSON object, on the suite read (`GET`) and in create/update responses. When omitted or `null`, the column SHALL be left/stored as NULL, preserving the built-in default behavior (see `metric-score-statistics`). `overallScore` SHALL NOT affect suite validity (`isValid`/`validationWarnings`); suite validity remains configuration-only. The expression SHALL be stored opaquely and SHALL NOT be validated as a runnable query at write time (a malformed or non-runnable expression surfaces at run-level computation, not at suite persistence).
Status: **Implemented**

#### Scenario: Set overallScore on update and read it back
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with an `overallScore` object referencing one specific metric column (`metric::<metricName>::<outputField>`)
- **THEN** system SHALL respond HTTP 200 with the updated suite whose `overallScore` equals the submitted object, and a subsequent `GET /api/v1/test-suites/{id}` SHALL return the same `overallScore`

#### Scenario: Set overallScore on create
- **WHEN** client calls `POST /api/v1/test-suites` with a valid body including an `overallScore` object
- **THEN** system SHALL create the suite persisting `overall_score` and return it in the response body

#### Scenario: Omitted overallScore leaves the column null
- **WHEN** client creates or updates a suite without an `overallScore` field
- **THEN** system SHALL store `overall_score` as NULL and the suite response SHALL omit `overallScore` (or return it as null)

#### Scenario: overallScore does not affect suite validity
- **WHEN** client sets `overallScore` on an otherwise valid suite
- **THEN** the suite's `isValid` and `validationWarnings` SHALL be unchanged by the presence or content of `overallScore`

### Implementation notes
- DTO fields: `TestSuiteRequestDto.overallScore` / `TestSuiteResponseDto.overallScore` (`Map<String, Object>`), per the JSONB-as-object convention.
- Conversion: `JsonbMapper.mapOverallScore(Map)` (write) / `mapOverallScore(String)` (read).
- Mapping: `TestSuiteMapper` `toEntity` / `update` / `toDto` (clone already preserves it via `toCloneEntity`).
- Column pre-exists: `V1.23__AddOverallScoreToTestSuites.sql` (no new migration).
