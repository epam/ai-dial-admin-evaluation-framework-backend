# Analytics Time-Based Partitioning

Native PostgreSQL declarative RANGE partitioning on `test_case_run_results`, `test_case_eval_summaries`, and `test_case_eval_scores`, introduced in `V1.20__PartitionAnalyticsHighVolumeTables.sql`. Exists to bound query-planner cost/index size as these append-only tables grow, and to turn retention into a metadata operation (`DROP`/`DETACH PARTITION`) instead of row-level `DELETE`.

## Partition key

Monthly RANGE partitioning on the existing `created_at_ms` BIGINT (epoch millis) column — not a generated `timestamptz` column. Postgres requires the partition key to be part of every unique constraint on a partitioned table; `created_at_ms` was already the leading column of each table's PK (and a trailing column of its natural-key unique constraint) specifically in anticipation of this. Partitioning on the raw BIGINT is fully transparent to application code: no new column on `test_case_run_results`/`test_case_eval_summaries`, no jOOQ type changes, no `ON CONFLICT` inference-list changes.

`test_case_eval_scores` didn't start with a time column at all, so it's the one exception: `V1.20` added `created_at_ms` (backfilled from the parent `test_case_eval_summaries` row) and changed its PK from `(eval_summary_id)` to `(created_at_ms, eval_summary_id)`.

## Naming convention

`<table>_p<yyyyMM>` for a real calendar month (e.g. `test_case_run_results_p202610`), plus two fixed partitions per table:
- `<table>_p_legacy` — everything created before the migration's cutover (`MINVALUE` to the start of the UTC month after the migration ran). Attached with zero data copy for the two tables that already had a time-leading PK; for `test_case_eval_scores`, backfilled first.
- `<table>_p_default` — catch-all `DEFAULT` partition for any `created_at_ms` outside every explicit range. Necessary because `created_at_ms` is a run's *meta-DB* creation timestamp, which can in principle be older than the oldest live partition.

`build.gradle`'s analytics jOOQ generator excludes all of these child relations (`withExcludes` regex covering `_p_legacy`, `_p_default`, `_p\d{6}` for all three table prefixes) — only the partitioned parent's `Table`/`Keys`/`Indexes` classes are ever generated. A new query on these tables SHOULD carry a `created_at_ms` predicate when the caller has that value available (see `EvalSummaryRepository.findLatestComputationId`'s `runCreatedAtMs` parameter for the pattern); if it can't, note the accepted cross-partition scan cost in a comment, as `MetricScoreLatestComputationDefaulter` and `RunComparisonService.requireComputation` do.

## Constraint-inclusion rule

Every unique constraint/index on a partitioned table must include the partition key. Verified/arranged for all three tables:

| Table | PK | Unique constraint/index |
|---|---|---|
| `test_case_run_results` | `(created_at_ms, id)` | `uq_results_run_case_index` ends in `created_at_ms` |
| `test_case_eval_summaries` | `(created_at_ms, id)` | `uq_eval_summaries_natural_key` ends in `created_at_ms` |
| `test_case_eval_scores` | `(created_at_ms, eval_summary_id)` (changed by `V1.20`; was `(eval_summary_id)`) | none besides the PK |

## Index inheritance

All secondary indexes are declared once on the partitioned parent and are *partitioned indexes* — Postgres automatically materializes a matching local index on every current and future partition. `AnalyticsPartitionMaintenanceService` never creates indexes itself; `CREATE TABLE ... PARTITION OF ...` inherits them for free.

## The eval_summaries ↔ eval_scores JOIN

`test_case_eval_scores` is partitioned on the same monthly boundaries as its parent, but it isn't reached through a partition-key-aware relationship by accident — the JOIN predicate in all 5 read sites (`PostgresEvalSummaryRepository`'s four query builders, `PostgresEvalSummaryEntityResolver.table()`) matches on **both** `eval_summary_id` and `created_at_ms`. This was made possible because `V1.20` backfilled every legacy row's `created_at_ms` with its *actual* parent timestamp (not a cheap sentinel), so the extra predicate matches correctly for old and new data alike — and it preserves Postgres's join-elimination optimization for queries that reference neither `score` nor `passed` (the composite key still covers a full unique constraint on the child side).

## Maintenance job contract

`AnalyticsPartitionMaintenanceJob` (`service.infrastructure.partition`), scheduled via `analytics.partitioning.maintenance-interval-ms`/`initial-delay-ms`, delegates to `AnalyticsPartitionMaintenanceService`:

1. **`ensureFuturePartitions()`** — for all three tables, ensures a partition exists for the current UTC month and each of the next `analytics.partitioning.look-ahead-months` months. Existence is checked by **range coverage**, not name — the current month has no dedicated partition at all for as long as `_p_legacy` remains unpruned (it's covered by legacy's wide range), so a name-only check would try to `CREATE` an overlapping range and fail.
2. **`dropExpiredPartitions()`** — a no-op entirely when `analytics.partitioning.retention-months` is `0` (the default: retention is opt-in). Otherwise, removes (drops, or detaches when `archive-instead-of-drop=true`) any partition whose upper bound is at or before the retention horizon, never the DEFAULT partition or one containing "now," capped at `AnalyticsPartitioningConstants.MAX_REMOVALS_PER_RUN` removals per run. `test_case_run_results` is evaluated independently. **`test_case_eval_summaries` and `test_case_eval_scores` are always removed as a pair** — the same month, the same mode (both dropped or both detached), in the same cycle. If removing either side fails, neither side is removed that cycle. There is no separate row-level "reaping" step for `test_case_eval_scores` — an earlier design draft that reaped it via a row-level `DELETE` scoped to the summaries partition (gated to only run on drop, never on archive) is superseded entirely by this pairing.
3. **`reportDefaultPartitionUsage()`** — logs a WARN with each table's `_p_default` row count, so operators notice if real data is landing outside every explicit partition before it becomes a real problem.

Ordering is always create-ahead → drop → report; a failure in one phase is logged and swallowed, never preventing the next phase from running.

## DST correctness note

Month-boundary arithmetic (in both `V1.20` and the pure `MonthlyPartitionBounds` helper) must never add a calendar-unit interval to a `timestamptz` value — Postgres applies such an interval using the **connection's session timezone**, so on a non-UTC session timezone, adding months across a DST transition silently shifts the result by an hour relative to the intended UTC boundary. `V1.20` does all month arithmetic on naive (`timestamp without time zone`) values, converting to `timestamptz`/epoch-millis only at the final extraction point, which has no timezone attached and therefore no DST rule to apply.

## Rollback runbook

Not a Flyway `U` migration (the project is forward-only). Per table: disable `analytics.partitioning.enabled` first. `DETACH` every partition including `_p_legacy`, `DROP` the now-empty partitioned parent, `RENAME` `_p_legacy` back to the original table name and its constraints/indexes, drop the temporary upper-bound `CHECK` constraint, then `INSERT ... SELECT` each other detached partition's rows back in and drop them. For `test_case_eval_scores` specifically, also `DROP COLUMN created_at_ms` and restore the original single-column PK and `ON CONFLICT` arbiter, reversing the write-path/JOIN changes as well as the partitioning itself. Follow with a forward Flyway migration that is a no-op when the table is already unpartitioned, so `flyway_schema_history` stays consistent for subsequent deploys.

## Operational verification vs. CI

CI includes one advisory `EXPLAIN`-based test asserting a run-scoped query touches a bounded number of partitions (a containment check, not exact plan-string matching, since plan shape is version- and statistics-sensitive). Deeper pruning validation — real data volumes, `EXPLAIN ANALYZE` timings, `pg_stat_user_tables` per partition — belongs in an operational runbook, not automated tests.
