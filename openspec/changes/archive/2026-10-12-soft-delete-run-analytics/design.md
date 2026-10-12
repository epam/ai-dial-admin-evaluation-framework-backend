## Context

`TestSuiteRunService.deleteRun` is a meta-DB-only hard delete, scoped by `@Transactional("metaTransactionManager")`. It validates the run is terminal, then calls `testSuiteRunRepository.deleteById(runId)`, which cascades within Postgres to meta-DB child tables (`run_metric_snapshots`, `test_case_run_inputs`). It has zero analytics-DB awareness today — no analytics repository or service is injected into `TestSuiteRunService`.

Five analytics-DB tables carry a run-identifying column (`test_suite_run_id VARCHAR(36) NOT NULL`, no physical FK — cross-database) and are never touched by `deleteRun`:

| Table | Read via Query DSL? | Read via direct repository? |
|---|---|---|
| `test_case_run_results` | No (no `StructuredQueryEntityResolver` exists for it) | Yes — `PostgresTestCaseRunResultRepository` (`findAll`, `findById`, `count`) |
| `test_case_eval_summaries` | Yes — `PostgresEvalSummaryEntityResolver`, entity `eval_summaries`, bare table | Yes — `PostgresEvalSummaryRepository` (`findAllForExport*`, `findById`, `count`, `aggregate`, `countMatches`, `findUnmatchedIds`, etc.) |
| `test_case_eval_scores` | Yes — `PostgresTestCaseEvalScoreEntityResolver`, entity `test_case_eval_scores`, **derived** table (`SCORES` constant) | No direct-read methods — reads only via the Query DSL entity |
| `test_case_metric_scores_aggregated` | Yes — `PostgresTestCaseMetricScoreAggregatedEntityResolver`, entity `test_case_metric_scores`, bare table | Yes — `PostgresTestCaseMetricScoreAggregatedRepository.findByRunIdAndComputationId` |
| `metric_score_result` | Yes — `PostgresMetricScoreResultEntityResolver`, entity `metric_score_results`, bare table | Yes — `PostgresMetricScoreResultRepository.findByRunAndComputation` |

(The analytics-DB `run_metric_snapshots` table is explicitly **frozen** — superseded by the meta-DB table of the same name — and is out of scope.)

Each table is owned (for writes) by its own small analytics-domain service (`EvalSummaryService`, `TestCaseEvalScoreService`, `TestCaseMetricScoreAggregatedService`, `MetricScoreService`, `AnalyticsResultService`), all under `service.domain.analytics`, all `@Transactional("analyticsTransactionManager")`. None of them owns a cross-cutting "this run no longer exists" concept — each is scoped to exactly one table's write path.

## Goals / Non-Goals

**Goals:**
- A deleted test suite run's rows in all five analytics tables above become unreadable through every existing read path (Query DSL entities and direct repository reads alike) immediately after `deleteRun` returns.
- No existing analytics row is ever updated — "deletion" is represented purely by an append (a tombstone row), preserving the append-only invariant that also underlies `computation_id` versioning.
- The exclusion predicate is defined once (`RunDeletionFilter.notDeleted`) and applied at a small, enumerable set of seams — each repository read method and one resolver-level base condition for the Query DSL — and a future run-scoped analytics table cannot be added without a registry test failing.
- Query DSL entity names and column sets (which the frontend consumes) are unchanged.
- Meta DB's `deleteRun` behavior for `test_suite_runs` itself is unchanged.

**Non-Goals:**
- No physical reclamation/retention job for tombstoned rows — `run_deletions` and the rows it marks persist indefinitely after this change.
- No backfill of tombstones for runs deleted before this change ships.
- No ClickHouse implementation — only the Postgres reference implementation, behind interface boundaries clean enough for a future ClickHouse implementation to satisfy without changing callers.
- No change to the frozen analytics `run_metric_snapshots` table.

## Decisions

**1. A tombstone table (`run_deletions`), not an `is_deleted` column on each analytics table.**
An `is_deleted` column requires an `UPDATE` of existing rows — the exact operation the append-only invariant (and a future ClickHouse backend, where updates are async mutations) is meant to avoid. It would also write-amplify per deletion: a run can fan out to many rows per table, so flagging all of them means an UPDATE whose cost scales with the run's result-set size, repeated across five tables. A single `run_deletions(test_suite_run_id, deleted_at_ms)` row per deletion is O(1) regardless of run size and needs no UPDATE anywhere.
*Alternative considered and rejected*: an `is_deleted` flag on the **meta** `test_suite_runs` row. Meta and analytics are separate datasources with no cross-DB join (`dual-datasource.md`), so a meta-side flag is invisible to every analytics read path — it does not solve the problem this change exists to fix.

**2. A `NOT EXISTS` anti-join predicate on every read, defined once in `RunDeletionFilter`.**
The predicate is added to each read query against a run-scoped table: `NOT EXISTS (SELECT 1 FROM run_deletions WHERE run_deletions.test_suite_run_id = <table>.test_suite_run_id)`. It is a primary-key point lookup per candidate row, and it leaves every table, generated jOOQ class, resolver binding and Query DSL entity exactly as it is.
No shared hook exists across `StructuredQueryEntityResolver` implementations or the analytics repositories (`StructuredQueryBuilder.build` applies only the caller's own filter on top of whatever `table()` a resolver returns), so enforcement is centralised at two seams:
- **Query DSL**: `StructuredQueryEntityResolver.baseCondition()` (default empty). `StructuredQueryBuilder.build`/`countRows` AND it onto the select of that entity's `table()`. Subqueries are compiled through `build`, so they are covered too. Resolvers over run-scoped tables return `RunDeletionFilter.notDeleted(<table>.TEST_SUITE_RUN_ID)`.
- **Direct repositories**: each read method ANDs `RunDeletionFilter.notDeleted(...)` into its own `where`; writes are never filtered.
*Trade-off accepted*: whether a read is filtered is a code-level property, so a new read method that forgets the helper would expose deleted data. Decision 5 is the backstop.

**3. A new, dedicated `RunDeletionService` (and matching `RunDeletionRepository`) in `service.domain.analytics` / `data.db.analytics.repository`, rather than bolting the tombstone write onto an existing analytics service.**
Each existing analytics-domain service (`EvalSummaryService`, `TestCaseEvalScoreService`, `TestCaseMetricScoreAggregatedService`, `MetricScoreService`, `AnalyticsResultService`) is scoped to exactly one table's write path; none naturally owns a cross-cutting "mark this run deleted" concept. A dedicated service keeps each existing service's responsibility unchanged and gives `TestSuiteRunService` one clear analytics-domain dependency to call, consistent with the cross-domain-service rule (`TestSuiteRunService` must call an analytics-domain service, never an analytics repository directly).

**4. Tombstone insert happens first, synchronously, inside `deleteRun`'s existing control flow — before the meta hard delete, not after and not asynchronously.**
`RunDeletionService.markDeleted(runId)` is `@Transactional("analyticsTransactionManager")` and commits independently of the meta transaction (cross-DB writes cannot be atomic — `dual-datasource.md`). `TestSuiteRunService.deleteRun` calls it first; only if it succeeds does the method proceed to `testSuiteRunRepository.deleteById(runId)` under the existing `@Transactional("metaTransactionManager")`. The insert is idempotent (`INSERT ... ON CONFLICT (test_suite_run_id) DO NOTHING`, following the exact pattern already used by all five existing analytics `saveAll` methods), so a client retry after any failure is always safe — see Risks.

**5. Enforcement: a registry test plus behavioural functional tests.**
`JooqSchemaDriftTest` lists every analytics base table with a `test_suite_run_id` column and asserts each is in an explicit `TOMBSTONE_FILTERED_TABLES` set (or the frozen `run_metric_snapshots` set); an unregistered table fails the build and the message says what to do. Registration is only honest if the table is exercised, so `RunDeletionFunctionalTests` seeds all five tables for a deleted run and a live control run and asserts, through the production repository methods and every Query DSL entity, that the deleted run is invisible and the live run is not. The functional tests deliberately do not use the raw-table back-door helpers, which still see tombstoned rows.

**6. `test_case_run_results` is filtered too, even though it has no Query DSL entity.**
It is read directly by `PostgresTestCaseRunResultRepository` (`findAll`, `findById`, `count`). "Fully excluded everywhere" means every independent read path, not only Query DSL-exposed ones.

**7. `PostgresTestCaseEvalScoreEntityResolver` keeps its derived `SCORES` projection over the base table.**
Its `baseCondition()` references the derived table's `test_suite_run_id` column, so the predicate is applied on the outer select; the planner flattens the simple subquery. `TestCaseScoreComputer` and the metric aggregators read the live run being computed and are intentionally unfiltered.

**8. Suite deletion also writes a tombstone for each of its runs, via a new `TestSuiteRunService` method called from `TestSuiteService.delete` before the suite row is removed.**
`TestSuiteService.delete` deletes a suite via `testSuiteRepository.deleteById(id)` inside a `TransactionTemplate`; the suite's runs are removed purely by the existing `test_suite_runs.test_suite_id ... ON DELETE CASCADE` FK — there is no Java-level loop over the suite's runs today, so `TestSuiteRunService.deleteRun` (and therefore the new tombstone write) is never invoked on this path. Since this path genuinely deletes run rows from meta today, its analytics rows must be tombstoned the same as single-run deletion, or "fully excluded everywhere" would silently fail for the (likely more common) suite-cleanup path.
Fix: a new repository method (`TestSuiteRunRepository.findIdsByTestSuiteId(UUID)`) plus a new `TestSuiteRunService.tombstoneAllRunsForSuite(UUID suiteId)` method that looks up the suite's run ids and calls `RunDeletionService` for each. `TestSuiteService.delete` calls this new method — respecting the cross-domain-service rule (`TestSuiteService` calls `TestSuiteRunService`, never `TestSuiteRunRepository` or `RunDeletionService` directly) — inside its existing `TransactionTemplate` block, before `testSuiteRepository.deleteById(id)` fires the cascade.

## Risks / Trade-offs

- **[Risk]** Meta delete fails *after* the tombstone commit (rare — a single-row delete with existing cascades, but not impossible) → the run's analytics data becomes invisible while its meta row still exists in a terminal, non-deleted state. **Mitigation**: the tombstone insert is idempotent, so retrying `deleteRun` is always safe — it re-attempts (now redundantly) the tombstone insert (no-op) and proceeds to the meta delete. No new recovery mechanism is needed beyond "the client can call delete again."
- **[Risk]** Suite delete (decision 8) writes one tombstone per run via `tombstoneAllRunsForSuite`, each committing independently in the analytics DB, before the meta `testSuiteRepository.deleteById` runs inside its own transaction. If the meta transaction then fails or rolls back (e.g. a PRIVATE dataset cascade throws), or if a failure occurs partway through the tombstone list, the suite and its runs survive while some or all of their analytics data is hidden. **Accepted, with mitigation**: tombstone insert is idempotent (`ON CONFLICT DO NOTHING`), so the client retrying `DELETE /api/v1/test-suites/{id}` is always safe — already-written tombstones are no-ops, any missing ones are written, and the meta delete then completes. No additional recovery mechanism is provided; until the retry succeeds the surviving runs' analytics data stays hidden.
- **[Risk]** Five tables × (resolver and/or repository) is a wider surface than the proposal's "and any others identified during design" implied, and nothing structural stops a new read method from skipping the predicate. **Mitigation**: one shared `RunDeletionFilter` and one resolver SPI hook; the resolver/repository changes land as one task group, and the registry test plus the behavioural functional tests are the backstop.
- **[Risk]** The anti-join over `PostgresTestCaseEvalScoreEntityResolver`'s derived table and over aggregate/comparison queries might not plan as cleanly at scale. **Mitigation**: verify via `EXPLAIN` during implementation, per the precedent in `docs/patterns/test-suite-runs-query-entity.md`.
- **[Risk]** `run_deletions` grows unboundedly with no cleanup (explicitly deferred). **Mitigation**: it's bounded by the number of *deleted runs*, inherently far smaller than the per-row analytics tables it guards, and each anti-join is a point lookup on its primary key (`test_suite_run_id`) regardless of its size.
- **[Trade-off]** Accepting this scope now (5 tables, new service, new filter seams, new tests) instead of a narrower Postgres-only hard-delete is a deliberate bet that the ClickHouse migration is a real near/medium-term plan — see the proposal's "Why." If that plan changes, this change is still correct and self-contained; it simply wouldn't have been the cheapest option in retrospect.
- **[Risk]** `TestSuiteService.delete` and `TestSuiteRunService.deleteRun` are two independent call sites that must each reach the tombstone-write path correctly, with no shared enforcement that a future third deletion path (if one is ever added) also does so. **Mitigation**: both route through the same `RunDeletionService`, keeping the actual tombstone-write logic in one place even though it has two callers; a functional test SHOULD cover suite deletion's effect on analytics visibility explicitly, not only single-run deletion.

## Migration Plan

1. Flyway migration `V1.22__CreateRunDeletionsTable.sql` (analytics DB) — creates `run_deletions(test_suite_run_id VARCHAR(36) PRIMARY KEY, deleted_at_ms BIGINT NOT NULL)`.
2. `./gradlew generateJooq`; commit the generated diff under `src/main/java-generated/` (new `RUN_DELETIONS` table class).
3. Add `RunDeletionRepository`/`PostgresRunDeletionRepository` (idempotent insert) and `RunDeletionService` (`service.domain.analytics`), following the existing repository/service conventions (`@LogExecution`, `@ConditionalOnProperty(datasource.analytics.vendor=POSTGRES)`, `@Qualifier("analyticsDsl")`).
4. Inject `RunDeletionService` into `TestSuiteRunService`; call `markDeleted(runId)` immediately before the existing `testSuiteRunRepository.deleteById(runId)` call in `deleteRun`. Add `TestSuiteRunRepository.findIdsByTestSuiteId(UUID)` and `TestSuiteRunService.tombstoneAllRunsForSuite(UUID suiteId)`; call it from `TestSuiteService.delete`'s existing `TransactionTemplate` block, before `testSuiteRepository.deleteById(id)`.
5. Add `RunDeletionFilter`; add `StructuredQueryEntityResolver.baseCondition()` applied by `StructuredQueryBuilder`; override it in the four run-scoped resolvers; AND `RunDeletionFilter.notDeleted` into the read methods of `PostgresTestCaseRunResultRepository`, `PostgresEvalSummaryRepository`, `PostgresTestCaseMetricScoreAggregatedRepository` and `PostgresMetricScoreResultRepository`.
6. Add the registry test and rewrite the deletion functional tests to assert through production read paths.
7. Update `docs/database-schema.md` with the new table and the exclusion rule.

**Rollback**: the change is purely additive at the schema level (one new table, no altered columns) and additive in application code (new service/repository, new call in `deleteRun`, resolver `table()` still returns the base table, with the `RunDeletionFilter` predicate ANDed on via `baseCondition()` and the repository `where` clauses). Reverting is a straightforward revert of the migration and code — there is no backfilled or migrated data to unwind, since this change deliberately does not backfill historical deletions.

## Open Questions

- Should `RunDeletionService` expose a read method (e.g. `isDeleted(runId)`) for any future internal caller, or stay write-only since all visibility is enforced by `RunDeletionFilter` on the read side? Proposed: write-only for now (YAGNI); add a read method only when a concrete caller needs it.
- ~~Location of the drift test~~ — resolved: `JooqSchemaDriftTest`.
