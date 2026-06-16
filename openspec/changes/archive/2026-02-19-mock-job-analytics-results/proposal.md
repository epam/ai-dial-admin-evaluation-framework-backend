## Why

The mocked evaluation job currently only simulates status transitions (PENDING → RUNNING → COMPLETED/FAILED) without writing any evaluation results to the analytics storage. This makes it impossible for FE/clients to build and test their integration with result-query APIs. Adding mocked result generation enables end-to-end integration testing before the real K8s-based evaluation job is implemented.

## What Changes

- **Mock job writes per-test-case results** to analytics storage on successful run completion — one `TestCaseRunResult` per enabled+valid test case × `numberOfRuns` (from `runConfig`)
- **`MockRequestBodyBuilder`** component builds a realistic request body JSON from the suite's `requestTemplate` + `inputBindings` applied to `testCase.data`, with per-test-case override support — extracted as a reusable component for the real job later
- **`MockResponseBodyBuilder`** component generates structured dummy response bodies: echo-style for SUCCESS, error-envelope for FAILED/ERROR/TIMEOUT
- **`MockResultsGenerator`** orchestrates paginated reading of enabled+valid test cases (via a dedicated repository method) and batch-writing results to analytics per page (separate analytics transaction per page-batch)
- **`TestSuiteRunProperties.MockJob`** gains `result-failure-probability` (default 0.10) controlling per-result `ERROR` rate, independent of the run-level `failure-probability`
- `TestSuiteEvaluationJob` calls `MockResultsGenerator` before marking a run COMPLETED; generator failure is non-fatal (logs warning, run still completes)

## Capabilities

### New Capabilities
- `mock-request-body-builder`: Injectable component that resolves a request body JSON string from `TestSuite.requestTemplate` + `inputBindings` applied over `TestCase.data`, respecting per-case overrides (`requestTemplateOverride`, `inputBindingsOverride`) and `${{variable:default}}` template syntax.

### Modified Capabilities
- `runner-and-jobs`: The mock job now produces analytics results (per-test-case `TestCaseRunResult` rows) in addition to transitioning run status. This is a temporary behaviour until the real K8s evaluation job is implemented.

## Impact

- **New classes**: `MockRequestBodyBuilder`, `MockResponseBodyBuilder`, `MockResultsGenerator`, `MockResultsBatchWriter` (transactional helper) — all in `.service.domain.job`
- **Modified classes**: `TestSuiteEvaluationJob` (inject + call generator), `TestSuiteRunProperties.MockJob` (add `resultFailureProbability`), `TestCaseRepository` / `PostgresTestCaseRepository` (add `findEnabledValidByTestSuiteId`)
- **Config**: `application.yml` gains `test-suite-run.mock-job.result-failure-probability: 0.10`
- **No DB migrations** — writes to existing `test_case_run_results` table via existing repository
- **No API changes** — results become visible through existing analytics endpoints
- **Temporary**: this whole mock result generation path will be removed when the real evaluation job is implemented
