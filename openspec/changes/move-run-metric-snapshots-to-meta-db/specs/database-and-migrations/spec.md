## MODIFIED Requirements

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
- **WHEN** a schema change is introduced
- **THEN** meta migrations SHALL be placed under `src/main/resources/db/migration/meta/POSTGRES/` and analytics migrations under `src/main/resources/db/migration/analytics/POSTGRES/` (or the appropriate vendor subdirectory). SQL migrations SHALL follow `V<version>__<Description>.sql`; Java migrations SHALL follow `V<version>__<Description>.java`, where underscores in the version segment denote the version separator.

#### Scenario: Schema DDL stays in SQL migrations
- **WHEN** a migration creates, alters, or drops a table, column, index, or constraint
- **THEN** it SHALL be an SQL migration. The jOOQ code generator and the schema drift guard build their own Flyway instances from the SQL migration directories only, so DDL introduced by a Java migration would be invisible to both.

#### Scenario: UUID storage format
- **WHEN** a UUID id is stored in Postgres
- **THEN** it SHALL be stored as `VARCHAR(36)`

## ADDED Requirements

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
- **WHEN** a meta migration reads from the analytics datasource and the expected source table does not exist, or the analytics vendor is not the supported Postgres vendor
- **THEN** the migration SHALL skip its cross-datasource work and log that it did so, rather than failing the migration — this is the case for a fresh installation where the source has never held data

#### Scenario: Unreachable analytics database fails startup
- **WHEN** the analytics database is unreachable at startup
- **THEN** the application SHALL fail to start with an error naming the analytics datasource, consistent with existing behavior (the analytics Flyway instance already migrates during bean construction)

## Implementation notes

- Flyway beans: `configuration.datasource.MetaFlywayConfiguration` and `configuration.datasource.AnalyticsFlywayConfiguration`. Ordering is expressed by `metaFlywayMigration` taking the analytics `Flyway` bean as a parameter.
- Java migrations are registered via `Flyway.configure()....javaMigrations(...)`.
- DDL-in-SQL is load-bearing for `generateJooq` (`build.gradle`) and `JooqSchemaDriftTest`, both of which construct Flyway from the migration directories and never see programmatically registered migrations.
