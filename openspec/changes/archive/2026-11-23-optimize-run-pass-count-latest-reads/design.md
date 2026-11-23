## Context

See proposal.md. Verified beforehand on a throwaway Postgres 17 with the real table and index definitions (1.25M rows, recomputed runs, tied timestamps, null-computation rows, deleted runs including suites with all runs deleted): the SQL below returned exactly the rows and order of the current queries; the page went from about 6 s (count + page) to 77 ms, the top-10 read for 20 suites from 35 ms to 2 ms.

Constraints: typed jOOQ DSL; `RunDeletionFilter.notDeleted` stays the single definition of the tombstone predicate; the `RunPassCountRepository` contract (javadoc semantics, null vs empty `suiteIds`) does not change.

## Goals / Non-Goals

**Goals:** cost proportional to the number of suites (page) or requested suites × N (trend), using the existing index.

**Non-Goals:** a second count strategy for every request; caching.

## Decisions

### D1. One ordering expresses both rankings

Inside a suite, order by

```
run_created_at_ms DESC, test_suite_run_id DESC,                    -- newest run
CASE WHEN computation_id IS NULL THEN 1 ELSE 0 END ASC,            -- then that run's latest computation
computed_at_ms DESC, computation_id ASC
```

The first row is the latest computation of the newest run, which is what `computation_rank = 1` followed by `suite_rank = 1` selects today. The index supplies `run_created_at_ms DESC`; Postgres finishes the order with an incremental sort over the few rows sharing the newest timestamp. The computation part of the ordering (null last, `computed_at_ms DESC`, `computation_id ASC`) is defined once, in a private helper returning those three sort fields for a given table; the new run ordering prepends the two run keys to it. Any other read that reduces runs to their latest computation must use the same helper.

Invariant relied on: all rows of one run carry the same `run_created_at_ms` and `test_suite_id` (both writers take them from the run; `run-pass-counts` spec, "Stored pass counts per completed run"). That is what makes the single ordering, and `DISTINCT ON (run_created_at_ms, test_suite_run_id)` in D4, yield one row per run.

### D2. Suite source

- Filtered (`suiteIds` non-null): `unnest(:ids)` of the **distinct** ids, de-duplicated in Java before binding (`suiteIds.stream().map(UUID::toString).distinct()`), in both reads. A duplicate id in the input must not produce duplicate rows (today `= ANY(...)` hides duplicates).
- Unfiltered (`suiteIds` null): recursive skip scan

```sql
WITH RECURSIVE suites AS (
    (SELECT test_suite_id FROM run_pass_counts ORDER BY test_suite_id LIMIT 1)
    UNION ALL
    SELECT (SELECT p.test_suite_id FROM run_pass_counts p
            WHERE p.test_suite_id > s.test_suite_id ORDER BY p.test_suite_id LIMIT 1)
    FROM suites s WHERE s.test_suite_id IS NOT NULL)
SELECT test_suite_id FROM suites WHERE test_suite_id IS NOT NULL
```

Alternative: `SELECT DISTINCT test_suite_id` — walks the whole index (58 ms vs 6 ms in the test) and keeps the cost tied to the number of runs.

The suite list is not tombstone-filtered; a suite whose runs are all deleted yields no row from the lateral and drops out.

### D3. `findLatestPage`

```sql
SELECT l.<7 columns>, count(*) OVER () AS total_elements
FROM   <suites> s
CROSS JOIN LATERAL (
    SELECT p.* FROM run_pass_counts p
    WHERE  p.test_suite_id = s.test_suite_id AND <notDeleted(p.test_suite_run_id)>
    ORDER  BY <D1> LIMIT 1) l
ORDER  BY l.passed_count::float8 / NULLIF(l.total_count, 0) ASC NULLS LAST,
          l.run_created_at_ms DESC, l.test_suite_id ASC
LIMIT :size OFFSET :offset
```

- Total: taken from `total_elements` of any returned row.
- No row returned and offset 0 → total 0, no second statement.
- No row returned and offset > 0 (page past the end) → one fallback statement: the same join without sort/limit, `SELECT count(*)`. The window count cannot report a total when the page is empty; today's behaviour (correct total on an out-of-range page) is kept.
- Alternative: always a separate count query — doubles the lateral work on every request for a case that is rare.

### D4. `findLatestRanked`

```sql
SELECT l.<7 columns>
FROM   unnest(:distinctIds) AS s(test_suite_id)
CROSS JOIN LATERAL (
    SELECT DISTINCT ON (p.run_created_at_ms, p.test_suite_run_id) p.*
    FROM   run_pass_counts p
    WHERE  p.test_suite_id = s.test_suite_id AND <notDeleted(p.test_suite_run_id)>
    ORDER  BY <D1> LIMIT :perSuiteLimit) l
ORDER  BY l.test_suite_id, l.run_created_at_ms DESC, l.test_suite_run_id DESC
```

`perSuiteLimit <= 0` returns an empty map without a query, as today (`suite_rank <= 0` matches nothing; a negative `LIMIT` would be an SQL error).

`DISTINCT ON` keeps the first row of each run (its latest computation) before the limit. A bare `LIMIT N` would let a run with several computations take several of the N slots (reproduced: 5,504 differing rows in the test data). The returned map keeps today's shape: suite id → runs newest first, suites in id order.

### D5. Code shape

- `rankedPerSuite` and the constants only it used are removed.
- Typed jOOQ (`DSL.lateral`, `distinctOn`, `withRecursive`, `DSL.unnest`). Where the typed DSL cannot express a construct, a plain-SQL fragment is allowed for that construct only, with a one-line comment saying why. The recursive CTE is the likely candidate: check the rendered SQL — the anchor `ORDER BY … LIMIT 1` must be parenthesised inside the `UNION ALL`, and jOOQ must not add a cast on the recursive member that changes the column type (`varchar(36)` vs `varchar`), which PostgreSQL rejects.
- The rate expression in the page order stays as it is.

## Risks / Trade-offs

- [Tie-break or deletion semantics drift] → existing functional tests of both endpoints run unchanged; new cases cover recomputed runs inside a top-N window, duplicate input ids, a page past the end, and a suite with all runs deleted.
- [A suite with a long tail of deleted newest runs scans those rows] → bounded by that suite's deleted runs; today's query scans everything.
- [The deletion check inside the lateral must stay a per-row index probe] → observed plan in the test: nested-loop anti join with an index-only scan on `run_deletions_pkey` under an incremental sort (presorted key `run_created_at_ms`) fed by an index scan on `idx_run_pass_counts_suite_run_created`, about five rows read per suite. A hash anti join rebuilt per suite would erase the gain; `LIMIT` inside the lateral makes the planner favour the nested loop.
- [Planner chooses a non-index plan on tiny tables] → irrelevant for cost at that size; results are identical.
- [Skip scan issues one index probe per suite] → 2,000 suites: 6 ms.

## Migration Plan

Code-only; revert to roll back.
