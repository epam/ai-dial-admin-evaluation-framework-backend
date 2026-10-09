## Context

`TestSuiteRunService.deleteRun` (`TestSuiteRunService.java:460-475`) is a meta-DB-only hard delete, scoped by `@Transactional("metaTransactionManager")`. It validates the run is terminal, then calls `testSuiteRunRepository.deleteById(runId)`, which cascades within Postgres to meta-DB child tables (`run_metric_snapshots`, `test_case_run_inputs`). It has zero analytics-DB awareness today — no analytics repository or service is injected into `TestSuiteRunService`.

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
- The mechanism that enforces exclusion lives at the schema level (a view), not scattered across application call sites, and a future run-scoped analytics table cannot be added without the enforcement test catching the gap.
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

**2. A `<table>_active` database view per table, not a `NOT EXISTS` predicate hand-added to each resolver/repository.**
No shared base class or filtering hook exists across `StructuredQueryEntityResolver` implementations, nor across the analytics repositories — each builds its own query independently (confirmed: `StructuredQueryBuilder.build` only applies the caller's own filter on top of whatever `table()` a resolver returns). Hand-adding the same anti-join in four resolvers and four repository classes is correct today but has no structural guard against a fifth run-scoped table being added later without it. A view turns "was this read path filtered?" from an application-code question into a schema question, answerable by a single `information_schema` query — see Decision 5.

**3. A new, dedicated `RunDeletionService` (and matching `RunDeletionRepository`) in `service.domain.analytics` / `data.db.analytics.repository`, rather than bolting the tombstone write onto an existing analytics service.**
Each existing analytics-domain service (`EvalSummaryService`, `TestCaseEvalScoreService`, `TestCaseMetricScoreAggregatedService`, `MetricScoreService`, `AnalyticsResultService`) is scoped to exactly one table's write path; none naturally owns a cross-cutting "mark this run deleted" concept. A dedicated service keeps each existing service's responsibility unchanged and gives `TestSuiteRunService` one clear analytics-domain dependency to call, consistent with the cross-domain-service rule (`TestSuiteRunService` must call an analytics-domain service, never an analytics repository directly).

**4. Tombstone insert happens first, synchronously, inside `deleteRun`'s existing control flow — before the meta hard delete, not after and not asynchronously.**
`RunDeletionService.markDeleted(runId)` is `@Transactional("analyticsTransactionManager")` and commits independently of the meta transaction (cross-DB writes cannot be atomic — `dual-datasource.md`). `TestSuiteRunService.deleteRun` calls it first; only if it succeeds does the method proceed to `testSuiteRunRepository.deleteById(runId)` under the existing `@Transactional("metaTransactionManager")`. The insert is idempotent (`INSERT ... ON CONFLICT (test_suite_run_id) DO NOTHING`, following the exact pattern already used by all five existing analytics `saveAll` methods), so a client retry after any failure is always safe — see Risks.

**5. Enforcement: a schema-introspection test, not a code-review convention.**
A test queries `information_schema.columns` for every analytics table with a `test_suite_run_id` column and asserts a `<table>_active` view exists for each (by naming convention). This is schema-anchored rather than Java-source-anchored, so it also catches a table that's read directly by a new repository method outside the Query DSL entirely — the same category of gap a purely resolver-focused check would miss.

**6. `test_case_run_results` gets an `_active` view too, even though it has no Query DSL entity.**
It's read directly by `PostgresTestCaseRunResultRepository` (export/listing paths). "Fully excluded everywhere" means every independent read path, not only Query DSL-exposed ones, so it's in scope on the same basis as the other four tables.

**7. `PostgresTestCaseEvalScoreEntityResolver`'s existing derived-table projection is retargeted to select `FROM test_case_eval_scores_active` instead of the bare `TEST_CASE_EVAL_SCORES` table**, keeping its existing column projection/aliasing logic unchanged — the view sits underneath the existing derived select, not beside it.

**8. Suite deletion also writes a tombstone for each of its runs, via a new `TestSuiteRunService` method called from `TestSuiteService.delete` before the suite row is removed.**
`TestSuiteService.delete` (`TestSuiteService.java:229-256`) deletes a suite via `testSuiteRepository.deleteById(id)` inside a `TransactionTemplate`; the suite's runs are removed purely by the existing `test_suite_runs.test_suite_id ... ON DELETE CASCADE` FK — there is no Java-level loop over the suite's runs today, so `TestSuiteRunService.deleteRun` (and therefore the new tombstone write) is never invoked on this path. Since this path genuinely deletes run rows from meta today, its analytics rows must be tombstoned the same as single-run deletion, or "fully excluded everywhere" would silently fail for the (likely more common) suite-cleanup path.
Fix: a new repository method (`TestSuiteRunRepository.findIdsByTestSuiteId(UUID)`) plus a new `TestSuiteRunService.tombstoneAllRunsForSuite(UUID suiteId)` method that looks up the suite's run ids and calls `RunDeletionService` for each. `TestSuiteService.delete` calls this new method — respecting the cross-domain-service rule (`TestSuiteService` calls `TestSuiteRunService`, never `TestSuiteRunRepository` or `RunDeletionService` directly) — inside its existing `TransactionTemplate` block, before `testSuiteRepository.deleteById(id)` fires the cascade.

## Risks / Trade-offs

- **[Risk]** Meta delete fails *after* the tombstone commit (rare — a single-row delete with existing cascades, but not impossible) → the run's analytics data becomes invisible while its meta row still exists in a terminal, non-deleted state. **Mitigation**: the tombstone insert is idempotent, so retrying `deleteRun` is always safe — it re-attempts (now redundantly) the tombstone insert (no-op) and proceeds to the meta delete. No new recovery mechanism is needed beyond "the client can call delete again."
- **[Risk]** Five tables × (resolver and/or repository) is a wider surface than the proposal's "and any others identified during design" implied, raising the chance of an inconsistent per-table approach. **Mitigation**: one Flyway migration creates all five views together; `tasks.md` will group the resolver/repository swaps as their own task group so they land as one reviewable unit, and the schema-introspection test is the backstop regardless of how the application-side wiring is split across tasks.
- **[Risk]** Layering a view under `PostgresTestCaseEvalScoreEntityResolver`'s already-derived table adds a second query layer; the Postgres planner might not pull the anti-join predicate up as cleanly at scale. **Mitigation**: verify via `EXPLAIN` during implementation, per the precedent in `docs/patterns/test-suite-runs-query-entity.md`.
- **[Risk]** `run_deletions` grows unboundedly with no cleanup (explicitly deferred). **Mitigation**: it's bounded by the number of *deleted runs*, inherently far smaller than the per-row analytics tables it guards, and each anti-join is a point lookup on its primary key (`test_suite_run_id`) regardless of its size.
- **[Trade-off]** Accepting this scope now (5 tables, new service, new views, new test) instead of a narrower Postgres-only hard-delete is a deliberate bet that the ClickHouse migration is a real near/medium-term plan — see the proposal's "Why." If that plan changes, this change is still correct and self-contained; it simply wouldn't have been the cheapest option in retrospect.
- **[Risk]** `TestSuiteService.delete` and `TestSuiteRunService.deleteRun` are two independent call sites that must each reach the tombstone-write path correctly, with no shared enforcement that a future third deletion path (if one is ever added) also does so. **Mitigation**: both route through the same `RunDeletionService`, keeping the actual tombstone-write logic in one place even though it has two callers; a functional test SHOULD cover suite deletion's effect on analytics visibility explicitly, not only single-run deletion.

## Migration Plan

1. Flyway migration `V1.22__CreateRunDeletionsTable.sql` (analytics DB) — creates `run_deletions(test_suite_run_id VARCHAR(36) PRIMARY KEY, deleted_at_ms BIGINT NOT NULL)` and the five `<table>_active` views (`test_case_run_results_active`, `test_case_eval_summaries_active`, `test_case_eval_scores_active`, `test_case_metric_scores_aggregated_active`, `metric_score_result_active`), each a `SELECT *` anti-joined against `run_deletions` on `test_suite_run_id`.
2. `./gradlew generateJooq`; commit the generated diff under `src/main/java-generated/`.
3. Add `RunDeletionRepository`/`PostgresRunDeletionRepository` (idempotent insert) and `RunDeletionService` (`service.domain.analytics`), following the existing repository/service conventions (`@LogExecution`, `@ConditionalOnProperty(datasource.analytics.vendor=POSTGRES)`, `@Qualifier("analyticsDsl")`).
4. Inject `RunDeletionService` into `TestSuiteRunService`; call `markDeleted(runId)` immediately before the existing `testSuiteRunRepository.deleteById(runId)` call in `deleteRun`. Add `TestSuiteRunRepository.findIdsByTestSuiteId(UUID)` and `TestSuiteRunService.tombstoneAllRunsForSuite(UUID suiteId)`; call it from `TestSuiteService.delete`'s existing `TransactionTemplate` block, before `testSuiteRepository.deleteById(id)`.
5. Repoint the four `StructuredQueryEntityResolver.table()` implementations and the direct-read methods on `PostgresTestCaseRunResultRepository`, `PostgresEvalSummaryRepository`, `PostgresTestCaseMetricScoreAggregatedRepository`, and `PostgresMetricScoreResultRepository` at the corresponding `_active` views.
6. Add the schema-introspection coverage test.
7. Update `docs/database-schema.md` with the new table and views.

**Rollback**: the change is purely additive at the schema level (new table, new views, no altered columns) and additive in application code (new service/repository, new call in `deleteRun`, resolver/repository targets swapped). Reverting is a straightforward revert of the migration and code — there is no backfilled or migrated data to unwind, since this change deliberately does not backfill historical deletions.

## Open Questions

- Should `RunDeletionService` expose a read method (e.g. `isDeleted(runId)`) for any future internal caller, or stay write-only since all visibility is enforced at the view layer? Proposed: write-only for now (YAGNI); add a read method only when a concrete caller needs it.
- Exact package/location for the new schema-introspection test — proposed alongside the existing schema-drift-guard-style tests (e.g. near `JooqSchemaDriftTest`), to be confirmed during `tasks.md`.
