# Test Suite Runs (Delta)

## Purpose
Delta spec for the `init-analytics-eval-results` change. Documents that no schema or API changes are needed for `test_suite_runs` — the existing `created_at_ms` column is reused as the timestamp source for analytics results (see design D8).

Status: **Unchanged**

## No Modifications Required

The analytics results feature uses run-anchored timestamps (design decision D8): all results for a given run share the run's `created_at_ms` from the existing `test_suite_runs` table. The analytics service reads this value via the existing `TestSuiteRunRepository.findById()` — no new columns, migrations, model changes, or services are needed on the test suite runs side.

**Previous approach (superseded):** An earlier design considered adding `results_min_created_at_ms` and `results_max_created_at_ms` time range hint columns to `test_suite_runs`, maintained by a cross-datasource `TestSuiteRunTimeRangeService`. This was replaced by the run-anchored timestamp approach, which is simpler (no cross-DB writes), more correct (partition-safe UNIQUE constraints), and more efficient (exact partition match vs range scan).

## Implementation Notes
- Design reference: `design.md` decision D8.
- No migration needed.
- No model, RowMapper, repository, or service changes to test suite runs.
- The `AnalyticsResultService` reads the run via the existing `TestSuiteRunRepository.findById()` for existence validation and timestamp retrieval.
