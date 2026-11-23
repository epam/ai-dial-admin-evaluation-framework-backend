## Why

Review of the run-trends PR: `findLatestPage` and `findLatestRanked` in `PostgresRunPassCountRepository` number every row of `run_pass_counts` in scope with two window functions before keeping rank 1 (or the top N). `findLatestPage` does that twice per request (count, then page). The cost grows with the total run history, and the per-run window cannot use the `(test_suite_id, run_created_at_ms DESC)` index. Measured on 2,000 suites × 500 runs (1.25M rows): about 3 s for the count plus 3 s for the page.

## What Changes

- `findLatestPage`: for each suite, read only its newest non-deleted run through the existing index (`LATERAL … LIMIT 1`). The total comes from `count(*) OVER ()` in the same statement.
- `findLatestRanked`: for each requested suite, read its newest N runs the same way, reducing each run to its latest computation before the limit.
- Unfiltered page: the suite list is produced by hopping through the index one suite at a time (recursive skip scan), not by `SELECT DISTINCT`.
- No behaviour change: same rows, same order, same tie-breaks, same deleted-run handling, same totals. No API, schema, index or configuration change.

Non-goals:
- No new index.
- ClickHouse.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

None. Requirements of `suite-run-trends`, `suite-pass-rate-summary` and `run-pass-counts` are unchanged; the change is declared with `skip_specs: true`.

## Impact

- Code: `PostgresRunPassCountRepository` only (`findLatestPage`, `findLatestRanked`, private helpers). Interface, service, controllers and DTOs are untouched.
- Readers affected: run trends (`SuiteRunTrendService`) and the pass-rate card (`SuitePassRateService`), both through `RunPassCountService`.
- Docs: "Readers" section of `docs/patterns/run-pass-counts.md`.
- Risk: a subtle difference in tie-breaking or deleted-run handling. Mitigation: the existing functional tests of both endpoints must pass unchanged, plus new cases for the paths the rewrite introduces (see tasks).
- Test plan: existing `SuiteRunTrendFunctionalTests`, `SuitePassRateEndpointFunctionalTests`, `RunDeletionFunctionalTests`, `RunPassCountFunctionalTests` unchanged and green; new repository-level cases listed in tasks.
- Rollout: no flag; rollback is reverting the commit.
