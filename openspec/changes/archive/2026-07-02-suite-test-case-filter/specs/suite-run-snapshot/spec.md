## MODIFIED Requirements

### Requirement: Snapshot phase execution
The snapshot phase SHALL execute atomically before the run transitions to RUNNING. The phase reads the live `TestSuite` and the live `Dataset` referenced by the suite, and pages through the dataset's test cases excluding those in the suite's `disabledTestCaseIds` and, when the suite has a `testCaseFilter`, keeping only the test cases that match it.
Status: **Planned**

#### Scenario: Snapshot phase sequence
- **WHEN** snapshot phase runs
- **THEN** it SHALL in a single `ISOLATION_REPEATABLE_READ` transaction:
  1. Delete any leftover `test_case_run_inputs` from prior failed attempts (`deleteByRunId`)
  2. Load the live `TestSuite`; throw `SnapshotSuiteMissingException` if absent
  3. Load the live `Dataset` referenced by `testSuite.datasetId`; throw `SnapshotDatasetMissingException` (new error code) if absent
  4. Build `SuiteSnapshotDto` via `SuiteSnapshotBuilder.build(testSuite, dataset)`; the builder sources `testCaseSchema` from the dataset and populates `datasetRef = {id: dataset.id, version: dataset.version, name: dataset.name}`
  5. Serialize snapshot to JSON; throw `IllegalStateException` on serialization error
  6. Page through the runnable test cases in the dataset — valid, excluded by `testSuite.disabledTestCaseIds`, and matching `testSuite.testCaseFilter` when set — and batch-insert into `test_case_run_inputs`
  7. Call `updateSuiteSnapshot(runId, snapshotJson)` and `updateNumberOfTestCases(runId, totalInputs)`

#### Scenario: Snapshot excludes disabled test cases
- **WHEN** the suite's `disabledTestCaseIds = [tc-2, tc-5]` and the dataset has test cases `[tc-1, tc-2, tc-3, tc-4, tc-5]`
- **THEN** `test_case_run_inputs` for the run SHALL contain rows for `[tc-1, tc-3, tc-4]` only; `numberOfTestCases = 3`

#### Scenario: Snapshot honors the suite testCaseFilter
- **WHEN** the suite has `testCaseFilter` matching only `[tc-1, tc-4]` among the valid, non-excluded test cases `[tc-1, tc-3, tc-4]`
- **THEN** `test_case_run_inputs` SHALL contain rows for `[tc-1, tc-4]` only; `numberOfTestCases = 2`; a null `testCaseFilter` SHALL impose no additional restriction

#### Scenario: Stale disabled ID is silently ignored
- **WHEN** the suite's `disabledTestCaseIds = [tc-deleted]` and `tc-deleted` is no longer in the dataset
- **THEN** the snapshot-phase query SHALL produce all valid test cases in the dataset; the stale id is naturally excluded by set-membership semantics and does NOT cause an error

#### Scenario: Snapshot excludes invalid test cases
- **WHEN** a test case in the dataset has `isValid = false`
- **THEN** it SHALL NOT appear in `test_case_run_inputs` (only `valid = true` rows are materialized)

#### Scenario: Snapshot row ordering is deterministic
- **WHEN** the snapshot phase pages through runnable test cases
- **THEN** the implementation SHALL `ORDER BY created_at_ms ASC, id ASC` so that `test_case_run_inputs.position` is assigned in a stable, repeatable order across attempts; this matches the legacy behavior of `findEnabledValidByTestSuiteId` (which ordered by `CREATED_AT_MS asc, ID asc`) so the snapshot of a given (suite, dataset) pair has the same row ordering before and after the migration

#### Scenario: Retry on serialization failure
- **WHEN** snapshot transaction fails with SQL state `40001`
- **THEN** system SHALL retry up to 2 times (3 total attempts); on final failure mark run FAILED with `SNAPSHOT_SERIALIZATION_CONFLICT`

#### Scenario: Non-serialization failure
- **WHEN** snapshot transaction fails with any other exception
- **THEN** system SHALL mark run FAILED; error code is `SNAPSHOT_SUITE_MISSING` for suite-not-found, `SNAPSHOT_DATASET_MISSING` for dataset-not-found, `SNAPSHOT_FAILED` otherwise

#### Scenario: Snapshot committed before RUNNING transition
- **WHEN** snapshot phase succeeds
- **THEN** `suite_snapshot` and `test_case_run_inputs` rows SHALL both be committed in the DB before `status` is set to RUNNING

#### Scenario: Inconsistent snapshot guard
- **WHEN** run transitions to RUNNING and exactly one of `suite_snapshot` / `test_case_run_inputs` is present
- **THEN** run SHALL be immediately marked FAILED with `SNAPSHOT_STATE_INCONSISTENT`

#### Scenario: Legacy-fallback synthesis sources schema from live dataset
- **WHEN** `resolveSnapshot()` runs against a run row created before the snapshot feature (i.e., `suite_snapshot IS NULL`)
- **THEN** synthesis SHALL load the live `TestSuite` AND the live `Dataset` referenced by the suite; build a transient `SuiteSnapshotDto` via `SuiteSnapshotBuilder.build(testSuite, dataset)` (version `"2"`, schema sourced from the live dataset); if either the suite or the dataset is missing, fail the run with the corresponding error code

## Implementation Notes
- `TestSuiteEvaluationJob.attemptSnapshot` selects via `RunnableTestCaseSelector.loadRunnablePage`
  (translation-layer reuse), replacing the direct
  `TestCaseRepository.findValidByDatasetIdExcludingIds` call; null `testCaseFilter` short-circuits to
  the prior valid + excluded predicate. See `suite-test-case-filter`.
