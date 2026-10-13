# Run deletion tombstone

Deleting a test suite run (or a suite, which cascades to its runs) removes its meta-DB rows but must also make its **analytics** rows invisible. No foreign key spans the two databases, and analytics data is append-only (a future ClickHouse backend makes in-place update/delete expensive), so deletion is represented by *appending* a tombstone and filtering reads — never by updating or deleting result rows.

## The tombstone

`run_deletions(test_suite_run_id VARCHAR(36) PRIMARY KEY, deleted_at_ms BIGINT NOT NULL)` in the analytics DB, no foreign keys.

`RunDeletionService.markDeleted(UUID)` (`service.domain.analytics`, `@Transactional("analyticsTransactionManager")`) delegates to `RunDeletionRepository.insert`, an idempotent `INSERT … ON CONFLICT (test_suite_run_id) DO NOTHING`.

Two callers, both through the service (never the repository — cross-domain rule):

- `TestSuiteRunService.deleteRun` calls `markDeleted(runId)` **before** `testSuiteRunRepository.deleteById(runId)`.
- `TestSuiteService.delete` calls `TestSuiteRunService.tombstoneAllRunsForSuite(suiteId)` inside its `TransactionTemplate` block, **before** `testSuiteRepository.deleteById(id)` fires the `ON DELETE CASCADE` that removes the runs (that path never reaches `deleteRun`).

The tombstone commits in its own analytics transaction; cross-database writes cannot be atomic. If the meta delete then fails, the run's analytics rows are already hidden while its meta row survives, and the client simply retries — the repeated tombstone insert is a no-op. Failure ordering is therefore always safe in the retry direction; there is no reverse state (meta row gone, analytics visible).

## The read filter

`data.db.analytics.repository.RunDeletionFilter.notDeleted(Field<String> runIdColumn)` is the single definition: `NOT EXISTS (SELECT 1 FROM run_deletions WHERE run_deletions.test_suite_run_id = <column>)`. It is applied at two seams, and writes are never filtered:

- **Direct repository reads** — each read method of `PostgresTestCaseRunResultRepository`, `PostgresEvalSummaryRepository`, `PostgresTestCaseMetricScoreAggregatedRepository` and `PostgresMetricScoreResultRepository` ANDs it into its own `where`. In `PostgresEvalSummaryRepository` this includes the run-comparison queries and the *other* run's probe table.
- **Query DSL** — `StructuredQueryEntityResolver.baseCondition()` (default empty). `StructuredQueryBuilder.build` and `countRows` AND it onto the select of the entity's `table()`; subqueries compile through `build`, so they are covered too. The four run-scoped resolvers (`eval_summaries`, `test_case_eval_scores`, `test_case_metric_scores`, `metric_score_results`) return `notDeleted(...)`. `test_case_eval_scores` references its derived `SCORES` table's `test_suite_run_id` column, so the predicate lands on the outer select.

Entity names, column sets and generated jOOQ classes are unchanged.

Deliberately **unfiltered**: `TestCaseScoreComputer` and the metric aggregators, which compute for the live run in progress, and the frozen analytics `run_metric_snapshots` table.

## Guard against drift

Whether a read is filtered is a code-level property, so two tests stand behind it:

- `JooqSchemaDriftTest.everyAnalyticsTableWithRunIdIsTombstoneFiltered` fails when an analytics base table with a `test_suite_run_id` column is in neither `TOMBSTONE_FILTERED_TABLES` nor the frozen set.
- `RunDeletionFunctionalTests` seeds all five tables for a deleted run and a live control run, then asserts through the production repository methods and every run-scoped Query DSL entity that only the deleted run disappears (single-run delete, suite delete, repeated tombstone write). It must not use the raw-table back-door helpers (`AnalyticsTestDataHelper.find*ByRunId`), which still see tombstoned rows.

**Adding a run-scoped analytics table:** AND `notDeleted(...)` into every read path, override `baseCondition()` if it backs a Query DSL entity, extend `RunDeletionFunctionalTests`, then register it in `TOMBSTONE_FILTERED_TABLES`.

## Performance

Verified with `EXPLAIN (ANALYZE)` on ~50k summary/score rows and 200k tombstones: run-scoped lookups plan as the existing run index scan plus a `Nested Loop Anti Join` probing `run_deletions_pkey` (Index Only Scan); multi-run `IN` queries use a `Hash Anti Join`. With only a handful of tombstones the planner seq-scans `run_deletions`, which is correct at that size.

## Out of scope

No physical reclamation or retention job (`run_deletions` and the rows it hides persist), and no backfill for runs deleted before this shipped. Full rationale: `openspec/changes/soft-delete-run-analytics/design.md`.
