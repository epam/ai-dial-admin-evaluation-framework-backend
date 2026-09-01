# Typed SQL DSL (jOOQ)

All repositories use the typed jOOQ 3.21 DSL instead of `NamedParameterJdbcTemplate` + SQL text blocks.

**Codegen pipeline** — two tasks, three packages. **Every model is generated from its own vendor's migrations**; nothing is derived by hand. Generated sources live in `src/main/java-generated/`, are committed to VCS, and CI compiles them without running codegen. Each task pre-cleans only its own subtree, so running one never deletes the other's output.

| Task | Generates | Source of truth | Needs |
|------|-----------|-----------------|-------|
| `./gradlew generateJooq` | `…data.db.jooq.meta` | `db/migration/meta/POSTGRES` | Zonky embedded PG (no Docker) |
| `./gradlew generateJooq` | `…data.db.jooq.analytics` | `db/migration/analytics/POSTGRES` | Zonky embedded PG (no Docker) |
| `./gradlew generateClickHouseJooq` | `…data.db.jooq.clickhouse` | `db/migration/analytics/CLICKHOUSE` | Docker (ClickHouse 25.8 via Testcontainers) |

The analytics schema therefore has **two generated twins**. `…jooq.analytics` is the canonical one: shared query code, the record mappers, the schema providers, `FilterWhitelists` and the Postgres repositories all use it, and it defines the column order the API publishes. `…jooq.clickhouse` is used only by code the ClickHouse vendor owns outright — the four `ClickHouse*Repository` overrides and the two `ClickHouse*EntityResolver`s. Mixing the two in one running app is safe: jOOQ fields render by name and every `DSLContext` sets `withRenderSchema(false)`, so the package a `Field` came from never reaches SQL.

ClickHouse codegen configuration (in `build.gradle`) bridges ClickHouse's thinner type metadata — `outputSchema "analytics"` keeps the generated schema class named `Analytics`, and forced types restore `VARCHAR(36)` on id columns (the length drives `JooqTableSchemaResolver`'s `uuid` inference), `JSONB` on the JSON payload columns, and the bounded `VARCHAR` widths. Index constants are generated for the Postgres-sourced models only, because ClickHouse data-skipping index names are table-scoped and the analytics schema declares `idx_id` on two tables.

**Evolving the analytics schema is dual-authored**: change the CLICKHOUSE migration *and* its POSTGRES twin, then rerun **both** tasks and commit both diffs.

**Guards** — one parity test plus one live-schema guard per vendor:

- `AnalyticsModelParityTest` (`data/db/jooq/`) — plain unit test, no Docker, no Spring. Compares the two generated analytics models column-for-column: same tables, same columns in both directions, same Java type / jOOQ type name / length / precision / scale / nullability, the same `'[`-prefixed JSON-array default decision that drives the array-vs-object inference, and the same published `QueryFieldType` map. This is what keeps the twins honest; it fails when only one vendor's migration was updated.
- `JooqSchemaDriftTest` (Zonky embedded PG, under `functional/`) diffs the live `information_schema` against the generated `meta` and `analytics` metadata — the usual "regenerate after a migration" guard for both.
- `ClickHouseSchemaDriftTest` (Docker, no Spring context, under `functional/`) migrates a live ClickHouse instance and diffs `system.columns` against the generated `clickhouse` tables — columns both directions, nullability, and the ClickHouse-type → jOOQ-type mapping the forced types are expected to produce. It catches "edited a CLICKHOUSE migration but forgot to rerun `generateClickHouseJooq`".

**DSLContext beans**: Both `MetaJdbcConfiguration` and `AnalyticsJdbcConfiguration` expose a `DSLContext` bean wrapping `TransactionAwareDataSourceProxy` so jOOQ participates in Spring-managed transactions. `Settings.withRenderSchema(false)` is set so jOOQ emits unqualified table names (tables live in the `public` schema at runtime).

**Exception translation**: Both DSLContext beans use `ExceptionTranslatorExecuteListener` (from `org.springframework.boot.autoconfigure.jooq`) so jOOQ exceptions are translated to Spring's `DataAccessException` hierarchy (preserving HTTP 409 on unique-constraint violations, etc.).

**RecordMapper pattern**: Each entity has a `*RecordMapper @Component` in the mapper package that maps a typed jOOQ `*Record` (or `Record`) to the domain model. JSONB columns surface as `JSONB` objects; call `.data()` to get the raw String before passing to `JsonbMapper`. The one exception is `AggregatedMetricDefinitionRowMapper`, which maps a JOIN-alias result with no generated Record type.

**JSONB inserts**: Use `JSONB.valueOf(jsonString)` for JSONB columns. Null-safe: `json != null ? JSONB.valueOf(json) : null`.
