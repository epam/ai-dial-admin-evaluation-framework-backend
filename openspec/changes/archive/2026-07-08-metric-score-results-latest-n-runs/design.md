## Context

`metric_score_result` (analytics DB, migration `V1.10`) stores Phase-3 metric-score results keyed only by `test_suite_run_id` + `computation_id`, with no suite reference and no timestamp. The `metric_score_results` Query-DSL entity derives its schema directly from the generated jOOQ table (`MetricScoreResultSchemaProvider` → `JooqTableSchemaResolver.resolve(METRIC_SCORE_RESULT)`), so it can only filter/sort over those columns. Answering "the latest N score aggregations for a suite" is therefore impossible today: there is no suite-level scope key, and no column to define "latest".

Constraints from the codebase:
- JDBC-only, jOOQ typed DSL; generated sources in `src/main/java-generated/` must be regenerated (`./gradlew generateJooq`), never hand-edited.
- Project-wide timestamp convention is epoch-millis `Long`; the sibling analytics table `run_metric_snapshots` already uses `computed_at_ms BIGINT`.
- Production code must not call `Instant.now()`/`System.currentTimeMillis()` — inject `Clock`.
- Both `test_suite_id` (from `test_case_eval_summaries`/`test_case_run_results`) and `computed_at_ms` (from `run_metric_snapshots`) are available **within the analytics DB**, so existing rows can be backfilled without a cross-datasource join.

## Goals / Non-Goals

**Goals:**
- Add `test_suite_id VARCHAR(36)` and `computed_at_ms BIGINT` to `metric_score_result`, backfilled and `NOT NULL`.
- Populate both on the write path so every new result carries them.
- Make both columns queryable through the existing `metric_score_results` entity with zero schema-provider changes.
- Verify end-to-end that the DSL can express `filter test_suite_id eq X, sort computed_at_ms DESC, limit N`.

**Non-Goals:**
- No new REST endpoint, DTO, or config property.
- No new built-in statistic or server-side "latest N aggregation" query — callers compose it from existing filter/sort/limit primitives.
- No change to the natural key, append-only semantics, or the code-defined statistics catalog.
- No Postgres partitioning work (the timestamp merely keeps that option open later).

## Decisions

**1. `computed_at_ms BIGINT` (epoch millis), not `TIMESTAMPTZ`.**
Matches `run_metric_snapshots.computed_at_ms` and the project epoch-millis `Long` convention, so `JooqTableSchemaResolver` maps it to `LONG` with no type override and MapStruct/DSL handling stays uniform. Alternative (`TIMESTAMPTZ`) is more idiomatic SQL but diverges from convention and needs a DSL `QueryFieldType` override.

**2. `test_suite_id` denormalized onto the row.**
The Query DSL is entity-agnostic and cannot join `metric_score_result → test_suite_runs`. Denormalizing the suite id lets `filter test_suite_id eq X` work directly. The value is available at write time from `MetricScoreComputationContext.testSuiteId` (already a field) — no new plumbing to obtain it.

**3. Timestamp sourced from an injected `Clock`, captured once per computation.**
`MetricScoreComputationExecutor` takes a `Clock` (bean from `ClockConfiguration`) and reads `clock.millis()` once at the top of `execute(ctx)`, threading it into `buildResult(...)`. All results of a computation therefore share one timestamp (matches the spec and enables deterministic `Clock.fixed` unit tests). Alternative (setting the timestamp in the repository per-insert) was rejected: it would scatter time access into the data layer and could yield differing timestamps across a batch.

**4. Schema exposure is automatic.**
`MetricScoreResultSchemaProvider` is unchanged; the resolver picks up the new columns after jOOQ regen: `test_suite_id` (VARCHAR(36) → UUID) and `computed_at_ms` (BIGINT → LONG). Verified against the resolver's type rules — no override needed.

**5. Backfill within the analytics DB, then `SET NOT NULL`.**
Migration adds the columns nullable, backfills `computed_at_ms` from `run_metric_snapshots` (`MIN(computed_at_ms) GROUP BY (test_suite_run_id, computation_id)` to avoid fan-out, since all snapshot rows of a computation share the timestamp) and `test_suite_id` from `SELECT DISTINCT test_suite_run_id, test_suite_id FROM test_case_eval_summaries`, then sets both `NOT NULL`. This is a one-time operational step (lives in the migration/tasks, not the spec).

**6. Add supporting index `(test_suite_id, computed_at_ms)`.**
Supports the suite-scoped, time-ordered latest-N access pattern the change is built for.

**Component interaction flow (write path):**
`TestSuiteEvaluationJob` → `MetricScoreComputation.execute(ctx)` → `MetricScoreComputationExecutor` builds `MetricScoreResult`s (now with `testSuiteId` = `ctx.getTestSuiteId()`, `computedAtMs` = captured `clock.millis()`) → `MetricScoreService.saveAll` → `PostgresMetricScoreResultRepository.saveAll` (insert sets the two new columns; `onConflict…doNothing` unchanged). Transaction boundary is unchanged (`@Transactional("analyticsTransactionManager")` on the service).

**Read path (unchanged code):** `POST /api/v1/queries/execute` → `StructuredQueryService` → `PostgresMetricScoreResultQueryRepository` → `StructuredQueryExecutor.execute("metric_score_results", analyticsDsl, METRIC_SCORE_RESULT, query)` (cache-backed overload). The new fields flow through automatically.

## Risks / Trade-offs

- **Orphan rows can't be backfilled → `SET NOT NULL` fails** → A `metric_score_result` row implies a completed computation that also wrote `run_metric_snapshots` + `test_case_eval_summaries`, so orphans should not exist; the migration defensively `DELETE`s any still-null rows before `SET NOT NULL` (scores are append-only and regenerable, so deletion is safe).
- **Denormalized `test_suite_id` could drift from the run's suite** → It is written once at computation time from the run context and never mutated; runs never change owning suite, so drift is not possible.
- **jOOQ regen drift** → Failing to run `generateJooq` breaks compilation and `JooqSchemaDriftTest`; the tasks include regen + committing the generated diff.
- **Bindings cache staleness** → `StructuredQueryExecutor` caches bindings per `Table<?>`; the cache is keyed by the (regenerated) table instance and populated at runtime, so new columns are reflected with no cache concern.

## Migration Plan

1. Add `V1.12__AddSuiteAndTimestampToMetricScoreResult.sql` (add nullable columns → backfill → delete un-backfillable → `SET NOT NULL` → add index).
2. `./gradlew generateJooq`; commit regenerated sources under `src/main/java-generated/.../analytics/`.
3. Ship model/mapper/repository/executor changes together (Flyway auto-applies on startup).
4. **Rollback:** the change is additive (new columns, new index). If rollback is required before adoption, a follow-up migration can drop the index and columns; no data loss for existing behavior since the natural key and `value` are untouched.

## Decisions — subquery-valued `in` (single-request latest-N)

**Motivation.** With `test_suite_id` + `computed_at_ms`, the DSL can scope by suite and order by time, but "all metric score results for the latest N runs" needs *top-N distinct runs, then all their rows* — a subquery/window. The generic DSL's `in` only accepted a literal `array`, forcing a two-step client flow. This change lifts that.

**Key decision — a subquery is compiled to a nested `SELECT` during translation (one SQL statement).** A subquery is literally "a query inside a query", so it maps onto the DSL directly: `FilterTranslator` builds the nested query with `StructuredQueryBuilder` and emits `left IN (SELECT …)`. The execute-and-inline alternative (a pre-pass) was rejected — it produces two statements and re-types values; nested SQL is the SQL-native, atomic choice.

**7. New `Expr` kind `SubqueryExpr(StructuredQuery query)`** (Jackson subtype `"subquery"`), valid **only** as the right operand of `in`. Sealed `switch (Expr)` sites are updated compiler-guided; `ExprTranslator.toField` rejects a bare `subquery` (like `array`) with a clear message.

**8. Compilation via derived-table wrap.** On `in` with a `SubqueryExpr`, `FilterTranslator` builds the nested query via the lazy builder, then wraps it: `left.in(select(firstColumn).from(subselect.asTable()))`. The subquery's **first select column is the membership key**; remaining columns exist only to drive its `ORDER BY`/`LIMIT`, so `ORDER BY max(computed_at_ms) DESC LIMIT N` is expressed by selecting `max(...) AS recency` and sorting by `recency`. This keeps the sort grammar untouched (`IN` still receives a single column). An empty subquery result naturally yields no matches (`IN (SELECT … 0 rows)` is false) — no special handling.

**9. Same-entity only (MVP).** The subquery's `entity` must equal the enclosing entity, so it reuses the enclosing `table` + `bindings` — no per-subquery table/binding resolution. Cross-entity → 400. Covers the latest-N-runs case; cross-entity is a future extension.

**10. Context held in a `ThreadLocal`, not threaded.** `FilterTranslator.toCondition(node, bindings, ctx)` stores the `TranslationContext(dsl, table, entity)` in a `ThreadLocal` for the duration of the call (save/restore in `finally` so sibling/nested subqueries see the right context and nothing leaks), then delegates to the plain 2-arg recursion. Only `subquerySelect` reads the ThreadLocal; the recursive methods keep their original `(node, bindings)` signatures. This mirrors the repo's existing `AuthorizationTokenHolder` ThreadLocal pattern and keeps the translator diff minimal. The 2-arg `toCondition(node, bindings)` (non-DSL callers `QueryDslRunnableTestCaseSelector` + render tests) sets no context → subquery rejected.

**11. Cycle break.** `StructuredQueryBuilder` → `FilterTranslator` (constructor); to let the translator build a nested select it takes a lazy `ObjectProvider<StructuredQueryBuilder>` (resolved on first use, so no bean cycle).

**12. Parameter resolver recurses into subqueries.** `QueryParameterResolver` rewrites `SubqueryExpr` by resolving params within its nested query, so a bound param inside a subquery substitutes correctly; the public paramless endpoint still rejects any surviving `param`.

**Component flow (read path, one request):** `POST /queries/execute` → `StructuredQueryService.execute(query, {})` → `QueryParameterResolver` (recurses subquery) → `repository.execute(...)` → `StructuredQueryExecutor` → `StructuredQueryBuilder.build` (creates `TranslationContext`) → `FilterTranslator` compiles the `in`-subquery against the enclosing table/bindings via the lazy builder. One SQL statement.

### Risks / Trade-offs (subquery)

- **Circular bean dependency** → resolved with `ObjectProvider<StructuredQueryBuilder>` (lazy lookup), not constructor injection.
- **ThreadLocal leakage/reentrancy** → the 3-arg entry saves the prior context and restores it (or `remove()`s) in `finally`, so sibling and nested subquery compilations are correct and no value survives the call.
- **Sort-by-aggregate limitation** → the DSL sort can only reference a *selected* column, and `IN` needs a single column; the derived-table wrap (first column = key, extra columns for ordering) resolves both without a grammar change.
- **Scope** → same-entity only for now; a cross-entity subquery would need per-subquery table/binding resolution.
- **Wire-compat** → additive: existing `array`-operand `in` is untouched; `subquery` is a new opt-in kind.

## Open Questions

None.
