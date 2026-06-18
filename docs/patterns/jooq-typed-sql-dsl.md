# Typed SQL DSL (jOOQ)

All repositories use the typed jOOQ 3.21 DSL instead of `NamedParameterJdbcTemplate` + SQL text blocks.

**Codegen pipeline**: `./gradlew generateJooq` boots Zonky EmbeddedPostgres, applies both Flyway migration sets, and writes generated sources to `src/main/java-generated/`. Sources are committed to VCS; CI compiles them without running codegen. Run `./gradlew generateJooq` and commit the diff whenever a Flyway migration changes the schema.

**Drift guard**: `JooqSchemaDriftTest` (under `functional/`) boots embedded PG, applies migrations, and diffs `information_schema` against the generated jOOQ metadata for both schemas. Failure message names the diverging element and instructs the developer to run `./gradlew generateJooq`.

**DSLContext beans**: Both `MetaJdbcConfiguration` and `AnalyticsJdbcConfiguration` expose a `DSLContext` bean wrapping `TransactionAwareDataSourceProxy` so jOOQ participates in Spring-managed transactions. `Settings.withRenderSchema(false)` is set so jOOQ emits unqualified table names (tables live in the `public` schema at runtime).

**Exception translation**: Both DSLContext beans use `ExceptionTranslatorExecuteListener` (from `org.springframework.boot.autoconfigure.jooq`) so jOOQ exceptions are translated to Spring's `DataAccessException` hierarchy (preserving HTTP 409 on unique-constraint violations, etc.).

**RecordMapper pattern**: Each entity has a `*RecordMapper @Component` in the mapper package that maps a typed jOOQ `*Record` (or `Record`) to the domain model. JSONB columns surface as `JSONB` objects; call `.data()` to get the raw String before passing to `JsonbMapper`. The one exception is `AggregatedMetricDefinitionRowMapper`, which maps a JOIN-alias result with no generated Record type.

**JSONB inserts**: Use `JSONB.valueOf(jsonString)` for JSONB columns. Null-safe: `json != null ? JSONB.valueOf(json) : null`.
