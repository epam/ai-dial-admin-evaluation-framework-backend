# Typed SQL DSL (jOOQ)

All repositories use the typed jOOQ 3.21 DSL instead of `NamedParameterJdbcTemplate` + SQL text blocks.

**Codegen pipeline** — two tasks, two sources of truth. Generated sources live in `src/main/java-generated/`, are committed to VCS, and CI compiles them without running codegen. Each task pre-cleans only its own subtree, so running one never deletes the other's output.

| Task | Generates | Source of truth | Needs |
|------|-----------|-----------------|-------|
| `./gradlew generateJooq` | `…data.db.jooq.meta` | `db/migration/meta/POSTGRES` | Zonky embedded PG (no Docker) |
| `./gradlew generateClickHouseJooq` | `…data.db.jooq.analytics` | `db/migration/analytics/CLICKHOUSE` | Docker (ClickHouse 25.8 via Testcontainers) |

The analytics model is deliberately **not** Postgres-sourced: the analytics data model must be vendor-independent, so schema evolution happens in the CLICKHOUSE migrations first and the POSTGRES analytics migrations are the derived twin. Codegen configuration (in `build.gradle`) bridges ClickHouse's thinner type metadata — `outputSchema "analytics"` keeps the generated schema class name, and forced types restore `VARCHAR(36)` on id columns (the length drives `JooqTableSchemaResolver`'s `uuid` inference), `JSONB` on the JSON payload columns, and the bounded `VARCHAR` widths. Index constants are generated for meta only, because ClickHouse data-skipping index names are table-scoped and the analytics schema declares `idx_id` on two tables.

Run the matching task and commit the diff whenever a migration changes a schema.

**Drift guards** — one per vendor, both under `functional/`:

- `JooqSchemaDriftTest` (Zonky embedded PG) diffs the live `information_schema` against the generated metadata: for meta it is the usual "regenerate after a migration" guard; for analytics it verifies that the **Postgres twin** has kept up with the ClickHouse-sourced model.
- `ClickHouseSchemaDriftTest` (Docker, no Spring context) migrates a live ClickHouse instance and diffs `system.columns` against the generated analytics tables — columns both directions, nullability, and the ClickHouse-type → jOOQ-type mapping the forced types are expected to produce. It catches "edited a CLICKHOUSE migration but forgot to rerun `generateClickHouseJooq`".

**DSLContext beans**: Both `MetaJdbcConfiguration` and `AnalyticsJdbcConfiguration` expose a `DSLContext` bean wrapping `TransactionAwareDataSourceProxy` so jOOQ participates in Spring-managed transactions. `Settings.withRenderSchema(false)` is set so jOOQ emits unqualified table names (tables live in the `public` schema at runtime).

**Exception translation**: Both DSLContext beans use `ExceptionTranslatorExecuteListener` (from `org.springframework.boot.autoconfigure.jooq`) so jOOQ exceptions are translated to Spring's `DataAccessException` hierarchy (preserving HTTP 409 on unique-constraint violations, etc.).

**RecordMapper pattern**: Each entity has a `*RecordMapper @Component` in the mapper package that maps a typed jOOQ `*Record` (or `Record`) to the domain model. JSONB columns surface as `JSONB` objects; call `.data()` to get the raw String before passing to `JsonbMapper`. The one exception is `AggregatedMetricDefinitionRowMapper`, which maps a JOIN-alias result with no generated Record type.

**JSONB inserts**: Use `JSONB.valueOf(jsonString)` for JSONB columns. Null-safe: `json != null ? JSONB.valueOf(json) : null`.
