# Suite Pass-Rate Summary

## Purpose

A dashboard needs one number for "how well are these test suites doing right now" and the same number for the
runs before them, to show whether it went up or down. Assembling it from raw counts forces every client to
re-implement the run selection, the latest-computation rule and the threshold-aware definition of "passed".
This capability returns, for a set of suites, the mean pass rate of each suite's latest run and of its
previous run in one request, computed in one place.

## Requirements

### Requirement: Suite pass-rate summary endpoint
The system SHALL expose `POST /api/v1/analytics/test-suites/pass-rate` accepting a JSON body `{ "suiteIds": [<uuid>, ...] }` and returning a single overall summary `{ "passRate", "previousPassRate", "suiteCount", "previousSuiteCount" }`. `passRate` SHALL be the mean over the requested suites of each suite's latest-run pass rate, and `previousPassRate` the mean over the requested suites of each suite's previous-run pass rate. `suiteCount` SHALL be the number of suites that contributed to `passRate`, and `previousSuiteCount` the number that contributed to `previousPassRate`. Rates SHALL be numbers in the range 0 to 1. A rate with no contributing suite SHALL be absent from the response (null) and its count SHALL be 0. The response SHALL contain no per-suite items, no trend field and no delta. Suite ids that have no eligible run, or do not exist, SHALL NOT cause an error.
Status: **Implemented**

#### Scenario: Two suites, one with a previous run
- **WHEN** suite A has two eligible runs (latest rate 0.5, previous rate 0.0) and suite B has one eligible run (rate 1.0)
- **THEN** the response is `passRate` 0.75, `suiteCount` 2, `previousPassRate` 0.0, `previousSuiteCount` 1

#### Scenario: No suite has an eligible run
- **WHEN** none of the requested suites has an eligible run
- **THEN** the response contains no `passRate` and no `previousPassRate`, `suiteCount` is 0 and `previousSuiteCount` is 0, and the status is 200

#### Scenario: Unknown suite id is ignored
- **WHEN** the request lists an id that does not belong to any suite together with a suite that has an eligible run
- **THEN** the unknown id does not change the result and the request succeeds

#### Scenario: Equal weight per suite
- **WHEN** one suite has 100 test cases at rate 1.0 and another has 2 test cases at rate 0.0
- **THEN** `passRate` is 0.5, not the pooled value over all test cases

### Requirement: Eligible runs for a suite
For each requested suite the system SHALL consider only runs that have a pass-count record (see `run-pass-counts`); such records exist only for runs whose status is `COMPLETED`, so runs that are PENDING, RUNNING, CANCELLING, FAILED or CANCELLED SHALL never be selected, even when they already have score rows. Among them the latest run SHALL be the one with the greatest run `created_at_ms`, with ties broken by the larger run id, and the previous run SHALL be the one ranked immediately after it. A suite with exactly one such run SHALL contribute to `passRate` only. Each selected run SHALL be read at its latest computation. A selected run that is soft-deleted SHALL NOT be eligible. A selected run whose record has a total of 0 SHALL contribute nothing for its position; it SHALL NOT be replaced by an older run. A completed run whose pass counts are not recorded yet SHALL NOT be considered until they are.
Status: **Implemented**

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
Status: **Implemented**

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

### Requirement: Request validation
The system SHALL reject a request with HTTP 400 when the body is missing or not valid JSON, `suiteIds` is missing or empty, `suiteIds` contains an element that is null or not a UUID, or `suiteIds` contains more than 100 distinct ids. Duplicate ids SHALL be removed before processing and SHALL NOT affect the result. The cap SHALL be a single named constant in `ValidationConstants`.
Status: **Implemented**

#### Scenario: Empty list
- **WHEN** `suiteIds` is empty or absent
- **THEN** the response is HTTP 400

#### Scenario: Over the cap
- **WHEN** `suiteIds` holds 101 distinct ids
- **THEN** the response is HTTP 400

#### Scenario: Malformed body or id
- **WHEN** the request body is missing, or `suiteIds` contains an id that is not a UUID
- **THEN** the response is HTTP 400 and no summary is computed

#### Scenario: Null element
- **WHEN** `suiteIds` contains a null element
- **THEN** the response is HTTP 400

#### Scenario: Duplicates do not change the result
- **WHEN** the same suite id appears twice in `suiteIds`
- **THEN** the suite is counted once

### Requirement: Datasource separation
The system SHALL compute the summary from the analytics datasource alone: each suite's latest two recorded runs and their pass counts are read in one read-only analytics transaction. The summary SHALL NOT query the meta datasource.
Status: **Implemented**

#### Scenario: Meta read outside the analytics transaction
- **WHEN** the summary is computed
- **THEN** only the analytics datasource is queried, so no meta query runs inside or around the analytics transaction

### Requirement: OpenAPI documentation
The endpoint SHALL be documented with request and response examples (minimal and full) under `src/main/resources/openapi/examples/` named for the registered path `/api/v1/analytics/test-suites/pass-rate`, and those examples SHALL appear in `/v3/api-docs`.
Status: **Implemented**

#### Scenario: Examples appear in the API docs
- **WHEN** `/v3/api-docs` is requested
- **THEN** the operation `POST /api/v1/analytics/test-suites/pass-rate` carries the request and `200` response examples

**Implementation notes:** `SuitePassRateController` (`web.controller`), `SuitePassRateService` and `PassRateCalculator` (`service.domain.analytics`); each suite's latest two recorded runs come from `RunPassCountService.getLatestRecordedRuns` (backed by `RunPassCountRepository.findLatestRanked`, tombstone-filtered, each run at its latest computation) inside one read-only analytics transaction; the pass rule and the stored counts are defined by `run-pass-counts`.
