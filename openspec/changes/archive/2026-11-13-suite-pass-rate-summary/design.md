## Context

Proposal: one endpoint, `POST /api/v1/analytics/test-suites/pass-rate`, returns the mean pass rate of each suite's latest run and of its previous run, with the number of suites behind each.

Current state (from code):
- `test_case_eval_scores` (analytics DB) holds one row per test case per run per computation: `test_suite_run_id`, `computation_id`, `execution_status`, `score`, `passed`. It has no `test_suite_id`. The service layer only inserts (`TestCaseEvalScoreService.batchInsert`); `TestCaseEvalScoreRepository` has only `saveAll`.
- `test_case_eval_summaries` (analytics DB) has `test_suite_id`, `test_suite_run_id`, `created_at_ms`, so run-to-suite and run ordering can be resolved without touching the meta DB.
- A run's latest computation is `ComputationResolver.resolveLatest(Collection<UUID>)` (`service.domain.analytics`), a batch lateral query on summaries ordered by `computed_at_ms DESC, computation_id ASC`, with `RunDeletionFilter` applied. It needs an ambient analytics transaction.
- `passed` is `score >= threshold` frozen in the run snapshot; it is null when the score or threshold is null, and `score`/`passed` are null when the test case's `execution_status` is FAILED. The threshold lives in `test_suite_runs.suite_snapshot` (`overallScoreThreshold`, meta DB).
- Precedents: `CostController`/`CostService.getTotalRunCosts` (multi-id POST, inline `ValidationException` cap, no 404 for unknown ids); `RunComparisonService` (meta reads first, then an explicit read-only analytics `TransactionTemplate`, never a meta query inside an analytics transaction).

Constraints: layering `.web` → `.service` → `.data.db`; a service may only use its own domain's repository (runs go through `TestSuiteRunService`); two datasources, no distributed transaction.

## Goals / Non-Goals

**Goals:**
- Return `passRate`, `previousPassRate`, `suiteCount`, `previousSuiteCount` for up to a capped set of suite ids, in one request and a bounded number of queries independent of the number of suites.
- Keep the pass rule in one injectable, unit-testable component.
- Exclude soft-deleted runs and use each run's latest computation.

**Non-Goals:**
- Per-suite output, trend enum or delta, DSL changes, schema or configuration changes.
- Covering runs that have eval summaries but no score rows (they are skipped, as agreed).

## Decisions

**1. Select runs entirely in the analytics DB with a window function.**
Query `test_case_eval_summaries` filtered by `test_suite_id IN (:ids)`, grouped by `(test_suite_id, test_suite_run_id)` with `max(created_at_ms)`, restricted to runs that have at least one row in `test_case_eval_scores`, with `RunDeletionFilter.notDeleted`, ranked per suite by `row_number() OVER (PARTITION BY test_suite_id ORDER BY max_created_at DESC, test_suite_run_id DESC)`, keeping rank ≤ 2. Ordering is by the run's most recent eval-summary timestamp, so re-computing an older run adds newer summary rows and moves it to the front ("latest scored run"). One query returns `(suiteId, runId, rank)` for every suite. Alternatives: (a) read runs from the meta `test_suite_runs` table then check score rows in analytics — rejected, it needs a cross-datasource round trip and can pick runs with no scores; (b) one query per suite — rejected, N queries.

**2. Latest computation per run via the existing `ComputationResolver.resolveLatest`.**
No new resolution logic; it already applies deletion filtering and ordering.

**3. Grouped counts with one new repository method.**
`TestCaseEvalScoreRepository.countByRunAndComputation(Map<UUID, UUID> runToComputation)` returns `RunPassCounts(runId, passedTrue, passedFalse, unscored, failed)`: `passedTrue` = `passed IS TRUE`, `passedFalse` = `passed IS FALSE`, `unscored` = `passed IS NULL AND execution_status = 'SUCCESS'`, `failed` = `execution_status <> 'SUCCESS'`. Implemented with conditional aggregates (`count(*) FILTER (WHERE …)`) over `(test_suite_run_id, computation_id) IN (row values)` (at most 2 × cap pairs), grouped by run, ANDed with `RunDeletionFilter.notDeleted`. Alternative: reuse the Query DSL entity — rejected, the DSL has no conditional aggregate in this path and a service should not call its own HTTP-shaped query layer.

**4. Thresholds from the run snapshot through the runs' own service.**
Add `TestSuiteRunRepository.findThresholdsByRunIds(Collection<UUID>)` projecting only `id` and `suite_snapshot->>'overallScoreThreshold'` (no heavy JSONB columns) and expose it via a new `TestSuiteRunService` method (`@Transactional("metaTransactionManager", readOnly = true)`). A run with no snapshot or no threshold key is simply absent from the map and is treated as "no threshold".

**5. Transaction order avoids nesting datasources.**
`SuitePassRateService` (no class-level `@Transactional`): step 1 runs a read-only `TransactionTemplate` on `analyticsTransactionManager` covering run selection, `resolveLatest` and the counts; step 2 calls the meta thresholds method; step 3 combines in memory. No meta query runs inside the analytics transaction.

**6. Pass rule isolated in `PassRateCalculator` (`@Component`, pure).**
`rate(RunPassCounts, boolean hasThreshold)`: `total = passedTrue + passedFalse + unscored + failed`; with a threshold `passed = passedTrue`; without one `passed = passedTrue + passedFalse + unscored` (all SUCCESS rows; `passedFalse` is zero in practice); rate is `passed / total`, or null if `total = 0`. Averaging helper: mean of non-null rates, null if none.

**7. Suite grouping and averaging in the service.**
Group the selected runs by suite; rank 1 is the latest, rank 2 the previous. `passRate` = mean over suites of the latest run's rate, `suiteCount` = number of suites with a rate; `previousPassRate`/`previousSuiteCount` likewise over rank-2 runs. A suite contributes to the previous average only if it has a second scored run.

**8. API shape and validation.**
`POST` with a body (like `POST /api/v1/costs/test-suite-runs`), not a `GET` list, so the id list is not limited by URL length. `SuitePassRateRequestDto { List<UUID> suiteIds }`; inline `ValidationException` (HTTP 400) when null/empty or over `ValidationConstants.MAX_BATCH_SUITE_IDS` (100); duplicates removed preserving order. Unknown suite ids produce no runs and are not an error. Response `SuitePassRateSummaryDto { Double passRate, Double previousPassRate, int suiteCount, int previousSuiteCount }`; rates are 0..1 and null (serialised absent, per the global `NON_NULL` inclusion) when there is no data.

**9. Placement.**
`SuitePassRateController` in `web.controller` (`@LogExecution @Validated`, OpenAPI annotations and examples); `SuitePassRateService` and `PassRateCalculator` in `service.domain.analytics`; DTOs in `service.domain.dto.analytics`; `RunPassCounts` in the analytics data model package. No MapStruct mapper: the DTO is built by hand, like the cost DTOs.

## Risks / Trade-offs

- [A "previous" run may be older than the run just before the latest, because runs without score rows are skipped] → Accepted and documented in the spec.
- [`created_at_ms` on summaries is a row timestamp, so a run's order is the time of its newest summary row] → Use `max(created_at_ms)` per run, matching the ordering already used by the DSL queries; tie-break by run id.
- [A re-scored run moves to the front, because its newest summary row is now the most recent, and its rate follows its newest computation; a re-scored older run can therefore rank ahead of genuinely newer runs] → Intended; the "latest run" means latest scored run, and "latest computation" is the project-wide rule. Covered by a spec scenario.
- [The two ranks are selected before the latest computation is resolved, so a run whose latest computation has no score rows (only an older computation was scored) is dropped yet still uses one of the two ranks; the suite then contributes to `passRate` only] → Accepted: a run re-computed without producing scores is an anomaly, and fetching extra ranks would complicate the window query for no real case. Documented as a spec scenario.
- [A suite with a threshold and unscored SUCCESS rows is not counted as passed; a suite without a threshold counts them] → Intended by the agreed rule; both are covered by tests.
- [Legacy runs without a snapshot read as "no threshold"] → Documented in the spec.
- [Large id lists] → Cap of 100 suites bounds the row-value `IN` to 200 pairs and the window query to 100 suites.

## Migration Plan

No schema or data migration. Deploy with the release; roll back by removing the endpoint (additive, no clients depend on it yet). Add OpenAPI examples, the new spec and its index entry in the same change.

## Open Questions

- ~~Cap value: 100 suite ids is a proposal; raise it if the FE needs more per call.~~ Resolved: the cap is 100 distinct ids, fixed in the spec and `ValidationConstants.MAX_BATCH_SUITE_IDS`.
- Whether to expose per-suite detail later: out of scope now, the DTO can gain an optional list without breaking the two numbers.
