# Database and Migrations

## Purpose
This spec defines database usage conventions, migration requirements, and the dual datasource Flyway configuration.

Status: **Implemented** (Postgres + Flyway + JDBC patterns, dual datasource)

## Key Terms
- **Vendor**: data source vendor selector (currently POSTGRES).
- **Migration**: Flyway SQL script under the vendor-specific migration directory.
- **Meta datasource**: The existing database for authoring metadata (test suites, test cases, runs, metric definitions).
- **Analytics datasource**: A separate database for append-oriented result storage (test case run results, future metric results).

## Requirements

### Requirement: Use PostgreSQL via JDBC only
The service SHALL use PostgreSQL via JDBC only (no JPA/Hibernate). Data access SHALL use the typed jOOQ 3.20 DSL (`DSLContext`) and `*RecordMapper` components for `Record → domain` mapping. `NamedParameterJdbcTemplate` SHALL NOT be used in repositories; it remains only in datasource configuration (`configuration.datasource`) and health indicators (`service.infrastructure.health`).

Status: **Implemented**

#### Scenario: Repository implementation
- **WHEN** implementing data access
- **THEN** the service SHALL use `@Qualifier("metaDsl") DSLContext` / `@Qualifier("analyticsDsl") DSLContext` and `*RecordMapper` components (no JPA/Hibernate, no `NamedParameterJdbcTemplate`)

### Requirement: Apply schema changes via Flyway migrations
Database schema changes MUST be delivered via Flyway migrations committed to the repository. Spring Boot Flyway auto-config is disabled (`spring.flyway.enabled=false`). Both meta and analytics Flyway beans are manually configured. Migrations MAY be SQL or Java, but **all schema DDL MUST live in SQL migrations** — Java migrations are restricted to data movement and data fixes.
Status: **Implemented**

#### Scenario: Both Flyway instances manually configured
- **WHEN** the application starts
- **THEN** Spring Boot Flyway auto-config SHALL be disabled (`spring.flyway.enabled=false`). Both meta and analytics Flyway beans SHALL be manually configured using the same approach.

#### Scenario: Meta migration path
- **WHEN** the application starts
- **THEN** the meta Flyway SHALL execute migrations from `classpath:db/migration/meta/${datasource.meta.vendor}/`

#### Scenario: Analytics migration path
- **WHEN** the application starts with `datasource.analytics.vendor` configured
- **THEN** the analytics Flyway SHALL execute migrations from `classpath:db/migration/analytics/${datasource.analytics.vendor}/`

#### Scenario: Default migration history table for both
- **WHEN** both Flyway instances run
- **THEN** both SHALL use the default `flyway_schema_history` table name. Since meta and analytics always run in separate databases/schemas (enforced by startup validation), there is no conflict.

#### Scenario: Independent version numbering
- **WHEN** migrations are added to meta or analytics
- **THEN** each SHALL use its own version numbering independently, across SQL and Java migrations alike — a version number SHALL NOT be reused between the two migration types within one datasource.

#### Scenario: Migration naming
- **WHEN** a schema change is introduced via SQL
- **THEN** meta migrations SHALL be placed under `src/main/resources/db/migration/meta/POSTGRES/` and analytics migrations under `src/main/resources/db/migration/analytics/POSTGRES/` (or the appropriate vendor subdirectory), following `V<version>__<Description>.sql`

#### Scenario: Java migration naming and location
- **WHEN** a data migration is introduced as a Java class
- **THEN** it SHALL live in the Java source tree (e.g. `com.epam.aidial.evaluation.configuration.datasource.migration`), not under the `db/migration/**` resource directories that hold SQL only, and SHALL be named `V<version>__<Description>` following Flyway's class-naming convention, where underscores in the version segment denote the version separator (e.g. `V1_33__CopyRunMetricSnapshotsFromAnalytics`)

#### Scenario: Schema DDL stays in SQL migrations
- **WHEN** a migration creates, alters, or drops a table, column, index, or constraint
- **THEN** it SHALL be an SQL migration. The jOOQ code generator and the schema drift guard build their own Flyway instances from the SQL migration directories only, so DDL introduced by a Java migration would be invisible to both.

#### Scenario: UUID storage format
- **WHEN** a UUID id is stored in Postgres
- **THEN** it SHALL be stored as `VARCHAR(36)`

### Requirement: Java migrations are registered explicitly
Java migrations SHALL be registered on their Flyway instance explicitly rather than discovered by classpath scanning, so they can receive collaborators (such as another datasource) through their constructor.
Status: **Implemented**

#### Scenario: Explicit registration
- **WHEN** a Java migration is added to a datasource's migration set
- **THEN** an instance of it SHALL be passed to that datasource's Flyway configuration at bean construction time, and it SHALL NOT rely on classpath scanning or on static state to obtain its dependencies

#### Scenario: Java migration participates in the Flyway transaction
- **WHEN** a Java migration writes to its own datasource
- **THEN** it SHALL write through the connection Flyway supplies, so its effects commit or roll back atomically with the migration's history entry

### Requirement: Meta migrations run after analytics migrations
The meta Flyway instance SHALL run after the analytics Flyway instance, so a meta migration that reads the analytics database always observes a fully migrated source.
Status: **Implemented**

#### Scenario: Deterministic ordering
- **WHEN** the application context starts
- **THEN** the analytics Flyway bean SHALL be fully initialized before the meta Flyway bean begins migrating, by an explicit bean dependency rather than by incidental ordering

#### Scenario: Cross-datasource read degrades safely
- **WHEN** a meta migration reads from the analytics datasource and the expected source table does not exist in the analytics schema
- **THEN** the migration SHALL skip its cross-datasource work and log that it did so, rather than failing the migration — this is the case for a fresh installation where the source has never held data

#### Scenario: Cross-datasource existence check is schema-qualified
- **WHEN** a meta migration checks `information_schema.tables` for a table on the analytics datasource
- **THEN** the query SHALL filter on `table_schema` bound to the configured analytics schema, not rely on the connection's default search_path alone — `information_schema.tables` lists every schema visible to the connecting role, so an unqualified check would match the meta database's own same-named table when meta and analytics share one database with different schemas (see "Same database with different schemas allowed")

#### Scenario: Unreachable analytics database fails startup
- **WHEN** the analytics database is unreachable at startup
- **THEN** the application SHALL fail to start with an error naming the analytics datasource, consistent with existing behavior (the analytics Flyway instance already migrates during bean construction)

### Requirement: Startup validation -- meta and analytics must be different databases
The service SHALL validate at startup that meta and analytics datasources point to different databases.
Status: **Implemented**

#### Scenario: Different databases
- **WHEN** the application starts and meta and analytics JDBC URLs resolve to different databases
- **THEN** startup SHALL proceed normally

#### Scenario: Same database and same schema detected
- **WHEN** the application starts and meta and analytics JDBC URLs resolve to the same database AND the configured schemas are the same (or both default to `public`)
- **THEN** the application SHALL fail to start with a descriptive error message indicating that meta and analytics must use separate databases or separate schemas to avoid table name collisions and Flyway history conflicts. URL comparison SHALL parse the JDBC URL to extract host, port, and database name rather than comparing raw strings (syntactic normalization only -- DNS-level equivalences like `localhost` vs `127.0.0.1` are NOT resolved).

#### Scenario: Same database with different schemas allowed
- **WHEN** the application starts and meta and analytics JDBC URLs resolve to the same database but `postgres.meta.datasource.schema` and `postgres.analytics.datasource.schema` are different
- **THEN** startup SHALL proceed normally. Flyway SHALL configure `defaultSchema` per datasource to isolate migration histories and tables.

### Requirement: Provide pagination and safe sorting
List queries SHALL support pagination and MUST protect sorting parameters from SQL injection, including multi-column sorting. After this change, that protection SHALL be enforced both by the whitelist (API field → typed DSL `Field<?>` reference) and by the type system itself — no raw client input ever flows into a SQL identifier.

Status: **Implemented**

#### Scenario: Sort parameter safety
- **WHEN** list endpoints accept sort fields (single or multiple)
- **THEN** repository layer SHALL whitelist allowed sort fields and resolve each to a typed `org.jooq.Field<?>` reference
- **AND THEN** it MUST NOT interpolate raw client input into SQL identifiers

#### Scenario: Multi-column sorting order
- **WHEN** a client provides multiple sort keys
- **THEN** the repository layer SHALL generate an `ORDER BY` clause (composed from `List<org.jooq.SortField<?>>`) with the same key precedence as the request

#### Scenario: Invalid sort field
- **WHEN** a client requests a sort field that is not in the allowlist for that query
- **THEN** the system SHALL reject the request with HTTP 400 (rather than attempting to execute SQL)

#### Scenario: Stable ordering for pagination
- **WHEN** sorting does not uniquely identify a row order
- **THEN** list queries SHOULD add a deterministic tie-breaker (e.g., `id ASC`) to keep pagination stable

### Requirement: Type-safe query construction is the only production query path
The data access layer SHALL construct production SQL through the typed jOOQ DSL only. Repositories MUST NOT use Java text blocks (`"""…"""`), `MapSqlParameterSource`, or `String` concatenation to build SQL. Bind values SHALL flow exclusively through typed jOOQ parameter expressions.

Status: **Implemented**

#### Scenario: Static review passes
- **WHEN** a reviewer searches the data layer for `NamedParameterJdbcTemplate`, `MapSqlParameterSource`, or SQL text blocks
- **THEN** there SHALL be zero hits outside `com.epam.aidial.evaluation.configuration.datasource`

#### Scenario: ArchUnit enforces fence
- **WHEN** a class outside `configuration.datasource` imports `NamedParameterJdbcTemplate` or `JdbcTemplate`
- **THEN** the `LayeredArchitectureTest` ArchUnit rule SHALL fail

### Requirement: Generated DSL sources are committed and regeneratable from migrations
Generated jOOQ classes (`Tables`, `Records`, `Keys`, `Indexes` for the meta and analytics schemas) SHALL be committed under `src/main/java-generated/com/epam/aidial/evaluation/data/db/jooq/{meta,analytics}/`. A Gradle task (`generateJooq`) SHALL regenerate them by applying the current Flyway migrations to an embedded PostgreSQL instance (no Docker dependency on developer machines or CI). Normal `./gradlew build` SHALL NOT run codegen.

Status: **Implemented**

#### Scenario: Build never blocks on codegen
- **WHEN** `./gradlew build` is invoked on a clean checkout
- **THEN** the build SHALL succeed without starting an embedded PostgreSQL
- **AND THEN** no Docker daemon SHALL be queried

#### Scenario: Adding a migration triggers regeneration
- **WHEN** a developer adds a new Flyway migration that changes meta or analytics schema
- **THEN** they SHALL run `./gradlew generateJooq` to refresh the committed generated sources
- **AND THEN** the resulting changes to `src/main/java-generated/` SHALL be included in the same commit as the migration

### Requirement: Schema drift guard
A test that runs as part of `./gradlew test` SHALL detect schema drift between Flyway migrations and committed DSL metadata. The test SHALL boot an embedded PostgreSQL in-process, apply all migrations for both `meta` and `analytics` schemas, and compare the live schema (`information_schema.tables`, `information_schema.columns`, primary keys, unique constraints) against the generated DSL metadata for both schemas.

Status: **Implemented**

#### Scenario: Stale generated sources fail CI
- **WHEN** a Flyway migration changes a column type but generated sources were not regenerated
- **THEN** the drift guard test SHALL fail
- **AND THEN** the failure message SHALL identify the diverging element (e.g. `meta.test_suites.is_valid: live=BOOLEAN, generated=VARCHAR`) and instruct the developer to run `./gradlew generateJooq`

#### Scenario: Aligned state passes silently
- **WHEN** Flyway migrations and committed DSL metadata match
- **THEN** the drift guard test SHALL pass with no warnings

## Implementation Notes
- Meta Flyway: Manually configured bean in `MetaFlywayConfiguration` using `metaDataSource`, migration path `classpath:db/migration/meta/${datasource.meta.vendor}/`
- Analytics Flyway: Manually configured bean in `AnalyticsFlywayConfiguration` using `analyticsDataSource`, migration path `classpath:db/migration/analytics/${datasource.analytics.vendor}/`
- **Startup validation ordering:** Implemented in `DatasourceValidationConfiguration` which produces a `DatasourceValidationResult` marker bean after all validations pass. Both Flyway `@Bean` methods declare this marker as a parameter, creating a hard bean dependency that guarantees validation completes before migration runs.
- **Flyway settings:** Both manually configured Flyway beans set `baselineOnMigrate(true)` and `validateMigrationNaming(true)`.
- Schema support: `defaultSchema` configured on each Flyway instance per `postgres.*.datasource.schema` properties.
- Repository pagination helpers: `com.epam.aidial.evaluation.data.db.repository.sql.*`
- jOOQ runtime: `org.jooq:jooq` 3.20+, Apache-2.0 OSS edition.
- Codegen: `org.jooq:jooq-codegen-gradle` plugin; database meta source: live embedded PostgreSQL via `io.zonky.test:embedded-postgres`.
- Generated sources: `src/main/java-generated/com/epam/aidial/evaluation/data/db/jooq/{meta,analytics}/`. Listed in `.gitignore` for `*.class` and `*.tmp`, NOT for the `.java` files themselves.
- Drift guard test: `JooqSchemaDriftTest` in the functional test source set. Uses the same Zonky binary cache as `generateJooq`, so once the binary is downloaded (~30 MB) subsequent test runs are fast.
- Flyway migration locations and naming convention (`V{major}.{minor}__{Description}.sql`) are unchanged.
- Flyway beans: `configuration.datasource.MetaFlywayConfiguration` and `configuration.datasource.AnalyticsFlywayConfiguration`. Meta-after-analytics ordering is expressed by `metaFlywayMigration` taking the analytics `Flyway` bean as an explicitly `@Qualifier`-ed parameter.
- Java migrations live in the Java source tree (`configuration.datasource.migration`) and are registered via `Flyway.configure()....javaMigrations(...)`, never classpath-scanned, so they can be constructor-injected with collaborators such as the other datasource.
- Keeping DDL in SQL is load-bearing for `generateJooq` (`build.gradle`) and `JooqSchemaDriftTest`: both construct their own Flyway from the migration directories and never see a programmatically registered migration.
- Tests: `configuration.datasource.migration.CopyRunMetricSnapshotsFromAnalyticsMigrationTest` drives the cross-datasource copy directly against two Testcontainers datasources (it cannot be exercised through an application boot, since Flyway runs before fixtures exist). `functional.DslContextSmokeTest#metaFlywayMigrationDependsOnAnalyticsFlywayMigration` asserts via `ConfigurableListableBeanFactory.getDependenciesForBean("metaFlywayMigration")` that `analyticsFlywayMigration` is among its dependencies, proving the ordering rather than merely observing that the context boots.
