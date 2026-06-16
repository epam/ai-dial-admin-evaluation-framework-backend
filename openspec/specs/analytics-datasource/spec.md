# Analytics Datasource

## Purpose
This spec defines the dual datasource configuration -- separate analytics DB alongside the existing meta DB. Configurable vendor, auth, connection properties, separate Flyway migration path, and qualified DataSource/JdbcTemplate beans.

Status: **Implemented**

## Key Terms
- **Meta datasource**: The existing database for authoring metadata (test suites, test cases, runs, metric definitions).
- **Analytics datasource**: A separate database for append-oriented result storage (test case run results, future metric results).
- **Vendor**: The database engine type (e.g., POSTGRES, future CLICKHOUSE).

## Requirements

### Requirement: Symmetric datasource configuration
The service SHALL support two named datasources -- `meta` and `analytics` -- configured under symmetric property paths: `datasource.meta.*` / `postgres.meta.datasource.*` and `datasource.analytics.*` / `postgres.analytics.datasource.*`.

**Both datasources are mandatory** -- there is no opt-out or `enabled` flag for analytics. All deployments must configure both.
Status: **Implemented**

#### Scenario: Meta datasource configuration
- **WHEN** the application starts
- **THEN** it SHALL read `datasource.meta.vendor` (required), `datasource.meta.auth.type` (required), and vendor-specific connection properties under `postgres.meta.datasource.*` (url, driver-class-name, username, password)

#### Scenario: Analytics datasource configuration
- **WHEN** the application starts
- **THEN** it SHALL read `datasource.analytics.vendor` (required), `datasource.analytics.auth.type` (required), and vendor-specific connection properties under `postgres.analytics.datasource.*` (url, driver-class-name, username, password)

#### Scenario: Both datasources point to same Postgres instance, different databases
- **WHEN** both `datasource.meta.vendor` and `datasource.analytics.vendor` are `POSTGRES` and both URLs point to the same host but different databases
- **THEN** the service SHALL create two independent DataSource beans, each connecting to its own database

#### Scenario: Schema-based separation (same database, different schemas)
- **WHEN** both datasources point to the same database but `postgres.meta.datasource.schema` and `postgres.analytics.datasource.schema` are configured to different schemas
- **THEN** the service SHALL create two independent DataSource beans with each HikariDataSource configured with its `schema` property, and Flyway configured with the corresponding `defaultSchema`

#### Scenario: Schema property defaults
- **WHEN** `postgres.meta.datasource.schema` or `postgres.analytics.datasource.schema` is not configured
- **THEN** the schema SHALL default to `public`

#### Scenario: Azure auth type for analytics
- **WHEN** `datasource.analytics.auth.type` is `azure`
- **THEN** the service SHALL create a `DynamicPasswordHikariDataSource` for analytics using Azure TokenCredential, same pattern as meta

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

### Requirement: Qualified transaction managers
The service SHALL create explicitly qualified `PlatformTransactionManager` beans for both datasources. All `@Transactional` annotations SHALL specify which transaction manager to use.
Status: **Implemented**

#### Scenario: Meta transaction manager
- **WHEN** the application starts with `datasource.meta.vendor=POSTGRES`
- **THEN** it SHALL create a `DataSourceTransactionManager` bean qualified as `metaTransactionManager` using the `metaDataSource`

#### Scenario: Analytics transaction manager
- **WHEN** the application starts with `datasource.analytics.vendor=POSTGRES`
- **THEN** it SHALL create a `DataSourceTransactionManager` bean qualified as `analyticsTransactionManager` using the `analyticsDataSource`

#### Scenario: Existing meta services updated
- **WHEN** the application starts
- **THEN** all existing meta services SHALL use `@Transactional("metaTransactionManager")` (updated from unqualified `@Transactional`)

#### Scenario: Analytics services use analytics transaction manager
- **WHEN** analytics service methods require transactional behavior
- **THEN** they SHALL use `@Transactional("analyticsTransactionManager")`

#### Scenario: Missing transaction manager qualifier fails
- **WHEN** a service uses unqualified `@Transactional` annotation
- **THEN** the application SHALL fail at the first transactional method call with a NoUniqueBeanDefinitionException

#### Scenario: Meta read within analytics transaction uses separate DataSource
- **WHEN** analytics services write to the analytics database
- **THEN** the meta DB read (for run existence validation and timestamp retrieval) occurs within the `@Transactional("analyticsTransactionManager")` method but uses the meta `DataSource` (a separate JDBC connection with auto-commit). No cross-TM `@Transactional` calls are needed, no distributed transaction concerns arise.

#### Scenario: TransactionTimestampAspect scoped to meta only
- **WHEN** a method annotated with `@Transactional("analyticsTransactionManager")` is invoked
- **THEN** the `TransactionTimestampAspect` SHALL skip timestamp initialization. The aspect checks the `@Transactional` annotation's `value()` and only initializes timestamps for meta transactions (`metaTransactionManager` or unqualified).

### Requirement: Unsupported vendor fails with clear message
The service SHALL validate at startup that the configured analytics vendor has an available repository implementation.
Status: **Implemented**

#### Scenario: Unsupported analytics vendor
- **WHEN** `datasource.analytics.vendor` is set to a value without a repository implementation (e.g., `CLICKHOUSE`)
- **THEN** the application SHALL fail to start with a clear error message (e.g., "Analytics vendor 'CLICKHOUSE' is not yet supported. Supported vendors: POSTGRES")

### Requirement: Analytics database health indicator
The service SHALL register a health indicator for the analytics datasource.
Status: **Implemented**

#### Scenario: Analytics health indicator registered
- **WHEN** the application starts with an analytics datasource configured
- **THEN** an `AnalyticsDatabaseHealthIndicator` SHALL be registered with the actuator health endpoint, reporting the health of the analytics database independently from the meta database

#### Scenario: Analytics DB unreachable
- **WHEN** the analytics database becomes unreachable
- **THEN** the health endpoint SHALL report the analytics component as `DOWN`

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
- Meta configuration: `MetaPostgresConfiguration` (renamed from `PostgresConfiguration`), `MetaJdbcConfiguration` (renamed from `JdbcConfiguration`).
- Analytics configuration: `AnalyticsPostgresConfiguration`, `AnalyticsJdbcConfiguration`.
- Transaction managers: `metaTransactionManager` in meta config, `analyticsTransactionManager` in analytics config.
- Startup validation: `DatasourceValidationConfiguration` produces `DatasourceValidationResult` marker bean. Validates datasource isolation and analytics vendor support.
- Schema properties: `postgres.meta.datasource.schema` and `postgres.analytics.datasource.schema` (default: `public`).
- Health indicator: `AnalyticsDatabaseHealthIndicator` alongside existing `DatabaseHealthIndicator` for meta.
- **Lombok configuration:** `lombok.config` includes `lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier` for `@RequiredArgsConstructor` qualifier propagation.
- `DSLContext` bean factory lives in `MetaJdbcConfiguration` / `AnalyticsJdbcConfiguration` alongside the existing `NamedParameterJdbcTemplate` bean. Constructing via `DSL.using(new TransactionAwareDataSourceProxy(ds), SQLDialect.POSTGRES)` ensures Spring transaction synchronization is honored.
