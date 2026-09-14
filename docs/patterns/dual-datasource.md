# Dual Datasource (Meta + Analytics)

The project has two named datasources. Every repository and service must use the correct qualifier.

| Concern | Meta | Analytics |
|---------|------|-----------|
| DSLContext | `@Qualifier("metaDsl")` | `@Qualifier("analyticsDsl")` |
| JdbcTemplate (health/config only) | `@Qualifier("metaJdbcTemplate")` | `@Qualifier("analyticsJdbcTemplate")` |
| Transaction manager | `@Transactional("metaTransactionManager")` | `@Transactional("analyticsTransactionManager")` |
| Read-only tx | `@Transactional(value = "metaTransactionManager", readOnly = true)` | `@Transactional(value = "analyticsTransactionManager", readOnly = true)` |
| Conditional | `@ConditionalOnProperty(name = "datasource.meta.vendor", havingValue = "POSTGRES")` | `@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "POSTGRES")` |
| Flyway migrations | `db/migration/meta/POSTGRES/` (runs **after** analytics) | `db/migration/analytics/POSTGRES/` (runs first) |

**Which database holds what is not inferable from the package name alone.** `run_metric_snapshots` is a **meta** table since meta V1.32 — `data.db.repository.PostgresRunMetricSnapshotRepository` uses `@Qualifier("metaDsl")` and is gated on `@ConditionalOnProperty("datasource.meta.vendor")`; `service.domain.RunMetricSnapshotService` uses `@Transactional("metaTransactionManager")` but carries no such conditional — the service is unconditional and always registered, only the Postgres repository implementation is gated. Don't assume parity between the two. An identically named table still exists in analytics, frozen and unread (see [database-schema.md](../database-schema.md)); it is excluded from analytics jOOQ codegen, so there is no `jooq.analytics.Tables.RUN_METRIC_SNAPSHOTS` to static-import by mistake.

**A query on the wrong qualifier does not fail — it silently escapes the transaction.** A `metaDsl` query issued inside an `analyticsTransactionTemplate` block (or vice versa) runs in autocommit on a different connection; it does not join the enclosing transaction. Code like that works and reads as if it were transactional. When a block exists to give every read one consistent snapshot, hoist the other datasource's read out of it rather than leaving it inside.

**Flyway ordering and Java migrations**: `metaFlywayMigration` takes the analytics `Flyway` bean as a parameter, so analytics is fully migrated before meta starts — a meta migration may therefore read the analytics database. Migrations MAY be Java, but **only for data movement or data fixes; all schema DDL stays in SQL**, because `generateJooq` and `JooqSchemaDriftTest` build their own Flyway from the SQL migration directories and never see a Java migration. Java migrations are registered explicitly via `Flyway.configure()...javaMigrations(new …)` in `MetaFlywayConfiguration` rather than classpath-scanned, so they can be constructor-injected with the other datasource; they write through the connection Flyway supplies so their effects commit atomically with the history entry, and they skip (logging) rather than fail when the other datasource's source table is absent. Only one exists: `V1_33__CopyRunMetricSnapshotsFromAnalytics`.

**Lombok + `@Qualifier`**: Add `lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier` to `lombok.config` so `@Qualifier` on fields is copied to Lombok-generated constructor parameters.

**`TransactionTimestampAspect`** is scoped to meta transactions only — it skips initialization when the transaction qualifier is `analyticsTransactionManager`. Analytics services must NOT rely on `TransactionTimestampContext`; they receive timestamps explicitly (e.g., from the run's `createdAt`).

**Cross-datasource service calls**: the cross-domain rule applies across datasources too. A meta-domain service that needs analytics data MUST call an analytics-domain service — it must not inject an analytics repository directly. The outer call carries `@Transactional("metaTransactionManager")`; the nested analytics call carries `@Transactional("analyticsTransactionManager")` and runs in a separate physical transaction (the two managers do not federate). Plan for this — you cannot expect atomic write-across-both-DBs, so design the analytics-side write to be idempotent and recoverable. See [best-practices spec](../../openspec/specs/best-practices/spec.md) for the full rule.

**`withRenderSchema(false)` gotcha for non-Spring jOOQ**: `metaDsl`'s jOOQ `Settings` are built with `.withRenderSchema(false)` (`MetaJdbcConfiguration.java:47`) so generated queries never schema-qualify table names — required because the same generated jOOQ sources run against differently-schema'd meta databases. A test or tool that builds its own `DSLContext` against meta outside Spring (e.g. a Flyway Java migration test driving `V1_33__CopyRunMetricSnapshotsFromAnalytics` directly against Testcontainers datasources) must set the same `withRenderSchema(false)` on its own `Settings`, or generated queries will schema-qualify and fail against a differently-named schema. `CopyRunMetricSnapshotsFromAnalyticsMigrationTest` mirrors this.
