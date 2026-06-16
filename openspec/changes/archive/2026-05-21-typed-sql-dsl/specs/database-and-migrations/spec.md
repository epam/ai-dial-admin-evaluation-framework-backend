## ADDED Requirements

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

## MODIFIED Requirements

### Requirement: Use PostgreSQL via JDBC only
The service SHALL use PostgreSQL via JDBC only (no JPA/Hibernate). Data access SHALL use the typed jOOQ 3.20 DSL (`DSLContext`) and `*RecordMapper` components for `Record → domain` mapping. `NamedParameterJdbcTemplate` SHALL NOT be used in repositories; it remains only in datasource configuration (`configuration.datasource`) and health indicators (`service.infrastructure.health`).

Status: **Implemented**

#### Scenario: Repository implementation
- **WHEN** implementing data access
- **THEN** the service SHALL use `@Qualifier("metaDsl") DSLContext` / `@Qualifier("analyticsDsl") DSLContext` and `*RecordMapper` components (no JPA/Hibernate, no `NamedParameterJdbcTemplate`)

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

## Implementation Notes

- jOOQ runtime: `org.jooq:jooq` 3.20+, Apache-2.0 OSS edition.
- Codegen: `org.jooq:jooq-codegen-gradle` plugin; database meta source: live embedded PostgreSQL via `io.zonky.test:embedded-postgres`.
- Generated sources: `src/main/java-generated/com/epam/aidial/evaluation/data/db/jooq/{meta,analytics}/`. Listed in `.gitignore` for `*.class` and `*.tmp`, NOT for the `.java` files themselves.
- Drift guard test: `JooqSchemaDriftTest` in the functional test source set. Uses the same Zonky binary cache as `generateJooq`, so once the binary is downloaded (~30 MB) subsequent test runs are fast.
- Flyway migration locations and naming convention (`V{major}.{minor}__{Description}.sql`) are unchanged.
