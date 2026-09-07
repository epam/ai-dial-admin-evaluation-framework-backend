# Analytics Time-Based Partitioning

## Purpose
This spec defines native PostgreSQL declarative RANGE partitioning of the three high-volume analytics tables (`test_case_run_results`, `test_case_eval_summaries`, `test_case_eval_scores`) by `created_at_ms`, monthly, so that read/write performance and future retention are bounded by partition count rather than total row count, with zero application-visible change to REST contracts or query results.

Status: **Implemented**

## Key Terms
- **Monthly partition**: A native PostgreSQL RANGE partition covering one calendar UTC month, named `<table>_p<yyyyMM>`.
- **`_p_legacy` partition**: The single wide partition holding all pre-cutover data for a table, created by attaching the original (pre-migration) table as-is.
- **`_p_default` partition**: The catch-all DEFAULT partition that accepts any row whose `created_at_ms` falls outside every explicitly created monthly partition.
- **Cutover**: The `now()`-derived boundary computed once at migration time, shared by all three tables, separating `_p_legacy` from the first real monthly partition.
- **Paired removal**: The rule that `test_case_eval_summaries` and `test_case_eval_scores` partitions for the same month are always created and removed together, in the same mode, since the two tables are 1:1 siblings on identical partition boundaries.

## Requirements

### Requirement: Monthly range partitioning of high-volume analytics tables
`test_case_run_results`, `test_case_eval_summaries`, and `test_case_eval_scores` SHALL be native PostgreSQL declarative RANGE-partitioned tables, partitioned by `created_at_ms`, with monthly partition boundaries expressed in UTC. For `test_case_eval_summaries` and `test_case_eval_scores`, partition boundaries SHALL be identical (the same set of months).

Status: **Implemented**

#### Scenario: Row routes to the correct monthly partition
- **WHEN** a row is inserted into `test_case_run_results`, `test_case_eval_summaries`, or `test_case_eval_scores` with a given `created_at_ms`
- **THEN** the row SHALL be stored in the partition whose UTC month range contains that `created_at_ms`, transparently to the writing code (no application-level partition selection)

#### Scenario: Existing constraints and indexes preserved on partitioned tables
- **WHEN** the tables are partitioned
- **THEN** the primary key on each table (`(created_at_ms, id)` for `test_case_run_results` and `test_case_eval_summaries`, `(created_at_ms, eval_summary_id)` for `test_case_eval_scores`), the unique constraint/index trailing in `created_at_ms` on the first two, and all pre-existing secondary indexes SHALL remain enforced and queryable, inherited by every partition

#### Scenario: Out-of-range timestamp still succeeds
- **WHEN** a row's `created_at_ms` falls outside every explicitly created monthly partition
- **THEN** the write SHALL succeed by routing into a DEFAULT partition rather than failing

#### Scenario: Score row remains correctly associated with its summary after partitioning
- **WHEN** a `test_case_eval_scores` row is read via a JOIN to its parent `test_case_eval_summaries` row, whether the summary row predates the partitioning migration or was written after it
- **THEN** the JOIN SHALL correctly match the score row to its summary row, using both `eval_summary_id` and `created_at_ms`

### Requirement: Partitioning is transparent to read paths
Cursor-based pagination, filtering, and export queries against `test_case_run_results` and `test_case_eval_summaries` SHALL return identical results and ordering after partitioning as before partitioning. No REST API or DTO contract change is permitted.

Status: **Implemented**

#### Scenario: Cursor pagination spans multiple partitions
- **WHEN** a client pages through list results whose underlying rows span more than one monthly partition
- **THEN** the returned sequence SHALL be ordered by `created_at_ms DESC, id DESC` with no duplicate or missing rows across the partition boundary, identical to the pre-partitioning behavior

#### Scenario: Run-scoped query prunes to a single partition
- **WHEN** a query filters by a specific run's `created_at_ms` (e.g. `findAll` with `runCreatedAtMs` supplied)
- **THEN** the query SHALL be satisfiable by scanning only the partition(s) covering that timestamp

### Requirement: Latest-computation resolution carries a partition-pruning predicate
Resolution of a run's latest metric computation (`findLatestComputationId`, `existsByRunIdAndComputationId`, and `ComputationResolver.resolve`) SHALL accept the run's `created_at_ms` as an optional predicate, applied whenever the caller has that value available, so the query can prune to the relevant partition instead of scanning every partition of `test_case_eval_summaries`.

Status: **Implemented**

#### Scenario: Caller supplies the run's creation timestamp
- **WHEN** `EvalSummaryService` or `EvalSummaryExportService` resolves a run's latest computation and already has the run's `created_at_ms` loaded
- **THEN** it SHALL pass that value through to `ComputationResolver.resolve`, which SHALL add it as an equality predicate on `created_at_ms`

#### Scenario: Caller without the timestamp still resolves correctly
- **WHEN** a caller (e.g. the Query DSL's `MetricScoreLatestComputationDefaulter`, or `RunComparisonService.requireComputation`) does not pass the run's `created_at_ms`
- **THEN** resolution SHALL still return the correct latest computation id, scanning across partitions as needed, with no change in result correctness

### Requirement: Proactive partition creation ahead of writes
The service SHALL run a scheduled job that ensures monthly partitions exist for the current month and a configurable number of look-ahead months, for all three partitioned tables, so that writes never fail due to a missing (non-default) partition for a near-future timestamp.

Status: **Implemented**

#### Scenario: Future partition created before it is needed
- **WHEN** the partition-maintenance job runs
- **THEN** it SHALL ensure a partition exists for the current UTC month and each of the next `analytics.partitioning.look-ahead-months` months, for `test_case_run_results`, `test_case_eval_summaries`, and `test_case_eval_scores`, creating any that are missing

#### Scenario: Idempotent on repeated runs
- **WHEN** the partition-maintenance job runs again and the required partitions already exist
- **THEN** it SHALL make no schema changes and SHALL NOT error

#### Scenario: Partition maintenance disabled
- **WHEN** `analytics.partitioning.enabled` is `false`
- **THEN** the job SHALL perform no partition creation, drop, or detach operations

### Requirement: Configurable retention via partition drop or detach
The service SHALL support dropping (deleting) or detaching (archiving) partitions whose entire time range is older than a configurable retention window, as a metadata-level operation rather than row-level `DELETE`. Retention SHALL be disabled by default.

Status: **Implemented**

#### Scenario: Retention disabled by default
- **WHEN** `analytics.partitioning.retention-months` is `0` (the default)
- **THEN** the partition-maintenance job SHALL never drop or detach any partition, regardless of its age

#### Scenario: Expired partition dropped
- **WHEN** `analytics.partitioning.retention-months` is set to a positive value and a partition's entire range is older than that many months before the current UTC month
- **THEN** the partition-maintenance job SHALL remove that partition (drop, or detach when `analytics.partitioning.archive-instead-of-drop` is `true`) without issuing a row-level `DELETE`

#### Scenario: Current and default partitions are never removed
- **WHEN** the partition-maintenance job evaluates partitions for expiry
- **THEN** it SHALL never drop or detach the partition containing the current UTC month, nor the DEFAULT partition, regardless of retention configuration

#### Scenario: Bounded work per maintenance run
- **WHEN** more partitions are eligible for removal than a configured per-run cap
- **THEN** the job SHALL remove at most that many partitions in a single run and SHALL process the remainder on a subsequent run
- **NOTE**: for `test_case_eval_summaries`/`test_case_eval_scores`, the cap counts one unit per expired month (i.e. per pair removed), not per physical partition — removing one expired month for that pair consumes the same one unit of the cap as removing one expired `test_case_run_results` partition, even though it removes two physical partitions

### Requirement: `test_case_eval_scores` partitions are removed in lockstep with their parent `test_case_eval_summaries` partition
For a given expired month, the `test_case_eval_summaries` partition and the `test_case_eval_scores` partition covering that same month SHALL be removed together, using the same removal mode (both dropped, or both archived), in the same maintenance cycle. Neither partition SHALL be removed independently of the other.

Status: **Implemented**

#### Scenario: Paired partitions dropped together
- **WHEN** the partition-maintenance job permanently drops a `test_case_eval_summaries` partition for a given month (`analytics.partitioning.archive-instead-of-drop` is `false`)
- **THEN** it SHALL also permanently drop the `test_case_eval_scores` partition covering that same month, in the same maintenance cycle

#### Scenario: Paired partitions archived together
- **WHEN** the partition-maintenance job archives (detaches) a `test_case_eval_summaries` partition for a given month (`analytics.partitioning.archive-instead-of-drop` is `true`)
- **THEN** it SHALL also archive (detach) the `test_case_eval_scores` partition covering that same month, in the same maintenance cycle, and both detached tables' data SHALL remain intact and queryable

#### Scenario: Removal failure on one side blocks removal of the paired partition
- **WHEN** removing either the `test_case_eval_summaries` partition or the paired `test_case_eval_scores` partition for a given month fails
- **THEN** the partition-maintenance job SHALL NOT remove the other partition of the pair in that cycle, and SHALL retry both on a subsequent run

## Implementation Notes
- Migration: `src/main/resources/db/migration/analytics/POSTGRES/V1.20__PartitionAnalyticsHighVolumeTables.sql` — ATTACH-based cutover with a `_p_legacy` partition for all three tables, computed from one shared `now()`-derived cutover at migration time; `test_case_eval_scores` additionally gets a new `created_at_ms` column backfilled via `UPDATE ... FROM test_case_eval_summaries` and a new composite PK `(created_at_ms, eval_summary_id)` before the ATTACH step. See `docs/patterns/analytics-time-partitioning.md`.
- New components: `AnalyticsPartitionMaintenanceJob`, `AnalyticsPartitionMaintenanceService`, `MonthlyPartitionBounds` (`service.infrastructure.partition`); `AnalyticsPartitionRepository` / `PostgresAnalyticsPartitionRepository` (`data.db.analytics.repository` / `data.db.analytics.repository.partition`); `AnalyticsPartitioningProperties` (`configuration.properties.analytics`); `AnalyticsPartitioningConstants` (`constants`).
- Configuration: `analytics.partitioning.{enabled,look-ahead-months,retention-months,archive-instead-of-drop,maintenance-interval-ms,initial-delay-ms}` — see `docs/configuration.md`.
- jOOQ codegen excludes partition-child relations (for all three tables) via an updated `withExcludes` regex in `build.gradle`; the generated `Table` classes for `test_case_run_results`/`test_case_eval_summaries` are otherwise unchanged, while `test_case_eval_scores`'s generated classes pick up its new column and composite key.
- `findLatestComputationId` / `existsByRunIdAndComputationId` / `ComputationResolver.resolve` gain a nullable `runCreatedAtMs` parameter. `TestCaseEvalScoreService.batchCreate`, `PostgresTestCaseEvalScoreRepository.saveAll`, and all 5 `test_case_eval_scores`-to-`test_case_eval_summaries` JOIN sites are updated for the new composite key. `run_metric_snapshots` and `metric_score_result` remain unpartitioned in this change.
- `RunComparisonService.requireComputation` and `MetricScoreLatestComputationDefaulter` both deliberately omit `runCreatedAtMs` (accepted, documented cross-partition-scan gaps) — see `docs/patterns/analytics-time-partitioning.md` and `docs/patterns/computation-versioning.md`.
