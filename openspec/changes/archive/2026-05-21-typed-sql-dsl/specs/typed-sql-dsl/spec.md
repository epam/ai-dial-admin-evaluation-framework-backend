## ADDED Requirements

### Requirement: Type-safe query construction across both datasources
The data access layer SHALL construct all production SQL queries through a type-safe DSL whose `Field<?>` and `Table<?>` references are generated from the live database schema. Repositories MUST NOT construct SQL via Java text blocks, `MapSqlParameterSource`, or `String` concatenation of column/table names.

Status: **Implemented**

#### Scenario: Schema rename breaks compile
- **WHEN** a developer renames a column in a Flyway migration
- **AND** regenerates the typed DSL classes via `./gradlew generateJooq`
- **THEN** every repository method referencing the old column name SHALL fail to compile
- **AND THEN** the failure SHALL point at the offending Java line, not a runtime SQL error

#### Scenario: Repository drops raw JdbcTemplate
- **WHEN** the data access layer is reviewed after the migration
- **THEN** no class in `com.epam.aidial.evaluation.data.db.**` SHALL import `org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate` or `org.springframework.jdbc.core.JdbcTemplate`
- **AND THEN** no class in `data.db.**` SHALL contain SQL text blocks (`"""SELECT … FROM …"""`)

#### Scenario: Bind values flow through typed parameters
- **WHEN** a repository builds a WHERE predicate from caller input
- **THEN** every user-supplied value SHALL be passed as a typed DSL parameter (e.g. `DSL.val(value)`), never inlined into SQL text
- **AND THEN** SQL injection SHALL be impossible by construction at this layer

### Requirement: Generated DSL classes are committed and reproducible from migrations
Generated DSL sources (tables, records, keys, indexes) SHALL live under `src/main/java-generated/com/epam/aidial/evaluation/data/db/jooq/{meta,analytics}/` and be committed to the repository. The build SHALL NOT regenerate them on every compile. A dedicated Gradle task (`generateJooq`) SHALL regenerate them from the current Flyway migrations.

Status: **Implemented**

#### Scenario: Fresh checkout compiles without codegen
- **WHEN** a developer clones the repository on a machine with no Docker and no local PostgreSQL
- **THEN** `./gradlew build` SHALL compile the project successfully using the committed generated sources

#### Scenario: Regeneration uses embedded PostgreSQL
- **WHEN** a developer runs `./gradlew generateJooq`
- **THEN** the task SHALL boot `io.zonky.test:embedded-postgres` (no Docker required)
- **AND THEN** apply both meta and analytics Flyway migrations against it
- **AND THEN** rewrite the contents of `src/main/java-generated/` from the resulting live schema

#### Scenario: CI does not run codegen
- **WHEN** the CI pipeline builds the project
- **THEN** the `generateJooq` task SHALL NOT execute
- **AND THEN** the build SHALL succeed using only committed sources

### Requirement: Schema drift guard test
A JUnit test SHALL detect drift between the current Flyway migrations and the committed generated DSL classes by booting an embedded PostgreSQL, applying all migrations, and comparing the live schema against the generated DSL metadata for both `meta` and `analytics` schemas.

Status: **Implemented**

#### Scenario: Drift fails the build
- **WHEN** a developer adds a Flyway migration that adds or removes a column and forgets to run `./gradlew generateJooq`
- **THEN** the drift guard test SHALL fail on the next CI build
- **AND THEN** the failure message SHALL identify the diverging table/column and instruct the developer to run `./gradlew generateJooq`

#### Scenario: Aligned state passes
- **WHEN** all Flyway migrations match the committed generated DSL metadata
- **THEN** the drift guard test SHALL pass without booting any external service beyond the in-process embedded PostgreSQL

### Requirement: ArchUnit fence forbids raw JdbcTemplate outside datasource configuration and health indicators
A LayeredArchitectureTest rule SHALL forbid imports of `org.springframework.jdbc.core.JdbcTemplate` and `org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate` outside two carved-out packages: `com.epam.aidial.evaluation.configuration.datasource` (datasource and Flyway wiring) AND `com.epam.aidial.evaluation.service.infrastructure.health` (`DatabaseHealthIndicator` and `AnalyticsDatabaseHealthIndicator`, which probe each datasource via `JdbcTemplate`). The rule SHALL apply to production sources (`src/main/java`, `src/main/java-generated`) only; test infrastructure classes are excluded by `ImportOption.DoNotIncludeTests`.

Status: **Implemented**

#### Scenario: Repository regression caught
- **WHEN** a contributor accidentally re-introduces `NamedParameterJdbcTemplate` in a repository, service, web controller, or test helper outside the two allowed packages
- **THEN** the ArchUnit test SHALL fail with a message naming the offending class and the allowed locations for that import

#### Scenario: Health indicator carve-out permitted
- **WHEN** `DatabaseHealthIndicator` or `AnalyticsDatabaseHealthIndicator` in `service.infrastructure.health` injects `JdbcTemplate` to issue its liveness probe
- **THEN** the ArchUnit rule SHALL NOT flag these classes
- **AND THEN** the rule expression SHALL enumerate both allowed packages explicitly: `resideOutsideOfPackages("..configuration.datasource..", "..service.infrastructure.health..")`

### Requirement: Dialect-specific JSON path traversal is isolated
PostgreSQL JSONB path traversal (single-level `->` / `->>` and two-level `value->key1->>key2`) SHALL be encapsulated in a single injectable `JsonPathAccessor` component. Repository code SHALL invoke the accessor's typed methods rather than emit PG-specific JSON operators directly.

Status: **Implemented**

#### Scenario: Single accessor used by all repositories
- **WHEN** any repository or shared SQL helper needs to traverse a JSONB path
- **THEN** it SHALL call a method on the injected `JsonPathAccessor` component
- **AND THEN** no other class SHALL emit `->` or `->>` operators directly via plain SQL templating

#### Scenario: Future ClickHouse port localised
- **WHEN** a future change adds a ClickHouse analytics backend
- **THEN** the only PostgreSQL-specific JSON access logic to rewrite SHALL be a sibling implementation of `JsonPathAccessor`
- **AND THEN** repositories and shared SQL helpers SHALL be unchanged by the dialect swap

### Requirement: DSLContext beans honor existing transaction qualifiers
Two `org.jooq.DSLContext` beans SHALL be exposed, qualified as `metaDsl` and `analyticsDsl`, each wrapping its respective `DataSource` via `org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy`. Transactional code annotated with `@Transactional("metaTransactionManager")` or `@Transactional("analyticsTransactionManager")` SHALL bind a single JDBC `Connection` across all DSL operations within the transaction boundary.

Status: **Implemented**

#### Scenario: Single connection per transaction
- **WHEN** a service method annotated with `@Transactional("metaTransactionManager")` performs multiple `metaDsl` calls
- **THEN** all those DSL calls SHALL execute on the same JDBC `Connection` bound to that transaction

#### Scenario: Vendor wiring preserved
- **WHEN** the application starts with `datasource.meta.vendor=POSTGRES` and `datasource.analytics.vendor=POSTGRES`
- **THEN** both `metaDsl` and `analyticsDsl` beans SHALL be present
- **AND THEN** they SHALL use `SQLDialect.POSTGRES`

## Implementation Notes

- jOOQ Open Source Edition (Apache-2.0). PostgreSQL dialect is fully supported.
- Codegen plugin: `org.jooq:jooq-codegen-gradle` (jOOQ 3.20+).
- Embedded PostgreSQL: `io.zonky.test:embedded-postgres` (downloads PG binaries from Maven cache; no Docker).
- Generated sources path: `src/main/java-generated/com/epam/aidial/evaluation/data/db/jooq/meta/**` and `.../jooq/analytics/**`.
- `JsonPathAccessor` package: `com.epam.aidial.evaluation.data.db.repository.sql.json`.
- ArchUnit rule: extends `LayeredArchitectureTest` (or a sibling) with a `noClasses().that().resideInAPackage(...)` rule.
- Drift guard test: `JooqSchemaDriftTest` under `src/test/java/.../functional/` boots Zonky once, applies migrations, reads `information_schema`, compares against generated metadata.
- DSLContext must be configured with `Settings.withRenderSchema(false)` so jOOQ emits unqualified table names (tables live in the `public` schema at runtime). Configure via `DSL.using(proxy, SQLDialect.POSTGRES, new Settings().withRenderSchema(false))`.
- DSLContext must register an `ExceptionTranslatorExecuteListener` (from `org.springframework.boot.autoconfigure.jooq`) so jOOQ exceptions are translated to Spring's `DataAccessException` hierarchy, preserving HTTP 409 on unique-constraint violations.
