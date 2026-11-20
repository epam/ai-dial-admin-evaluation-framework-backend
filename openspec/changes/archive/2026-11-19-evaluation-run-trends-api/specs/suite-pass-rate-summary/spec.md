## MODIFIED Requirements

### Requirement: Eligible runs for a suite
For each requested suite the system SHALL consider only runs that have a pass-count record (see `run-pass-counts`); such records exist only for runs whose status is `COMPLETED`, so runs that are PENDING, RUNNING, CANCELLING, FAILED or CANCELLED SHALL never be selected, even when they already have score rows. Among them the latest run SHALL be the one with the greatest run `created_at_ms`, with ties broken by the larger run id, and the previous run SHALL be the one ranked immediately after it. A suite with exactly one such run SHALL contribute to `passRate` only. Each selected run SHALL be read at its latest computation. A selected run that is soft-deleted SHALL NOT be eligible. A selected run whose record has a total of 0 SHALL contribute nothing for its position; it SHALL NOT be replaced by an older run. A completed run whose pass counts are not recorded yet SHALL NOT be considered until they are.
Status: **Planned**

#### Scenario: Failed or cancelled run is skipped
- **WHEN** a suite's newest runs are FAILED or CANCELLED and have partial score rows
- **THEN** they are not eligible and the latest and previous runs are the two newest COMPLETED runs

#### Scenario: Run still in flight is skipped
- **WHEN** a suite's newest run is RUNNING (for example in Phase 3) and its score rows are already written
- **THEN** it is not eligible and is not counted as latest or previous

#### Scenario: Completed run without score rows contributes nothing
- **WHEN** a suite's newest COMPLETED run has no eval summaries and no score rows at its latest computation, so its pass-count record has a total of 0
- **THEN** the suite contributes nothing to `passRate`, and the older completed run still counts as the previous run

#### Scenario: Latest computation is used
- **WHEN** an eligible run has pass counts for two computations
- **THEN** only the counts of its latest computation are used

#### Scenario: Deleted run is excluded
- **WHEN** a run has been deleted (deletion tombstone written, then the run row removed)
- **THEN** it is not eligible and is not counted as latest or previous

#### Scenario: Completed run not recorded yet
- **WHEN** a suite's newest COMPLETED run has no pass-count record and its older completed run has one
- **THEN** the older run is the latest run until the newest run's counts are recorded

### Requirement: Threshold-aware pass rate per run
The system SHALL compute a run's pass rate as the passed count divided by the total count of the run's pass-count record at its latest computation, as defined by the pass rule of `run-pass-counts`: a test case counts once however many eval-summary rows it has; it counts as passed when all its eval-summary rows are SUCCESS and it is not scored below the threshold (`passed` false); a test case with `passed` false and any test case with a non-SUCCESS execution SHALL count as not passed, whether or not it has a `test_case_eval_scores` row. A run without a threshold (including a run with no snapshot) has no `passed` values, so every SUCCESS test case SHALL count as passed and every other test case as not passed.
Status: **Planned**

#### Scenario: Threshold set, mixed outcomes
- **WHEN** a run with a threshold has 1 test case passed, 1 below the threshold, 1 SUCCESS and unscored, and 1 FAILED
- **THEN** its pass rate is 0.5

#### Scenario: Unscored SUCCESS test case without a score row
- **WHEN** a run with a threshold has 1 passed test case and 1 SUCCESS test case that has eval summaries but no score row
- **THEN** its pass rate is 1.0, and with 1 below-threshold test case instead of the passed one it is 0.5

#### Scenario: No threshold, FAILED and omitted SUCCESS
- **WHEN** a run has no threshold, 1 FAILED test case and 1 SUCCESS test case without a score row
- **THEN** its pass rate is 0.5

#### Scenario: Failed test case without a score row
- **WHEN** a suite has no per-test-case score definition and its run has 1 test case with a non-SUCCESS eval summary and 1 SUCCESS test case, neither with a score row
- **THEN** its pass rate is 0.5

#### Scenario: Metric error without a score row
- **WHEN** a run's test case executed successfully but a metric failed, so its eval-summary row is not SUCCESS, and it has no score row
- **THEN** it counts in the denominator and not as passed

#### Scenario: Several summary rows per test case
- **WHEN** a test case has several eval-summary rows (repetitions, turns or requests)
- **THEN** it counts once in the denominator

#### Scenario: No threshold
- **WHEN** a run has no threshold and has 2 SUCCESS test cases (null `passed`) and 1 FAILED
- **THEN** its pass rate is 2/3

#### Scenario: Failed execution is not a pass
- **WHEN** a run's test case has `execution_status` FAILED
- **THEN** it counts in the denominator and not as passed, with or without a threshold

#### Scenario: Run without a snapshot
- **WHEN** a legacy run has no suite snapshot
- **THEN** it is treated as having no threshold

### Requirement: Datasource separation
The system SHALL compute the summary from the analytics datasource alone: each suite's latest two recorded runs and their pass counts are read in one read-only analytics transaction. The summary SHALL NOT query the meta datasource.
Status: **Planned**

#### Scenario: Meta read outside the analytics transaction
- **WHEN** the summary is computed
- **THEN** only the analytics datasource is queried, so no meta query runs inside or around the analytics transaction
