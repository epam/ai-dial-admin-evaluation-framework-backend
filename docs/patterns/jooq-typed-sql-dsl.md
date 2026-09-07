# Typed SQL DSL (jOOQ)

All repositories use the typed jOOQ 3.21 DSL instead of `NamedParameterJdbcTemplate` + SQL text blocks.

**Codegen pipeline**: `./gradlew generateJooq` boots Zonky EmbeddedPostgres, applies both Flyway migration sets, and writes generated sources to `src/main/java-generated/`. Sources are committed to VCS; CI compiles them without running codegen. Run `./gradlew generateJooq` and commit the diff whenever a Flyway migration changes the schema.

**Drift guard**: `JooqSchemaDriftTest` (under `functional/`) boots embedded PG, applies migrations, and diffs `information_schema` against the generated jOOQ metadata for both schemas. Failure message names the diverging element and instructs the developer to run `./gradlew generateJooq`.

**DSLContext beans**: Both `MetaJdbcConfiguration` and `AnalyticsJdbcConfiguration` expose a `DSLContext` bean wrapping `TransactionAwareDataSourceProxy` so jOOQ participates in Spring-managed transactions. `Settings.withRenderSchema(false)` is set so jOOQ emits unqualified table names (tables live in the `public` schema at runtime).

**Exception translation**: Both DSLContext beans use `ExceptionTranslatorExecuteListener` (from `org.springframework.boot.autoconfigure.jooq`) so jOOQ exceptions are translated to Spring's `DataAccessException` hierarchy (preserving HTTP 409 on unique-constraint violations, etc.).

**RecordMapper pattern**: Each entity has a `*RecordMapper @Component` in the mapper package that maps a typed jOOQ `*Record` (or `Record`) to the domain model. JSONB columns surface as `JSONB` objects; call `.data()` to get the raw String before passing to `JsonbMapper`. The one exception is `AggregatedMetricDefinitionRowMapper`, which maps a JOIN-alias result with no generated Record type.

**Splitting a joined record with `into(TABLE)`**: To read two tables in one query without a bespoke carrier, project `ArrayUtils.addAll(TABLE_A.fields(), TABLE_B.fields())` and split each row with `record.into(TABLE_A)` / `record.into(TABLE_B)`, which hands back the generated `*Record` types so both existing `*RecordMapper` components are reused unchanged (see `PostgresMetricDeclarationVersionRepository.findLatestPerMetricDeclaration`). This is safe even when the two tables share column names — `metric_declarations` and `metric_declaration_versions` both have `id`, `description`, `created_at_ms`, `display_name` — because jOOQ's `FieldsImpl.field0` resolves each target field by **Field identity** first (then by qualified name), and `TABLE.fields()` returns the same singleton `TableField` instances that went into the projection. Three things break that identity match and silently degrade to matching by unqualified column name: aliasing a projected column (`.as("…")`), aliasing the table (`TABLE.as("t")`), and projecting an asterisk or a bare `select()` (whose fields are then rebuilt from `ResultSet` metadata). So don't alias in such a query — aliasing is what forces the flat-carrier route of `AggregatedMetricDefinition` + `AggregatedMetricDefinitionRowMapper`, and `into(TABLE)` exists to avoid it. Also never sort or mutate `TABLE.fields()` in place: it returns the live internal array, so copy it first.

**JSONB inserts**: Use `JSONB.valueOf(jsonString)` for JSONB columns. Null-safe: `json != null ? JSONB.valueOf(json) : null`.
