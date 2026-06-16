## MODIFIED Requirements

### Requirement: Explicitly qualified DataSource and JdbcTemplate beans
The service SHALL create both meta and analytics `DataSource`, `DSLContext`, and `NamedParameterJdbcTemplate` beans with explicit `@Qualifier` annotations. No unqualified default SHALL exist. The `DSLContext` beans SHALL be the canonical query entry point for production repository code; the `NamedParameterJdbcTemplate` beans SHALL remain only as legacy infrastructure required by Spring's connection pooling and Flyway wiring, and MUST NOT be injected into production repositories or test helpers.

Status: **Implemented**

#### Scenario: Meta beans qualified
- **WHEN** the application starts with `datasource.meta.vendor=POSTGRES`
- **THEN** it SHALL create a `DataSource` bean qualified as `metaDataSource`, a `NamedParameterJdbcTemplate` bean qualified as `metaJdbcTemplate`, and a `DSLContext` bean qualified as `metaDsl`
- **AND THEN** the `metaDsl` bean SHALL wrap `metaDataSource` via `TransactionAwareDataSourceProxy` and use `SQLDialect.POSTGRES`

#### Scenario: Analytics beans qualified
- **WHEN** the application starts with `datasource.analytics.vendor=POSTGRES`
- **THEN** it SHALL create a `DataSource` bean qualified as `analyticsDataSource`, a `NamedParameterJdbcTemplate` bean qualified as `analyticsJdbcTemplate`, and a `DSLContext` bean qualified as `analyticsDsl`
- **AND THEN** the `analyticsDsl` bean SHALL wrap `analyticsDataSource` via `TransactionAwareDataSourceProxy` and use `SQLDialect.POSTGRES`

#### Scenario: Missing qualifier fails at startup
- **WHEN** a repository or service injects `DSLContext` without a `@Qualifier` annotation
- **THEN** the application SHALL fail to start with a `NoUniqueBeanDefinitionException` (two candidates, neither is primary)

#### Scenario: Existing meta repositories updated
- **WHEN** the application starts
- **THEN** all meta repositories SHALL inject `@Qualifier("metaDsl") DSLContext` instead of `@Qualifier("metaJdbcTemplate") NamedParameterJdbcTemplate`

### Requirement: ConditionalOnProperty for analytics repositories
Analytics repository implementations SHALL be gated on `datasource.analytics.vendor` so the project can ship multiple vendor implementations of the same `*Repository` interface and select among them at runtime. The `Postgres*` prefix and `havingValue = "POSTGRES"` SHALL remain so that future vendor backends (e.g. `Clickhouse*EvalSummaryRepository` with `havingValue = "CLICKHOUSE"`) can be added without renaming the existing implementations.

Status: **Implemented**

#### Scenario: Postgres analytics repository active for POSTGRES vendor
- **WHEN** `datasource.analytics.vendor=POSTGRES`
- **THEN** `Postgres*` analytics repositories SHALL be instantiated by Spring

#### Scenario: Vendor-conditional swap
- **WHEN** a future change introduces a `Clickhouse*` analytics repository with `havingValue = "CLICKHOUSE"`
- **AND** `datasource.analytics.vendor=CLICKHOUSE`
- **THEN** the `Clickhouse*` implementation SHALL be instantiated instead of the `Postgres*` sibling
- **AND THEN** the analytics `*Repository` interface SHALL be unchanged

## Implementation Notes

- `DSLContext` bean factory lives in `MetaJdbcConfiguration` / `AnalyticsJdbcConfiguration` alongside the existing `NamedParameterJdbcTemplate` bean. Constructing via `DSL.using(new TransactionAwareDataSourceProxy(ds), SQLDialect.POSTGRES)` ensures Spring transaction synchronization is honored.
- `lombok.config` must include `lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier` (already present) for `@RequiredArgsConstructor` to carry `@Qualifier` to generated constructor parameters.
