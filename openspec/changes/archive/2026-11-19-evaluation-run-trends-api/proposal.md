## Why

The System Health dashboard has an "Evaluation run trends" table: one row per test suite with the pass rates of its last runs, the latest rate and the change against the previous run, worst suites first. Nothing serves it today. The pass rate of a run exists only as a query over every test case row of that run, so "sort all suites by their latest pass rate, page by page" cannot be answered without reading the test case rows of every suite's latest run on every request.

The same dashboard already shows a pass-rate card (`suite-pass-rate-summary`, #304). It counts a failed test case as passed whenever that test case has no score row, because the status of a test case is read from the score row. That is the case for every suite without an explicit per-test-case score (the default configuration, and suites whose overall score is a custom function), for runs whose score write failed, and for runs older than per-test-case scores. The table and the card must show the same numbers.

## What Changes

- New analytics table `run_pass_counts`: one row per completed run and computation with the number of passed and total test cases. Analytics migration `V1.23__CreateRunPassCountsTable.sql`.
- The row is written when a run completes. A reconciliation job writes the row for every completed run whose latest computation has none, which repairs failed writes, fills in runs completed before this change, and picks up a newer computation appended to a completed run through the eval-summary write API. No data migration.
- One pass rule, used for the stored counts: a test case passes when every one of its eval-summary rows is `SUCCESS` and it is not scored below the threshold. This closes the gap above.
- New endpoint `GET /api/v1/analytics/test-suites/run-trends`: offset-paged, one item per suite that has at least one recorded completed run, sorted by latest pass rate ascending. Each item carries the suite, its evaluatee, up to 10 latest runs with their pass rates, the latest rate and the delta to the previous run. Suites can be filtered with the same `filter` fields as the suite list.
- `POST /api/v1/analytics/test-suites/pass-rate` (#304) reads the stored counts instead of counting test case rows. Its contract is unchanged; its numbers drop for every run that has failed or metric-errored test cases without a score row (see Why). Its OpenAPI description is corrected.

Current state: **Implemented** — `suite-pass-rate-summary`, `run-analytics-deletion`. Target state: **Planned** — everything in this change.

Goals:
- Serve the trends table with a cost that does not grow with the number of test cases.
- Sorting, paging and total are computed in the database over all suites, not over one page.
- The trends table and the pass-rate card agree on every run's pass rate.
- Adding a "selected applications" filter later only changes how the set of suite ids is chosen.

Non-goals:
- No status or version columns, no run names in the trend points.
- No filter by selected applications yet, no sort other than latest pass rate ascending.
- No change to how `test_case_eval_scores` rows are written, and no backfill of score rows.
- No recompute (re-scoring a run) feature. The table holds several computations per run, and a computation appended through the existing eval-summary write API is picked up by reconciliation; nothing more.
- No per-caller scoping: like the other analytics endpoints, it lists every suite.
- ClickHouse and table partitioning are out of scope.

## Capabilities

### New Capabilities

- `run-pass-counts`: stored pass counts per completed run and computation — the pass rule, when the row is written, reconciliation of missing rows, deleted-run exclusion.
- `suite-run-trends`: the run-trends endpoint — item shape, run selection, sorting, paging, suite filter, datasource separation, API docs.

### Modified Capabilities

- `suite-pass-rate-summary`: requirements "Eligible runs for a suite", "Threshold-aware pass rate per run" and "Datasource separation" — runs and counts come from the stored pass counts; a failed test case without a score row no longer counts as passed.
- `run-analytics-deletion`: requirement "Analytics reads exclude rows of deleted runs" — `run_pass_counts` joins the run-scoped tables whose reads exclude deleted runs.

## Impact

- **Data**: new analytics table `run_pass_counts` (`test_suite_run_id`, `test_suite_id`, `computation_id` nullable, `run_created_at_ms`, `passed_count`, `total_count`, `computed_at_ms`), unique per run and computation, index on `(test_suite_id, run_created_at_ms DESC)`. One row per run, so it stays small. `docs/database-schema.md` and the jOOQ sources are updated.
- **API**: one new GET endpoint with OpenAPI examples and a query-parameter registry entry. The #304 endpoint keeps its contract.
- **Code** (new classes, existing packages):
  - `data.db.analytics`: `RunPassCountRepository` + Postgres implementation, model `RunPassCount`.
  - `service.domain.analytics`: `RunPassCountService` (compute and record, reads), `RunPassCountReconciliationJob`, `SuiteRunTrendService`; `PassRateCalculator` and `SuitePassRateService` are simplified.
  - `web.controller`: `SuiteRunTrendController`; DTOs under `service.domain.dto.analytics`.
  - `TestSuiteEvaluationJob` records the counts after a run completes. `TestSuiteRunService` and `TestSuiteService` gain light read methods (completed runs page, suite ids by filter, suites by ids).
- **Config** (under `analytics.run-pass-counts.reconciliation`, each with an environment variable): `enabled` (`RUN_PASS_COUNTS_RECONCILIATION_ENABLED`), `initial-delay-ms`, `interval-ms`, `batch-size`, `min-computation-age-ms`. `docs/configuration.md` must be updated.
- **Security**: authenticated like the other analytics endpoints; no new roles.
- **Risks**:
  - Until reconciliation has run once after deployment, runs completed earlier are missing from both the table and the card. Mitigation: the job starts shortly after startup; the delay is configurable.
  - Pass rates of the #304 card drop for runs with failed or metric-errored test cases that have no score row. This affects default-configured suites and all runs older than per-test-case scores, not an edge case. It is the correction, and it is called out in the release notes.
  - During a rolling deployment, runs completed by an old instance are not recorded until the next reconciliation pass (up to one interval); until then an older run shows as the suite's latest.
  - A completed run whose row failed to write is invisible until the next reconciliation pass.
- **Rollout**: ships in one release. Rollback is a revert; the table can stay (nothing else reads it).
- **Test plan**: unit tests for the calculator and services; functional tests under `PostgresFunctionalTests` for the count rule, the write on completion, reconciliation, the endpoint (sorting, paging, filter, trend, delta), the #304 endpoint on the new source, and deleted-run exclusion (`RunDeletionFunctionalTests`, `JooqSchemaDriftTest`).
