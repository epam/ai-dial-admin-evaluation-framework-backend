## MODIFIED Requirements

### Requirement: Trigger a test suite run
Status: **Implemented**
An additional **run-time presence check** SHALL be performed after the `valid = false` guard: the service SHALL count the runnable (valid + enabled, excluding the suite's `disabledTestCaseIds`) test cases for the bound dataset, and if the count is zero, SHALL respond with HTTP 409 `INVALID_OPERATION` with message "Suite has no valid and enabled test cases". No run record SHALL be persisted and no async job SHALL be dispatched when this check fails.

Guard order:
1. Suite not found → 404 `NOT_FOUND`
2. Unbound (`datasetId == null`) → 409 `SUITE_HAS_NO_DATASET`
3. Config-invalid (`isValid == false`) → 409 `INVALID_OPERATION`
4. Zero runnable test cases → 409 `INVALID_OPERATION`
5. Concurrent run limits → 429 `TOO_MANY_REQUESTS`

#### Scenario: Bound suite with no runnable test cases rejected
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/runs` for a config-valid, bound suite whose dataset has zero runnable (valid + enabled, excluding `disabledTestCaseIds`) test cases
- **THEN** system SHALL respond with HTTP 409 Conflict and error code `INVALID_OPERATION` with message "Suite has no valid and enabled test cases"; no run record SHALL be persisted and no async job SHALL be dispatched

#### Implementation notes
- `TestSuiteRunService.createRun` (service/domain/TestSuiteRunService.java) — guard #4 added after guard #3; throws `InvalidOperationException("Suite has no valid and enabled test cases")`.
- `RunnableTestCaseCounter.countRunnable` (service/domain/RunnableTestCaseCounter.java) — called with `suite.datasetId` and `suite.disabledTestCaseIds`; delegates to `testCaseRepository.countValidByDatasetIdExcludingIds`.
- `TestSuiteRunFunctionalTests` — covers zero-runnable → 409 and post-add → 202.
