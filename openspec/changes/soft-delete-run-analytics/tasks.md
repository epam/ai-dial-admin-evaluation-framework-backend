## 1. Schema: tombstone table and active views

- [x] 1.1 Add Flyway migration `V1.22__CreateRunDeletionsTable.sql` (analytics DB) creating `run_deletions(test_suite_run_id VARCHAR(36) PRIMARY KEY, deleted_at_ms BIGINT NOT NULL)`, no foreign keys
- [x] 1.2 In the same migration, create the five `<table>_active` views (`test_case_run_results_active`, `test_case_eval_summaries_active`, `test_case_eval_scores_active`, `test_case_metric_scores_aggregated_active`, `metric_score_result_active`), each `SELECT *` from its base table anti-joined (`NOT EXISTS`/`LEFT JOIN ... IS NULL`) against `run_deletions` on `test_suite_run_id`
- [x] 1.3 Run `./gradlew generateJooq`; commit the generated diff under `src/main/java-generated/` (new `RUN_DELETIONS` table class plus the five `*_ACTIVE` view classes)
- [x] 1.4 Update `docs/database-schema.md` with the new `run_deletions` table and the five `_active` views

## 2. Tombstone write path

- [x] 2.1 Add `RunDeletionRepository`/`PostgresRunDeletionRepository` (`data.db.analytics.repository`) with an idempotent `insert(String testSuiteRunId, long deletedAtMs)` using `INSERT ... ON CONFLICT (test_suite_run_id) DO NOTHING`, following the existing `saveAll` idiom (`@ConditionalOnProperty(datasource.analytics.vendor=POSTGRES)`, `@Qualifier("analyticsDsl")`, `@LogExecution`)
- [x] 2.2 Add `RunDeletionService` (`service.domain.analytics`) with `markDeleted(UUID runId)`, `@Transactional("analyticsTransactionManager")`, `@LogExecution`, delegating to the repository
- [x] 2.3 Add `TestSuiteRunRepository.findIdsByTestSuiteId(UUID suiteId)` (and its Postgres implementation) returning the suite's run ids
- [x] 2.4 Add unit tests for `RunDeletionService`/`RunDeletionRepository` covering a first insert and an idempotent repeated insert for the same run id

## 3. Wire tombstone writes into both deletion paths

- [x] 3.1 Inject `RunDeletionService` into `TestSuiteRunService`; in `deleteRun`, call `markDeleted(runId)` immediately before the existing `testSuiteRunRepository.deleteById(runId)` call, inside the existing `@Transactional("metaTransactionManager")` method
- [x] 3.2 Add `TestSuiteRunService.tombstoneAllRunsForSuite(UUID suiteId)` — looks up the suite's run ids via `findIdsByTestSuiteId` and calls `RunDeletionService.markDeleted` for each
- [x] 3.3 Call `tombstoneAllRunsForSuite` from `TestSuiteService.delete`'s existing `TransactionTemplate` block, before `testSuiteRepository.deleteById(id)` fires the cascade
- [x] 3.4 Unit test: `deleteRun` calls the tombstone write before the meta delete, and a tombstone-write failure prevents the meta delete from being attempted
- [x] 3.5 Functional test (`PostgresFunctionalTests`): deleting a terminal run with existing analytics rows (across all five tables) excludes those rows from every affected read path afterward
- [x] 3.6 Functional test: deleting a suite with one or more runs excludes each of those runs' analytics rows from every affected read path afterward (covers the suite-cascade path, not just single-run deletion)
- [x] 3.7 Functional test: retrying `DELETE /api/v1/test-suite-runs/{id}` after a simulated tombstone-write failure succeeds without error (idempotent recovery)

## 4. Repoint reads at the active views

- [x] 4.1 `PostgresEvalSummaryEntityResolver.table()` → `TEST_CASE_EVAL_SUMMARIES_ACTIVE`
- [x] 4.2 `PostgresTestCaseEvalScoreEntityResolver`'s derived-table projection → `FROM TEST_CASE_EVAL_SCORES_ACTIVE` instead of the bare table, keeping its existing column projection/aliasing unchanged
- [x] 4.3 `PostgresTestCaseMetricScoreAggregatedEntityResolver.table()` → `TEST_CASE_METRIC_SCORES_AGGREGATED_ACTIVE`
- [x] 4.4 `PostgresMetricScoreResultEntityResolver.table()` → `METRIC_SCORE_RESULT_ACTIVE`
- [x] 4.5 `PostgresTestCaseRunResultRepository`'s direct-read methods (`findAll`, `findById`, `count`) → read from `TEST_CASE_RUN_RESULTS_ACTIVE`
- [x] 4.6 `PostgresEvalSummaryRepository`'s direct-read methods (`findAllForExport*`, `findById`, `count`, `aggregate`, `countMatches`, `findUnmatchedIds`, `findUnmatchedTestCaseIds`, `findLatestComputationId(s)`, `existsByRunIdAndComputationId`) → read from `TEST_CASE_EVAL_SUMMARIES_ACTIVE`; writes (`saveAll`) continue targeting the base table
- [x] 4.7 `PostgresTestCaseMetricScoreAggregatedRepository.findByRunIdAndComputationId` → read from `TEST_CASE_METRIC_SCORES_AGGREGATED_ACTIVE`
- [x] 4.8 `PostgresMetricScoreResultRepository.findByRunAndComputation` → read from `METRIC_SCORE_RESULT_ACTIVE`
- [x] 4.9 Verify via `EXPLAIN` that the view's anti-join is pulled up / stays index-backed for at least one high-traffic path (`test_case_eval_summaries_active` run-scoped lookup, and the layered `test_case_eval_scores_active` derived select), per the precedent in `docs/patterns/test-suite-runs-query-entity.md`
- [x] 4.10 Functional/unit test updates for each repository/resolver confirming reads still return non-deleted rows unchanged (regression coverage for the view swap itself, independent of the delete-exclusion tests in section 3)

## 5. Drift-prevention test

- [x] 5.1 Add a schema-introspection test (e.g. near `JooqSchemaDriftTest`) that queries `information_schema.columns` for every analytics table with a `test_suite_run_id` column and asserts a `<table>_active` view exists for each
- [x] 5.2 Run the test against the current schema and confirm it passes for all five run-scoped tables

## 6. Spec and documentation maintenance

- [ ] 6.1 Update `openspec/specs/README.md` per the Spec Index Maintenance Policy — add the new `run-analytics-deletion` spec folder entry
- [ ] 6.2 Add a new pattern doc (e.g. `docs/patterns/run-deletion-tombstone.md`) describing the tombstone-table + `_active`-view mechanism, and add it to `docs/patterns/README.md` and the AGENTS.md "Unique Patterns" table — this is a new, reusable cross-cutting pattern, not a one-off feature
- [ ] 6.3 Update `openspec/config.yaml` per the Config Maintenance Policy — note the new tombstone + active-view convention as the project's pattern for soft-visibility exclusion on append-only analytics data
- [ ] 6.4 Note (do not act on yet): the delta specs under this change's `specs/` sync into `openspec/specs/` at archive time per this project's `rules.archive` checklist (see `CLAUDE.md`) — no separate sync task needed here
