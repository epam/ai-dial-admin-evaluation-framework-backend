## Why

`run_metric_snapshots` lives in the analytics database but is not analytics data. It holds one row per TSMD per computation — captured metric bindings, declaration versions, and output schemas — which is configuration shaped like meta, not append-only per-row result data. Storing it beside `test_case_run_results` costs us three things:

1. **No referential integrity.** `TestSuiteRunService.deleteRun` issues a bare `deleteById` against the meta database; every snapshot row for that run is silently orphaned, and no cross-database FK can prevent it.
2. **No joins with run metadata.** Every consumer reads runs from meta and snapshots from analytics, then joins in the JVM. The upcoming enriched test-suite-run listing needs `metricNames` per run and would have to add a third data statement to do it.
3. **A ClickHouse liability.** Per `openspec/specs/analytics-datasource/spec.md` the analytics vendor is pluggable and a ClickHouse branch exists. A small table whose write path depends on `UNIQUE(computation_id, tsmd_id)` upsert semantics is precisely what should not be reimplemented on a column store.

Now, because the enriched listing should be built against the final data shape rather than one about to move.

## What Changes

- **`run_metric_snapshots` is created in the meta database** (meta `V1.32`), mirroring the analytics `V1.6` column set and adding `FOREIGN KEY (test_suite_run_id) REFERENCES test_suite_runs(id) ON DELETE CASCADE`.
- **Existing snapshot rows are copied** from analytics to meta by a Flyway **Java** migration (meta `V1_33`). Snapshots are not regenerable — they capture bindings as of the computation, and the source TSMD may since have changed or been deleted — so losing them would break export column planning and the detailed `eval_summaries` schema for every historical run. The copy skips rows whose run no longer exists in meta (the orphans above, which the new FK would reject) and logs the dropped count.
- **This is the repository's first Java Flyway migration.** It is registered explicitly via `.javaMigrations(...)` in `MetaFlywayConfiguration` so it can be constructor-injected with the analytics `DataSource`; classpath scanning would force a no-arg constructor plus a static holder. All DDL stays in `V1.32` SQL, because `generateJooq` and `JooqSchemaDriftTest` build their own Flyway from the SQL files and never see a programmatically registered migration.
- **Flyway bean ordering becomes explicit.** Today `metaFlywayMigration` and `analyticsFlywayMigration` both depend only on `DatasourceValidationResult` and nothing orders them. The meta bean will take the analytics `Flyway` bean as a parameter so the copy always reads a fully migrated source.
- **The analytics `run_metric_snapshots` table is kept**, frozen and unread. No analytics migration ships here; its removal is deferred (see below). Analytics `V1.8` and `V1.12` reference the table and must still apply on a fresh database.
- **Deleting a run now deletes its metric snapshots**, via the new cascade. This is the intended fix for issue 1.
- **The REST path moves** to `/api/v1/run-metric-snapshots`. The `/analytics/` prefix was already inaccurate and becomes actively misleading once the data is in meta.
- **`/api/v1/analytics/run-metric-snapshots` is retained as a deprecated alias** for both `GET` and `POST`, so the UI's `GET` keeps working across the transition. It is a separate `@Deprecated(forRemoval = true)` controller delegating to the same service, rather than a second path on the class-level `@RequestMapping` — springdoc would otherwise emit duplicate operations sharing a suffixed operationId, with no way to mark only the old one `deprecated: true`. Not **BREAKING** for this reason.
- **The endpoint gains OpenAPI examples it never had.** The three existing `openapi/examples/run-metric-snapshot-*.json` files do not match `OpenApiExampleCustomizer`'s `{pathKey}-{method}-{type}-{name}.json` convention, so they are silently never injected. They are renamed to the new path's key and covered by a test asserting they appear in `/v3/api-docs`.

### Deferred, tracked here so they are not lost

- **Drop the analytics `run_metric_snapshots` table.** Deliberately out of scope: keeping it preserves a clean rollback path and avoids introducing a Flyway ordering dependency between the two databases' migrations. When it is added it MUST be ordered after the meta copy.
- **Remove the deprecated `/api/v1/analytics/run-metric-snapshots` alias** once the UI has migrated.

### Explicitly not fixed

The new FK cascades snapshots only. `deleteRun` still orphans `test_case_eval_summaries`, `test_case_run_results`, and `metric_score_result` in analytics, because no cross-database FK is possible. Stated so the cascade is not mistaken for a general fix.

## Capabilities

### New Capabilities

None. This relocates an existing capability's storage and adjusts its API surface.

### Modified Capabilities

- `metrics-storage`: the `run_metric_snapshots` table moves from the analytics database to the meta database and gains a cascading FK to `test_suite_runs`; the batch-write and list endpoints move to `/api/v1/run-metric-snapshots` with the old paths retained as deprecated aliases.
- `database-and-migrations`: Flyway Java migrations become a supported migration type alongside SQL, with the constraint that schema DDL stays in SQL; the meta Flyway instance is ordered after the analytics instance and may read the analytics datasource during migration.
- `test-suite-runs`: deleting a run now cascades to its `run_metric_snapshots` rows. The current spec hedges this as "any future related resources via CASCADE" (spec.md:322); it becomes concrete.
- `metric-evaluation`: RunMetricSnapshots and EvalSummary records now write to two different databases (meta and analytics, respectively) instead of one; the pre-evaluation ordering guarantee — snapshots written before any `/evaluate` call — is unchanged.
- `typed-sql-dsl`: the analytics jOOQ generator gains a permanent exclusion for `run_metric_snapshots`, so its canonical generated binding lives only under `jooq.meta.Tables` even though the frozen table still physically exists in the live analytics schema.
- `openapi-examples`: adds the requirement that a response media type (`produces = MediaType.APPLICATION_JSON_VALUE`) must be declared for `OpenApiExampleCustomizer` to have anywhere to attach response examples — discovered while giving the moved endpoint its first-ever examples (both new controllers needed it, not just correctly named example files).

Not modified, despite referencing the table: `eval-summary-export`, `query-schema-discovery`, and `metrics-system` all name `run_metric_snapshots` in requirements but never state which database holds it. Their observable behavior is unchanged, so they take no delta.

## Impact

**Migrations.** New meta `V1.32__CreateRunMetricSnapshotsTable.sql` and `V1_33__CopyRunMetricSnapshotsFromAnalytics.java`. Current highest meta version is `V1.31`. No analytics migration; `V1.20` stays unclaimed.

**Packages moved.** `data.db.analytics.{model,mapper,repository}.RunMetricSnapshot*` → `data.db.{model,mapper,repository}`; `service.domain.analytics.RunMetricSnapshotService` → `service.domain`; `service.domain.dto.analytics.RunMetricSnapshot*Dto` → `service.domain.dto`. Qualifiers flip from `analyticsDsl`/`analyticsTransactionManager`/`datasource.analytics.vendor` to their meta counterparts. This also dissolves an existing mixed-manager smell: `batchCreate` currently opens an analytics transaction and calls the meta `TestSuiteRunRepository` inside it.

**Transaction boundaries.** Two reads must be hoisted out of analytics transactions or they silently escape into autocommit: `EvalSummaryExportService:232` (`findByRunId`, moves into the existing `metaTransactionTemplate` block on `MetaSetup`) and `RunComparisonService:116` (whose enclosing `analyticsTransactionTemplate` exists specifically to give every read a consistent snapshot — see the comment at :85).

**Import-only churn.** `EvalSummariesSchemaProvider`, `MetricScoreComputationExecutor`, `MetricFieldDiscoverer`, `EvalSummaryExportColumnPlanner`, `InProcessMetricEvaluationExecutor`.

**jOOQ.** Keeping the analytics table means `RUN_METRIC_SNAPSHOTS` would generate into both `jooq.meta` and `jooq.analytics` — the repository's first duplicate generated table name, where a wrong static import compiles clean and queries the wrong database. Prevented by excluding it from the analytics generator (`build.gradle:325`), regenerating, and deleting the stale analytics sources. `JooqSchemaDriftTest:92` moves the table between its two lists.

**Boot behavior.** The meta migration now reads the analytics datasource. This adds no new failure mode: `analyticsFlywayMigration` already calls `.migrate()` in its bean method, so the application already fails to start when analytics is unreachable. (`DatasourceValidationResult` only parses JDBC URLs; it never connects.) The migration skips rather than fails only when the analytics source table is absent, which covers fresh installs. There is no `datasource.analytics.vendor` branch: `DatasourceValidationConfiguration` already hard-fails startup for any analytics vendor other than `POSTGRES`, and both Flyway `@Bean` methods require its `DatasourceValidationResult` marker, so the application cannot boot far enough to run this migration with an unsupported vendor configured — a vendor check inside the migration would be unreachable dead code (see design D5).

**Tests.** Snapshot helpers move from `AnalyticsTestDataHelper` to `MetaTestDataHelper`; the `PostgresTestPersistenceService:61` truncate moves to the meta side; roughly 14 functional classes take helper and import churn; 7 REST call sites move to the new path with coverage retained on the deprecated alias. A dedicated migration test is required because Flyway runs at context startup before fixtures exist — it drives the migration class directly against two Testcontainers datasources.

**Docs.** `docs/database-schema.md` (new meta table, and the analytics copy marked frozen as of this release so nobody trusts it later), `docs/key-packages.md`, `docs/patterns/computation-versioning.md`, `docs/patterns/eval-summaries-read-surface.md`, `docs/patterns/dual-datasource.md`. No configuration properties change, so `docs/configuration.md` is untouched. `openspec/config.yaml` needs a note that Flyway Java migrations are now a supported type, since that is a project-wide convention rather than a feature.

**Downstream.** Unblocks the enriched test-suite-run listing (issue #197): `metricNames` folds into the same meta page query that already extracts deployment refs, taking that endpoint from three data statements to two.
