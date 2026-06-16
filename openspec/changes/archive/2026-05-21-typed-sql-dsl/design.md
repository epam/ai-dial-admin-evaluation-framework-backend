## Context

The data access layer today is ~3,000 LOC of `Postgres*Repository` classes that build SQL via Java text blocks and `MapSqlParameterSource`, with three shared helper components (`WhereBuilder`, `OrderByBuilder`, `PageRequestSqlBuilder`) that already act as an untyped DSL.

The current data flow:

```
Service                                                       Repository
  │                                                                │
  ├── List<FilterCondition>, PageRequest ─► whereBuilder.build() ──►
  │     │                                       │                  │
  │     │      FilterWhitelists.TEST_SUITES ◄───┤                  │
  │     │      (Map<apiField, FilterFieldDefinition(String col)>)  │
  │     │                                                          │
  │     ▼                                                          │
  │  WhereClause(String sql, MapSqlParameterSource params)         │
  │     │                                                          │
  │     ▼                                                          │
  │  buildSelectSql(whereClause, orderBy)  ── string concatenation │
  │     │                                                          │
  │     ▼                                                          │
  │  NamedParameterJdbcTemplate.query(sql, params, rowMapper)      │
  │     │                                                          │
  │     ▼                                                          │
  │  RowMapper<TestSuite>                                          │
```

After this change the same flow becomes:

```
Service                                                       Repository
  │                                                                │
  ├── List<FilterCondition>, PageRequest ─► whereBuilder.build() ──►
  │     │                                       │                  │
  │     │      FilterWhitelists.TEST_SUITES ◄───┤                  │
  │     │      (Map<apiField, FilterFieldDefinition(Field<?> col)>)│
  │     │                                                          │
  │     ▼                                                          │
  │  org.jooq.Condition                                            │
  │     │                                                          │
  │     ▼                                                          │
  │  dsl.select(LIST_FIELDS).from(TEST_SUITES)                     │
  │     .where(condition).orderBy(sortFields).limit(...).offset(...)
  │     │                                                          │
  │     ▼                                                          │
  │  Result<TestSuitesRecord> ──► RecordMapper<TestSuitesRecord,T> │
```

**Constraints that drive design decisions:**
- No Docker on CI (user-stated). Codegen must run on a developer machine and the generated sources must be committed.
- Dual datasource (meta + analytics) must keep its `@Qualifier`-based transaction-manager wiring intact.
- ClickHouse is the medium-term analytics-vendor candidate. Architecture should not bake in PostgreSQL-only assumptions outside an isolated seam.
- Layering rule: SQL building lives in `data.db`. The service layer never sees jOOQ types directly — repositories accept domain inputs (`List<FilterCondition>`, `PageRequest`, IDs, models) and return domain outputs (`Page<T>`, `Optional<T>`, models).

## Goals / Non-Goals

**Goals:**
- Eliminate text-block SQL and `MapSqlParameterSource` from every `Postgres*Repository` and from shared query helpers in `data.db.repository.sql/`.
- Make schema drift fail at compile time, not at first runtime query.
- Preserve the exact public contract of the existing filter/sort whitelists, the `Page<T>` / `CursorPage<T>` shapes, and the `OptimisticLockException` signaling on update-version mismatch.
- Localise PostgreSQL-specific JSONB path traversal in one component so a future ClickHouse analytics backend can substitute it without touching every repository.
- Land the migration as a sequenced set of internally-compiling commits on `feat/917-typed-sql-dsl` that merges to `development` once. No coexistence of jOOQ and text-block SQL in the final state.

**Non-Goals:**
- Implementing ClickHouse support now. This change leaves room for it; it does not deliver it.
- Replacing Flyway, changing migration locations, or altering schema.
- Changing REST APIs, DTOs, OpenAPI examples, or error responses.
- Replacing MapStruct (used for DTO ↔ model mapping); MapStruct stays.
- Replacing Lombok or the `@LogExecution` aspect.
- Introducing `dsl.fetchInto(Class)` reflection-based mapping. We use explicit `RecordMapper` components for type safety and to keep mapping logic discoverable.

## Decisions

### D1. jOOQ Open Source Edition + PostgreSQL dialect; ClickHouse dialect deferred

jOOQ OSS (Apache-2.0) supports `SQLDialect.POSTGRES` fully. `SQLDialect.CLICKHOUSE` is also in OSS as of jOOQ 3.20 but is EXPERIMENTAL with known limitations (correlated subqueries, nested JSON access, NULL semantics — see jOOQ #17461). We adopt PostgreSQL today; CH is a future change that will swap dialect + add `Clickhouse*` analytics repositories behind the existing `@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "...")` switch.

Alternatives considered:
- **QueryDSL.** Type-safe DSL, comparable feature surface, but smaller ecosystem, weaker PostgreSQL-specific support (JSONB, `RETURNING`), and Spring-side momentum has shifted to jOOQ. Kept as Plan B only if jOOQ adoption hits an unforeseen blocker.
- **MyBatis Dynamic SQL.** Still relies on string-typed column references for many operations and adds a second mapping layer alongside our existing row mappers. Does not deliver the schema-as-source-of-truth property.
- **Spring Data JDBC / JPA / Hibernate.** Project is explicitly JDBC-only; JPA semantics (lazy loading, dirty checking) conflict with the current explicit-SQL model. Out of scope.

### D2. Codegen via Zonky `embedded-postgres`, sources committed under `src/main/java-generated/`

The codegen pipeline:
1. `./gradlew generateJooq` boots `io.zonky.test:embedded-postgres` (downloads PG binaries from Maven, no Docker required).
2. Flyway applies all migrations from `src/main/resources/db/migration/meta/POSTGRES/` and `.../analytics/POSTGRES/` against the embedded instance.
3. jOOQ codegen reverse-engineers the live schema into `src/main/java-generated/com/epam/aidial/evaluation/data/db/jooq/meta/**` and `.../jooq/analytics/**`.
4. Generated sources are committed to the repository.
5. CI runs `./gradlew build` which compiles the committed generated sources — no codegen, no Docker, no Zonky on CI.

Alternatives considered:
- **`DDLDatabase` / `LiquibaseDatabase`** (in-process H2). jOOQ docs explicitly call out: PostgreSQL JSONB is not supported because H2 cannot parse it. Our schema is JSONB-saturated. `LiquibaseDatabase` is additionally deprecated in jOOQ 3.21. Rejected as not viable.
- **Testcontainers (Docker required).** Conflicts with the no-Docker-on-CI constraint, and forces every developer to have Docker available. Zonky removes that requirement.
- **Live local PostgreSQL.** Workable but fragile — every developer must reproduce schema state and apply migrations before regenerating. Zonky is hermetic by comparison.
- **Generated sources NOT committed (rebuilt every compile).** Forces Zonky to run on CI, which adds 5–10 s and Maven-cached PG binaries to every build. Committing is the standard jOOQ pattern when codegen is decoupled from build.

### D3. Drift-guard test runs in `./gradlew test`, not codegen

The major risk of committed generated sources is staleness — a developer adds a migration and forgets to regenerate. Mitigation: a `JooqSchemaDriftTest` (JUnit) that boots Zonky in-process, applies all migrations, and asserts that the live `information_schema` matches the committed jOOQ metadata for both `meta` and `analytics` schemas (compare table list, column list per table, column types, primary keys, unique constraints — anything that affects generated code shape).

The test runs on every CI build via the regular test suite. Zonky's first boot is ~5 s; cache-warmed subsequent boots are ~1–2 s. Acceptable cost.

Alternatives considered:
- **Pre-commit hook.** Local-only, easily bypassed, doesn't catch contributions from forks.
- **Schema-diff over generated `.java` files.** Brittle to jOOQ-generator version bumps and unrelated formatting changes.
- **No guard.** Rejected — A3 without drift detection is the staleness footgun the original exploration called out.

### D4. `Postgres*Repository` naming preserved; vendor-conditional wiring stays

Repository classes keep the `Postgres*Repository` prefix and the `@ConditionalOnProperty(...vendor=POSTGRES)` guard. This leaves linguistic and wiring room for `Clickhouse*EvalSummaryRepository` (and siblings) as future analytics-vendor implementations behind the same `*Repository` interface. The `*Repository` interface itself is unchanged by this change.

### D5. `RowMapper<T>` → `RecordMapper<? extends Record, T>` everywhere

We replace the 12 `RowMapper` classes with `RecordMapper` components in the same packages, suffixed `*RecordMapper`. Each becomes an injectable `@Component` to remain consistent with the existing pattern. Mapping rules (JSONB deserialization, default values, enum coercion) are unchanged; only the input type changes from `ResultSet` to `org.jooq.Record`.

Rationale: keeping `RowMapper<T>` preserves the JDBC-string mental model the issue explicitly calls out as a drag on onboarding. Doing this *in the same change* avoids leaving a half-converted state where queries are typed but row consumption is not.

Alternative considered: **jOOQ's `Result.into(Class)` reflection mapping.** Rejected because mapping logic becomes invisible (no traceable component); JSONB columns need explicit decoder calls; and it loses the project's "specialized components, never private methods" convention.

### D6. `WhereBuilder` returns `org.jooq.Condition`; `JsonPathAccessor` handles JSONB

Signatures change as follows:

```
// Before
WhereClause WhereBuilder.build(List<FilterCondition>, FilterSpec)
// After
Condition WhereBuilder.build(List<FilterCondition>, FilterSpec)
```

```
// Before
List<String>          OrderByBuilder.build(List<SortKey>, SortSpec)    // emitted as " ORDER BY a ASC, b DESC"
// After
List<SortField<?>>    OrderByBuilder.build(List<SortKey>, SortSpec)
```

`FilterFieldDefinition` carries a `Field<?>` instead of a `String column`. The dotted-key parsing (`metricValues.Accuracy.score`) stays in `WhereBuilder`; once parsed, JSONB access delegates to a new `JsonPathAccessor` component:

```
public interface JsonPathAccessor {
    Field<JSONB>   jsonbAt(Field<JSONB> column, Field<String> key);
    Field<String>  jsonbAtAsText(Field<JSONB> column, Field<String> key);
    Field<BigDecimal> jsonbAtAsNumeric(Field<JSONB> column, Field<String> key1, Field<String> key2);
}
```

The PG implementation uses `DSL.jsonbGetAttribute(...)` / `DSL.jsonbGetAttributeAsText(...)` and a final `.cast(SQLDataType.NUMERIC)` for the two-level numeric case. A future `ClickhouseJsonPathAccessor` would emit `JSONExtractString(...)` / `JSONExtractFloat(...)` against a CH `String` or `Map(...)` column. **This is the single PG-specific seam** introduced by the change.

### D7. Optimistic locking via `.returning().fetchOne()`

Current pattern (`PostgresTestSuiteRepository.java:240-248`):

```java
try {
    TestSuite updated = jdbcTemplate.queryForObject(UPDATE_SQL_WITH_RETURNING, params, rowMapper);
    return updated;
} catch (EmptyResultDataAccessException ex) {
    throw new OptimisticLockException(...);
}
```

After:

```java
TestSuitesRecord updated = dsl.update(TEST_SUITES)
        .set(TEST_SUITES.NAME, suite.getName())
        .set(TEST_SUITES.VERSION, TEST_SUITES.VERSION.plus(1))
        // ...
        .where(TEST_SUITES.ID.eq(idStr).and(TEST_SUITES.VERSION.eq(expectedVersion)))
        .returning()
        .fetchOne();
if (updated == null) {
    throw new OptimisticLockException(...);
}
return testSuiteRecordMapper.map(updated);
```

One round-trip preserved. Behavior identical.

State-transition update methods on meta repositories (e.g. `updateToRunning`, `updateToFailed`, `updateSuiteSnapshot`, `updateNumberOfTestCases`) continue to take `updatedAt` / `completedAt` / `startedAt` as explicit parameters per the AGENTS.md convention (`TransactionTimestampContext + Aspect` section). The jOOQ port does NOT change those method signatures and does NOT introduce `TransactionTimestampContext.getTimestamp()` calls inside repository methods.

### D8. Batch insert with `ON CONFLICT DO NOTHING`

Current pattern (analytics, `PostgresEvalSummaryRepository.saveAll`):

```java
jdbcTemplate.batchUpdate(INSERT_SQL_WITH_ON_CONFLICT, batchParams);
```

After:

```java
List<InsertOnDuplicateStep<EvalSummariesRecord>> queries = summaries.stream()
        .map(s -> dsl.insertInto(EVAL_SUMMARIES, EVAL_SUMMARIES.ID, /* ... */)
                .values(s.getId().toString(), /* ... */)
                .onConflict(EVAL_SUMMARIES.TEST_SUITE_RUN_ID, EVAL_SUMMARIES.TEST_CASE_ID,
                        EVAL_SUMMARIES.RUN_INDEX, EVAL_SUMMARIES.COMPUTATION_ID,
                        EVAL_SUMMARIES.CREATED_AT_MS)
                .doNothing())
        .toList();
dsl.batch(queries).execute();
```

Per jOOQ #6092 we cannot use `batchInsert(records)` with `.onDuplicateKeyIgnore()` — we build per-row `InsertQuery` and pass them all to `dsl.batch(...)`. PG native `ON CONFLICT DO NOTHING` is preserved; no MERGE emulation.

### D9. Dynamic projection tiers stay; expressed as `List<Field<?>>`

`PostgresEvalSummaryRepository`'s four SQL constants (`SELECT_LIST_COLUMNS`, `SELECT_EXPORT_COLUMNS`, `SELECT_EXPORT_JOIN_COLUMNS`, `SELECT_BY_ID_DETAIL_SQL`) become four `List<Field<?>>` constants of typed `Field` references. The `s.` / `r.` aliases for the JOIN variant become `EVAL_SUMMARIES.as("s")` and `TEST_CASE_RUN_RESULTS.as("r")`. TOAST-avoidance behavior is preserved by selecting the same column subset.

Keyset pagination retains the `LIMIT size + 1` lookahead and the composite-PK `ORDER BY ... DESC` pattern documented in AGENTS.md (`CursorCodec and Keyset Pagination`). Worked example of the jOOQ shape used by `PostgresEvalSummaryRepository.findAll`:

```java
List<EvalSummaryRow> rows = dsl
    .select(SELECT_LIST_COLUMNS)
    .from(EVAL_SUMMARIES)
    .where(condition.and(pageRequestSqlBuilder.cursorPredicate(cursor)))
    .orderBy(EVAL_SUMMARIES.CREATED_AT_MS.desc(), EVAL_SUMMARIES.ID.desc())
    .limit(size + 1)
    .fetch(evalSummaryRecordMapper);
boolean hasMore = rows.size() > size;
if (hasMore) rows = rows.subList(0, size);
Cursor next = hasMore ? new Cursor(rows.get(rows.size()-1).createdAtMs(), rows.get(rows.size()-1).id()) : null;
return new CursorPage<>(rows, next, hasMore);
```

### D10. `TransactionAwareDataSourceProxy` for both `DSLContext` beans

```java
@Bean
@Qualifier("metaDsl")
public DSLContext metaDsl(@Qualifier("metaDataSource") DataSource ds) {
    return DSL.using(new TransactionAwareDataSourceProxy(ds), SQLDialect.POSTGRES);
}
```

This ensures jOOQ honors Spring's transaction synchronisation — `@Transactional("metaTransactionManager")` continues to bind a single Connection across all `metaDsl` operations within the transaction boundary, exactly as `metaJdbcTemplate` does today. `TransactionTimestampAspect` (meta-only) is unaffected because it only inspects the `@Transactional` qualifier name.

### D11. ArchUnit fence: ban `NamedParameterJdbcTemplate` / `JdbcTemplate` outside `configuration.datasource`

Without this fence, the migration can silently regress — any future contributor reaches for the familiar API and reintroduces text-block SQL. The rule is added alongside the existing `LayeredArchitectureTest`.

### D12. Functional test helpers ported to jOOQ; assertions stay model-level

`MetaTestDataHelper` and `AnalyticsTestDataHelper` move from raw INSERT/SELECT to `dsl.insertInto(...)` and `dsl.select(...).fetchInto(...)`. Assertions in functional tests still go through the repository interfaces (`repository.findById(id)`, `repository.count()`) — no test-class-level jOOQ usage outside helpers. Existing back-door helpers (`forceSuiteInvalid` and friends) are preserved as method intent.

### D13. Sequencing on `feat/917-typed-sql-dsl`

Internal commits on the branch, each compiles + passes tests:

1. **Build plumbing.** Add jOOQ + Zonky deps, codegen task, generated sources for both schemas. No callers yet. `DSLContext` beans wired but only injected by a smoke-test bean. Drift-guard test added.
2. **Spike repository.** Convert one small repository end-to-end as a reference (e.g. `PostgresMetricDeclarationRepository`) including its `RowMapper` → `RecordMapper` swap and helper conversion. Functional tests for it must pass.
3. **Shared helpers.** Rewrite `WhereBuilder`, `OrderByBuilder`, `PageRequestSqlBuilder`, `FilterFieldDefinition`, `FilterWhitelists`, `SortWhitelists` to typed jOOQ outputs. Add `JsonPathAccessor`. The two converted repositories (from step 2 and any new one) compile against the new signatures; the old text-block repos still compile because they don't depend on these signatures yet — but the change must be sequenced so callers update in step.
4. **Repository conversion sweep.** One commit per repository, alphabetised: meta first (8 commits), then analytics (3 commits). Each commit converts the repository, swaps the matching mapper, runs that repository's functional tests.
5. **Delete dead infrastructure.** `WhereClause`, `PostgresJsonbSqlParameter`, all SQL text-block constants. The `@RequiredArgsConstructor` field for `NamedParameterJdbcTemplate` is removed.
6. **Add ArchUnit fence.** Verify it triggers if `NamedParameterJdbcTemplate` is imported anywhere except `configuration.datasource`.
7. **Docs sweep.** AGENTS.md Do's/Don'ts list update; `docs/code-templates.md` rewrite for jOOQ-based templates; delta specs for `entity-filtering`, `sorting`, `database-and-migrations`, `analytics-datasource`, `testing-conventions`.

## Risks / Trade-offs

- **[Schema drift undetected]** A developer adds a Flyway migration and forgets to regenerate jOOQ classes → CI compiles fine but generated metadata diverges from real schema. → **Mitigation:** D3 drift-guard test fails on the next CI run. Test failure message lists exact diffs ("column `foo.bar` not present in generated metadata, regenerate with ./gradlew generateJooq").
- **[ClickHouse port underestimated]** "jOOQ + dialect switch" reads as "swap one annotation", but CH's data model differs from PG (no UPDATE/DELETE in the OLTP sense, no JSONB, NULL semantics). → **Mitigation:** D6 isolates the PG-specific JSON access in `JsonPathAccessor`. CH port is still real engineering work; this change does not pretend otherwise.
- **[`batchInsert` + `onConflict` ergonomics]** jOOQ #6092: cannot compose `batchInsert(Record...)` with `.onDuplicateKeyIgnore()`. We use `dsl.batch(queries)` over per-row `InsertQuery`. Slightly more verbose; same wire-level behavior. → No mitigation needed beyond noting it in `docs/code-templates.md`.
- **[Generated sources bloat the repo]** Codegen for two schemas ≈ 30+ tables produces several hundred kilobytes of `.java`. → Accepted cost. Generated sources are stable across non-schema edits and diff cleanly per migration.
- **[Zonky binary cache size on developer machines]** First `./gradlew generateJooq` downloads ~30 MB of PG binaries to `~/.embedpostgresql/`. → One-time cost. Documented in `docs/code-templates.md`.
- **[Two `DSLContext` beans + Lombok `@Qualifier`]** Field injection via `@RequiredArgsConstructor` + `@Qualifier` requires `lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier` in `lombok.config` — already present in this project. → No mitigation needed.
- **[`UPDATE … RETURNING` for non-PG dialects]** Behaviour is PG-specific. → For meta DB this is fine (PG-only by design). For analytics, if CH replaces PG, optimistic locking is irrelevant (append-only model). No analytics-side optimistic locks exist today. → No action required.
- **[Functional test helper changes have wide blast radius]** Helpers are used by 65 functional test files. → **Mitigation:** Helper signatures stay the same (same method names, same arguments); only internals change. Tests do not need to be touched except where they directly inject `JdbcTemplate` (forbidden today per AGENTS.md anyway — should be zero occurrences).
- **[Reviewer cost on a single large branch]** Total diff is large even split into ~15 commits on a feature branch. → **Mitigation:** Sequenced commits each pass tests and `checkstyleMain`. Reviewer can step through commit-by-commit. PR description must explicitly list the commit sequence and the per-repository conversion checklist.

## Migration Plan

This is a **code-only refactor with no production-state migration**. No data migration, no schema change, no API change, no rollout phasing.

Deployment is a single merge to `development` once the branch is approved. Rollback is a single `git revert` of the merge commit.

The only artefact written during the change is `src/main/java-generated/`. If a downstream branch needs to absorb the change before merge, regenerating from current `development` migrations and rebasing is the standard recipe.

## Open Questions

- **Generated package path: `data.db.jooq.meta` and `data.db.jooq.analytics`, or single `data.db.jooq` with table prefix?** Preference: two subpackages — clean import lines, no name collisions if both schemas ever share a table name. Defer final answer to implementation; either choice is reversible.
- **Should `JsonPathAccessor` live in `data.db.repository.sql/` or `data.db.repository.sql.json/`?** Cosmetic; the latter signals "this is the dialect seam" more clearly. Recommended: `data.db.repository.sql.json`.
- **Does the OSS edition allow disabling code generation for some tables (`flyway_schema_history`)?** Yes via `excludes` regex in the codegen XML. To be configured in `build.gradle`.
- **Drift-guard test runtime budget on CI.** First-time Zonky boot is ~5 s; if this proves intolerable for fast-feedback PRs we can gate it behind a profile that runs only on main-targeting PRs. Defer until measured.
