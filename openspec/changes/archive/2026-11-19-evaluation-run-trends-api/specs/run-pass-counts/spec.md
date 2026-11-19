## Purpose

Dashboards rank and compare test suites by the pass rate of their runs. Counting a run's test cases on every request does not scale to "all suites, sorted". This capability stores, for each completed run and computation, how many test cases passed and how many there were, and defines the one rule for what "passed" means.

## ADDED Requirements

### Requirement: Stored pass counts per completed run
The system SHALL store, in the analytics database, at most one pass-count record per run and computation: the run, its suite, the computation, the run's creation timestamp, the number of passed test cases, the total number of test cases, and the computation's timestamp (the greatest `computed_at_ms` of the computation's eval summaries, or of its score rows when it has no summaries; 0 for a record without a computation). A record SHALL be written only for runs whose status is `COMPLETED`. A record without a computation (0 of 0: written when the computation being recorded has no eval-summary rows and no score rows, or when the run has no eval summaries at all) SHALL rank below every record of the same run that has one. Records SHALL be append-only: a record SHALL never be updated, and recording the same run and computation again SHALL succeed and leave the existing record unchanged. The run's creation timestamp SHALL equal the run's `created_at_ms`.
Status: **Planned**

#### Scenario: One record per run and computation
- **WHEN** the counts of the same run and computation are recorded twice
- **THEN** exactly one record exists and it holds the values of the first write

#### Scenario: Several computations of one run
- **WHEN** a run has records for two computations
- **THEN** both records are kept, and readers use the one of the run's latest computation (greatest computation timestamp, ties broken by the smaller computation id)

#### Scenario: Only completed runs
- **WHEN** a run ends as FAILED or CANCELLED, or is still in flight
- **THEN** no pass-count record is written for it

#### Scenario: Record without a computation is superseded
- **WHEN** a run has a 0-of-0 record without a computation and later gets a record for a computation
- **THEN** readers use the record of the computation, whatever its timestamp

### Requirement: Pass rule for a test case
The system SHALL count each test case of a run's computation once, however many eval-summary rows (repetitions, turns, requests) it has. A test case SHALL count as passed when every one of its eval-summary rows of that computation has `execution_status` `SUCCESS` and it has no `test_case_eval_scores` row of that computation with `passed` false or with an `execution_status` other than `SUCCESS`. Every other test case SHALL count as not passed. The total SHALL be the number of distinct test cases that have an eval-summary row or a `test_case_eval_scores` row in that computation. The rule SHALL NOT depend on whether the suite has a per-test-case score definition or a threshold.
Status: **Planned**

#### Scenario: Threshold set, mixed outcomes
- **WHEN** a computation has 1 test case with `passed` true, 1 with `passed` false, 1 SUCCESS test case without a `passed` value, and 1 test case whose execution failed
- **THEN** the counts are 2 passed of 4

#### Scenario: Failed test case in a suite without a score definition
- **WHEN** a suite has no per-test-case score definition (so the run has no `test_case_eval_scores` rows) and the run has 1 test case with a non-SUCCESS eval summary and 1 with SUCCESS eval summaries only
- **THEN** the counts are 1 passed of 2

#### Scenario: One failed repetition fails the test case
- **WHEN** a test case has three eval-summary rows and one of them is not SUCCESS
- **THEN** it counts once in the total and not as passed

#### Scenario: Metric error fails the test case
- **WHEN** a test case's execution succeeded but a metric failed, so its eval-summary row is not SUCCESS, and it has no score row
- **THEN** it counts as not passed

#### Scenario: Unscored SUCCESS test case
- **WHEN** a SUCCESS test case has no `test_case_eval_scores` row, or has one with no `passed` value
- **THEN** it counts as passed

#### Scenario: Run without test cases
- **WHEN** a completed run has no eval-summary rows and no score rows
- **THEN** its record has 0 passed of 0, and its pass rate is undefined (absent), not 0

### Requirement: Counts are recorded when a run completes
The system SHALL record a run's pass counts for the computation the run produced right after the run's status becomes `COMPLETED`; when that computation has no eval-summary rows and no score rows, the record SHALL be the 0-of-0 record without a computation. A failure to record, of any kind, SHALL NOT change the run's status, SHALL NOT fail the run and SHALL NOT suppress the run's terminal notification; it SHALL be logged, and the missing record SHALL be left to reconciliation.
Status: **Planned**

#### Scenario: Completed run gets its record
- **WHEN** a run with 2 passed test cases out of 3 reaches `COMPLETED`
- **THEN** a pass-count record of 2 of 3 exists for the run and its computation

#### Scenario: Cancelled at the last moment
- **WHEN** a run is cancelled before it can be marked `COMPLETED`
- **THEN** no pass-count record is written for it

#### Scenario: Recording fails
- **WHEN** writing the pass-count record fails
- **THEN** the run stays `COMPLETED`, its completion is notified as usual and no error is reported for the run

### Requirement: Reconciliation of missing records
The system SHALL periodically, starting shortly after startup, find `COMPLETED` runs whose latest computation has no pass-count record and record the counts of that computation, in bounded batches. This covers runs whose record failed to write, runs completed before records existed, and completed runs that received a newer computation through the eval-summary write API. A computation younger than a configurable minimum age SHALL be left for a later pass, so that a computation still being written is not recorded half-way. A run's latest computation SHALL be determined from its eval summaries; a run without any eval summaries SHALL get a single 0-of-0 record with no computation, so that it is not processed again. Reconciliation SHALL be safe to run concurrently on several application instances and SHALL be safe to repeat. A failure on one batch SHALL be logged and SHALL NOT stop the rest of the pass or later passes. Reconciliation SHALL be switchable off by configuration; the initial delay, the interval, the batch size and the minimum age SHALL be configurable.
Status: **Planned**

#### Scenario: Run completed before the record could be written
- **WHEN** a `COMPLETED` run has eval summaries but no pass-count record and reconciliation runs
- **THEN** the run has a pass-count record computed from its latest computation

#### Scenario: Newer computation appended to a completed run
- **WHEN** a `COMPLETED` run with a pass-count record receives eval summaries of a newer computation, older than the minimum age, and reconciliation runs
- **THEN** the run has a second record for the newer computation and readers use it

#### Scenario: Computation still being written
- **WHEN** a `COMPLETED` run's latest computation is younger than the minimum age and reconciliation runs
- **THEN** no record is written for that computation in this pass

#### Scenario: Nothing to do
- **WHEN** every `COMPLETED` run has a pass-count record for its latest computation and reconciliation runs
- **THEN** no record is added or changed

#### Scenario: Legacy run without eval summaries
- **WHEN** a `COMPLETED` run has no eval summaries and reconciliation runs twice
- **THEN** the run has exactly one record, 0 of 0, after the first pass and is untouched by the second

#### Scenario: Runs that are not completed are skipped
- **WHEN** reconciliation runs while a run is RUNNING, FAILED or CANCELLED
- **THEN** no record is written for that run

#### Scenario: Switched off
- **WHEN** reconciliation is disabled by configuration
- **THEN** no reconciliation pass runs

### Requirement: Reads exclude deleted runs
Every read of pass-count records that serves a client SHALL exclude the records of runs that have a deletion tombstone (see `run-analytics-deletion`). Writes, and the reads that serve them (counting the test cases of a run being recorded, reading its computation's timestamp, finding a run's latest computation for reconciliation, checking whether a record already exists), SHALL NOT be filtered.
Status: **Planned**

#### Scenario: Deleted run disappears
- **WHEN** a run with a pass-count record is deleted
- **THEN** no client-serving read of pass-count records returns it, and the records of other runs are unaffected
