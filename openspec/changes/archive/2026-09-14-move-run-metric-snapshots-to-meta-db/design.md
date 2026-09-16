## Context

See `proposal.md` — Why. Constraints that shape the approach:

- **Meta and analytics are configured as separate databases in this deployment**, but that is not an enforced invariant. `postgres.meta.datasource.url` = `evaluation_db`, `postgres.analytics.datasource.url` = `evaluation_analytics_db` today, but `DatasourceValidationConfiguration` only *fails* startup when host, port, database, **and** schema all match (`DatasourceValidationConfiguration.java:50-57`); when host/port/database match but the schemas differ, it logs and lets startup proceed (`:59-66`). Same-database/different-schema is a supported topology (`database-and-migrations` spec, "Same database with different schemas allowed", `spec.md:69-71`). **Consequence:** a cross-datasource `information_schema.tables` existence check (D5) MUST filter by `table_schema` bound to the analytics schema — `information_schema.tables` lists every schema visible to the connecting role, not only the one on the connection's search_path, so an unqualified check would silently match the meta database's own same-named table in a shared-database deployment. Cross-database *SQL* (a join, or an unqualified reference resolved by search_path) is impossible only when the two are genuinely separate databases; when they share one, they still cannot be joined without schema-qualifying every reference, so any data copy must still move rows through the JVM either way. Note that `generateJooq` is a separate case: it boots one embedded Postgres with `meta` and `analytics` as two schemas, purely so one codegen run can see both.
- **Flyway is hand-wired.** `MetaFlywayConfiguration` and `AnalyticsFlywayConfiguration` each build a `Flyway` and call `.migrate()` inside the bean method; Boot's autoconfiguration is off. Migration therefore happens during bean construction, before any repository or service exists.
- **Zero SQL-level coupling to unwind.** Every reader of `run_metric_snapshots` goes through `RunMetricSnapshotRepository` and joins in the JVM. `EvalSummaryRepository` and `ComputationResolver` name the table only in javadoc. Nothing in the analytics query layer joins it.
- **Two historical analytics migrations reference the table** — `V1.8__NormalizeErrorShapedMetricValues.sql` joins `output_schema`, and `V1.12__AddSuiteAndTimestampToMetricScoreResult.sql` backfills `computed_at_ms` from it. Both must still apply against a fresh database, which constrains when the analytics table may be dropped.
- **`deleteRun` already orphans rows.** `TestSuiteRunService.deleteRun` calls `deleteById` with no analytics cleanup, so the analytics table today holds snapshot rows whose run is gone from meta. Any copy into a table with an FK must reckon with them.

## Goals / Non-Goals

**Goals:**

- One canonical home for `run_metric_snapshots`, in meta, with a real FK to `test_suite_runs`.
- Historical rows preserved, since they are not regenerable.
- Reader and writer changes that are mechanical and behavior-preserving, with transaction boundaries that stay honest.
- No new way to accidentally query the wrong database.

**Non-Goals:**

- Dropping the analytics table (deferred — see proposal).
- Removing the deprecated endpoint alias (deferred).
- Cleaning up orphaned analytics result rows on run deletion — impossible without a cross-database FK, and out of scope.
- Making `test_suite_runs` a Query DSL entity. Registered entities remain `test_suites`, `test_cases`, `eval_summaries`, `metric_score_results`; this change does not make runs queryable.
- Changing the response DTO shape or the filter contract of either endpoint.

## Decisions

### D1: Split the move into an SQL DDL migration and a Java data migration

Meta `V1.32__CreateRunMetricSnapshotsTable.sql` holds the table, the unique index, the run index, and the FK. Meta `V1_33__CopyRunMetricSnapshotsFromAnalytics.java` holds only `INSERT`s.

**Why not one Java migration doing both:** `generateJooq` and `JooqSchemaDriftTest` each construct their own `Flyway` pointed at the SQL migration *directories*. A programmatically registered Java migration is invisible to them. DDL in Java would mean the generated jOOQ sources and the drift guard never see the table — codegen would silently produce nothing and the drift test would pass while being blind. Keeping DDL in SQL makes that impossible rather than merely unlikely.

**Why not pure SQL for the copy:** separate databases; `postgres_fdw` / `dblink` would need an extension and credentials we do not manage.

**Why a Flyway migration rather than an `ApplicationRunner` backfill:** the copy must be atomic with the DDL and must run exactly once with a recorded history entry. A runner would need its own idempotence bookkeeping and could run against a half-migrated schema.

### D2: Register the Java migration explicitly, not by classpath scanning

`MetaFlywayConfiguration` gains the analytics `DataSource` (and the analytics **schema**, `postgres.analytics.datasource.schema` — not the vendor string; the migration's schema-qualified existence check in D5 needs the schema, and both constructor parameters are typed `String` so passing the vendor by mistake compiles cleanly and silently) as bean parameters and passes a constructed instance:

```java
Flyway.configure()
        .dataSource(metaDataSource)
        .locations(location)
        .javaMigrations(new V1_33__CopyRunMetricSnapshotsFromAnalytics(analyticsDataSource, analyticsSchema))
        ...
```

The real bean method (`MetaFlywayConfiguration.java:19-31`) also takes the analytics `Flyway` bean as an explicitly `@Qualifier("analyticsFlywayMigration")`-annotated parameter, purely for ordering — see D3.

**Why:** Flyway instantiates scanned migrations reflectively via a no-arg constructor, which would force the analytics `DataSource` to arrive through static mutable state. Explicit registration keeps it constructor-injected and makes the migration directly unit-testable — which matters, because it cannot be exercised through an application boot (D6).

**Trade-off:** the registration is invisible from the migration directory, so someone reading only `db/migration/meta/POSTGRES/` will not see that `V1.33` exists. Mitigated by a comment in `V1.32`'s header pointing at the Java migration and by the `database-and-migrations` spec.

### D3: Order the meta Flyway bean after the analytics Flyway bean

`metaFlywayMigration` takes the analytics `Flyway` bean as an explicitly `@Qualifier("analyticsFlywayMigration")`-annotated parameter.

**Why:** today both beans depend only on `DatasourceValidationResult` and nothing orders them relative to each other. The copy reads a table created by analytics `V1.6`; if meta migrated first on a fresh install, the source would not exist. The skip guard (D5) already makes that case correct rather than fatal, but relying on a guard for something we can make deterministic is worse than just making it deterministic.

**Why a bean parameter over `@DependsOn("analyticsFlywayMigration")`:** not because it is "type-checked" — it isn't, in any way `@DependsOn` is not. Both `metaFlywayMigration` and `analyticsFlywayMigration` are unqualified beans of type `Flyway`, so a bare `Flyway` parameter on the meta bean method is, in the general case, exactly as name-based as `@DependsOn`'s bean-name string; adding the explicit `@Qualifier("analyticsFlywayMigration")` makes that name-based resolution visible instead of implicit, and keeps it correct if a third `Flyway`-typed bean is ever introduced. The real reason to prefer the bean-parameter form is call-site readability: the ordering dependency sits in the method signature next to a comment explaining why, right beside the code that actually uses the analytics datasource, rather than in an annotation attribute with no other reference to analytics anywhere in the method.

### D4: Write through Flyway's connection, read on a separate connection

The migration reads from a connection it opens off the injected analytics `DataSource` (`autoCommit=false` + `setFetchSize` so the driver streams rather than materializing the result set), and writes through `context.getConnection()`.

**Why:** writing on Flyway's own connection puts the inserts inside the migration's transaction, so a failure mid-copy rolls back the data *and* the history entry together — the migration re-runs cleanly next boot. The read connection is read-only and independently closed; it must not be Flyway's, which belongs to the other database.

### D5: Skip, do not fail, when the source table is absent

Before reading, the migration checks `information_schema.tables` — **schema-qualified** (`table_schema = ?` bound to the analytics schema; see the Context section's consequence note) — for `run_metric_snapshots` on the analytics side. A miss logs and returns without error.

**Why no vendor check inside the migration:** `DatasourceValidationConfiguration` already hard-fails startup for any `datasource.analytics.vendor` other than `POSTGRES` (`DatasourceValidationConfiguration.java:17,33-38`), and both Flyway `@Bean` methods take its `DatasourceValidationResult` marker as a required parameter — so the application cannot boot far enough to construct either Flyway bean, let alone run this migration, with an unsupported vendor configured. A vendor branch inside the migration would be unreachable dead code; the migration relies on the existing startup validation instead of duplicating it.

**Why skip absent-table rather than fail:** a fresh installation migrates both databases from empty. There is nothing to copy and no reason to fail. An unreachable analytics database is a different matter and still fails the boot — but that is pre-existing behavior, since `analyticsFlywayMigration` already calls `.migrate()` during bean construction. This change adds no new startup dependency.

### D6: Guard orphans via an explicit existence check, not the insert's outcome

For each batch of up to 1000 source rows, the migration first collects the batch's distinct `test_suite_run_id`s and queries which of them exist in meta (`SELECT id FROM test_suite_runs WHERE id = ANY (?)`). Rows whose run is not in that result are counted as orphans and dropped before any `INSERT` is attempted for them. Only the remaining, run-verified rows are batched into:

```sql
INSERT INTO run_metric_snapshots (id, computation_id, test_suite_run_id, ...)
SELECT ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?
WHERE EXISTS (SELECT 1 FROM test_suite_runs WHERE id = ?)
ON CONFLICT (computation_id, tsmd_id) DO NOTHING
```

via `addBatch` / `executeBatch`. The migration reports three counts, summed across all batches: `copied` (positive update counts from `executeBatch()`), `orphaned` (from the existence check above), and `alreadyPresent` (the run-verified rows minus `copied` — rows that passed the existence check but were skipped by `ON CONFLICT DO NOTHING`, i.e. a retry over an already-copied target).

**Why not derive the orphan count from `attempted - inserted`:** `ON CONFLICT DO NOTHING` also yields a zero update count for a row that already exists in the target, and pgjdbc MAY collapse a batch's per-statement outcomes to `SUCCESS_NO_INFO` — both indistinguishable from an orphan by update count alone. Deriving orphans that way would, on a second run over a partially-copied target, misreport every already-copied row as a fresh orphan. The explicit existence check keeps the orphan count accurate on every run, including retries, not just the first — so the Migration Plan's verification step below can rely on it unconditionally.

**Why also keep the `WHERE EXISTS` clause in the INSERT:** defense-in-depth against a run being deleted between the batch's existence check and its insert (the migration is not holding a lock on `test_suite_runs`); it costs one extra index probe per row and never changes the outcome the existence check already determined.

**Why not pre-load all run ids into a `Set` and filter in Java:** run counts are unbounded and this runs during startup. The batch-scoped existence check costs one indexed `= ANY(?)` lookup per batch, not one query per row, and keeps memory flat regardless of table size.

**Why `ON CONFLICT DO NOTHING` as well:** makes a partial previous attempt harmless and matches the existing write path's semantics.

### D7: Exclude the analytics table from jOOQ codegen

`build.gradle`'s analytics generator changes to `.withExcludes("flyway_schema_history|run_metric_snapshots")`. The generated `jooq/analytics/tables/RunMetricSnapshots.java` and its record are deleted; `jooq/meta` gains the new sources.

**Why:** keeping the analytics table (proposal D4) would otherwise generate `RUN_METRIC_SNAPSHOTS` into *both* `jooq.meta.Tables` and `jooq.analytics.Tables` — the first duplicate generated table name in the repository. A wrong static import then compiles cleanly, passes review, and queries the wrong database at runtime. Excluding it from codegen removes the failure mode instead of documenting it. `JooqSchemaDriftTest` moves the table between its two lists accordingly, writing the meta one as an FQN to match how that test already handles meta tables.

### D8: Deprecated alias as a separate controller

A new `@Deprecated(forRemoval = true)` controller at `/api/v1/analytics/run-metric-snapshots` delegates both `GET` and `POST` to the same `RunMetricSnapshotService`.

**Why not a second path in the class-level `@RequestMapping`:** springdoc emits one operation per path × method. Two paths on one class yields duplicate operations whose operationIds collide and get suffixed (`listSnapshots_1`), with no way to mark only the legacy pair `deprecated: true`. A separate class gets clean `@Operation(deprecated = true)` metadata, its own operationIds, and deletes as one file when the UI has migrated.

**Why alias `POST` too, when only `GET` is known to be used by the UI:** the internal write path calls the service directly rather than over HTTP, so `POST` has no *known* external caller — but "no known caller" is not "no caller", and the alias costs one extra method in a file already destined for deletion.

### D9: Hoist two reads out of analytics transactions

- `EvalSummaryExportService:232` — `findByRunId` moves from the `analyticsTransactionTemplate` block into the existing `metaTransactionTemplate` block; `MetaSetup` carries the result forward.
- `RunComparisonService:116` — the snapshot read is hoisted ahead of the `analyticsTransactionTemplate.execute` block.

**Why this is not optional:** a `metaDsl` query issued inside an analytics transaction does not join it. It runs in autocommit on a different connection. The code would still work, but silently: `RunComparisonService`'s enclosing transaction exists specifically so every read sees one consistent snapshot (comment at `:85`), and leaving the call where it is would quietly exempt one read from that guarantee. Moving it is cheaper than explaining why it is fine.

### D10: Move packages to match the new datasource

`data.db.analytics.{model,mapper,repository}.RunMetricSnapshot*` → `data.db.{model,mapper,repository}`; `service.domain.analytics.RunMetricSnapshotService` → `service.domain`; `service.domain.dto.analytics.RunMetricSnapshot*Dto` → `service.domain.dto`. Qualifiers flip to `metaDsl`, `metaTransactionManager`, and `@ConditionalOnProperty("datasource.meta.vendor")`.

**Why move rather than leave in place with flipped qualifiers:** the `.analytics` package segment is how a reader infers which datasource a repository talks to. A repository in `data.db.analytics.repository` holding `@Qualifier("metaDsl")` is exactly the kind of thing that survives review and then misleads for a year.

A side effect worth naming: `RunMetricSnapshotService.batchCreate` currently opens an **analytics** transaction and calls the **meta** `TestSuiteRunRepository` inside it, so the run-existence check has never been atomic with the insert. After the move both sit in one meta transaction and the check becomes meaningful. That is a behavior improvement, spec'd in the `metrics-storage` delta.

## Risks / Trade-offs

- **Copy fails mid-flight on a large installation** → The whole migration is one Flyway transaction, so a failure rolls back both the data and the history row; the next boot retries from scratch. The `ON CONFLICT DO NOTHING` makes a retry after partial manual intervention harmless too.
- **Startup slows on the first boot after upgrade** → Volume is `#computations × #TSMDs`, not per-result-row — tens to low thousands of rows for a realistic installation. Batched at 1000. If an installation is somehow far larger, the migration is still bounded and one-time.
- **Someone later drops the analytics table without ordering it after the copy** → Called out in the proposal's deferred items and in the `database-and-migrations` delta. The drop must be ordered after meta `V1.33`, and nothing in the code enforces that today because nothing needs to yet.
- **The frozen analytics table drifts and someone trusts it** → `docs/database-schema.md` marks it frozen as of this release, and the `metrics-storage` delta states no code path reads or writes it.
- **The alias outlives its usefulness** → It is `@Deprecated(forRemoval = true)`, marked `deprecated: true` in OpenAPI, isolated in one file, and tracked as a deferred removal item.
- **The Java migration is a new pattern nobody has maintained here** → It is the only one, it is covered by a dedicated test (below), and the `database-and-migrations` delta constrains what future Java migrations may do (data only, explicit registration).

## Migration Plan

**Deploy:** ordinary rolling deploy. On startup, analytics Flyway runs first (D3), then meta Flyway applies `V1.32` (DDL) and `V1.33` (copy) in order. No manual step, no downtime window, no configuration change.

**Verification after deploy:** compare `SELECT count(*) FROM run_metric_snapshots` across both databases. The meta count will be lower than the analytics count by exactly the orphan count the migration logged — accurate on every run, including a retry after a partial previous copy, because the orphan count comes from D6's explicit existence check rather than from the insert's own update counts.

**Rollback:** redeploy the previous artifact. The analytics table was never modified and still holds every row, so the old code reads its original source unchanged. The meta table and its two history rows are left behind, inert — a re-upgrade re-applies nothing (the history entries already exist) and reads the meta table it already populated. This clean rollback is the entire reason the analytics table is kept (proposal D4).

**Testing approach:** the copy cannot be exercised by a normal `@PostgresFunctionalTests` boot, because Flyway runs during bean construction — long before any test fixture exists, so there is never anything in the source to copy. The migration class is therefore driven directly against two Testcontainers datasources, covering: a populated copy, re-running against an already-copied target (idempotent), a row whose run is absent from meta (dropped and logged, migration succeeds), an absent source table (skipped, migration succeeds), and a **forced mid-copy failure** (e.g. a row that violates the target schema partway through a batch) verifying that no `run_metric_snapshots` rows land in meta and no `flyway_schema_history` row for `V1.33` is recorded — the rollback-atomicity guarantee D1/D4 claim (the copy and its history entry commit or roll back together), otherwise never exercised by any of the other cases. Everything else is covered by the existing functional suites once their helpers and REST paths are repointed, plus a new test asserting the renamed OpenAPI examples appear in `/v3/api-docs`.
