# Run pass counts

Dashboards rank suites by the pass rate of their runs. Counting a run's test cases per request cannot sort *all* suites, so each completed run's counts are stored once and read from there.

## What is stored

Analytics table `run_pass_counts`: one row per `(test_suite_run_id, computation_id)` with `passed_count`, `total_count`, the run's `created_at_ms` and the computation's `computed_at_ms`. Append-only: a row is never updated.

- A row is written only for a `COMPLETED` run.
- `computation_id` is null only for a run without eval summaries: one 0-of-0 row with `computed_at_ms = 0`, so any real computation recorded later wins.
- The unique index is an expression index on `(test_suite_run_id, COALESCE(computation_id, ''))`. The insert must be `onConflictDoNothing()` **without a conflict target**; the column-list form used by other analytics inserts does not match an expression index and fails at runtime.

## The pass rule

Defined once, in `PostgresRunPassCountRepository.countForComputation`. A test case counts once per computation. It passes when every one of its eval-summary rows is `SUCCESS` and it has no score row with `passed = false` or a non-`SUCCESS` status.

The status comes from eval summaries on purpose: a test case has no `test_case_eval_scores` row when the suite has no effective per-test-case score definition (the default), when the score write failed, or when the run predates that table. Reading the status from the score row alone counts those failed test cases as passed.

The threshold is not needed: `passed` is non-null only when the run has one.

## Writers

1. `TestSuiteEvaluationJob.recordPassCountsQuietly`, right after `updateToCompleted` returns 1. It catches `RuntimeException` deliberately: anything escaping would reach the job's outer handler, which logs the run as failed and suppresses the COMPLETED notification.
2. `RunPassCountReconciliationJob` (`analytics.run-pass-counts.reconciliation.*`): pages COMPLETED runs in meta by keyset and records every run whose **latest computation** has no row. This repairs failed writes, fills in history (there is no data migration), and picks up a computation appended to a completed run through the eval-summary write API. A computation younger than `min-computation-age-ms` is left for a later pass, because that API writes in batches and a half-written computation would be frozen.

Both go through `RunPassCountService`. Reconciliation runs on every instance without a lock; the insert is idempotent. It is disabled in `PostgresFunctionalTests`; tests call the job method directly.

## Readers

`RunPassCountService.getLatestPage` (each suite's latest run, sorted worst-first, with total) and `getLatestRecordedRuns` (latest N runs per suite). Both reduce to each run's latest computation (`computed_at_ms DESC, computation_id ASC`, null computation last) and rank a suite's runs by `run_created_at_ms DESC, test_suite_run_id DESC`. Used by `SuiteRunTrendService` and `SuitePassRateService`, which therefore agree on every run's pass rate.

`RunPassCountService.getWeeklySuiteHealth` serves `SuiteHealthDistributionService` (`GET /api/v1/analytics/test-suites/health-distribution`): per week, how many suites are healthy and how many are failing at a given threshold.

- Same latest-computation reduction as the other readers (shared `computationRankedPerRun`).
- A suite's weekly rate is pooled: sum of passed over sum of total across its runs created that week. A suite with no runs, or a total of 0, in a week is not counted in that week.
- Weeks are UTC, Monday start. `HealthWeekRange` takes the current week start from the calendar; flooring epoch milliseconds to a week would give Thursday starts. A run's week is `(run_created_at_ms - startMs) / WEEK_MS` in `BIGINT` arithmetic, with both values inlined as literals so the select-list and `GROUP BY` expressions match.
- One statement, no transaction, and no meta read unless a suite filter is given (a deleted suite's runs are tombstoned, so they drop out on their own).
- No index supports the unfiltered range scan on `run_created_at_ms`. Add one on `(run_created_at_ms)` if the table grows well beyond a few thousand runs.

Client-serving reads apply `RunDeletionFilter.notDeleted`. Writer-side reads (the count, the existence check, the latest-computation lookup) are deliberately unfiltered; see [run deletion tombstone](run-deletion-tombstone.md).

## Consequences to keep in mind

- A completed run without a row is invisible to every reader until reconciliation records it.
- A functional test that drives a run to COMPLETED leaves a `run_pass_counts` row. Tests asserting totals call `AnalyticsTestDataHelper.cleanupRunPassCounts()` first.
- A future re-scoring feature should record its own row when it finishes; reconciliation is the fallback.

Full rationale: `openspec/changes/evaluation-run-trends-api/design.md` and `openspec/changes/suite-health-distribution-api/design.md` (both under `openspec/changes/archive/` once archived).
