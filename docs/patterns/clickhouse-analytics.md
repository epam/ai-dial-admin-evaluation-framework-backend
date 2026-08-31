# ClickHouse analytics vendor

`datasource.analytics.vendor=CLICKHOUSE` is a full alternative to the default `POSTGRES` analytics
vendor, behind the existing meta/analytics seam (see [Dual Datasource](dual-datasource.md)). Design
background: [docs/design/analytics-on-clickhouse/index.html](../design/analytics-on-clickhouse/index.html).

## Render-time dialect switching

`JsonPathAccessor`/`FilterTranslator` build SQL shared by **both** datasources (meta is always
Postgres; analytics is either vendor) — a vendor-gated bean can't express this, since the same
`FilterTranslator` instance renders both kinds of query within one request. `DialectAwareSql`
(`data.db.repository.sql`) solves it with jOOQ `CustomCondition`/`CustomField`, which defer to a
callback run at **render time**, when `ctx.family()` reports the dialect of *that* render call:

```java
DialectAwareSql.condition(family -> family == SQLDialect.CLICKHOUSE ? chSql : pgSqlUnchanged);
```

Every `byFamily` function treats `CLICKHOUSE` as the only special case and renders today's Postgres
SQL byte-identical for every other family — this is what keeps the pre-existing Postgres
render-pinning unit tests passing unmodified. Consumers: `DialectAwareJsonPathAccessor` (`->`/`->>`/
numeric-cast JSONB access), `FilterTranslator` (null-satisfies, negate, array containment),
`PostgresEvalSummaryRepository#lowerName`, `BuiltInQueryFunctions` (`roc_auc`, percentiles,
`width_bucket`), `DialectAwareSql#numericCast`.

**Pitfall — defensive extra parens on a `CustomCondition` operand.** Both `DSL.not(condition)` and a
plain-SQL template's `{0}` substitution add a defensive parenthesis around a `Condition` argument
when it is a `CustomCondition` (unlike a plain jOOQ-native condition, e.g. `Like`, which doesn't get
one). Composing several dialect-aware conditions through `DSL.not(...)` from the outside therefore
changes the paren shape between the "same node negated directly" and "positive node wrapped by a
`not` logical node" cases — harmless (SQL stays valid and semantically identical either way), but
surprising if you're diffing rendered SQL. `FilterTranslator` avoids stacking these where it can (each
negated array-containment form is built as one flat family-specific expression instead of composing
`nullSatisfies`/`negate` around an already-built dialect-aware `Condition`), and pins both shapes in
`FilterTranslatorClickHouseRenderTest` where composition is unavoidable (a `not` logical node wrapping
a `co` comparison).

## ClickHouse vendor twins

Every `ClickHouse*Repository`/`ClickHouse*EntityResolver` **extends** its Postgres twin rather than
reimplementing it — the jOOQ query surface built by the base class already renders correctly for
`CLICKHOUSE` once the injected `DSLContext` uses that dialect, and read paths only need overriding
where ClickHouse's SQL surface or driver genuinely diverges (see "Known engine semantics" below).
`@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "CLICKHOUSE")` gates every
twin; the Postgres base stays the vendor-agnostic default (`matchIfMissing = true` on
`AnalyticsJdbcConfiguration`'s own POSTGRES conditional).

Every twin overrides `saveAll`: ClickHouse has no `ON CONFLICT`, so writes are a plain `dsl.batch(...)`
of `INSERT`s instead of an upsert.

**No-op `analyticsTransactionManager`** (`ClickHouseNoOpTransactionManager`, extends
`AbstractPlatformTransactionManager` with no-op `doBegin`/`doCommit`/`doRollback`): ClickHouse has no
transaction semantics on a single connection, so existing `@Transactional("analyticsTransactionManager")`
demarcations and `TransactionTemplate` callers throughout the analytics service layer keep binding to a
valid bean and executing, they just demarcate nothing. Justification: analytics writes are idempotent,
append-only batches deduplicated at read time (below), so a failed batch can simply be retried.

## Dedup: ReplacingMergeTree as ON CONFLICT

`ReplacingMergeTree` is ClickHouse's stand-in for Postgres' `UNIQUE` + `ON CONFLICT DO NOTHING` — there
is no upsert primitive, and duplicate rows sharing an `ORDER BY` key collapse only in the background,
eventually. Two connection properties on the analytics Hikari pool (`AnalyticsClickHouseConfiguration`)
make reads deterministic without waiting for a merge:

- `clickhouse_setting_final=1` — every read behaves as if `FINAL` were specified, collapsing
  `ReplacingMergeTree` duplicates immediately.
- `clickhouse_setting_join_use_nulls=1` — restores SQL-standard `NULL` fill for an unmatched `LEFT
  JOIN` row (ClickHouse's default fills the type's zero-value instead, e.g. `''` for `String`, which
  silently breaks every anti-match predicate in the run-comparison queries).

These are **connection properties**, not `connectionInitSql = "SET final = 1"`: the ClickHouse V2
driver sends each statement as an independent, stateless HTTP request, so a session-wide `SET` is
silently forgotten per pooled connection (verified against a live server — `system.settings` kept
reporting `final = 0`, and a duplicated key still returned two rows). The `clickhouse_setting_` prefix
attaches the setting to every request instead. `AnalyticsClickHouseConfiguration` is the single source
of truth for this — any comment elsewhere that says "session-wide `SET`" is stale.

`ORDER BY` should equal the Postgres `onConflict` key set. `test_case_run_results` is the one
exception: its `ORDER BY` leads with `test_suite_id`, a **superset** of the PG unique key. The two
keys partition identically today only because `test_suite_id` is functionally dependent on
`test_suite_run_id` — a future feature that reassigns a run's suite must revisit that `ORDER BY`.

## Schema management: Flyway (`flyway-database-clickhouse`)

`AnalyticsClickHouseConfiguration#analyticsFlywayMigration` runs a `flyway-database-clickhouse` Flyway
bean shaped exactly like `AnalyticsFlywayConfiguration`'s Postgres bean — same `baselineOnMigrate(true)`,
`validateMigrationNaming(true)`, `flyway.migrate()` call, and dependency on
`DatasourceValidationResult` for bean ordering. Migrations live under
`db/migration/analytics/CLICKHOUSE`, tracked in a real `flyway_schema_history` table, each script
running exactly once — the same story as Postgres.

Two things are required for this to work, both verified against a live ClickHouse 25.8:

- **clickhouse-jdbc &ge; 0.10.0.** On 0.9.0, the plugin's schema-existence probe
  (`SELECT COUNT() FROM system.databases WHERE name = ?`) failed: the driver's ANTLR statement parser
  couldn't parse the bare `name` column reference, reported zero bind parameters, and `setString()`
  threw `ArrayIndexOutOfBoundsException` before any SQL reached the server. 0.10.0 fixed the parser;
  the probe and a full `flyway.migrate()` both succeed now.
- **A `jdbc:clickhouse://` URL, not `jdbc:ch://`.** The plugin's `ClickHouseDatabaseType.handlesJDBCUrl`
  only claims the `jdbc:clickhouse:` prefix, so it never recognizes the database type on the driver's
  shorter `jdbc:ch:` alias — Flyway would silently fail to find a matching `DatabaseType`. The
  application itself still accepts both prefixes (`DatasourceValidationConfiguration#parseJdbcUrl`);
  only the Flyway-facing defaults (`application.yml`, docker-compose, test fixtures, docs) standardize
  on the long form.

`V1.1__Init.sql` still uses `CREATE TABLE IF NOT EXISTS` — not because scripts re-run on every startup
(they don't, any more), but for transition safety: an environment that ran the old
hand-rolled schema initializer (see history below) already has the tables but no
`flyway_schema_history` row, and `baselineOnMigrate` may still execute `V1.1` there on first boot. New
migrations (V1.2+) are written as ordinary, non-idempotent Flyway scripts.

**History.** Between the initial ClickHouse vendor implementation and this fix, schema management was a
hand-rolled `ClickHouseSchemaInitializer` (`ResourceDatabasePopulator` re-running every script on every
startup, no history table) — a workaround for the 0.9.0 driver bug above. It has been removed now that
the driver bump makes Flyway work.

## Known engine semantics

- **Float64, not `numeric`.** A bare ClickHouse `decimal` means `Decimal(10, 0)` — scale zero — so
  `cast(0.5 as decimal)` silently truncates to `0`. `DialectAwareSql#numericCast` casts to `Float64`
  on ClickHouse instead (coerced back to `NUMERIC` for the caller's `Field<BigDecimal>`); same
  reasoning behind `ClickHouseMetricScoreResultRepository#exactFloat64` rendering an explicit
  plain-decimal string (`toFloat64('0.85...')`) rather than letting jOOQ inline a `Double` in Java's
  scientific notation, which ClickHouse's textual parser is one ULP off on.
- **`lower` is ASCII-only.** ClickHouse's `lower`/lowercase LIKE folding don't fold non-ASCII letters;
  `lowerUTF8` is the Unicode-aware equivalent, used wherever case-insensitive comparison must match
  Postgres' locale-aware `lower` (`PostgresEvalSummaryRepository#lowerName`, `FilterTranslator`'s
  ignore-case array containment).
- **The V2 JDBC driver's parser rejects a `SELECT` nested inside a scalar expression** — e.g. the
  jOOQ-generated `select exists (select 1 … where …)`. It reports zero bind parameters and the bind
  fails before any SQL reaches the server. `ClickHouseEvalSummaryRepository#existsByRunIdAndComputationId`
  replaces it with a `select 1 … limit 1` probe.
- **No `roc_auc_score` stored function** — the built-in `arrayAUC(scores, labels)` covers the same
  need, with its argument order swapped relative to `roc_auc_score(labels, scores)`.
- **No approximate `quantile`.** `percentile_cont`/`percentile_disc` dialect-switch to
  `quantileExactInclusive`/`quantileExactLow` — ClickHouse's default `quantile()` is approximate and
  diverges from PG's exact semantics, breaking deterministic assertions.
- **JSONB payloads are plain `String`/`Nullable(String)` columns**, not ClickHouse's native `JSON`
  type — this keeps the application's serialized payload byte-for-byte rather than letting ClickHouse
  re-parse and re-serialize (and thus mutate) it on read. Array-element containment (`co`/`nc` on an
  `ARRAY`-typed field) therefore goes through `JSONExtract`/`JSONExtractArrayRaw`/`has`/`arrayExists`
  rather than JSONB operators; the literal's `ValueType` (known at translate time) picks the
  `JSONExtract` return type (`Nullable(Float64)` for a number, `Nullable(Bool)` for a boolean) or, for
  a type ClickHouse can't recover faithfully from JSON text (`date`, `uuid`), rejects the query with a
  `ValidationException` (HTTP 400) rather than silently rendering a comparison that can never match.
  See `ClickHouseTypeNames` (`data.db.repository.sql`) for the shared type-literal constants.

## Testing

`ClickHouseFunctionalTests` mirrors `PostgresFunctionalTests` against a real `clickhouse-server`
Testcontainer; render-pinning unit tests (`*ClickHouseRenderTest`) pin the SQL text each dialect-switch
seam produces without a container. Two suites are excluded on ClickHouse:
`RocAucScoreFunctionalTests` (exercises the Postgres stored function directly) and
`EvalSummaryIndexFunctionalTests` (asserts against `pg_indexes`).
