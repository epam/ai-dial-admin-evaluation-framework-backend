## Context

See proposal.md for motivation. What shapes the approach:

- `run_pass_counts` (analytics) holds `test_suite_id`, `run_created_at_ms`, `passed_count`, `total_count` per run and computation. `PostgresRunPassCountRepository` already reduces rows to each run's latest computation and applies `RunDeletionFilter.notDeleted` (`rankedPerSuite`).
- `RunPassCountService` is the only gateway to that repository; `SuiteRunTrendService` shows the cross-datasource flow for an optional suite filter (`FilterParser` → `TestSuiteService.findIds` → analytics read).
- Deleting a suite tombstones all its runs, so an unfiltered analytics read never counts a deleted suite and needs no meta lookup.
- Functional tests run on the real clock; there is no test clock bean.
- Two datasources, no distributed transaction, no meta query inside an analytics transaction.

## Goals / Non-Goals

**Goals:**
- One analytics query per request, aggregated in SQL; no per-suite or per-week round trips.
- Week arithmetic defined once, in one injectable component, unit-tested on a fixed clock.
- Reuse the latest-computation reduction instead of writing a second copy.

**Non-Goals:**
- No change to how pass counts are written or reconciled.
- No caching of the result.
- No Query DSL entity for this aggregation.

## Decisions

### D1. API contract

`GET /api/v1/analytics/test-suites/health-distribution`

| Parameter | Type | Default | Validation |
|---|---|---|---|
| `weeks` | integer | 8 | 1..52 |
| `threshold` | number | 0.75 | 0..1 inclusive, finite |
| `filter` | repeated string | none | as the test suite list (`@FilterParam`) |

```json
{
  "threshold": 0.75,
  "weeks": [
    { "weekStart": 1794787200000, "healthyCount": 12, "failingCount": 3, "suiteCount": 15 }
  ]
}
```

- Counts, not fractions: the client draws the stacked bar and can show absolute numbers in tooltips; fractions would lose `suiteCount`.
- `threshold` is echoed so a client that relied on the default can label the legend.
- `weeks` and `threshold` are nullable controller parameters validated with `@Min`/`@Max` and `@DecimalMin`/`@DecimalMax` on the `@Validated` controller; defaults come from `AnalyticsConstants` (`HEALTH_DISTRIBUTION_DEFAULT_WEEKS = 8`, `HEALTH_DISTRIBUTION_MAX_WEEKS = 52`, `HEALTH_DISTRIBUTION_DEFAULT_THRESHOLD = 0.75`). Type mismatches and constraint violations already map to HTTP 400 in `DefaultExceptionHandler`. `NaN` parses as a `Double`, so the tests must cover it; if the range constraints let it through, the controller rejects a non-finite value explicitly.
- Alternative considered: adding the distribution to the run-trends response. Rejected: the trends response is paged and sorted, the distribution is neither, and the two cards load independently.

### D2. Week range component

New injectable `HealthWeekRange` component (`service.domain.analytics`, `Clock` injected) with one method returning, for a number of weeks, a `WeekRange` record (same package): `startMs` (Monday 00:00 UTC of the oldest week), `endMs` (exclusive: the first millisecond after the current week) and `weekStarts` (oldest first). A constant `long WEEK_MS = 7L * 86_400_000L` lives in `AnalyticsConstants`; it must be a `long`, because 52 weeks in milliseconds does not fit an `int`.

- The current week start is taken from the calendar, not from epoch arithmetic: `LocalDate.now(clock.withZone(UTC)).with(TemporalAdjusters.previousOrSame(MONDAY))` at start of day. Flooring epoch milliseconds to a multiple of `WEEK_MS` would give Thursday starts, because 1970-01-01 was a Thursday.

- UTC has no daylight saving, so every week is exactly `WEEK_MS` long and a run's week is `(run_created_at_ms - startMs) / WEEK_MS` by integer division. This keeps bucketing in plain `BIGINT` arithmetic — no `date_trunc`, no time zone functions, the same expression on any vendor.
- Alternative considered: `date_trunc('week', to_timestamp(...))` in SQL. Rejected: it depends on the session time zone unless carefully forced to UTC, and the service would still have to generate the empty weeks.

### D3. One aggregate query

New repository read `findWeeklySuiteHealth(suiteIdsOrNull, startMs, endMs, threshold)` returning `List<WeeklySuiteHealth(weekIndex, healthyCount, failingCount)>` (model in `data.db.analytics.model`), only for weeks that have counted suites:

```sql
SELECT week_index,
       count(*) FILTER (WHERE passed::float8 / total >= :threshold) AS healthy_count,
       count(*) FILTER (WHERE passed::float8 / total <  :threshold) AS failing_count
FROM (
    SELECT test_suite_id,
           (run_created_at_ms - :startMs) / <WEEK_MS> AS week_index,
           sum(passed_count) AS passed,
           sum(total_count)  AS total
    FROM   <latest computation per run, tombstone-filtered, scoped>
    GROUP  BY test_suite_id, week_index
    HAVING sum(total_count) > 0
) per_suite_week
GROUP BY week_index
```

- The inner source is the existing per-run reduction, extracted from `rankedPerSuite` into a shared private method (`computationRankedPerRun(scope)`, which returns the rows with their computation rank; each caller keeps rank 1); `rankedPerSuite` keeps adding its per-suite rank on top. The scope condition gains `run_created_at_ms >= :startMs AND run_created_at_ms < :endMs`, applied before the window function so only the range's rows are ranked. Both bounds are computed in Java as `long`; the week length is inlined from `AnalyticsConstants.WEEK_MS` as a `BIGINT`. The lower bound guarantees a non-negative `week_index`. Restricting by run creation time before ranking is safe: all computations of one run share `run_created_at_ms`.
- The rate is computed as `passed::float8 / total`, the same double division `PassRateCalculator` performs, so a suite whose rate displays as 0.75 is classified consistently with the other cards.
- `RunPassCountService.getWeeklySuiteHealth(...)` is a plain pass-through. Unlike the run-trends and pass-rate readers, which wrap two statements in one read-only analytics transaction for a consistent view, this reader issues a single statement and opens no transaction around it.
- Alternative considered: return per suite-week rows and bucket in Java. Rejected: transfers up to suites × weeks rows for a result of at most 52 pairs of numbers.

### D4. No new index

The range predicate is on `run_created_at_ms` alone; the existing `(test_suite_id, run_created_at_ms DESC)` index serves it only when a filter narrows the suites. Without a filter the query scans the table. The largest environment holds about 3,300 runs, so a scan is cheap. An index on `(run_created_at_ms)` is a one-line migration when volumes grow; adding it now would cost a migration and a jOOQ regeneration for no measurable gain.

### D5. Service flow

`SuiteHealthDistributionService.getDistribution(int weeks, double threshold, List<String> filter)` (`service.domain.analytics`, `@LogExecution`):

1. Parse the filter; when non-empty, `TestSuiteService.findIds(filters)` (meta, own transaction). An empty id list short-circuits to all-zero weeks without an analytics read.
2. `HealthWeekRange` → range.
3. `RunPassCountService.getWeeklySuiteHealth(...)` (analytics, one statement).
4. Map `weekIndex` → entry; fill missing weeks with zeros; `suiteCount = healthy + failing`.

No analytics transaction is opened around step 3 and no meta read follows it, so the datasource rule holds by construction. `FilterValidationException` propagates to the existing 400 handler.

### D6. Testing on the real clock

- Exact boundaries (Monday 00:00, Sunday 23:59:59.999, year change, week count) are unit-tested in `HealthWeekRangeTest` with `Clock.fixed`.
- Functional tests take the application's `Clock` bean, derive the current week start with the same component, and seed pass-count rows at offsets from week starts (first and last millisecond included). The query has no "now" upper bound other than the end of the current week, so a row seeded later in the current week than the actual time is still counted.
- A test that starts just before Monday 00:00 UTC and sends its request just after would see its rows one week off. Each test reads the current week start before seeding and again after the response and is skipped (`Assumptions.assumeTrue`) when the two differ.
- A run job finishing late from an earlier nested test class can record a pass-count row stamped "now", which lands in the current week. Tests asserting exact counts therefore either place their rows in past weeks or scope the request with an `id in [...]` filter on the suites they created. Only the "Default request" and "No recorded runs" scenarios run unfiltered, and they assert shape (and zero counts for past weeks), not current-week counts.
- The "Unfiltered request" scenario of the datasource requirement is verified by the service unit test (no `TestSuiteService` interaction), not by a functional test.

## Risks / Trade-offs

- [Unfiltered request scans `run_pass_counts`] → fine at current size; D4 names the index to add.
- [Numbers are low until reconciliation has recorded older runs] → same behaviour and same mitigation (first pass 60 s after startup) as run trends; mentioned in the endpoint description.
- [Pooled rate hides a single bad run among large good ones] → intended (user decision); the run-trends card shows per-run rates.
- [A large filter result passes many suite ids to the analytics query] → same `= ANY(array)` shape run trends already uses.
- [Midnight-boundary and stray-row flakes in functional tests] → see D6.
- [Query parameter `weeks` (a number) and response field `weeks` (a list) share a name] → kept for brevity; both OpenAPI descriptions say which is which.

## Migration Plan

Additive endpoint, no schema or config change. Rollback is reverting the commit. Merge after `evaluation-run-trends-api`.
