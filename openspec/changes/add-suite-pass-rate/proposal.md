## Why

The frontend needs a per-suite trend view: for the last N runs of a test suite, how many test-case rows
failed to execute, how many executed and passed the suite's overall-score threshold, and how many executed
but did not pass. Today the only way to get these numbers is one `POST /api/v1/queries/execute` grouped by
`passed` **per run**, after first listing the suite's runs and resolving each run's latest computation — N+1
round trips across two datasources, with the "latest computation" rule reimplemented client-side.

This change adds one read-only endpoint that answers the whole question in a single call, resolving each
run's latest computation the same way every other analytics read already does.

## What Changes

- **New** `GET /api/v1/analytics/eval-summaries/test-case-pass-rate/{testSuiteId}?lastN=<n>` — the suite's
  newest `lastN` runs (by `created_at_ms DESC, id DESC`, any status), each entry carrying `testSuiteRunId`,
  `computationId`, `status`, `runCreatedAtMs` and five per-row counts over the run's **latest** computation:
  `failedCount` (`execution_status <> SUCCESS`), `successPassedCount` (`SUCCESS` and `passed IS TRUE`),
  `successNotPassedCount` (`SUCCESS` and `passed IS FALSE`), `successNoVerdictCount` (`SUCCESS` and no score
  row or `passed IS NULL`), `totalCount`. The four buckets always sum to `totalCount`.
- **Granularity is the eval-summary row** — one count per test case × repetition × request × turn, the same
  population `run-comparison-metric-scores` counts. Nothing is collapsed to a per-test-case verdict.
- **Runs without eval summaries are omitted** (PENDING, or failed before Phase 2 wrote anything), so the
  response MAY hold fewer than `lastN` entries. RUNNING runs **are** included with partial counts — Phase 2
  flushes in batches — which is why every entry carries the run's `status`.
- **Latest computation per run** is resolved from `test_case_eval_summaries` (`computed_at_ms DESC`), with a
  deterministic `computation_id DESC` tie-break. The same tie-break is added to the existing
  `EvalSummaryRepository.findLatestComputationId`, so "latest" is one rule everywhere.
- **`lastN` bounds** are configurable: `analytics.pass-rate.default-last-n` (default `10`) and
  `analytics.pass-rate.max-last-n` (default `100`). `lastN < 1` or `> max` → 400. `docs/configuration.md`
  gains a new `### 6.13 Analytics Pass Rate` section (renumbering `6.13 JSONata Evaluation` → `6.14`).
- No breaking changes. No DB schema change, no migration.

## Capabilities

### New Capabilities
- `suite-pass-rate`: per-suite, per-run execution/pass/fail row counts over each run's latest computation,
  for the suite's newest N runs, exposed as one read-only REST endpoint.

### Modified Capabilities
- `metrics-storage`: the *Computation versioning model* requirement's "Latest resolution" scenario gains a
  deterministic tie-break — when two computations of one run share the greatest `computed_at_ms`, the greater
  `computation_id` wins. `computed_at_ms` is assigned once per computation, so ties are theoretical, but the
  new endpoint resolves N runs in one `DISTINCT ON` and must name its ordering; pinning the shared rule keeps
  the two resolution paths provably identical.

## Impact

**Goals** — one call for the suite trend view; counts that agree row-for-row with `POST /queries/execute`
grouped by `passed` on the same run and computation; the existing "latest computation" rule reused, not
reimplemented.

**Non-goals** — per-test-case (collapsed) verdicts; a client-supplied `computation` override; filtering by
run status; pagination or cursoring beyond `lastN`; percentages (the client divides); any change to how
`score`/`passed` are computed or stored (`eval-summary-scoring`).

**Current state (all Implemented)** — `test_case_eval_summaries` carries `execution_status` per row;
`test_case_eval_scores` carries nullable `score`/`passed` per row, LEFT JOINed into every eval-summary read;
`passed` is null when the run snapshot has no `overallScoreThreshold` or the row has no score. Latest
computation resolution exists as `EvalSummaryRepository.findLatestComputationId(runId)`, single-run only.

**API** — one new endpoint on the existing `EvalSummaryController`. `400 VALIDATION_ERROR` (`lastN` not a
positive integer, or above the configured max), `404 NOT_FOUND` (unknown suite). A suite with runs but none
carrying summaries returns `200` with `runs: []`. Semantics a client must know, stated on the DTO
`@Schema` descriptions: `failedCount` includes `ERROR` rows whose request succeeded but a metric threw;
`successNoVerdictCount` counts every `SUCCESS` row with no available verdict, which has more than one
cause — a score row present with `passed IS NULL` (no `overallScoreThreshold` on the suite snapshot, or a
null score), or no score row at all (the suite has no `overallScore` definition, a score row not yet
written because the run is still flushing, or a score write that failed and was swallowed) — so a non-zero
count on a `COMPLETED` run with a threshold means a missing or failed score row, not "no threshold
configured".

**Data** — meta DB: one new light-projection read (`id`, `status`, `created_at_ms`) on
`TestSuiteRunRepository`, served by the existing `idx_test_suite_runs_test_suite_id`. Analytics DB: one new
aggregate read on `EvalSummaryRepository` — `DISTINCT ON (test_suite_run_id)` over
`idx_eval_summaries_run_computed_at`, joined back to the summaries and LEFT JOINed to
`test_case_eval_scores`, four `count() FILTER (WHERE …)` plus `count()` grouped by run and computation.
`IN`-list size is bounded by `max-last-n`, so no parameter-ceiling risk. No cross-datasource join.

**Transactions** — meta reads first through `TestSuiteService` / `TestSuiteRunService` (each opens its own
read-only meta transaction); the analytics aggregate runs inside an explicit read-only
`TransactionTemplate("analyticsTransactionManager")`, the pattern `RunComparisonService` uses.

**Security** — standard OIDC/JWT chain under `/api/v1/**`; no new roles, no external calls.

**New classes** — `PassRateProperties` (`configuration.properties.analytics`); `RunPassRateStats` carrier
(`data.db.analytics.model`); `TestSuiteRunRef` light projection (`data.db.model`); `PassRateService`
(`service.domain.analytics`); `SuitePassRateResponseDto` / `RunPassRateDto`
(`service.domain.dto.analytics`). No new packages.

**Config** — `analytics.pass-rate.default-last-n`, `analytics.pass-rate.max-last-n`; defaults in
`application.yml` only; two six-column rows in `docs/configuration.md`.

**Risks** — (1) `DISTINCT ON` is Postgres-only; the analytics vendor is `POSTGRES`-only today and the
repository is already `@ConditionalOnProperty` on that vendor, so a future ClickHouse implementation would
express the same as `argMax(computation_id, (computed_at_ms, computation_id))`. (2) A RUNNING run's counts
move between calls; `status` on the entry is the client's signal. (3) The tie-break edit to
`findLatestComputationId` touches a shared read path; `ComputationResolverTest` is Mockito-only and cannot
observe an `orderBy` change, so the regression guard is the new tie-break repository functional test plus
the existing functional suites that exercise `findLatestComputationId` end-to-end through
`ComputationResolver` (`EvalSummaryTests`, `EvalSummaryExportTests`, `RunComparisonTests`).

**Rollout** — additive; no flag, no migration, no backfill.

**Test plan** — unit tests for `PassRateService` (default/max `lastN`, 404, meta ordering preserved,
no-summary runs dropped, `status`/`runCreatedAtMs` attached). Repository functional tests: two computations
per run → only the latest counted; `computed_at_ms` tie → greater `computation_id` (also for
`findLatestComputationId`); every bucket, including score row absent vs. present with `passed = null`;
`FAILED`/`TIMEOUT`/`ERROR` all → `failedCount`. Endpoint functional tests via `TestRestTemplate`: `lastN`
trimming and default, empty suite, 404, 400 on `0` and on `> max`, OpenAPI examples present in
`/v3/api-docs`. New `@ConfigurationProperties` bean → one context-booting functional test runs first.
