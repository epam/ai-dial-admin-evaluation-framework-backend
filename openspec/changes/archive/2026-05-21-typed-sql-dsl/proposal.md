## Why

The data access layer is built on `NamedParameterJdbcTemplate` with SQL written as Java text blocks across both meta and analytics datasources. This produces four compounding costs:

1. **No compile-time safety.** Column names, table names, parameter bindings, and result column references are plain strings. Schema drift (renames, drops, type changes) is caught only at runtime — usually by functional tests that happen to exercise the affected query.
2. **Duplicated query-building logic.** Filtering (`WhereBuilder` + `FilterWhitelists`), sorting (`OrderByBuilder` + `SortWhitelists`), pagination (`PageRequestSqlBuilder`), and projection are hand-assembled per repository. Each new list endpoint reinvents whitelisting and clause concatenation.
3. **Onboarding and refactoring friction.** SQL text blocks interleaved with `MapSqlParameterSource` population are hard to skim. IDE rename / find-usages / safe-delete do not work across the SQL boundary, and AI coding agents have to fall back to grep-style reasoning instead of LSP-driven navigation.
4. **Future analytics-vendor risk.** A ClickHouse-backed analytics datasource is on the medium-term roadmap. Text-block SQL is opaque to such a port; a typed DSL keeps query shapes structured and isolates dialect-specific bits to a small seam.

Now is the right time because data-layer surface area is still tractable (≈3,000 LOC across 11 `Postgres*Repository` implementations) and the in-flight `ef-as-dial-app` change does not touch repository internals — minimising merge collision.

## What Changes

- Adopt **jOOQ** as the type-safe query DSL for **both** datasources (meta and analytics). PostgreSQL is in the jOOQ Open Source Edition; no licensing concern. **BREAKING (internal):** every production query path stops using raw `NamedParameterJdbcTemplate` SQL by the end of this change. No coexistence between text-block SQL and jOOQ in the final state.
- Add the **jooq-codegen-gradle** plugin (jOOQ 3.20+) and configure code generation via **`io.zonky.test:embedded-postgres`** so codegen runs without Docker. The generated jOOQ classes (`Tables`, `Records`, `Keys`, `Indexes`) are committed to `src/main/java-generated/` and treated as a build artefact that lives in version control. A schema-drift guard test runs in `./gradlew test` (Zonky-based, no Docker on CI).
- Convert all 11 `Postgres*Repository` implementations to jOOQ. Keep the `Postgres*Repository` naming — vendor abstraction stays so a future `Clickhouse*` analytics sibling can plug in via `@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "CLICKHOUSE")`.
- Rewrite the shared SQL helpers under `data.db.repository.sql/`:
  - `WhereBuilder` returns `org.jooq.Condition` (not `WhereClause(String, MapSqlParameterSource)`).
  - `OrderByBuilder` returns `List<org.jooq.SortField<?>>`.
  - `PageRequestSqlBuilder` returns typed limit/offset and cursor predicates.
  - `FilterFieldDefinition` carries a typed `Field<?>` instead of a `String` column name.
  - **NEW:** a `JsonPathAccessor` component encapsulates PostgreSQL JSONB path traversal (`->`/`->>`, two-level `metricValues.<metric>.<output>` access). This is the single PG-specific seam that a future CH backend would substitute (`JSONExtract(...)`).
  - The whitelist contract (API field name → DB column + allowed operators) is preserved exactly. All `FilterWhitelists.*` and `SortWhitelists.*` entries keep their public API mapping; only the value type changes.
- Replace all `RowMapper<T>` implementations (12 files under `data.db.mapper/` and `data.db.analytics.mapper/`) with typed **`RecordMapper<? extends Record, T>`** components. Domain models, mapping rules, and JSONB deserialization behavior are unchanged. Removing the `ResultSet`/`RowMapper` mechanism is required to eliminate the JDBC-string mental model the issue calls out.
- Delete the dead infrastructure once all repositories are converted: `WhereClause`, `MapSqlParameterSource`-based code paths, `PostgresJsonbSqlParameter`, and the SQL text-block constants in every `Postgres*Repository`.
- Add an **ArchUnit fence** banning `NamedParameterJdbcTemplate` / `JdbcTemplate` imports outside `configuration.datasource` so the migration cannot regress.
- Add two `DSLContext` beans (`metaDsl`, `analyticsDsl`) bound through `TransactionAwareDataSourceProxy` so jOOQ honors the existing meta / analytics `PlatformTransactionManager` qualifiers. `TransactionTimestampAspect` and `TransactionTimestampContext` are unchanged. `@Transactional("metaTransactionManager")` / `@Transactional("analyticsTransactionManager")` semantics stay intact.
- Update AGENTS.md (Do's/Don'ts list — `NamedParameterJdbcTemplate`, `RowMapper`, `MapSqlParameterSource` references become historical / removed) and `docs/code-templates.md` (new template: jOOQ repository, `RecordMapper`, typed filter/sort whitelist).

Explicitly **out of scope**:
- REST API, DTOs, error responses, OpenAPI examples — unchanged.
- Database schema and Flyway migrations — unchanged.
- Transaction management semantics, dual-datasource boundary, `@Qualifier`-based wiring — unchanged.
- Business logic, validation rules, mapping rules — unchanged.

## Capabilities

### New Capabilities
- `typed-sql-dsl`: jOOQ-based query construction across both datasources. Codegen pipeline (committed sources, no Docker on CI), `DSLContext` beans with transaction-aware datasource wrapping, drift-guard test, and the ArchUnit fence that prevents regression to raw `NamedParameterJdbcTemplate`.

### Modified Capabilities
- `entity-filtering`: `WhereBuilder` returns `org.jooq.Condition` instead of `WhereClause(String, MapSqlParameterSource)`. `FilterFieldDefinition.column` becomes `Field<?>` (typed). JSONB path traversal moves into a dedicated `JsonPathAccessor` component. Whitelist API contract (field names, operators, JSONB key validation, error shape) is unchanged.
- `sorting`: `OrderByBuilder` returns `List<org.jooq.SortField<?>>`. Sort whitelist contract preserved.
- `database-and-migrations`: Generated jOOQ classes committed under `src/main/java-generated/`; manual `./gradlew generateJooq` regenerates from current migrations via Zonky embedded PostgreSQL. Drift-guard test runs on CI. No change to Flyway migration locations or process.
- `analytics-datasource`: Two `DSLContext` beans replace the two `NamedParameterJdbcTemplate` beans as the canonical query entry point. Conditional-on-vendor pattern preserved. Cursor-pagination shape on analytics repositories unchanged.
- `testing-conventions`: Functional test data helpers (`MetaTestDataHelper`, `AnalyticsTestDataHelper`) ported to jOOQ. ArchUnit rule added forbidding `NamedParameterJdbcTemplate` outside `configuration.datasource`. Drift-guard test added.

## Impact

**Affected code (new):**
- `build.gradle`: jooq-codegen-gradle plugin, jOOQ runtime dependency, Zonky embedded-postgres, codegen task wired to meta + analytics schemas.
- `src/main/java-generated/com/epam/aidial/evaluation/data/db/jooq/meta/**` and `.../jooq/analytics/**`: committed generated sources.
- `configuration/datasource/MetaJdbcConfiguration.java` and `AnalyticsJdbcConfiguration.java`: add `DSLContext` bean wrapping each datasource with `TransactionAwareDataSourceProxy` and `SQLDialect.POSTGRES`.
- `data/db/repository/sql/JsonPathAccessor.java`: new component for PG JSONB path traversal.
- `src/test/.../JooqSchemaDriftTest.java`: Zonky-driven boot + Flyway + schema comparison against committed jOOQ metadata.
- ArchUnit rule additions in `LayeredArchitectureTest` (or sibling).

**Affected code (modified):**
- All 11 `Postgres*Repository` implementations.
- All 12 `*RowMapper` classes → become `*RecordMapper` components in the same packages.
- `WhereBuilder`, `OrderByBuilder`, `PageRequestSqlBuilder`, `FilterFieldDefinition`, `WhereClause` (deleted), `PostgresJsonbSqlParameter` (deleted).
- `FilterWhitelists` and `SortWhitelists`: `Field<?>` references instead of `String` column names.
- Functional test helpers (`MetaTestDataHelper`, `AnalyticsTestDataHelper`) and any direct SQL in functional tests.
- AGENTS.md (Do's/Don'ts list, key-packages table), `docs/code-templates.md` (jOOQ-based repository/mapper templates).

**Dependencies added:**
- `org.jooq:jooq` (3.20+, OSS edition).
- `org.jooq:jooq-meta-extensions` (codegen only).
- `io.zonky.test:embedded-postgres` (codegen + drift test only).
- `org.jooq:jooq-codegen-gradle` Gradle plugin.

**Dependencies removed:**
- Direct `spring-boot-starter-jdbc` usage from repositories (kept in datasource configuration; required for connection pooling and Flyway).

**No change to:** REST API contracts, DTOs, OpenAPI examples, ErrorView, database schema, Flyway migrations, JWT/security configuration, observability/logging.

**Operational risk:** Schema drift if a developer adds a migration and forgets to regenerate jOOQ classes. Mitigated by the Zonky-based drift-guard test running on every CI build.

**Rollback considerations:** The change is delivered on `feat/917-typed-sql-dsl` as a sequenced set of internally-compiling commits merging once into `development`. Reverting the merge cleanly restores the prior text-block SQL state; no production-state migration (schema, data) is involved.
