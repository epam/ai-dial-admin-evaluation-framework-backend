# Runner and Jobs — Delta Spec

Delta for change: `mock-job-analytics-results`

---

## ADDED Requirements

### Requirement: Mock job writes per-test-case analytics results on successful completion
On successful run completion the mock evaluation job SHALL write one `TestCaseRunResult` per enabled-and-valid test case per run index (0 to `numberOfRuns - 1` from `runConfig`) to the analytics storage.

#### Scenario: Results written for all enabled+valid test cases
- **WHEN** the mock job completes a run successfully (status → COMPLETED)
- **THEN** the system SHALL have written `TestCaseRunResult` rows to analytics storage for every enabled-and-valid test case in the suite, for each run index from 0 to `numberOfRuns - 1`

#### Scenario: Disabled or invalid test cases are excluded
- **WHEN** a test suite contains test cases with `enabled = false` or `isValid = false`
- **THEN** those test cases SHALL NOT have `TestCaseRunResult` rows written for that run

#### Scenario: Large suites are processed in page-batches
- **WHEN** a test suite has more enabled+valid test cases than the configured page size
- **THEN** the system SHALL read enabled+valid test cases in pages (via a dedicated repository query) and commit each page-batch to analytics in a separate analytics transaction

#### Scenario: Result generation failure does not prevent run completion
- **WHEN** analytics result generation throws an unexpected exception
- **THEN** the mock job SHALL log a WARN and still mark the run as COMPLETED (results may be incomplete)

### Requirement: Mock job produces structured request body per test case
The mock job SHALL set `TestCaseRunResult.requestBody` to a JSON string resolved from the suite's `requestTemplate` + `inputBindings` applied over `TestCase.data` (with per-case overrides), using `MockRequestBodyBuilder`.

#### Scenario: Request body reflects template resolution
- **WHEN** the suite has a `requestTemplate` with bindings
- **THEN** `TestCaseRunResult.requestBody` SHALL be the resolved template body JSON string

#### Scenario: Request body falls back to test case data when no template
- **WHEN** the suite has no `requestTemplate`
- **THEN** `TestCaseRunResult.requestBody` SHALL be the raw `TestCase.data` JSON string

### Requirement: Mock job produces structured response body per execution status
The mock job SHALL set `TestCaseRunResult.responseBody` and `TestCaseRunResult.responseStatusCode` according to the simulated `ExecutionStatus` for that result.

#### Scenario: SUCCESS response body
- **WHEN** a result is assigned `ExecutionStatus.SUCCESS`
- **THEN** `responseStatusCode` SHALL be `200` and `responseBody` SHALL be a chat-completions-style JSON envelope

#### Scenario: ERROR response body
- **WHEN** a result is assigned `ExecutionStatus.ERROR`
- **THEN** `responseStatusCode` SHALL be `500` and `responseBody` SHALL contain a JSON error envelope with code `MOCK_INTERNAL_ERROR`

### Requirement: Mock job respects per-result failure probability
The mock job SHALL assign `ExecutionStatus.ERROR` to individual results with probability `test-suite-run.mock-job.result-failure-probability`, independently of the run-level `failure-probability`.

#### Scenario: Configurable per-result error rate
- **WHEN** `result-failure-probability` is configured (default 0.10)
- **THEN** approximately that fraction of results SHALL be marked `ExecutionStatus.ERROR`, the rest `ExecutionStatus.SUCCESS`

### Requirement: Mock job respects numberOfRuns from runConfig
The mock job SHALL generate `numberOfRuns` results per test case (one per run index), where `numberOfRuns` is parsed from `TestSuiteRun.runConfig`.

#### Scenario: Multiple run indices generated
- **WHEN** `runConfig.numberOfRuns` is 3
- **THEN** each enabled+valid test case SHALL have 3 `TestCaseRunResult` rows with `runIndex` 0, 1, and 2

#### Scenario: Default to single run when runConfig is absent or unparseable
- **WHEN** `runConfig` is null or cannot be parsed
- **THEN** the mock job SHALL generate results with `runIndex = 0` only and log a WARN
