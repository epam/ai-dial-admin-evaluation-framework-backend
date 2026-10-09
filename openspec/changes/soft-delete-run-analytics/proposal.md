## Why

Deleting a test suite run today only removes its meta-DB row (`test_suite_runs`, with FK cascade to `run_metric_snapshots` and `test_case_run_inputs`). The run's analytics-DB rows — `test_case_eval_summaries`, `test_case_eval_scores`, and any other run-scoped analytics table — are never touched, because no foreign key can span the meta and analytics databases. `docs/database-schema.md` already documents this as a known, permanent gap: those rows "must not be assumed reclaimed." Today they are also not excluded from any read — a deleted run's results remain fully visible through every analytics read path (Query DSL entities, exports, aggregates).

This matters more now because analytics is planned to move to ClickHouse in the near/medium term, where in-place deletion or update of existing rows is expensive and not immediately consistent (mutations are async; see `docs/patterns/computation-versioning.md`'s append-only invariant). Building a correct, backend-agnostic deletion story now — while analytics is still Postgres-only — avoids having to redesign the same feature twice when ClickHouse lands.

## What Changes

- Introduce a new append-only `run_deletions` table in the analytics DB (`run_id`, `deleted_at_ms`), one row per deleted run. No existing analytics row is ever updated — deletion is represented by appending a tombstone, not mutating result rows.
- `TestSuiteRunService.deleteRun` now also triggers an analytics-side tombstone write, via a new analytics-domain service method (not a direct repository injection — preserves the existing cross-domain-service rule). The tombstone insert happens before the meta-DB hard delete, inside the same `@Transactional("metaTransactionManager")` call: if the tombstone write fails, the meta transaction rolls back and the whole `deleteRun` call is safely retryable. The insert is idempotent (`INSERT ... ON CONFLICT DO NOTHING` on `run_id`).
- `TestSuiteService.delete` (suite deletion) also writes a tombstone for every run owned by the suite before deleting the suite row. Suite deletion today removes its runs purely via a DB-level `ON DELETE CASCADE` FK, bypassing `deleteRun` entirely — without this, suite-cascade-deleted runs' analytics rows would remain permanently visible despite both the run and the suite being gone.
- Meta DB's `test_suite_runs` deletion behavior is **unchanged** — it remains a real, synchronous hard delete with existing FK cascades. This change does not touch `PostgresTestSuiteRunRepository.deleteById` or the `test_suite_runs` Query DSL entity resolver (that resolver already reflects deletion correctly, since the meta row is simply gone).
- For every analytics table that carries a run-scoping column directly (`test_case_eval_summaries`, `test_case_eval_scores`, and any others identified during design), add a companion `<table>_active` database view that excludes tombstoned runs via an anti-join against `run_deletions`. Every analytics read path for those tables — Query DSL entity resolvers, direct repository reads, export/aggregate queries — is repointed at the `_active` view instead of the base table.
- Add a schema-introspection test asserting every analytics table with a run-scoping column has a matching `<table>_active` view, so a future run-scoped analytics table cannot be added without the exclusion view (prevents silent drift).
- **Out of scope for this change** (explicitly deferred):
  - No physical reclamation/retention job for tombstoned analytics rows — `run_deletions` and the underlying rows persist indefinitely after this change; a cleanup job is a separate, future change.
  - No backfill of tombstones for runs already deleted before this change ships (those orphans remain exactly as invisible/untracked as they are today).
  - No ClickHouse-specific implementation. This change only builds the Postgres reference implementation. The repository/analytics-service interface boundaries are kept clean so a future ClickHouse implementation can satisfy the same contract without changing callers (cross-domain service callers, Query DSL registry), but no ClickHouse code, schema, or resolver is introduced here.

## Capabilities

### New Capabilities
- `run-analytics-deletion`: the `run_deletions` tombstone table, the analytics-domain service method that writes a tombstone on run deletion, and the `<table>_active` views (plus the schema-introspection coverage test) that exclude tombstoned runs from every analytics read path.

### Modified Capabilities
- `test-suite-runs`: `deleteRun` gains a new step — writing an analytics tombstone via the analytics-domain service before the existing meta-DB hard delete — with the operation remaining safely retryable on partial failure.
- `test-suites`: suite deletion gains a new step — writing an analytics tombstone for every run owned by the suite, before the suite row is deleted and its runs cascade — so suite-cascade-deleted runs' analytics data is excluded from reads the same as single-run deletion.
- `metrics-storage`: reads of `test_case_eval_summaries` and `test_case_eval_scores` (Query DSL entities and direct repository reads alike) exclude rows belonging to deleted runs, via the new `_active` views.
- `analytics-eval-results`: reads of `test_case_run_results` (direct repository reads — export/listing; no Query DSL entity exists for this table) exclude rows belonging to deleted runs.
- `test-case-metric-score-aggregation`: reads of `test_case_metric_scores_aggregated` (the `test_case_metric_scores` Query DSL entity, plus direct repository reads) exclude rows belonging to deleted runs.
- `metric-score-statistics`: reads of `metric_score_result` (the `metric_score_results` Query DSL entity, plus direct repository reads) exclude rows belonging to deleted runs.

(`test_suite_runs` itself is meta-backed and unaffected — its hard delete already makes a deleted run's own row disappear from that Query DSL entity with no further change needed.)

## Impact

- **New table**: `run_deletions` (analytics DB) — new Flyway migration under `src/main/resources/db/migration/analytics/POSTGRES/` (`V{major}.{minor}__add_run_deletions.sql`), following the existing meta-after-analytics migration ordering.
- **New views**: one `<table>_active` view per affected run-scoped analytics table, added in the same or a follow-up Flyway migration; `./gradlew generateJooq` must be run and the generated diff committed (`src/main/java-generated/`) per existing convention.
- **Modified code**: `TestSuiteRunService.deleteRun` (new call to an analytics-domain service), a new analytics-domain service method + its repository method for the tombstone insert, every affected `StructuredQueryEntityResolver`'s `table()` (repointed at the view), any repository method reading those tables directly (exports, aggregates).
- **New test**: schema-introspection test enforcing every run-scoped analytics table has a matching `_active` view.
- **Docs**: `docs/database-schema.md` update for the new table/views (required whenever a Flyway migration changes schema).
- **No API contract change** — this is entirely an internal data-visibility fix; no new/changed endpoints, request/response DTOs, or OpenAPI examples.
- **No config changes.**
