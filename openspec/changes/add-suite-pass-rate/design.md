## Context

See `proposal.md` — Why. What shapes the approach:

- **Two datasources, no cross-DB join.** Run identity, status and creation time live in meta
  (`test_suite_runs`); rows and verdicts live in analytics (`test_case_eval_summaries` LEFT JOIN
  `test_case_eval_scores` on `eval_summary_id`). The scores table has no FK and no run context of its own.
- **"Latest computation" is already a rule**, not a flag: `EvalSummaryRepository.findLatestComputationId`
  orders one run's summaries by `computed_at_ms DESC LIMIT 1` (`docs/patterns/computation-versioning.md`).
  `computed_at_ms` is assigned once per computation in `MetricEvaluationContext`, so every row of one
  computation shares it.
- **Transactions live in the service layer.** Repositories carry no `@Transactional`; a `@Transactional`
  helper invoked from within the same bean opens nothing (`RunComparisonService` documents exactly this and
  uses an explicit `TransactionTemplate`).
- **Cross-domain rule.** A service may inject only its own domain's repository; suite/run existence goes
  through `TestSuiteService` / `TestSuiteRunService` (`openspec/specs/best-practices/spec.md`).
- **Indexes at hand.** Analytics: `idx_eval_summaries_run_computed_at (test_suite_run_id, computed_at_ms
  DESC, computation_id)` and `idx_eval_summaries_run_computation (test_suite_run_id, computation_id)`.
  Meta: `idx_test_suite_runs_test_suite_id (test_suite_id)` only — there is **no** composite with
  `created_at_ms`.
- **OpenAPI examples by filename.** `OpenApiExampleCustomizer.pathToKey` strips `{`/`}` from the registered
  path, so a path-variable endpoint's example file carries the bare variable name.

## Goals / Non-Goals

**Goals:**
- One meta read + one analytics read per request, both bounded by `max-last-n`.
- Counts that are row-for-row equal to `POST /queries/execute` over `eval_summaries` grouped by `passed`
  for the same `(run, computation)`.
- One shared "latest computation" rule, provably identical between the single-run and the multi-run path.

**Non-Goals:**
- A ClickHouse implementation of the aggregate (the analytics vendor is `POSTGRES`-only today).
- Caching or precomputation; the aggregate is cheap at ≤100 runs.
- Any change to how `score`/`passed` are produced.

## Decisions

### 1. Meta drives the window; analytics fills it

The service asks meta for the newest `lastN` runs of the suite — `(id, status, created_at_ms)` ordered
`created_at_ms DESC, id DESC`, the ordering `findLatestByTestSuiteId` already uses — then hands the ids to
one analytics aggregate. Runs the aggregate returns no row for are dropped; the rest are re-ordered by the
meta list (a `LinkedHashMap` keyed by run id) and decorated with `status` and `runCreatedAtMs`.

*Alternative rejected: analytics-first.* `test_case_eval_summaries.test_suite_id` exists, so one could
select the distinct runs per suite from analytics alone. But run ordering is by creation time, which
analytics does not hold, and there is no index on `test_suite_id` there; it would also lose `status`,
which decision 4 needs. Meta-first uses the suite's index and returns a bounded id list.

*Alternative rejected: widen the window until N runs have summaries.* Unbounded round trips for a suite
with a long tail of early-failed runs; and the honest answer to "last 10 runs" for such a suite is that
most of them produced nothing. The spec makes the possibly-short list a contract.

### 2. One `DISTINCT ON` derived table for latest-per-run

```sql
WITH latest AS (
  SELECT DISTINCT ON (test_suite_run_id) test_suite_run_id, computation_id
    FROM test_case_eval_summaries
   WHERE test_suite_run_id IN (:ids)
   ORDER BY test_suite_run_id, computed_at_ms DESC, computation_id DESC)
SELECT s.test_suite_run_id, s.computation_id,
       count(*) FILTER (WHERE s.execution_status <> 'SUCCESS')                                  AS failed,
       count(*) FILTER (WHERE s.execution_status =  'SUCCESS' AND sc.passed IS TRUE)            AS success_passed,
       count(*) FILTER (WHERE s.execution_status =  'SUCCESS' AND sc.passed IS FALSE)           AS success_not_passed,
       count(*) FILTER (WHERE s.execution_status =  'SUCCESS' AND sc.passed IS NULL)            AS success_no_verdict,
       count(*)                                                                                  AS total
  FROM latest l
  JOIN test_case_eval_summaries s ON s.test_suite_run_id = l.test_suite_run_id
                                 AND s.computation_id    = l.computation_id
  LEFT JOIN test_case_eval_scores sc ON sc.eval_summary_id = s.id
 GROUP BY s.test_suite_run_id, s.computation_id
```

Expressed in the typed jOOQ DSL (`select(...).distinctOn(...)`, `count().filterWhere(...)`). The derived
table is a bounded index scan on `idx_eval_summaries_run_computed_at (test_suite_run_id, computed_at_ms
DESC, computation_id)` per run, with a trailing sort on `computation_id` — the index's third column is
**ascending**, while the tie-break here orders it descending, so Postgres cannot walk the index alone for
that last key; the join back uses `idx_eval_summaries_run_computation`. `sc.passed IS NULL` is true both for
a missing score row and for a present row with `passed = NULL`, which is exactly the spec's
`successNoVerdictCount` — no `CASE` needed, and the four buckets partition every row by construction.

*Alternative rejected: N calls to `findLatestComputationId` + N count queries.* 2N round trips for what
one statement answers; the whole point of the endpoint is to remove the client's N+1.

*Alternative rejected: `max(computed_at_ms)` subquery joined back.* Needs a second join to recover
`computation_id`, and cannot express a tie-break without a window function anyway. `DISTINCT ON` with the
ordered tie-break is the shortest correct form on Postgres, and the repository is already
`@ConditionalOnProperty(vendor = POSTGRES)`.

### 3. Pin the tie-break in both resolution paths

`findLatestComputationId` gains `, COMPUTATION_ID.desc()` after `COMPUTED_AT_MS.desc()`. `computation_id`
is stored as `VARCHAR(36)` and bound/compared as a `String` throughout the repository, so `ORDER BY
computation_id DESC` is **lexicographic (text) ordering of the canonical lowercase UUID string** — not the
same total order as `java.util.UUID.compareTo`, which compares the two 64-bit halves as signed longs. Every
artifact and test that names "the greater `computation_id`" means this text ordering. Ties are theoretical
(one `computed_at_ms` per computation), but the multi-run aggregate has to name *some* order, and the two
paths must be the same rule or a future divergence is invisible. One repository functional test seeds a tie
with two computation ids whose canonical strings disagree with their `UUID.compareTo` order and asserts both
methods return the same id, computed via `Comparator.comparing(UUID::toString)`. Recorded as a
`metrics-storage` delta.

### 4. `status` on every entry

Phase 2 flushes summaries in batches, so a RUNNING run legitimately has rows and its counts move between
calls. Omitting RUNNING runs would hide the run the user is most likely watching; including them without a
marker would let a client draw a "final" 3/30 pass rate from a run that is 10% done. `status` costs nothing —
it is on the meta rows already fetched — and is the same `RunStatus` vocabulary the run endpoints use.

### 5. Transaction shape

`PassRateService.getPassRate` is **not** `@Transactional`. In order:

1. `TestSuiteService.getById(suiteId)` — its own read-only meta tx; throws `EntityNotFoundException` → 404.
2. `TestSuiteRunService.findRecentRuns(suiteId, n)` — new public method, its own read-only meta tx.
3. `analyticsTransactionTemplate.execute(...)` → `EvalSummaryRepository.countPassRateByLatestComputation`.

Same shape as `RunComparisonService`; the analytics template is built in the constructor from the
`@Qualifier("analyticsTransactionManager")` manager (there is no `TransactionTemplate` bean). A single
read-only analytics tx gives the aggregate one snapshot; meta and analytics are never open together.

### 6. Bounds resolution in the service, `@Min(1)` on the boundary

The controller declares `@RequestParam(required = false) @Min(1) Integer lastN` under the class-level
`@Validated`, so `0`/negative → `ConstraintViolationException` / `HandlerMethodValidationException` → 400
before the service runs (both are handled by `DefaultExceptionHandler` and return `400 VALIDATION_ERROR`;
which one is actually thrown depends on Spring's binding path, so tests assert on the response status and
error code, not on the exception type). Default and
max live in `PassRateProperties`; the service applies the default and throws `ValidationException` naming
`analytics.pass-rate.max-last-n` when exceeded. `@Max` cannot carry a configurable value, so the max check is
necessarily server-side code.

### 7. Placement

- Endpoint on `EvalSummaryController` (`/api/v1/analytics/eval-summaries`): the data is eval summaries and
  their scores. `/test-case-pass-rate/{testSuiteId}` is two segments, so it cannot collide with the
  existing `/{id}`.
- `PassRateService` in `service.domain.analytics` beside `AnalyticsResultService` / `ComputationResolver`;
  it injects `EvalSummaryRepository` directly, as `ComputationResolver` does.
- `TestSuiteRunRef` (`data.db.model`) — a record `(UUID id, String status, long createdAtMs)` — `status` stays a `String` like `TestSuiteRun.status` — because
  the full `TestSuiteRun` row carries `suite_snapshot` JSONB, which is the TOAST column the selective-
  projection pattern exists to avoid on bulk reads.
- `RunPassRateStats` (`data.db.analytics.model`) — a record of two UUIDs and five `long`s; no
  `RecordMapper`, mapped inline like `EvalSummaryMatchStats`.

### 8. OpenAPI

`@ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json"))` on the operation
(the customizer skips responses without JSON content), plus two files
`openapi/examples/api-v1-analytics-eval-summaries-test-case-pass-rate-testSuiteId-GET-response-200-{minimal,full}.json`
— **no braces** in the name. `minimal` = one COMPLETED run; `full` = one RUNNING run with partial counts
ahead of two terminal runs, including a no-verdict bucket > 0. A functional test asserts both example names
appear in `/v3/api-docs`, since a misnamed file fails silently. Not a filter/sort list endpoint, so no
`OpenApiQueryParamCustomizer` registry entry.

## Risks / Trade-offs

- [`DISTINCT ON` is Postgres-only] → The repository is vendor-conditional already; a ClickHouse
  implementation would use `argMax`. Noted in the pattern doc, not abstracted now.
- [Meta sort has no composite index] → `LIMIT ≤ 100` over one suite's runs, filtered by
  `idx_test_suite_runs_test_suite_id`; the in-memory sort is over that suite's rows only. Revisit only if
  EXPLAIN on a large tenant shows otherwise.
- [Tie-break edit on a shared path] → `ComputationResolverTest` is a pure Mockito test and cannot observe an
  `orderBy` change; the actual guard is the new tie-break repository functional test (tasks.md 2.6(b)) plus
  the existing functional suites that exercise `findLatestComputationId` end-to-end through
  `ComputationResolver` (`EvalSummaryTests`, `EvalSummaryExportTests`, `RunComparisonTests`).
- [Partial counts for RUNNING runs] → `status` on the entry; documented on the DTO.
- [`successNoVerdictCount` conflates causes] → the bucket is "no verdict available", not "no threshold
  configured" — it also fires when there is no score row at all: the suite has no `overallScore`
  definition, a score row has not been written yet (mid-run flush), or a score write failed and was
  swallowed. The `@Schema` description states all four causes; the FE MUST NOT treat a non-zero count as
  proof the suite has no threshold.
- [`findLatestComputationId`'s second `ORDER BY` key defeats the pure top-1 index descent] → adding
  `computation_id DESC` for the tie-break means `idx_eval_summaries_run_computed_at` (whose third column is
  ascending) can no longer serve the query as a single index descent; Postgres instead does a bounded index
  scan on the `(test_suite_run_id, computed_at_ms DESC)` prefix and sorts the newest `computed_at_ms`'s rows
  by `computation_id` — index-only, over at most a handful of rows, an accepted cost. A follow-up could flip
  the index's third column to `DESC` to restore the pure descent; out of scope for this change.

## Migration Plan

Additive. No schema change, no data backfill, no feature flag. Deploy; roll back by redeploying the
previous build.
