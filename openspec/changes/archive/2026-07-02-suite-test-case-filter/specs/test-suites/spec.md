## ADDED Requirements

### Requirement: Per-suite `testCaseFilter` on the suite API
The suite create and update request bodies SHALL accept an optional `testCaseFilter` field — a JSON
object holding a Structured Query DSL `filter` subtree that selects which of the bound dataset's test
cases the suite runs (see `suite-test-case-filter`). The system SHALL persist it verbatim to
`test_suites.test_case_filter` (JSONB) and SHALL return it, as a JSON object, on the suite read
(`GET`) and in create/update responses. When omitted or `null`, the column SHALL be stored as NULL,
meaning "no filter". Unlike `overallScore`, `testCaseFilter` SHALL be validated at write time against
the bound dataset's test-case schema: an unknown field, type mismatch, or malformed filter SHALL be
rejected with HTTP 400 `VALIDATION_ERROR`. A non-null `testCaseFilter` on a suite with no bound
dataset (`datasetId IS NULL`) SHALL be rejected with HTTP 400, because it cannot be validated or
applied. `testCaseFilter` SHALL NOT affect suite validity (`isValid`/`validationWarnings`); suite
validity remains configuration-only.
Status: **Planned**

#### Scenario: Set testCaseFilter on update and read it back
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` for a dataset-bound suite with a
  `testCaseFilter` object referencing valid `data::<field>` fields of the dataset schema
- **THEN** system SHALL respond HTTP 200 with the updated suite whose `testCaseFilter` equals the
  submitted object, and a subsequent `GET /api/v1/test-suites/{id}` SHALL return the same object

#### Scenario: Invalid filter rejected at write time
- **WHEN** client submits a `testCaseFilter` that references a field not present in the bound
  dataset's test-case schema (or is otherwise not translatable)
- **THEN** system SHALL respond HTTP 400 `VALIDATION_ERROR` and SHALL NOT persist the filter

#### Scenario: Filter on unbound suite rejected
- **WHEN** client submits a non-null `testCaseFilter` for a suite whose `datasetId IS NULL`
- **THEN** system SHALL respond HTTP 400 `VALIDATION_ERROR`

#### Scenario: Omitted testCaseFilter leaves the column null
- **WHEN** client creates or updates a suite without a `testCaseFilter` field
- **THEN** system SHALL store `test_case_filter` as NULL and the suite response SHALL omit
  `testCaseFilter` (or return it as null)

#### Scenario: Clone inherits the source filter
- **WHEN** a suite carrying a `testCaseFilter` is cloned
- **THEN** the cloned suite SHALL inherit the same `testCaseFilter` (as with `overallScore`)

#### Scenario: testCaseFilter does not affect suite validity
- **WHEN** client sets a valid `testCaseFilter` on an otherwise valid suite
- **THEN** the suite's `isValid` and `validationWarnings` SHALL be unchanged

## Implementation Notes
- DTO fields `TestSuiteRequestDto.testCaseFilter` / `TestSuiteResponseDto.testCaseFilter`
  (`Map<String, Object>`), per the JSONB-as-object convention; conversion via
  `JsonbMapper.mapTestCaseFilter`.
- Mapping in `TestSuiteMapper` `toEntity` / `update` / `toDto` / `toRequestDto` / `toCloneEntity`.
- New column: `V1.24__AddTestCaseFilterToTestSuites.sql` (`test_case_filter JSONB`), then
  `./gradlew generateJooq`.
- Write-time validation delegates to `RunnableTestCaseSelector.validateFilter(datasetId, filterJson)`
  from `TestSuiteService` (see `suite-test-case-filter`).
