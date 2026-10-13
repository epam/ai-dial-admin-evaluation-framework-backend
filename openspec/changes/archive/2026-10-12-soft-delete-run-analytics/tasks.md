## 1. Schema: tombstone table

- [x] 1.1 Add Flyway migration `V1.22__CreateRunDeletionsTable.sql` (analytics DB) creating `run_deletions(test_suite_run_id VARCHAR(36) PRIMARY KEY, deleted_at_ms BIGINT NOT NULL)`, no foreign keys
- [x] 1.2 Run `./gradlew generateJooq`; commit the generated diff under `src/main/java-generated/` (new `RUN_DELETIONS` table class)
- [x] 1.3 Update `docs/database-schema.md` with the new `run_deletions` table and the read-path exclusion rule

## 2. Tombstone write path

- [x] 2.1 Add `RunDeletionRepository`/`PostgresRunDeletionRepository` (`data.db.analytics.repository`) with an idempotent `insert(UUID testSuiteRunId, long deletedAtMs)` using `INSERT ... ON CONFLICT (test_suite_run_id) DO NOTHING`, following the existing `saveAll` idiom (`@ConditionalOnProperty(datasource.analytics.vendor=POSTGRES)`, `@Qualifier("analyticsDsl")`, `@LogExecution`)
- [x] 2.2 Add `RunDeletionService` (`service.domain.analytics`) with `markDeleted(UUID runId)`, `@Transactional("analyticsTransactionManager")`, `@LogExecution`, delegating to the repository
- [x] 2.3 Add `TestSuiteRunRepository.findIdsByTestSuiteId(UUID suiteId)` (and its Postgres implementation) returning the suite's run ids
- [x] 2.4 Add unit tests for `RunDeletionService`/`RunDeletionRepository` covering a first insert and an idempotent repeated insert for the same run id

## 3. Wire tombstone writes into both deletion paths

- [x] 3.1 Inject `RunDeletionService` into `TestSuiteRunService`; in `deleteRun`, call `markDeleted(runId)` immediately before the existing `testSuiteRunRepository.deleteById(runId)` call, inside the existing `@Transactional("metaTransactionManager")` method
- [x] 3.2 Add `TestSuiteRunService.tombstoneAllRunsForSuite(UUID suiteId)` — looks up the suite's run ids via `findIdsByTestSuiteId` and calls `RunDeletionService.markDeleted` for each
- [x] 3.3 Call `tombstoneAllRunsForSuite` from `TestSuiteService.delete`'s existing `TransactionTemplate` block, before `testSuiteRepository.deleteById(id)` fires the cascade
- [x] 3.4 Unit test: `deleteRun` calls the tombstone write before the meta delete, and a tombstone-write failure prevents the meta delete from being attempted
- [x] 3.5 Functional test (`PostgresFunctionalTests`): deleting a terminal run with existing analytics rows (across all five tables) excludes those rows from every affected read path afterward, and leaves a live run's rows visible
- [x] 3.6 Functional test: deleting a suite with one or more runs excludes each of those runs' analytics rows from every affected read path afterward (covers the suite-cascade path, not just single-run deletion)
- [x] 3.7 Functional test: re-writing an existing tombstone is a no-op and the run stays excluded (a true mid-delete failure cannot be injected without test-only seams)

## 4. Filter reads by tombstone predicate

- [x] 4.1 Add `RunDeletionFilter.notDeleted(Field<String>)` (`data.db.analytics.repository`) — the single `NOT EXISTS (… run_deletions …)` definition
- [x] 4.2 Add `StructuredQueryEntityResolver.baseCondition()` (default empty); `StructuredQueryBuilder.build` and `countRows` AND it onto the entity's select (subqueries go through `build`)
- [x] 4.3 Override `baseCondition()` in `PostgresEvalSummaryEntityResolver`, `PostgresTestCaseEvalScoreEntityResolver` (over its derived `SCORES` table), `PostgresTestCaseMetricScoreAggregatedEntityResolver`, `PostgresMetricScoreResultEntityResolver`; `table()` returns the base table
- [x] 4.4 `PostgresTestCaseRunResultRepository` (`findAll`, `findById`, `count`) AND the predicate
- [x] 4.5 `PostgresEvalSummaryRepository` (`findAllForExport*`, `findById`, `count`, `aggregate`, `countMatches`, `findUnmatched*`, `findLatestComputationId(s)`, `existsByRunIdAndComputationId`, and the other-run probe table) AND the predicate; `saveAll` untouched
- [x] 4.6 `PostgresTestCaseMetricScoreAggregatedRepository.findByRunIdAndComputationId` and `PostgresMetricScoreResultRepository.findByRunAndComputation` AND the predicate
- [x] 4.7 Verified via `EXPLAIN (ANALYZE)` on ~50k summary/score rows with 200k tombstones: run-scoped lookups on `test_case_eval_summaries`, the `test_case_eval_scores` derived select, and the `findLatestComputationId` shape all plan as the existing run index scan plus a `Nested Loop Anti Join` probing `run_deletions_pkey` (Index Only Scan); multi-run `IN` queries use a `Hash Anti Join`
- [x] 4.8 Regression: the existing query/repository functional suites pass unchanged (live rows still returned)

## 5. Drift-prevention and behavioural tests

- [x] 5.1 `JooqSchemaDriftTest`: every analytics base table with a `test_suite_run_id` column must be registered in `TOMBSTONE_FILTERED_TABLES` (or the frozen set)
- [x] 5.2 Rewrite `RunDeletionFunctionalTests` to seed all five tables for a deleted run and a live control run and assert, via production repository reads and every run-scoped Query DSL entity, that only the deleted run disappears (single-run delete, suite delete, idempotent tombstone re-write)

## 6. Spec and documentation maintenance

- [x] 6.1 Update `openspec/specs/README.md` per the Spec Index Maintenance Policy — add the new `run-analytics-deletion` spec folder entry
- [x] 6.2 Add a new pattern doc (e.g. `docs/patterns/run-deletion-tombstone.md`) describing the tombstone-table + `RunDeletionFilter` predicate mechanism, and add it to `docs/patterns/README.md` and the AGENTS.md "Unique Patterns" table — this is a new, reusable cross-cutting pattern, not a one-off feature
- [x] 6.3 Update `openspec/config.yaml` per the Config Maintenance Policy — note the new tombstone + shared-predicate convention as the project's pattern for soft-visibility exclusion on append-only analytics data
- [x] 6.4 Note: the delta specs under this change's `specs/` sync into `openspec/specs/` at archive time per this project's `rules.archive` checklist (see `CLAUDE.md`) — no separate sync task needed here
