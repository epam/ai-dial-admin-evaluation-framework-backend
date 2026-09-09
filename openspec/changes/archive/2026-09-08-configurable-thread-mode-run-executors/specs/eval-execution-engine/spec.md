## MODIFIED Requirements

### Requirement: In-process evaluation execution
The `InProcessEvaluationExecutor` SHALL read test inputs from the `test_case_run_inputs` table (populated at snapshot phase) in pages, dispatch execution tasks (one per test case per run index) bounded by the configured concurrency level, collect results, and flush them to analytics DB in batches. For legacy runs without a snapshot (no `test_case_run_inputs` rows), it SHALL fall back to reading live test cases from the suite.
Status: **Implemented**

#### Scenario: Snapshot path — pages from inputs table
- **WHEN** `test_case_run_inputs` rows exist for the run (snapshot phase committed)
- **THEN** the executor SHALL page through `testCaseRunInputRepository.findByRunId()` instead of `testCaseRepository.findEnabledValidByTestSuiteId()`. The `testCaseRepository` SHALL NOT be called.

#### Scenario: Legacy path — falls back to live test cases
- **WHEN** `test_case_run_inputs` rows do NOT exist for the run (legacy run without snapshot)
- **THEN** the executor SHALL fall back to paging through live test cases from `testCaseRepository.findEnabledValidByTestSuiteId()`, wrapping each `TestCase` as a `TestCaseRunInput` struct for uniform worker interface.

#### Scenario: Sequential execution (default)
- **WHEN** `concurrencyLevel` is 1 (default)
- **THEN** the executor SHALL process test case calls one at a time, in order (page by page, case by case, run index by run index)

#### Scenario: Parallel execution
- **WHEN** `concurrencyLevel` is greater than 1 (e.g., 10)
- **THEN** the executor SHALL process up to `concurrencyLevel` test case calls concurrently using a semaphore-bounded, thread-per-task worker executor supplied by the run owner — virtual threads when `spring.threads.virtual.enabled` is `true` (default), platform daemon threads when it is `false`; the executor SHALL NOT be created by the execution engine itself

#### Scenario: All enabled and valid test cases are executed
- **WHEN** the executor runs for a suite with N enabled+valid test cases and `numberOfRuns = M`
- **THEN** the executor SHALL dispatch exactly N * M evaluation tasks (one per case per run index 0..M-1)

#### Scenario: Disabled and invalid test cases are skipped
- **WHEN** the suite contains test cases with `enabled = false` or `isValid = false`
- **THEN** those test cases SHALL NOT be dispatched for execution (they are excluded at snapshot phase)

#### Scenario: Test cases read in pages
- **WHEN** the suite has more enabled+valid test cases than fit in a single page
- **THEN** the executor SHALL read inputs using paginated queries

