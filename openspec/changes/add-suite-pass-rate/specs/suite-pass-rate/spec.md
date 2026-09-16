## Purpose

Per-suite trend data: for the newest N runs of one test suite, how many eval-summary rows failed to
execute, executed and passed the suite's overall-score threshold, executed but did not pass, or executed
without a pass/fail verdict — each run counted over its latest computation, in one read-only call.

## ADDED Requirements

### Requirement: Suite pass-rate endpoint
The system SHALL expose `GET /api/v1/analytics/eval-summaries/test-case-pass-rate/{testSuiteId}?lastN=<n>`
returning `testSuiteId` and a `runs` array. Each `runs` entry SHALL carry `testSuiteRunId`, `computationId`,
`status` (the run's current status), `runCreatedAtMs` (epoch milliseconds), `failedCount`,
`successPassedCount`, `successNotPassedCount`, `successNoVerdictCount` and `totalCount`.

`runs` SHALL be ordered newest run first — by run creation time descending, then run id descending — and
SHALL be a JSON array, not an object keyed by run id.
Status: **Planned**

#### Scenario: Suite with evaluated runs
- **WHEN** a client requests the pass rate of a suite whose newest runs have eval summaries
- **THEN** the response contains one entry per such run, newest first, each carrying its computation id, status, creation time and the five counts

#### Scenario: Newest-first ordering
- **WHEN** a suite has runs created at times T1 < T2 < T3, all with eval summaries
- **THEN** `runs[0]` is the T3 run and `runs[2]` is the T1 run

### Requirement: Run window selection
The system SHALL select the suite's `lastN` most recently created runs regardless of their status, then
SHALL drop from the response every selected run that has no eval-summary rows. The response MAY therefore
contain fewer than `lastN` entries; the window SHALL NOT be widened to compensate.

A run whose status is not terminal (e.g. RUNNING) SHALL be included when it already has eval-summary rows;
its counts describe the rows written so far, and its `status` field is the client's signal that the counts
are not final.
Status: **Planned**

#### Scenario: lastN trims the window
- **WHEN** a suite has three runs with eval summaries and the client passes `lastN=2`
- **THEN** the response contains exactly the two newest runs

#### Scenario: Runs without eval summaries are omitted
- **WHEN** the suite's newest run is PENDING and has no eval summaries, and the client passes `lastN=2`
- **THEN** the response contains one entry — the next-newest run — and the PENDING run does not appear

#### Scenario: Suite without evaluated runs
- **WHEN** a suite exists but none of its runs has eval summaries (or it has no runs)
- **THEN** the response is `200` with `runs` equal to an empty array

#### Scenario: A running run reports partial counts
- **WHEN** a RUNNING run has eval summaries written by completed evaluation batches
- **THEN** it appears in `runs` with `status` `RUNNING` and counts over the rows written so far

### Requirement: Per-row status and verdict buckets
Each run's counts SHALL be computed over that run's eval-summary rows for its latest computation, one
count per row — that is, per test case × repetition × request × turn — with no collapsing to a
per-test-case verdict.

The buckets SHALL be defined as: `failedCount` = rows whose execution status is not `SUCCESS`
(`FAILED`, `TIMEOUT` or `ERROR`); `successPassedCount` = `SUCCESS` rows whose `passed` is `true`;
`successNotPassedCount` = `SUCCESS` rows whose `passed` is `false`; `successNoVerdictCount` = `SUCCESS` rows
that have no score row or whose `passed` is `null`; `totalCount` = all rows. The four buckets SHALL always
sum to `totalCount`.

An `ERROR` row whose request succeeded but whose metric evaluation threw SHALL be counted in `failedCount`,
because its stored execution status is not `SUCCESS`. `successNoVerdictCount` SHALL count every `SUCCESS`
row with no available verdict, which has more than one cause, in two groups: a score row exists but
`passed` is `null` — because (1) the run's suite snapshot has no `overallScoreThreshold`, so `passed` is
`null` for every row with a computed score, or because the score itself is `null`; or no score row was
ever written for that row — because (2) the run's suite snapshot has no `overallScore` definition at all,
(3) the row's score has not been written yet (scores are written in a separate step after each flush batch
of eval summaries is committed, so a run in progress always has a trailing window of `SUCCESS` rows with no
score row), or (4) the score write for that row failed and the failure was logged and swallowed, leaving the
row without a score row even after the run reaches a terminal status. A non-zero `successNoVerdictCount` on
a `COMPLETED` run whose suite has an `overallScoreThreshold` therefore indicates a missing or failed score
row (causes 2-4), not the absence of a threshold (cause 1); clients SHALL NOT infer "no threshold
configured" from this bucket alone.
Status: **Planned**

#### Scenario: Every bucket populated
- **WHEN** a run's latest computation has 2 FAILED rows, 25 SUCCESS rows with `passed = true`, 3 SUCCESS rows with `passed = false` and 1 SUCCESS row without a score row
- **THEN** the entry reports `failedCount` 2, `successPassedCount` 25, `successNotPassedCount` 3, `successNoVerdictCount` 1 and `totalCount` 31

#### Scenario: All non-SUCCESS statuses are failures
- **WHEN** a run has one FAILED, one TIMEOUT and one ERROR row
- **THEN** `failedCount` is 3

#### Scenario: Score row present with a null verdict
- **WHEN** a SUCCESS row has a score row whose `passed` is `null`
- **THEN** it is counted in `successNoVerdictCount`, not in `successPassedCount` or `successNotPassedCount`

#### Scenario: Suite without a threshold
- **WHEN** a run's suite snapshot has no `overallScoreThreshold`, so every row's `passed` is `null`
- **THEN** `successPassedCount` and `successNotPassedCount` are 0 and `successNoVerdictCount` equals the number of SUCCESS rows

#### Scenario: Score row not yet written or its write was swallowed
- **WHEN** a suite's snapshot has an `overallScoreThreshold`, but a `SUCCESS` row has no score row — because the run is still flushing batches, or a score write for that row failed and was logged and swallowed
- **THEN** the row is counted in `successNoVerdictCount`, and this does not by itself indicate the suite has no threshold

#### Scenario: Multi-turn rows count per turn
- **WHEN** a SUCCESS test case produced three per-turn rows, two passed and one not
- **THEN** it contributes 2 to `successPassedCount` and 1 to `successNotPassedCount`

### Requirement: Counts use each run's latest computation
The system SHALL count each run over its most recent computation — the one with the greatest
`computed_at_ms` among that run's eval-summary rows, ties broken by the greatest `computation_id` compared
as its canonical lowercase 36-character text (lexicographic order, matching the `VARCHAR(36)` storage and
`ORDER BY computation_id DESC`, not `java.util.UUID.compareTo`) — and SHALL report that computation as the
entry's `computationId`. Rows of older computations SHALL NOT contribute. The endpoint SHALL NOT accept a
client-supplied computation override.
Status: **Planned**

#### Scenario: Re-evaluated run counts only the latest computation
- **WHEN** a run was evaluated twice and the older computation's rows have a different status mix
- **THEN** only the newer computation's rows are counted and its id is returned as `computationId`

#### Scenario: Equal timestamps resolve deterministically
- **WHEN** two computations of one run share the same greatest `computed_at_ms`
- **THEN** the computation whose `computation_id` is greater under lexicographic (text) comparison of its canonical UUID string is counted and reported

### Requirement: lastN bounds and error semantics
`lastN` SHALL be optional. When absent, the system SHALL use a configurable default. The system SHALL reject
`lastN` below 1 or above a configurable maximum with `400 VALIDATION_ERROR`, naming the limit in the
message. An unknown `testSuiteId` SHALL yield `404 NOT_FOUND`. Guards SHALL be evaluated in the order:
(1) 400 `lastN` shape and bounds, (2) 404 unknown suite.
Status: **Planned**

#### Scenario: Default window
- **WHEN** the client omits `lastN`
- **THEN** the window is the configured default number of runs

#### Scenario: Zero or negative lastN
- **WHEN** the client passes `lastN=0`
- **THEN** the request fails with `400 VALIDATION_ERROR`

#### Scenario: lastN above the maximum
- **WHEN** the client passes a `lastN` greater than the configured maximum
- **THEN** the request fails with `400 VALIDATION_ERROR` and the message names the maximum

#### Scenario: Unknown suite
- **WHEN** `testSuiteId` does not correspond to an existing suite
- **THEN** the request fails with `404 NOT_FOUND`

### Requirement: Configurable window bounds
The default and maximum `lastN` SHALL be configurable via `analytics.pass-rate.default-last-n` and
`analytics.pass-rate.max-last-n`, both integers of at least 1, with their defaults defined in the
application configuration file (not in code) and documented in the configuration reference.
Status: **Planned**

#### Scenario: Bounds read from configuration
- **WHEN** an operator sets `analytics.pass-rate.max-last-n=20`
- **THEN** `lastN=21` fails with `400 VALIDATION_ERROR` and `lastN=20` succeeds

#### Scenario: Misconfigured bounds fail fast at startup
- **WHEN** `analytics.pass-rate.default-last-n` exceeds `analytics.pass-rate.max-last-n`
- **THEN** the application SHALL fail to start with a validation error naming both
  `analytics.pass-rate.default-last-n` and `analytics.pass-rate.max-last-n`

### Requirement: Read-only and datasource-local
The endpoint SHALL persist nothing. It SHALL read run identity and status from the meta datasource and
counts from the analytics datasource without a cross-datasource join; the number of run ids passed to the
analytics read SHALL be bounded by `analytics.pass-rate.max-last-n`.
Status: **Planned**

#### Scenario: Nothing is written
- **WHEN** a pass-rate request is served
- **THEN** no row in either datasource is created, updated or deleted

## Implementation notes

- Endpoint: `web/controller/EvalSummaryController` — `GET /test-case-pass-rate/{testSuiteId}`.
- Orchestration: `service/domain/analytics/PassRateService` — suite existence via `TestSuiteService.getById`,
  run window via `TestSuiteRunService.findRecentRuns` (delegates to `TestSuiteRunRepository`), analytics
  aggregate via `EvalSummaryRepository` inside a read-only `TransactionTemplate("analyticsTransactionManager")`.
- Meta read: `TestSuiteRunRepository.findRecentByTestSuiteId(suiteId, limit)` (`PostgresTestSuiteRunRepository`)
  → light projection `(id, status, created_at_ms)`, ordered `created_at_ms DESC, id DESC`.
- Analytics read: `PostgresEvalSummaryRepository.countPassRateByLatestComputation(runIds)` — `DISTINCT ON
  (test_suite_run_id)` latest-computation derived table over `idx_eval_summaries_run_computed_at`, joined
  to `test_case_eval_summaries` and LEFT JOINed to `test_case_eval_scores`, `count() FILTER (WHERE …)` × 4
  plus `count()`, `GROUP BY test_suite_run_id, computation_id`.
- Models: `data/db/model/TestSuiteRunRef` (meta projection), `data/db/analytics/model/RunPassRateStats`
  (analytics aggregate row).
- Response DTOs: `service/domain/dto/analytics/RunPassRateDto`, `service/domain/dto/analytics/SuitePassRateResponseDto`.
- Bounds: `analytics.pass-rate.*` (`configuration/properties/analytics/PassRateProperties`).
- Tests: `PassRateServiceTest`, `PassRatePropertiesTest`, `PassRateRepositoryFunctionalTests`,
  `PassRateFunctionalTests`.
- OpenAPI examples: `api-v1-analytics-eval-summaries-test-case-pass-rate-testSuiteId-GET-response-200-minimal.json`,
  `api-v1-analytics-eval-summaries-test-case-pass-rate-testSuiteId-GET-response-200-full.json`.
- Rationale: `openspec/changes/add-suite-pass-rate/design.md`.
