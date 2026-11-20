## Why

The System Health dashboard has a second card next to "Evaluation run trends": "Test suite health distribution", a stacked bar per week showing how many test suites were healthy and how many were failing. The stored per-run pass counts (`run_pass_counts`, added by `evaluation-run-trends-api`) already hold everything the card needs, so it can be served by one analytics query without touching result or score tables.

## What Changes

- New endpoint `GET /api/v1/analytics/test-suites/health-distribution` returning one entry per week, oldest first, with the number of healthy suites, failing suites and suites counted that week.
- A suite's weekly pass rate is pooled: passed test cases divided by all test cases over the suite's recorded completed runs created in that week, each run at its latest recorded computation, deleted runs excluded.
- A suite is healthy in a week when its weekly pass rate is at or above the threshold, failing otherwise. A suite with no recorded run, or with no test cases, in a week is not counted in that week.
- Weeks are UTC, starting Monday 00:00. The last entry is the current, possibly partial, week. Weeks with no counted suite are still returned with zero counts.
- Query parameters:
  - `threshold` — default 0.75, 0 to 1 inclusive;
  - `weeks` — default 8, 1 to 52;
  - `filter` — the test suite filter the run-trends endpoint takes.
  An out-of-range `threshold` or `weeks`, or an invalid `filter`, returns 400.
- The chart is global: it covers every suite matching the filter, independent of the run-trends page.
- No paging and no `sort` parameter.

Goals: one endpoint, one analytics query, same filter semantics as run trends, so a later "selected applications" filter is added once for both cards.

Non-goals:
- No schema change and no new index (see design D4).
- No third "watch" zone; the dashboard shows two.
- No client-supplied time zone or week start day.
- No carrying a suite's last known state into weeks where it did not run.
- ClickHouse is out of scope.

## Capabilities

### New Capabilities
- `suite-health-distribution`: weekly counts of healthy and failing test suites derived from stored run pass counts — endpoint, week bucketing, pooled weekly rate, threshold, range, suite filter, deleted-run exclusion, OpenAPI documentation.

### Modified Capabilities

None. The stored counts, the pass rule and the deleted-run rule (`run-pass-counts`, `run-analytics-deletion`) are used as they are.

## Impact

- API: one new GET endpoint; no change to existing endpoints. No security change — same access rules as the other `/api/v1/analytics` endpoints.
- Data: reads `run_pass_counts` and `run_deletions` (analytics) and, only when a filter is given, suite ids from meta. No migration.
- Config: none. Default and maximum number of weeks and the default threshold are constants in `AnalyticsConstants`.
- New classes (planned): `SuiteHealthDistributionController` (`web.controller`), `SuiteHealthDistributionService` (`service.domain.analytics`), DTOs `SuiteHealthDistributionDto` and `SuiteHealthWeekDto` (`service.domain.dto.analytics`), analytics model `WeeklySuiteHealth` (`data.db.analytics.model`), an injectable week-range component, one new `RunPassCountRepository` read and its `RunPassCountService` pass-through.
- Docs: `docs/patterns/run-pass-counts.md` (new reader), `docs/key-packages.md`, `openspec/specs/README.md`; `OpenApiQueryParamCustomizer` registry entry and two OpenAPI example files.
- Risks:
  - The numbers under-report until run pass counts are recorded for older runs (first reconciliation pass), as for run trends.
  - The query scans the stored counts of the requested range without a supporting index; acceptable at current volumes (a few thousand runs), revisit if it grows.
- Rollout: additive, no flag. Depends on `evaluation-run-trends-api` being merged first (stacked branch).
- Test plan: functional tests through the endpoint on seeded pass-count rows with a fixed clock for week boundaries, pooling, threshold edge, zero-total suites, recomputed and deleted runs, filter, validation errors; unit tests for the week-range component and the service; an OpenAPI test for examples and parameter descriptions.
