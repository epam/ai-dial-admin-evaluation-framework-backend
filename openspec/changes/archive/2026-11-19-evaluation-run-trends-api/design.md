## Context

See `proposal.md` for motivation. Constraints that shape the design:

- Two datasources, no distributed transaction. Run status, suite names and suite references live in meta; test case rows live in analytics. A meta query must not run inside an analytics transaction.
- Analytics data is append-only; deletion is a tombstone (`run_deletions`) filtered on read.
- A run becomes `COMPLETED` in exactly one place, `TestSuiteEvaluationJob` (`updateToCompleted`, matches only RUNNING). `COMPLETED` is final.
- The job produces one computation per run; the computation id is created in that job. There is no re-scoring feature, but the public eval-summary write API (`POST /api/v1/analytics/eval-summaries`) does not check the run status, so a client can append a newer computation to a COMPLETED run, in several batches.
- `passed` on a score row is non-null only when the run's snapshot has a threshold, so the threshold itself is not needed to count passes.
- A test case has no `test_case_eval_scores` row when the suite has no effective per-test-case score definition (the default, and custom-function overall scores), when the score write failed (logged and swallowed), or when the run predates that table. Only eval summaries know that such a test case failed, including a metric error, which turns the summary row non-SUCCESS.
- The existing #304 summary counts test cases with a full join of summaries and scores per request, for at most 200 runs. The trends table needs the latest rate of *every* suite to sort, so that query shape does not carry over.

## Goals / Non-Goals

**Goals:**
- Sort, page and count over all suites in one analytics query that reads one small row per run.
- One definition of a run's pass counts, shared by the trends endpoint and the #304 summary.
- No data migration: history is filled by the same job that repairs failed writes.

**Non-Goals:**
- Generic sort options, cursor paging, a Query DSL entity for the new table.
- Storing suite names, evaluatees or thresholds in analytics.

## Decisions

### D1. Store per-run counts in a new analytics table

`run_pass_counts`, migration `analytics/POSTGRES/V1.23__CreateRunPassCountsTable.sql`:

| Column | Type | Notes |
|---|---|---|
| `test_suite_run_id` | VARCHAR(36) NOT NULL | |
| `test_suite_id` | VARCHAR(36) NOT NULL | |
| `computation_id` | VARCHAR(36) NULL | null only for a run without eval summaries |
| `run_created_at_ms` | BIGINT NOT NULL | the run's `created_at_ms` |
| `passed_count` | INTEGER NOT NULL | |
| `total_count` | INTEGER NOT NULL | |
| `computed_at_ms` | BIGINT NOT NULL | the computation's timestamp; 0 for a null computation (D3) |

- Unique index on `(test_suite_run_id, COALESCE(computation_id, ''))`. The insert is `ON CONFLICT DO NOTHING` **without a conflict target** (jOOQ `onConflictDoNothing()`): the column-list form used by the other analytics inserts does not match an expression index and fails at runtime.
- Index on `(test_suite_id, run_created_at_ms DESC)`.
- No foreign keys, no partitioning: one row per run.

Why counts and not a rate: counts are exact, let the reader derive the rate, and show the sample size in the trend.

Alternatives:
- Compute on read from `test_case_eval_summaries` / `test_case_eval_scores`: cost grows with test cases of every suite's latest run on each request. Rejected.
- Add the counts to the meta run row: then sorting is a meta query, but the counts come from analytics and a recompute would need meta updates from analytics code; also breaks "analytics is append-only". Rejected.
- Always write a score row per test case so that scores alone carry the status: changes the writer and needs a score-row backfill. Dropped earlier in favour of reading the status from eval summaries.

### D2. Pass rule is one SQL statement over summaries and scores

Per `(run, computation)`: distinct test cases of eval summaries with `bool_and(execution_status = 'SUCCESS')`, full-joined with the score rows; a test case passes when the summaries flag is not false, the score status is not non-SUCCESS and `passed` is not false. This is today's `countByRunAndComputation` plus the summaries status. The query is not tombstone-filtered: it serves the writer, like `TestCaseScoreComputer`.

`PostgresTestCaseEvalScoreRepository.countByRunAndComputation` and `RunPassCounts` (four buckets) are replaced by the new repository's count query returning `(passed, total)`. `PassRateCalculator.rate` becomes `passed / total` (null for total 0); its threshold parameter goes away. `TestSuiteRunService.getThresholdsByRunIds` and `getLatestCompletedRunIdsBySuite` are removed if nothing else calls them.

### D3. `computed_at_ms` comes from the computation, not from the write

The row's `computed_at_ms` is the greatest `computed_at_ms` of the computation's eval summaries, so "latest computation of a run" in this table orders the same way as everywhere else (`computed_at_ms DESC, computation_id ASC`). All summaries of one computation share one timestamp, so the job can pass the value it already has. A null-computation 0-of-0 row gets `computed_at_ms = 0`, so that any real computation recorded later for the same run wins, whatever timestamp its writer supplied.

`RunPassCountService.record` decides the null case itself: when the computation has no eval summaries and no score rows it stores a null computation, whether it is called by the job or by reconciliation. That keeps one 0-of-0 row per run.

### D4. Write after the status flip, never fail the run

`TestSuiteEvaluationJob`, in the branch where `updateToCompleted` returned 1 and after `statusChanged = true`, calls a dedicated `recordPassCountsQuietly(...)` method that invokes `RunPassCountService.record(runId, suiteId, computationId, runCreatedAtMs)`. The service runs count + insert in one analytics transaction. The method catches `RuntimeException`, logs it (exception as last argument) and returns; D5 repairs the gap.

The broad catch is deliberate and documented in the method: anything escaping here lands in the job's outer `catch (Exception)`, which logs the run as failed, marks the trace FAILED and resets `statusChanged`, so the COMPLETED SSE event is never sent. A `DataAccessException`-only catch misses `CannotCreateTransactionException` (connection acquisition), which is a `TransactionException`.

Writing after the flip keeps the invariant "a row exists only for a COMPLETED run" without a second status check. Writing before the flip would leave rows for runs cancelled at the last moment.

### D5. Reconciliation job instead of a data migration

`RunPassCountReconciliationJob` (`service.domain.analytics`), `@Scheduled(initialDelayString, fixedDelayString)` from `analytics.run-pass-counts.reconciliation.*`, a no-op when `enabled` is false:

1. Page through COMPLETED runs in meta by keyset `(created_at_ms, id)` via a new `TestSuiteRunService` method returning a light projection `(id, suiteId, createdAt)`, `batch-size` at a time. The meta read happens outside any analytics transaction.
2. Per batch, in one analytics transaction: resolve each run's latest computation and its timestamp from eval summaries, and read the `(run, computation)` pairs that already have a row. Both are writer-side reads and are not tombstone-filtered.
3. Record every run whose latest computation has no row and is older than `min-computation-age-ms`; a run without summaries and without any row gets the null-computation 0-of-0 row.

A failing batch is logged and the pass continues with the next one. Concurrent instances are harmless because the insert is idempotent, and the existing scheduled jobs run without a lock in the same way. Runs deleted before tombstones existed are not in meta any more, so they are never recorded. A run whose meta delete failed after its tombstone was written stays in meta and gets a row that every client read filters out; harmless.

Comparing against the latest computation, not "any row for the run", is what keeps the stored counts in step with every other reader after a client appends a computation to a completed run. `min-computation-age-ms` exists because such a client writes in batches and a record is never updated: recording a half-written computation would freeze partial counts.

Defaults: enabled, initial delay 60 s, interval 1 h, batch size 500, minimum age 10 min. Functional tests disable the job in `PostgresFunctionalTests` properties and call its method directly.

Alternatives:
- Java Flyway migration: must write through Flyway's own connection (meta) while the target is analytics, runs inside startup with no statement timeout, and does not repair later write failures. Rejected.
- Startup-only backfill: does not repair failed writes or appended computations.
- Record from the eval-summary write API: it is called once per batch, so the first batch would freeze partial counts. Rejected.
- Sweep only a recent time window: cheaper, but then history needs a separate one-off. A full pass is a keyset scan of meta plus two indexed analytics reads per 500 runs; acceptable hourly. Revisit if it shows up in monitoring.

### D6. Trends read: two analytics queries, then one meta query

`SuiteRunTrendService.getTrends(filters, pageRequest)`:

1. Filter given → `TestSuiteService.findIds(filters)` (meta, `FilterWhitelists.TEST_SUITES`; converts the repository's `InvalidFilterException` to `FilterValidationException` like `getAll`, so a bad filter is a 400) → `suiteIds`. Empty result → empty page. No filter → no meta call, no id restriction.
2. One read-only analytics transaction (`TransactionTemplate`, as in `SuitePassRateService`):
   - **Q1 page**: reduce `run_pass_counts` (tombstone-filtered, optional `test_suite_id = ANY(:ids)`) to each run's latest computation, rank runs per suite by `run_created_at_ms DESC, test_suite_run_id DESC`, keep rank 1, order by `passed_count::float / NULLIF(total_count, 0) ASC NULLS LAST, run_created_at_ms DESC, test_suite_id`, `LIMIT/OFFSET`; plus the count of rank-1 rows.
   - **Q2 trend**: same reduction for the page's suite ids, rank ≤ 10.
3. `TestSuiteService.findByIds(pageSuiteIds)` (meta) after the transaction, loading only id, name, suite type and the reference columns into `TestSuite`. An injectable `SuiteEvaluateeMapper` (`service.domain.analytics`) derives the target with `SuiteTargetResolver.fromEntity` and switches exhaustively on the sealed `DeploymentTarget` / `RouteTarget` / `McpToolTarget` (per `docs/patterns/suite-target.md`), not on `suiteType`. A suite missing in meta is dropped from the page.

The suite-id set is the only coupling between the filter and the analytics queries. A later "selected applications" filter adds another way to produce that set (a meta method filtering on reference ids) and changes nothing else.

Offset paging with a total is used because the dashboard table shows page numbers and the sort key is a computed, non-unique rate; the analytics cursor convention targets large append-only row sets, which this is not.

Alternatives:
- One query returning page and trend together: harder to read, saves one round trip. Not worth it.
- Rank suites in meta: meta has no pass rates.

### D7. #304 summary reads the same table

`SuitePassRateService.summarize` becomes: one analytics query for the requested suite ids, rank ≤ 2 (the Q2 shape with limit 2), then the existing mean logic. Meta is no longer queried. Side effect, stated in the spec: a completed run whose row is missing is ignored until recorded, instead of occupying its position with nothing.

### D8. API contract

`GET /api/v1/analytics/test-suites/run-trends?page=&size=&filter=` → `PageResponseDto<SuiteRunTrendDto>`:

```json
{
  "suiteId": "…", "suiteName": "Docs search regression", "suiteType": "MCP_TOOL",
  "evaluatee": { "id": "docs-toolset", "name": "Docs toolset", "type": "dial-toolset", "toolName": "search" },
  "runs": [ { "runId": "…", "createdAt": 1790000000000, "passRate": 0.8, "passedCount": 8, "totalCount": 10 } ],
  "latestPassRate": 0.8, "previousPassRate": 0.9, "delta": -0.1
}
```

`evaluatee.toolName` is present for MCP suites only; `evaluatee.id` and `evaluatee.type` are absent for ROUTE suites. `evaluatee.type` is the stored reference type, unmapped: DEPLOYMENT references carry it optionally and unvalidated, so a derived MODEL/APP/TOOL category would be a guess; the UI maps what it recognises. The trend length (10) is a constant in a new `AnalyticsConstants` class (`constants`). Controller `SuiteRunTrendController` uses `PaginationParamResolver`, `@Validated`, `@FilterParam`, and gets a registry entry in `OpenApiQueryParamCustomizer`.

Errors: invalid `page`/`size`/`filter` → 400 through the existing handlers. No new error codes.

## Risks / Trade-offs

- [Rows missing until the first reconciliation pass after deployment] → 60 s initial delay; the pass is logged with the number of rows written.
- [Rolling deployment: runs completed by an old instance are unrecorded for up to one interval] → the next pass records them; meanwhile an older run shows as latest.
- [A client appends a computation in batches spread over more than the minimum age] → partial counts are recorded and never corrected. The minimum age is configurable; no known client does this.
- [Shared functional-test context] → the job is disabled in tests, and tests that assert totals clean `run_pass_counts` first, because every test that completes a run now leaves a row.
- [A filter matching very many suites sends a large id array to analytics] → suites number in the hundreds to low thousands; a single array bind is fine. Revisit with a cap if that changes.
- [Suite deleted between the analytics and meta reads] → item dropped, page may be one short; the total is slightly high until the next request.
- [Full reconciliation pass grows with the number of runs] → keyset scan plus one indexed lookup per 500 runs; see D5.
- [#304 numbers change for suites without a score definition] → intended; release note.
- [Future recompute] → a second computation appends a row; readers already pick the latest. A recompute feature should record its own row when it finishes; reconciliation is the fallback.

## Migration Plan

1. Deploy: Flyway creates the empty table.
2. First reconciliation pass fills history; until then both endpoints under-report.
3. Rollback: revert the code. The table is unused by older code and can stay.

## Open Questions

- Whether the dashboard needs run names in the trend tooltip. It would add one meta bulk read of at most `size × 10` runs; not needed for the table as mocked.
