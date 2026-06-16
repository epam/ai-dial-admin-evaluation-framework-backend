# Dual Datasource (Meta + Analytics)

The project has two named datasources. Every repository and service must use the correct qualifier.

| Concern | Meta | Analytics |
|---------|------|-----------|
| DSLContext | `@Qualifier("metaDsl")` | `@Qualifier("analyticsDsl")` |
| JdbcTemplate (health/config only) | `@Qualifier("metaJdbcTemplate")` | `@Qualifier("analyticsJdbcTemplate")` |
| Transaction manager | `@Transactional("metaTransactionManager")` | `@Transactional("analyticsTransactionManager")` |
| Read-only tx | `@Transactional(value = "metaTransactionManager", readOnly = true)` | `@Transactional(value = "analyticsTransactionManager", readOnly = true)` |
| Conditional | `@ConditionalOnProperty(name = "datasource.meta.vendor", havingValue = "POSTGRES")` | `@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "POSTGRES")` |
| Flyway migrations | `db/migration/meta/POSTGRES/` | `db/migration/analytics/POSTGRES/` |

**Lombok + `@Qualifier`**: Add `lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier` to `lombok.config` so `@Qualifier` on fields is copied to Lombok-generated constructor parameters.

**`TransactionTimestampAspect`** is scoped to meta transactions only — it skips initialization when the transaction qualifier is `analyticsTransactionManager`. Analytics services must NOT rely on `TransactionTimestampContext`; they receive timestamps explicitly (e.g., from the run's `createdAt`).

**Cross-datasource service calls**: the cross-domain rule applies across datasources too. A meta-domain service that needs analytics data MUST call an analytics-domain service — it must not inject an analytics repository directly. The outer call carries `@Transactional("metaTransactionManager")`; the nested analytics call carries `@Transactional("analyticsTransactionManager")` and runs in a separate physical transaction (the two managers do not federate). Plan for this — you cannot expect atomic write-across-both-DBs, so design the analytics-side write to be idempotent and recoverable. See [best-practices spec](../../openspec/specs/best-practices/spec.md) for the full rule.
