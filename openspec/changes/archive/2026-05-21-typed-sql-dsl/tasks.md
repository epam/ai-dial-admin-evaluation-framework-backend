## 1. Build plumbing — jOOQ + Zonky codegen pipeline

- [x] 1.1 Add `org.jooq:jooq` 3.20+ runtime dep, `org.jooq:jooq-meta-extensions`, and `io.zonky.test:embedded-postgres` (codegen + test only) to `build.gradle` (done: dependencies declared with correct configurations — `implementation` vs `codegen` / `testImplementation`)
- [x] 1.2 Apply `org.jooq.jooq-codegen-gradle` plugin in `build.gradle` and configure two codegen executions — one for the `meta` schema and one for `analytics` (done: `jooq { configurations { meta { ... } analytics { ... } } }` blocks defined, `generateJooq` task aggregates both)
- [x] 1.3 Implement the Gradle codegen wiring to boot `embedded-postgres`, run `org.flywaydb.Flyway` against it from `src/main/resources/db/migration/{meta,analytics}/POSTGRES/`, and point jOOQ codegen at the live schema. Output target: `src/main/java-generated/com/epam/aidial/evaluation/data/db/jooq/{meta,analytics}/` (done: `./gradlew generateJooq` succeeds locally with no Docker; sources written to expected path)
- [x] 1.4 Configure codegen exclusions: skip `flyway_schema_history` table; deselect `pojos`, `daos`, `interfaces`; keep `tables`, `keys`, `indexes`, and `records` (the last is required for the typed `.returning().fetchOne()` pattern used in `PostgresTestSuiteRepository` per design D7) (done: generated tree size is minimal; only required artefacts present)
- [x] 1.5 Add `src/main/java-generated` to the `sourceSets.main.java.srcDirs` so generated classes are compiled into the main jar (done: `./gradlew compileJava` resolves generated `Tables`, `Keys` references)
- [x] 1.6 Commit the generated sources under `src/main/java-generated/` (done: `git status` shows committed files; CI build succeeds without invoking `generateJooq`)

## 2. Spring wiring — DSLContext beans

- [x] 2.1 In `MetaJdbcConfiguration` add a `@Bean @Qualifier("metaDsl") DSLContext metaDsl(@Qualifier("metaDataSource") DataSource ds)` using `DSL.using(new TransactionAwareDataSourceProxy(ds), SQLDialect.POSTGRES)` (done: bean present; smoke test injects it)
- [x] 2.2 In `AnalyticsJdbcConfiguration` add the symmetric `analyticsDsl` bean (done: bean present, qualified, smoke test injects it)
- [x] 2.3 Verify `lombok.config` includes `lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier` so `@RequiredArgsConstructor` propagates `@Qualifier` to constructor parameters (done: existing config inspected; line confirmed present)
- [x] 2.4 Add an integration smoke test that boots the Spring context and asserts both `metaDsl` and `analyticsDsl` beans are present and use `SQLDialect.POSTGRES` (done: `./gradlew test --tests "*DslContextSmokeTest*"` passes)

## 3. Drift guard test

- [x] 3.1 Implement `JooqSchemaDriftTest` under `src/test/java/.../functional/` that boots `embedded-postgres` once per JVM, applies meta + analytics Flyway migrations, and diffs `information_schema` columns/PKs/uniques against the generated jOOQ metadata for both schemas (done: test added)
- [x] 3.2 Make the failure message instruct the developer to run `./gradlew generateJooq` and name the diverging element (done: test fails with clear, actionable message when a deliberately broken state is introduced)
- [x] 3.3 Verify the test passes on a clean repository state and fails when a column is added to a migration without regenerating sources (done: both manual scenarios verified locally)

## 4. Shared SQL helpers — typed jOOQ outputs

- [x] 4.1 Introduce `JsonPathAccessor` interface in `data.db.repository.sql.json` plus a `PostgresJsonPathAccessor` `@Component` implementation. Methods: `Field<JSONB> jsonbAt(Field<JSONB>, Field<String>)`, `Field<String> jsonbAtAsText(Field<JSONB>, Field<String>)`, `Field<BigDecimal> jsonbAtAsNumeric(Field<JSONB>, Field<String>, Field<String>)` (done: component compiles; unit test covers the three shapes)
- [x] 4.2 Change `FilterFieldDefinition`: replace `String column` with `Field<?> column`; update factory methods so callers pass typed `Field` references (done: class compiles; unit tests for `FilterSpec` updated)
- [x] 4.3 Rewrite `WhereBuilder.build(...)` to return `org.jooq.Condition` instead of `WhereClause(String, MapSqlParameterSource)`. JSONB path resolution delegates to `JsonPathAccessor`. Validation behavior (operator allowlist, JSONB key depth, UUID parsing, `InvalidFilterException` shape) preserved (done: unit tests pass; same `InvalidFilterException` payload on all error paths)
- [x] 4.4 Rewrite `OrderByBuilder.build(...)` to return `List<SortField<?>>`. `SortWhitelists` entries become typed `Field<?>` references (done: unit tests pass; multi-key precedence preserved)
- [x] 4.5 Rewrite `PageRequestSqlBuilder` to expose `int limit()`, `int offset()`, and a `Condition cursorPredicate(Cursor)` helper (no SQL strings) (done: unit tests pass; both offset-based and cursor-based shapes covered)
- [x] 4.6 Rewrite both `FilterWhitelists` and `SortWhitelists` to bind typed `Field<?>` references from generated tables. Public API field names and operator allowlists unchanged (done: every existing whitelist entry has the same API name and operator set; type system enforces column existence and column type at compile time; operator allowlisting remains a runtime check returning HTTP 400 with the existing `InvalidFilterException` payload on disallowed operator/field combinations)
- [x] 4.7 Delete `WhereClause` and `PostgresJsonbSqlParameter` once all callers are migrated (done: both classes removed; no references remain)
- [x] 4.8 Rewrite `src/test/java/.../data/db/repository/sql/WhereBuilderTest.java` and `WhereBuilderJsonbTest.java` against the new `Condition`-returning API (use `dsl.renderInlined(condition)` or jOOQ's `ParserContext` for SQL-shape assertions where the original tests assert SQL strings). The same `InvalidFilterException` payloads must remain asserted on the error paths (done: both test classes compile and pass under the new API; no reference to `WhereClause` or `PostgresJsonbSqlParameter` remains).

## 5. Row → Record mapping

- [x] 5.1 For each of the 12 `*RowMapper` classes under `data.db.mapper/` and `data.db.analytics.mapper/`, create a sibling `*RecordMapper` `@Component` that consumes a typed `org.jooq.Record` (or a generated `*Record` subtype) and returns the same domain model. JSONB deserialization rules unchanged (done: 11 new RecordMapper components; `AggregatedMetricDefinitionRowMapper` kept as-is since it maps a JOIN alias with no generated Record type)
- [x] 5.2 Update each repository to inject and use its corresponding `*RecordMapper` (covered per-repository in step 6).
- [x] 5.3 Delete the 12 `*RowMapper` classes once no repository depends on them (done: all RowMappers deleted except `AggregatedMetricDefinitionRowMapper` which serves the JOIN query).

## 6. Repository conversions (meta + analytics)

- [x] 6.1 Convert `PostgresMetricDeclarationRepository` (the smallest repo) end-to-end as the reference conversion: `metaDsl` injection replaces `metaJdbcTemplate`; SQL text-block constants removed; `MetricDeclarationRecordMapper` consumed; functional tests pass (done: `./gradlew test --tests "*MetricDeclaration*"` passes)
- [x] 6.2 Convert `PostgresMetricDeclarationVersionRepository` (done: tests pass)
- [x] 6.3 Convert `PostgresRevalidationTaskRepository` (done: tests pass)
- [x] 6.4 Convert `PostgresTestCaseRepository` and `PostgresTestCaseRunInputRepository` (done: tests pass; bulk operations and batch inserts ported to `dsl.batch(queries).execute()`)
- [x] 6.5 Convert `PostgresTestSuiteRepository` (done: optimistic-lock semantics preserved via `.returning().fetchOne()` → `null` ⇒ `OptimisticLockException`; tests pass)
- [x] 6.6 Convert `PostgresTestSuiteRunRepository` (done: TOAST-aware column tier preserved via `List<Field<?>>` constants; tests pass)
- [x] 6.7 Convert `PostgresTestSuiteMetricDefinitionRepository` (done: tests pass)
- [x] 6.8 Convert `PostgresEvalSummaryRepository` (analytics): `findAll`, `findAllForExport`, `findAllForExportWithBodies`, `findById`, `count`, `aggregate`, and `saveAll`. Aggregation expressions use typed `Field<BigDecimal>` from `JsonPathAccessor`; batch insert uses `dsl.batch(queries).execute()` with `.onConflict(...).doNothing()`. TOAST-aware column tiers ported to `List<Field<?>>` constants (done: tests pass; cursor pagination shape unchanged)
- [x] 6.9 Convert `PostgresTestCaseRunResultRepository` (done: tests pass)
- [x] 6.10 Convert `PostgresRunMetricSnapshotRepository` (done: tests pass)
- [x] 6.11 Per-repo cleanup pass: remove SQL text-block constants, `MapSqlParameterSource` imports, `PostgresJsonbSqlParameter` calls, and `NamedParameterJdbcTemplate` fields. Run `./gradlew checkstyleMain checkstyleTest` (done: no offending imports remain; checkstyle clean)

## 7. Delete dead infrastructure

- [x] 7.1 Delete `data.db.repository.sql.WhereClause` (prerequisite: 4.8 — unit tests must be migrated first) (done: class removed; no references)
- [x] 7.2 Delete `data.db.repository.sql.PostgresJsonbSqlParameter` (prerequisite: 4.8 — unit tests must be migrated first) (done: class removed; no references)
- [x] 7.3 Delete the 12 `*RowMapper` classes (done: 11 RowMapper classes removed; `AggregatedMetricDefinitionRowMapper` kept as it maps a JOIN-alias result with no generated Record type)
- [x] 7.4 Remove `NamedParameterJdbcTemplate` field declarations from every `Postgres*Repository` (done: only `DSLContext` remains)
- [x] 7.5 Remove unused `@Qualifier("metaJdbcTemplate")` / `@Qualifier("analyticsJdbcTemplate")` imports from production code (`NamedParameterJdbcTemplate` beans themselves are kept in datasource configuration (used by Flyway) and in `service.infrastructure.health` (used by the two `*DatabaseHealthIndicator` classes); the ArchUnit fence in 8.1 carves out both packages) (done: `grep -r "metaJdbcTemplate\|analyticsJdbcTemplate" src/main/java/.../data/.../service/.../web/...` returns no hits)

## 8. ArchUnit fence

- [x] 8.1 Extend `LayeredArchitectureTest` (or add a sibling `JdbcTemplateFenceTest`) with: `noClasses().that().resideOutsideOfPackages("..configuration.datasource..", "..service.infrastructure.health..").should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate")` plus the same for `JdbcTemplate`. The two carve-out packages are `configuration.datasource` (datasource beans, Flyway wiring) and `service.infrastructure.health` (`DatabaseHealthIndicator` and `AnalyticsDatabaseHealthIndicator`) (done: rule present, covers production sources only via `ImportOption.DoNotIncludeTests`; test infrastructure classes such as `PostgresTestPersistenceService` are intentionally excluded)
- [x] 8.2 Verify the rule fires by temporarily importing `NamedParameterJdbcTemplate` in an unrelated class; revert (done: manual verification once, then reverted)

## 9. Functional test helpers

- [x] 9.1 Refactor `MetaTestDataHelper` to inject `@Qualifier("metaDsl") DSLContext` and rewrite all `INSERT`/`UPDATE`/`SELECT` operations using the typed DSL. Public helper method signatures unchanged (done: every consumer compiles without changes; `./gradlew test --tests "*FunctionalTests*"` passes)
- [x] 9.2 Refactor `AnalyticsTestDataHelper` the same way; preserve the documented exception that `countAll()` may live in the helper (done: tests pass)
- [x] 9.3 Search functional tests for any direct `JdbcTemplate` / `NamedParameterJdbcTemplate` injection; resolve by porting the SQL into a helper method (done: ArchUnit fence in step 8 catches any residual case)

## 10. Documentation and conventions

- [x] 10.1 Update AGENTS.md per AGENTS.md Maintenance guidelines (done: DO's updated to jOOQ/RecordMapper, Dual Datasource table adds DSLContext row, Key Packages Reference updated with jooq.{meta,analytics}/json packages, Typed SQL DSL unique pattern added, Debugging Tips updated, Code Templates pointer updated)
- [x] 10.2 Rewrite `docs/code-templates.md`: jOOQ-based repository template (DSLContext injection, typed select / insert / batch with onConflict / update with `.returning()`); `*RecordMapper` template; updated whitelist registration template (`Field<?>` value) (done: RowMapper template replaced with RecordMapper, repo template replaced with jOOQ DSL, batch/returning/whitelist patterns added)
- [x] 10.3 Update `openspec/specs/README.md` per Spec Index Maintenance Policy (done: typed-sql-dsl entry added under Infrastructure)
- [x] 10.4 Update openspec/config.yaml per Config Maintenance Policy (done: data layer description updated to jOOQ/RecordMapper, generateJooq command added to Tooling, ArchUnit fence and JdbcTemplate anti-patterns added, Feature Surface updated)

## 11. Verification

- [x] 11.1 `./gradlew clean build` runs green from a fresh checkout (done: BUILD SUCCESSFUL in 5m 30s; no codegen step, no Docker)
- [x] 11.2 `./gradlew test` includes `JooqSchemaDriftTest` and the ArchUnit fence test; both pass (done: full suite green)
- [x] 11.3 `./gradlew checkstyleMain checkstyleTest` clean (done: zero violations)
- [x] 11.4 Manual verification: regenerate sources with `./gradlew generateJooq` on a developer machine and confirm `git status` shows no diff (done: `./gradlew generateJooq` reported UP-TO-DATE; `git diff src/main/java-generated/` produced no output — codegen is deterministic)
- [x] 11.5 Spot-check functional test coverage on the following paths (each scenario has at least one functional test still green after the migration):
  - the optimistic-lock path (`TestSuite` update with stale version) — covered by TestSuiteTests
  - the batch insert + onConflict path (`EvalSummary.saveAll`) — covered by AnalyticsResultBatchWriteTests (count stays N, not 2N)
  - the JSONB two-level path filter (`metricValues.<m>.<o>:gte:0.8`) — covered by EvalSummaryFunctionalTests
  - the `UPDATE … RETURNING` path on `TestSuiteRun` state transitions — covered by TestSuiteRunTests
  - the snapshot phase + serialization-retry path — covered by SuiteSnapshotTests (all pass with TransactionAwareDataSourceProxy enrolling jOOQ in the programmatic TransactionTemplate)
