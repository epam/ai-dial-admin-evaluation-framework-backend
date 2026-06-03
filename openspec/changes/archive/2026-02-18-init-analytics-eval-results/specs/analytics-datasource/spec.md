# Analytics Datasource

## Purpose
This spec defines the dual datasource configuration — separate analytics DB alongside the existing meta DB. Configurable vendor, auth, connection properties, separate Flyway migration path, and qualified DataSource/JdbcTemplate beans.

Status: **New**

## Key Terms
- **Meta datasource**: The existing database for authoring metadata (test suites, test cases, runs, metric definitions).
- **Analytics datasource**: A separate database for append-oriented result storage (test case run results, future metric results).
- **Vendor**: The database engine type (e.g., POSTGRES, future CLICKHOUSE).

## ADDED Requirements

### Requirement: Symmetric datasource configuration (**BREAKING**)
The service SHALL support two named datasources — `meta` and `analytics` — configured under symmetric property paths. Existing `datasource.vendor` SHALL be renamed to `datasource.meta.vendor` and `postgres.datasource.*` to `postgres.meta.datasource.*`.

**Both datasources are mandatory** — there is no opt-out or `enabled` flag for analytics. All deployments (including development and testing) must configure both `datasource.meta.*` and `datasource.analytics.*` properties. This is acceptable at the current early stage (no production deployments). If a future use case requires running without analytics, a `datasource.analytics.enabled` flag with conditional activation of the entire analytics subsystem can be introduced.

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
The service SHALL create both meta and analytics DataSource/JdbcTemplate beans with explicit `@Qualifier` annotations. No unqualified default SHALL exist.

#### Scenario: Meta beans qualified
- **WHEN** the application starts with `datasource.meta.vendor=POSTGRES`
- **THEN** it SHALL create a `DataSource` bean qualified as `metaDataSource` and a `NamedParameterJdbcTemplate` bean qualified as `metaJdbcTemplate`

#### Scenario: Analytics beans qualified
- **WHEN** the application starts with `datasource.analytics.vendor=POSTGRES`
- **THEN** it SHALL create a `DataSource` bean qualified as `analyticsDataSource` and a `NamedParameterJdbcTemplate` bean qualified as `analyticsJdbcTemplate`

#### Scenario: Missing qualifier fails at startup
- **WHEN** a repository or service injects `NamedParameterJdbcTemplate` without a `@Qualifier` annotation
- **THEN** the application SHALL fail to start with a NoUniqueBeanDefinitionException (two candidates, neither is primary)

#### Scenario: Existing meta repositories updated
- **WHEN** the application starts
- **THEN** all existing meta repositories SHALL inject `@Qualifier("metaJdbcTemplate")` and `@Qualifier("metaDataSource")` where applicable

### Requirement: Symmetric Flyway configuration for both datasources
The service SHALL disable Spring Boot Flyway auto-config and manually configure both meta and analytics Flyway beans using the same approach. See `database-and-migrations` delta spec for full Flyway details.

#### Scenario: Both Flyways manually configured
- **WHEN** the application starts
- **THEN** both meta and analytics Flyway beans SHALL be manually configured (Spring Boot auto-config disabled)

#### Scenario: Meta Flyway path updated to dedicated subfolder
- **WHEN** the application starts
- **THEN** meta Flyway SHALL execute migrations from `classpath:db/migration/meta/${datasource.meta.vendor}/` (moved from `classpath:db/migration/${datasource.vendor}/`)

#### Scenario: Analytics Flyway migration path
- **WHEN** the application starts
- **THEN** analytics Flyway SHALL execute migrations from `classpath:db/migration/analytics/${datasource.analytics.vendor}/`

#### Scenario: Default history table for both
- **WHEN** both Flyway instances run
- **THEN** both SHALL use the default `flyway_schema_history` table name (no conflict since they run in separate databases, enforced by startup validation)

### Requirement: Startup validation — meta and analytics must be different databases
The service SHALL validate at startup that meta and analytics datasources point to different databases. See `database-and-migrations` delta spec for full validation details.

#### Scenario: Same database and same schema rejected
- **WHEN** meta and analytics JDBC URLs resolve to the same database AND the configured schemas are the same (or both default to `public`)
- **THEN** the application SHALL fail to start with a descriptive error indicating that meta and analytics must use separate databases or separate schemas. URL comparison SHALL parse the JDBC URL to extract host, port, and database name rather than comparing raw strings — this avoids false negatives from syntactically different but equivalent URLs (e.g., default port omitted vs explicit `:5432`, different query parameter ordering). Note: DNS-level equivalences (e.g., `localhost` vs `127.0.0.1`) are NOT resolved — only syntactic normalization is performed.

### Requirement: Qualified transaction managers
The service SHALL create explicitly qualified `PlatformTransactionManager` beans for both datasources. All `@Transactional` annotations SHALL specify which transaction manager to use.

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
- **THEN** the application SHALL fail at the first transactional method call with a NoUniqueBeanDefinitionException (no primary transaction manager). Unlike unqualified `JdbcTemplate` injection (which fails at startup), `@Transactional` resolution is lazy — but tests will catch this early.

#### Scenario: Meta read within analytics transaction uses separate DataSource
- **WHEN** analytics services write to the analytics database
- **THEN** the meta DB read (for run existence validation and timestamp retrieval per design D8) occurs within the `@Transactional("analyticsTransactionManager")` method but uses the meta `DataSource` (a separate JDBC connection with auto-commit). The `analyticsTransactionManager` only governs the analytics `DataSource`, so the meta read is functionally independent — no cross-TM `@Transactional` calls are needed, no distributed transaction concerns arise

#### Scenario: TransactionTimestampAspect scoped to meta only
- **WHEN** a method annotated with `@Transactional("analyticsTransactionManager")` is invoked
- **THEN** the `TransactionTimestampAspect` SHALL skip timestamp initialization (no `TransactionSynchronizationManager.bindResource`). The aspect SHALL check the `@Transactional` annotation's `value()` and only initialize timestamps for meta transactions (`metaTransactionManager` or unqualified). Analytics services use run-anchored timestamps from meta DB (design D8), not `TransactionTimestampContext`.

### Requirement: Unsupported vendor fails with clear message
The service SHALL validate at startup that the configured analytics vendor has an available repository implementation.

#### Scenario: Unsupported analytics vendor
- **WHEN** `datasource.analytics.vendor` is set to a value without a repository implementation (e.g., `CLICKHOUSE`)
- **THEN** the application SHALL fail to start with a clear error message (e.g., "Analytics vendor 'CLICKHOUSE' is not yet supported. Supported vendors: POSTGRES") rather than a cryptic `NoSuchBeanDefinitionException`. This validation SHALL be performed in `DatasourceValidationConfiguration`.

### Requirement: Analytics database health indicator
The service SHALL register a health indicator for the analytics datasource.

#### Scenario: Analytics health indicator registered
- **WHEN** the application starts with an analytics datasource configured
- **THEN** an `AnalyticsDatabaseHealthIndicator` SHALL be registered with the actuator health endpoint, reporting the health of the analytics database independently from the meta database

#### Scenario: Analytics DB unreachable
- **WHEN** the analytics database becomes unreachable
- **THEN** the health endpoint SHALL report the analytics component as `DOWN`

### Requirement: ConditionalOnProperty for analytics repositories
Analytics repository implementations SHALL be conditional on the analytics vendor property.

#### Scenario: Postgres analytics repository activation
- **WHEN** `datasource.analytics.vendor=POSTGRES`
- **THEN** the Postgres implementation of analytics repositories SHALL be activated

#### Scenario: Meta repository conditional property updated
- **WHEN** the application starts
- **THEN** existing meta repositories SHALL use `@ConditionalOnProperty(name = "datasource.meta.vendor", havingValue = "POSTGRES")` (updated from `datasource.vendor`)

## Implementation Notes
- Design reference: `design.md` decisions D1, D2, D3, D11.
- Meta configuration: `MetaPostgresConfiguration` (renamed from `PostgresConfiguration`), `MetaJdbcConfiguration` (renamed from `JdbcConfiguration`).
- Analytics configuration: `AnalyticsPostgresConfiguration`, `AnalyticsJdbcConfiguration`.
- Transaction managers: `metaTransactionManager` in meta config, `analyticsTransactionManager` in analytics config.
- Both Flyway beans manually configured (Spring Boot auto-config disabled).
- **Startup validation ordering:** Implement validation in a dedicated `@Configuration` class (e.g., `DatasourceValidationConfiguration`) that produces a marker bean (e.g., `DatasourceValidationResult`) after all validations pass. Both Flyway `@Bean` methods SHALL declare this marker as a parameter, creating a hard bean dependency that guarantees validation completes before migration runs. Note: `@Import` alone does NOT guarantee `@PostConstruct` ordering — an explicit bean dependency is required. Parse JDBC URLs to extract host/port/database for comparison (syntactic normalization only — no DNS resolution). Also validate that the configured analytics vendor has an available repository implementation — fail with a clear message if not (e.g., "Analytics vendor 'CLICKHOUSE' is not yet supported").
- Schema properties: `postgres.meta.datasource.schema` and `postgres.analytics.datasource.schema` (default: `public`). Configure on HikariDataSource and Flyway `defaultSchema`. **Note:** When using schema-based separation, schemas must exist or the DB user must have `CREATE SCHEMA` privilege (Flyway creates the `defaultSchema` if it doesn't exist, since Flyway 6.5+).
- Health indicator: `AnalyticsDatabaseHealthIndicator` — registered alongside the existing `DatabaseHealthIndicator` for meta. Inject `@Qualifier("analyticsRawJdbcTemplate")` (raw `JdbcTemplate`, not `NamedParameterJdbcTemplate` — consistent with existing `DatabaseHealthIndicator` pattern). Update existing `DatabaseHealthIndicator` to inject `@Qualifier("metaRawJdbcTemplate")`.
- **Lombok configuration:** Project `lombok.config` at the root SHALL include `lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier` to enable `@Qualifier` annotations on fields to be copied to Lombok-generated constructor parameters. Without this, `@RequiredArgsConstructor` repositories will not resolve qualified beans correctly.
