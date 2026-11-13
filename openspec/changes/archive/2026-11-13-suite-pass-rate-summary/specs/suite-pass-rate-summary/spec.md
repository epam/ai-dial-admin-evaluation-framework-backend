## ADDED Requirements

### Requirement: Suite pass-rate summary endpoint
The system SHALL expose `POST /api/v1/analytics/test-suites/pass-rate` accepting a JSON body `{ "suiteIds": [<uuid>, ...] }` and returning a single overall summary `{ "passRate", "previousPassRate", "suiteCount", "previousSuiteCount" }`. `passRate` SHALL be the mean over the requested suites of each suite's latest-run pass rate, and `previousPassRate` the mean over the requested suites of each suite's previous-run pass rate. `suiteCount` SHALL be the number of suites that contributed to `passRate`, and `previousSuiteCount` the number that contributed to `previousPassRate`. Rates SHALL be numbers in the range 0 to 1. A rate with no contributing suite SHALL be absent from the response (null) and its count SHALL be 0. The response SHALL contain no per-suite items, no trend field and no delta. Suite ids that have no eligible run, or do not exist, SHALL NOT cause an error.
Status: **Planned**

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
For each requested suite the system SHALL consider only runs that have at least one row in `test_case_eval_scores` and are not soft-deleted. Among them the latest run SHALL be the one whose newest eval-summary row (`max(created_at_ms)`) is greatest, with ties broken by the larger run id, and the previous run SHALL be the eligible run ranked immediately after it. A run without score rows SHALL be skipped. A suite with exactly one eligible run SHALL contribute to `passRate` only. Each run SHALL be read at its latest computation (see `latest-computation-resolution`).
Status: **Planned**

#### Scenario: Run without score rows is skipped
- **WHEN** a suite's newest run has eval summaries but no score rows
- **THEN** that run is not eligible and the latest eligible run is the next newest one

#### Scenario: Latest computation is used
- **WHEN** an eligible run has been scored by two computations
- **THEN** only the rows of its latest computation are counted

#### Scenario: Re-scored older run becomes the latest
- **WHEN** an older eligible run is re-computed so that its newest eval-summary row is more recent than that of a genuinely newer run
- **THEN** the re-scored run is ranked latest (using its newest computation's rate) and the newer run becomes the previous run

#### Scenario: Latest computation has no score rows
- **WHEN** a run is among a suite's two newest runs that have score rows, but its latest computation has none (only an older computation was scored)
- **THEN** that run is skipped but still occupies one of the two ranks considered, so the suite may contribute to `passRate` only, even when a third eligible run exists

#### Scenario: Deleted run is excluded
- **WHEN** a run has a deletion tombstone
- **THEN** it is not eligible and is not counted as latest or previous

### Requirement: Threshold-aware pass rate per run
The system SHALL compute a run's pass rate as passed test cases divided by all test cases of its latest computation, where the threshold is the run's `overallScoreThreshold` captured in its suite snapshot. When the run has a threshold, a test case SHALL count as passed only if `passed` is true; a SUCCESS test case with no `passed` value (unscored) and any test case whose `execution_status` is not SUCCESS SHALL count as not passed. When the run has no threshold (including a run with no snapshot), every test case whose `execution_status` is SUCCESS SHALL count as passed and every other test case as not passed. The denominator SHALL include every row of the run's latest computation.
Status: **Planned**

#### Scenario: Threshold set, mixed outcomes
- **WHEN** a run with a threshold has 1 test case passed, 1 below the threshold, 1 SUCCESS and unscored, and 1 FAILED
- **THEN** its pass rate is 0.25

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
Status: **Planned**

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
The system SHALL read analytics data (run selection, latest computation, test-case counts) in one read-only analytics transaction, and SHALL read run thresholds from the meta datasource outside that transaction, through the owning domain service. A meta query SHALL NOT execute inside the analytics transaction.
Status: **Planned**

#### Scenario: Meta read outside the analytics transaction
- **WHEN** the summary is computed
- **THEN** the thresholds are fetched after the analytics transaction has completed

### Requirement: OpenAPI documentation
The endpoint SHALL be documented with request and response examples (minimal and full) under `src/main/resources/openapi/examples/` named for the registered path `/api/v1/analytics/test-suites/pass-rate`, and those examples SHALL appear in `/v3/api-docs`.
Status: **Planned**

#### Scenario: Examples appear in the API docs
- **WHEN** `/v3/api-docs` is requested
- **THEN** the operation `POST /api/v1/analytics/test-suites/pass-rate` carries the request and `200` response examples

**Implementation notes:** `SuitePassRateController` (`web.controller`), `SuitePassRateService` and `PassRateCalculator` (`service.domain.analytics`), reuses `ComputationResolver.resolveLatest`, new `TestCaseEvalScoreRepository` count method and `TestSuiteRunService` threshold read.
