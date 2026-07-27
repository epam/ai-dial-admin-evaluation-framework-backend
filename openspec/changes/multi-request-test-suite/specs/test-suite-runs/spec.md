## MODIFIED Requirements

### Requirement: Trigger a test suite run
The service SHALL provide `POST /api/v1/test-suites/{testSuiteId}/runs` to create and trigger a new test suite run. The endpoint SHALL validate the request, verify the suite is bound to a dataset, persist a run record with status PENDING (including `testRunName` and `numberOfTestCases` snapshot), dispatch an async job, and return the run details immediately (without waiting for job completion). The unbound-suite guard (`datasetId IS NULL`) SHALL run before the `valid = false` check, so unbound suites SHALL surface as HTTP 409 with error code `SUITE_HAS_NO_DATASET` regardless of their validation state. Two **chain guards** SHALL run after the `valid = false` guard and before the runnable-count check: the suite's normalized chain length SHALL be re-checked against the currently configured maximum (rejecting with HTTP 409 `INVALID_OPERATION` when exceeded, because the configurable cap may have been lowered after the suite was saved), and a **multi-request** suite bound to a dataset containing any multi-turn test case SHALL be rejected with HTTP 409 `INVALID_OPERATION`. An additional **run-time presence check** SHALL then be performed: the service SHALL count the runnable test cases for the bound dataset — those that are valid, excluded neither by the suite's `disabledTestCaseIds` nor (when set) by the suite's `testCaseFilter` — and if the count is zero, SHALL respond with HTTP 409 `INVALID_OPERATION` with message "Suite has no valid and enabled test cases". No run record SHALL be persisted and no async job SHALL be dispatched when any of these checks fails.

Guard order:
1. Suite not found → 404 `NOT_FOUND`
2. Unbound (`datasetId == null`) → 409 `SUITE_HAS_NO_DATASET`
3. Config-invalid (`isValid == false`) → 409 `INVALID_OPERATION`
3b. Chain length exceeds the configured maximum → 409 `INVALID_OPERATION`
3c. Multi-request suite bound to a dataset containing a multi-turn case → 409 `INVALID_OPERATION`
4. Zero runnable test cases → 409 `INVALID_OPERATION`
5. Concurrent run limits → 429 `TOO_MANY_REQUESTS`

Status: **Planned**

#### Scenario: Successful run trigger
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/runs` with a valid `RunConfigDto` body and the test suite exists and is bound to a dataset
- **THEN** system SHALL create a `test_suite_runs` record with status `PENDING`, populate `testRunName` (from config or auto-generated), snapshot `numberOfTestCases` from the bound dataset's runnable test case count (valid, not excluded by `disabledTestCaseIds`, and matching `testCaseFilter` when set), dispatch an async evaluation job on the dedicated executor, and return HTTP 202 Accepted with the `TestSuiteRunResponseDto`

#### Scenario: Test suite not found
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/runs` with a non-existent `testSuiteId`
- **THEN** system SHALL respond with HTTP 404 and error code `NOT_FOUND`

#### Scenario: Unbound suite (datasetId is null) rejected
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/runs` for an existing suite whose `datasetId IS NULL`
- **THEN** system SHALL respond with HTTP 409 and error code `SUITE_HAS_NO_DATASET`; no run record SHALL be persisted and no async job SHALL be dispatched; this check SHALL run before the `valid = false` guard so the dataset-binding failure mode is reported even when the suite would also fail validation

#### Scenario: Invalid run configuration
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/runs` with an invalid body (e.g., `numberOfRuns` is null, zero, negative, or exceeds maximum)
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`

#### Scenario: Global concurrent run limit exceeded
- **WHEN** client triggers a run but the total count of PENDING + RUNNING runs across all suites has reached the configured global limit
- **THEN** system SHALL respond with HTTP 429 Too Many Requests with error code `TOO_MANY_REQUESTS` and a message indicating the global limit was reached, including current and maximum counts in `details`

#### Scenario: Per-suite concurrent run limit exceeded
- **WHEN** client triggers a run but the count of PENDING + RUNNING runs for the target test suite has reached the configured per-suite limit
- **THEN** system SHALL respond with HTTP 429 Too Many Requests with error code `TOO_MANY_REQUESTS` and a message indicating the per-suite limit was reached, including current and maximum counts in `details`

#### Scenario: Test suite not in valid state
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/runs` for a bound test suite that has `valid = false` (failed validation)
- **THEN** system SHALL respond with HTTP 409 Conflict and error code `INVALID_OPERATION` with a message indicating the test suite is not in a valid state (this check applies only to suites that pass the `SUITE_HAS_NO_DATASET` guard)

#### Scenario: Chain longer than the current cap is rejected
- **WHEN** a suite whose normalized chain length exceeds the currently configured maximum triggers a run — including a suite saved while the cap was higher
- **THEN** system SHALL respond with HTTP 409 Conflict and error code `INVALID_OPERATION` naming the chain length and the cap; no run record SHALL be persisted and no async job SHALL be dispatched

#### Scenario: Multi-request suite over a multi-turn dataset is rejected
- **WHEN** a multi-request suite bound to a dataset containing at least one test case with `multi_turn_data` triggers a run
- **THEN** system SHALL respond with HTTP 409 Conflict and error code `INVALID_OPERATION`; no run record SHALL be persisted and no async job SHALL be dispatched

#### Scenario: Single-request suite over a multi-turn dataset still runs
- **WHEN** a single-request suite bound to a dataset containing multi-turn cases triggers a run
- **THEN** the chain guards SHALL pass and the run SHALL proceed with unchanged multi-turn behavior

#### Scenario: Bound suite with no runnable test cases rejected
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/runs` for a config-valid, bound suite whose dataset has zero runnable test cases — because none are valid, all valid ones are in `disabledTestCaseIds`, or none match the suite's `testCaseFilter`
- **THEN** system SHALL respond with HTTP 409 Conflict and error code `INVALID_OPERATION` with message "Suite has no valid and enabled test cases"; no run record SHALL be persisted and no async job SHALL be dispatched

#### Scenario: Runnable count honors testCaseFilter
- **WHEN** a config-valid, bound suite has valid, non-excluded test cases but its `testCaseFilter` matches a non-empty subset of them
- **THEN** the zero-runnable guard SHALL pass and the persisted `numberOfTestCases` SHALL equal the count of the filter-matching subset

#### Scenario: Chain guards precede the runnable-count query
- **WHEN** a suite both exceeds the chain cap and has zero runnable test cases
- **THEN** system SHALL report the chain-cap failure, because the chain guards run before the runnable-count check

#### Scenario: Executor rejects job submission
- **WHEN** the run is created successfully but the dedicated executor's queue is full and max pool size is reached at async dispatch time
- **THEN** the run SHALL have been persisted with status PENDING and HTTP 202 returned to the client. The service SHALL catch `RejectedExecutionException` in the post-commit callback, mark the run as FAILED with error category `RESOURCE_LIMIT` and code `EXECUTOR_REJECTED`, and log a warning

## ADDED Requirements

### Requirement: Chain length configuration property
The maximum number of requests permitted in a suite's normalized chain SHALL be configurable via `test-suite.multi-request.max-requests`, with its default defined in `application.yml`. The property SHALL be documented in `docs/configuration.md`.
Status: **Planned**

#### Scenario: Default cap applies when unset
- **WHEN** the property is not overridden
- **THEN** the configured default SHALL apply to both the save-time and run-creation chain checks

#### Scenario: Override is honored by both checks
- **WHEN** an operator overrides the property
- **THEN** both suite save and run creation SHALL enforce the overridden value

## Implementation notes

`TestSuiteRunService.createRun` guard sequence; multi-turn presence reuses `TestCaseRepository.existsMultiTurnByDatasetId`; chain length obtained from the shared chain normalizer.
