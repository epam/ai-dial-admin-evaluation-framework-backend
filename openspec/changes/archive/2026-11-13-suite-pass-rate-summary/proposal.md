## Why

The FE shows an "average pass rate across a set of test suites (latest runs)" and wants a trend against the previous runs. Today it has to assemble this from Query DSL counts: one scores query for the latest runs, another for the previous runs, a lookup to read each run's threshold, and its own calculation. The backend already owns every input (each run's threshold in its snapshot, the per-test-case `passed` and `execution_status`, the latest computation per run), so it can return the two numbers directly and keep the pass-rate rule in one place.

## What Changes

- **Planned:** new endpoint `POST /api/v1/analytics/test-suites/pass-rate`. The request body carries `suiteIds`. The response is an overall summary only: `passRate` (mean of each suite's latest-run pass rate), `previousPassRate` (mean of each suite's previous-run pass rate), and the number of suites behind each number (`suiteCount`, `previousSuiteCount`). No per-suite items, no trend field or delta; the FE derives the direction.
- **Runs used:** for each suite, the latest two runs that have rows in `test_case_eval_scores` (a run without score rows is skipped). Each run is read at its latest computation, and soft-deleted runs are excluded.
- **Pass rule per run (threshold-aware, taken from the run's snapshot):**
  - threshold set: passed = rows with `passed = true`; rows that are SUCCESS but unscored (null `passed`) and FAILED rows do not count; rate = passed / all rows;
  - no threshold: passed = rows with `execution_status = SUCCESS`; rate = SUCCESS rows / all rows.
- **Averaging:** mean of per-suite rates, so each suite counts once. A suite with no previous run is left out of the previous average only. When no suite qualifies the corresponding number is null.
- **Request validation:** `suiteIds` must be non-empty and within a new cap (`ValidationConstants.MAX_BATCH_SUITE_IDS`, proposed 100); duplicates are removed; violations are HTTP 400. Unknown suite ids are not an error: they simply have no runs.
- New components (all in existing packages): `SuitePassRateController` (`web.controller`), `SuitePassRateService` and `PassRateCalculator` (`service.domain.analytics`), request/response DTOs (`service.domain.dto.analytics`), `RunPassCounts` model, plus new repository methods for grouped counts and for reading run thresholds.
- Non-goals: per-suite output, a trend enum, changes to the Query DSL, DB schema or configuration changes, handling of runs that have summaries but no score rows.

## Capabilities

### New Capabilities
- `suite-pass-rate-summary`: the batch endpoint above, the run-selection rule (latest two runs with score rows), the threshold-aware pass-rate rule, averaging and null/empty behaviour, request validation, and the response contract.

### Modified Capabilities
<!-- None: existing capabilities (eval-summary-scoring, latest-computation-resolution, run-analytics-deletion) are reused unchanged. -->

## Impact

- **Code:** new controller, service, calculator, DTOs and model; new methods on `TestCaseEvalScoreRepository` (+ Postgres impl, grouped counts with `RunDeletionFilter.notDeleted`) and `TestSuiteRunRepository`/`TestSuiteRunService` (batch read of `overallScoreThreshold` for given run ids, projecting only the needed fields); new constant in `ValidationConstants`. Reuses `ComputationResolver.resolveLatest`. Layering and the dual-datasource rule are respected: analytics reads and meta reads run in separate steps, never nested.
- **API:** one new endpoint under `/api/v1/analytics`; OpenAPI examples added under `src/main/resources/openapi/examples/` (`api-v1-analytics-test-suites-pass-rate-POST-…`, minimal and full request and response) with a test that they appear in `/v3/api-docs`.
- **Docs and specs:** new spec `suite-pass-rate-summary` and an entry in `openspec/specs/README.md`; `docs/key-packages.md` only if a new package appears. No migration, so `docs/database-schema.md` is unchanged.
- **Risks:** "previous run" skips runs without score rows, so it can be older than the run just before the latest; runs are ordered by their most recent eval-summary timestamp, so a re-scored run moves to the front (latest scored run) and takes its newest computation's rate; legacy runs without a snapshot are treated as having no threshold.
- **Rollout:** single release, no flags, additive; revert by removing the endpoint.
- **Test plan:** unit tests for `PassRateCalculator`, the service and request validation; a `PostgresFunctionalTests` nested class with fixtures for several runs and re-computations, unscored and FAILED rows, no-threshold runs and a deleted run; repository functional test for the count method; live check on :8096 with suites `64ab7f1f-4f93-4aa5-a035-01ded741a6aa` and `b5167912-1414-4056-815b-491e20111c64` (expected `passRate` 0.75 over 2 suites and `previousPassRate` 0.0 over 1 suite).
