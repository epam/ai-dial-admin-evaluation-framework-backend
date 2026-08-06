## ADDED Requirements

### Requirement: Import run captures a suite snapshot

An import run SHALL capture a `suite_snapshot` (via the same snapshot phase normal runs use) so that Phase 3 score computation and any retrospective read of the run see a frozen record of the suite's deployment/endpoint (or MCP tool), request template, response columns, dataset reference, and `overallScore` definition as they were at import time — instead of resynthesizing them from the live suite/dataset. An import run SHALL NOT capture `test_case_run_inputs` and SHALL NOT have its `number_of_test_cases` overwritten by the snapshot phase, since Phase 1 (the only consumer of `test_case_run_inputs`) never executes for an import run and `number_of_test_cases` is already set from the actual imported result count at run creation.

Status: **Planned**

#### Scenario: Imported run's GET response includes a non-null suite snapshot
- **WHEN** an import request completes and `GET /api/v1/test-suites/{testSuiteId}/runs/{runId}` is called for that run
- **THEN** the response's `suiteSnapshot` field SHALL be non-null and SHALL reflect the suite's deployment/endpoint (or MCP tool) configuration as of import time

#### Scenario: Imported run's number of test cases is unaffected by the snapshot phase
- **WHEN** an import request with `N` CSV rows completes
- **THEN** the run's `numberOfTestCases` SHALL equal `N` (the imported row count), not the count of currently-runnable test cases in the suite's live dataset

#### Scenario: Score computation uses the persisted snapshot, not a live resynthesis
- **WHEN** Phase 3 (`computeMetricScores`) resolves `overallScoreDefinition` for an import run
- **THEN** it SHALL read the `overallScore` value from the persisted `suite_snapshot` captured at import time, not from a transient snapshot built from the suite's live configuration at score-computation time

## MODIFIED Requirements

### Requirement: Import eval results and create a run in one request
The system SHALL provide `POST /api/v1/test-suites/{testSuiteId}/runs/import` that accepts a batch of already-produced eval results for an existing, dataset-bound test suite, and in a single request creates a `TestSuiteRun`, persists the batch as `TestCaseRunResult` rows, captures a suite snapshot (see "Import run captures a suite snapshot"), and asynchronously triggers metric evaluation and score computation against the persisted results — without invoking any deployment.

Status: **Implemented**

#### Scenario: Successful import creates a run and returns immediately
- **WHEN** a client submits a non-empty, valid batch of eval results for an existing, valid, dataset-bound suite
- **THEN** the system creates a `TestSuiteRun` with status `PENDING`, persists the results, and returns `202 Accepted` with the run resource while evaluation continues asynchronously

#### Scenario: Deployment invocation is never performed
- **WHEN** an import request is processed
- **THEN** the system does not invoke any deployment/model and instead uses the caller-supplied response bodies directly as the basis for metric evaluation
