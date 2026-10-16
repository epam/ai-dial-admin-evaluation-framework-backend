## Context

The Query DSL pipeline (`StructuredQueryService` → `JooqStructuredQueryExecutor`) calls `StructuredQueryEntityResolver.rewrite(query)` once, on the top-level query only, then builds SQL from the resolver's `table()`, `bindings(query)` and `baseCondition()`. Three analytics entities carry a `(test_suite_run_id, computation_id)` pair — `test_case_eval_scores`, `metric_score_results`, `test_case_metric_scores` — plus `eval_summaries`.

Two of them (`test_case_eval_scores`, `metric_score_results`) already call `MetricScoreLatestComputationDefaulter` from `rewrite`. It resolves `computation_id eq "latest"` only when the filter contains exactly one `test_suite_run_id eq <uuid>`: it calls `ComputationResolver.resolve("latest", runId)` (eval-summary based, ordered `computed_at_ms DESC, computation_id ASC`) and swaps the sentinel for the resolved UUID literal. For any other shape the sentinel survives and is rejected as an invalid UUID. A run set produced by a subquery (`test_suite_run_id in (subquery on eval_summaries … limit N)`) is invisible to this pre-SQL rewrite, so the dashboard query "per-run outcome counts for the last N runs, against each run's latest computation" cannot be expressed; omitting `computation_id` sums every computation of a recomputed run.

All four entities except `test_case_eval_scores` resolve to a bare generated jOOQ table; `test_case_eval_scores` resolves to the derived table `PostgresTestCaseEvalScoreEntityResolver.SCORES` (alias `test_case_eval_scores`). Bindings are built once from `JooqTableSchemaResolver.bindings(table)` (`Map<String, QueryFieldBinding>`, `QueryFieldBinding(name, Field<?>, QueryFieldType)`). A binding's field may be any jOOQ expression (`PostgresTestSuiteRunEntityResolver` already binds a correlated scalar subquery, `metric_names`). `eval_summaries` is the table "latest" is defined from (`idx_eval_summaries_run_computed_at` on `(test_suite_run_id, computed_at_ms DESC, computation_id)`).

Constraints: JDBC/jOOQ typed DSL only; layering web → service → data (the query entities live in `query.service.repository`, which already imports the generated `data.db.jooq.analytics` tables); every Spring component carries `@LogExecution`; no `System.currentTimeMillis`; no migration for query-level behaviour. See the proposal for motivation and scope. D1–D7 are the behavioural decisions; D8 (transactions, errors, security) records that nothing new is needed there.

## Goals / Non-Goals

**Goals:**
- `computation_id eq "latest"` means "each run's own latest computation" for any filter shape, including a subquery-selected run set, on the opted-in entities.
- One shared, testable component holds the logic; an entity opts in with a few lines.
- The canonical definition of "latest" (order and tiebreak) is unchanged and pinned by a test.
- No change to default projections, published schema, or any request that works today.

**Non-Goals:**
- A new endpoint, DTO, page extender, `test_suite_id` column or Flyway migration.
- Opting in `eval_summaries` (decided later from `EXPLAIN` evidence).
- `ne` / `in` / non-`eq` use of `"latest"`; rewriting inside nested subqueries (the pipeline does not rewrite them today).
- Closing the score-row gap (`test_case_eval_scores` has no rows for suites without an overall-score definition, nor for SUCCESS test cases without a computed score), or changing how "last N runs" is ranked.

## Decisions

### D1 — Resolve "latest" in SQL through a virtual boolean binding
The rewrite replaces `computation_id eq "latest"` with `is_latest_computation eq true`, where `is_latest_computation` is a `QueryFieldBinding(name, Field<Boolean>, BOOLEAN)` over:

```
<computation_id column> = (SELECT latest.computation_id
                             FROM test_case_eval_summaries latest
                            WHERE latest.test_suite_run_id = <run id column>
                            ORDER BY latest.computed_at_ms DESC, latest.computation_id ASC
                            LIMIT 1)
```

The rewritten filter is the exact semantic equivalent of the sentinel at any position in the filter tree (inside `and`/`or`/`not`), so the SQL form needs none of the literal path's "exactly one run predicate" preconditions. The run set may come from a literal list, an `in` subquery or no predicate at all.

*Alternatives:* (a) pre-execute the run subquery inside `rewrite`, then `ComputationResolver.resolveLatest` and rewrite to literal `in` lists — needs the resolver to depend on the executor (constructor cycle through the registry), adds round trips, is non-atomic and only works for a few recognisable filter shapes; (b) a `QueryResultPageExtender` on `test_suite_runs` (as `OverallScoreTestSuiteRunsPageExtender` does) — row-mode and meta-page only, returns a derived key instead of ordinary grouped rows; (c) a dedicated REST endpoint — rejected, the Query DSL is the intended surface; (d) a stored `is_latest` flag or denormalised column — contradicts the append-only, no-`is_latest` rule in `docs/patterns/computation-versioning.md` and needs a migration and backfill.

### D2 — The field is a binding, not a column of the entity's table
`table()` and the `SCORES` projection are untouched; the binding is added only to the `bindings` map. Consequences: an empty-`select` row query still projects `table().fields()` and pays no per-row sub-select; schema discovery (`*SchemaProvider` read the table, not the bindings) does not publish the field; no jOOQ regeneration. The binding map handed back by `schemaResolver.bindings(...)` is not mutated — `LatestComputationSupport.withLatest(base, latest)` returns a new map. A client could still type `is_latest_computation` (it is a valid binding); that is an unpublished, unsupported alias.

### D3 — One shared opt-in component, `LatestComputationSupport`
New `@Component @LogExecution` in `query.service.repository`:
- `QueryFieldBinding latestBinding(Field<String> runIdField, Field<String> computationIdField)` — builds the D1 expression with typed jOOQ against `TEST_CASE_EVAL_SUMMARIES.as("latest")`.
- `Map<String, QueryFieldBinding> withLatest(Map<String, QueryFieldBinding> base, QueryFieldBinding latest)`.
- `StructuredQuery rewriteLatest(StructuredQuery query)` — tree walk over `FilterNode` (`LogicalNode` / `ComparisonNode`), replacing every `computation_id eq "latest"` (case-insensitive) with `is_latest_computation eq {BOOLEAN,"true"}`; returns the same instance when nothing changed. It reuses `MetricScoreLatestComputationDefaulter.isLatestComputationEq` and `withFilter` (both made package-private static) rather than duplicating the match and the query-copy.

An entity passes its own `Field`s, so a bare table (`METRIC_SCORE_RESULT.TEST_SUITE_RUN_ID`, `.COMPUTATION_ID`) and the derived `SCORES` (`SCORES.field(TEST_CASE_EVAL_SCORES.TEST_SUITE_RUN_ID)`) both work with no table conversion. Composition per entity:

```java
this.bindings = support.withLatest(schemaResolver.bindings(TABLE),
        support.latestBinding(TABLE.TEST_SUITE_RUN_ID, TABLE.COMPUTATION_ID));
...
public StructuredQuery rewrite(StructuredQuery q) {
    return support.rewriteLatest(latestComputationDefaulter.resolveLatestComputation(q));
}
```

`test_case_metric_scores` has no rewrite hook today and gains one (`latestComputationDefaulter` is not needed there for correctness, but keeping the same two-step composition keeps single-run queries index-friendly and uniform).

*Alternatives:* auto-apply in the entity-agnostic executor for any entity exposing both columns — puts domain knowledge in the engine, the same reason `QueryResultPageExtender` was introduced as a registered seam; fold the logic into `MetricScoreLatestComputationDefaulter` — it is named and wired for metric scores, and the SQL path is entity-parameterised, so a separate component with one clear responsibility tests more easily.

### D4 — Literal single-run path first, SQL path for the rest
`rewrite` runs the existing defaulter first. A single pinned run still becomes a UUID literal (`computation_id = '<uuid>'`), which is sargable on the entities' run/computation indexes. `rewriteLatest` only sees what survived. Existing defaulter unit tests keep their meaning; the two behaviours that change are: a `"latest"` with no run predicate, or a multi-run predicate, no longer fails on the sentinel; a single pinned run with no summaries is unchanged (the defaulter leaves the sentinel, and `rewriteLatest` then turns it into the SQL condition, which yields no rows instead of a 400 — documented in the spec).

### D5 — Correlate against `test_case_eval_summaries`, not the entity's own table
"Latest" must mean the same thing as `ComputationResolver` ("latest computation with readable rows", from eval summaries). Using the scores table would silently fall back to an older computation for a run whose newest computation produced no score rows — the failure `OverallScoreTestSuiteRunsPageExtender` explicitly avoids. A run whose latest computation has no score rows therefore contributes no rows.

### D6 — No `RunDeletionFilter` inside the correlated lookup
Each entity's `baseCondition()` already hides a deleted run's rows, and a run is deleted entirely or not at all, so the inner anti-join would add a per-row cost with no behavioural difference. `ComputationResolver` applies it because it is called for arbitrary run ids; here the outer row has already passed `baseCondition()`.

### D7 — Null polarity and operators
`computation_id = (subquery)` is NULL for a run with no summaries, so `is_latest_computation eq true` excludes it. A negated sentinel (`not (computation_id eq "latest")`) follows the existing Query DSL null-polarity rule (`not` is total: NULL satisfies), so it returns rows of runs with no summaries. Only `eq` is rewritten; `ne` / `in` with `"latest"` are left untouched and still rejected by the engine.

### D8 — Transactions, errors, security
Read-only, one statement through the entity's existing `analyticsDsl`; no new transaction scope (the query package has none). No new error codes: a malformed non-`"latest"` UUID is still a 400 from translation. No security or configuration surface.

### Component interaction flow
`StructuredQueryService.execute` → parameter pre-pass → `JooqStructuredQueryExecutor` → `resolver.rewrite(query)` = `LatestComputationSupport.rewriteLatest(defaulter.resolveLatestComputation(query))` → `StructuredQueryBuilder.build` using `resolver.bindings(query)` (now containing `is_latest_computation`) → filter translated with the BOOLEAN binding → `baseCondition()` (deleted-run exclusion) applied → SQL executed.

### Rollout in three iterations (each ≤ 5 tasks, one task group)
(1) `LatestComputationSupport` + unit tests + `test_case_eval_scores` opt-in; (2) `metric_score_results` and `test_case_metric_scores` opt-in + resolver tests; (3) functional tests, `EXPLAIN` evidence, docs and spec updates.

## Risks / Trade-offs

- **[Per-row cost of the correlated lookup]** evaluated for each candidate row → index-only probe on `idx_eval_summaries_run_computed_at`, bounded by the run predicate (unique key leading column `test_suite_run_id`); capture `EXPLAIN (ANALYZE)` of the dashboard query on seeded data and record it in `docs/patterns/computation-versioning.md`. `eval_summaries` is deferred precisely because it is the largest table and self-correlated.
- **[Two definitions of "latest" can drift]** SQL expression vs `ComputationResolver` / `findLatestComputationIds` → a unit test renders the binding with the Postgres dialect and asserts `order by computed_at_ms desc, computation_id asc ... limit 1`; a functional test pins the same-millisecond tie (smaller `computation_id` wins).
- **[Internal alias exposure]** `is_latest_computation` is typeable but unpublished → document as unsupported; no schema entry, so it cannot be discovered from `GET /api/v1/queries/entities/schema/{name}`.
- **[Behaviour change for a pinned run with no summaries]** previously a 400 on the sentinel, now an empty result → called out in the spec; no accepted request changes meaning.
- **[No rewrite inside nested subqueries]** a `"latest"` placed inside a subquery's own filter is still rejected, because the pipeline rewrites only the top-level query → documented limitation; the dashboard query puts `"latest"` at the top level.
- **[Score-row gap remains]** suites without an overall-score definition produce no `test_case_eval_scores` rows, so per-run counts are empty for them → out of scope, stated in the proposal and spec.
- **[Wrong table reference in the derived `SCORES`]** the binding must use `SCORES.field(...)` (alias `test_case_eval_scores`), not the raw generated table, or the correlation breaks → a resolver unit test and a functional test through the entity.

## Migration Plan

No schema change, no data migration, no configuration. Deploy as a normal release; behaviour is purely additive for requests that previously failed. Rollback is a revert: the only observable difference is that multi-run `"latest"` filters are rejected again.

## Open Questions

- ~~Is there an existing "published schema field set == binding keys" parity test for any of the three entities?~~ **Resolved (task 1.5):** checked during iteration 1; the internal binding is asserted absent from `table().fields()` and the published schema by the resolver unit tests.
- ~~Is the map returned by `JooqTableSchemaResolver.bindings` immutable?~~ **Resolved:** `withLatest` always returns a new map and the unit test does not assume mutability of its input.
- ~~Opt-in of `eval_summaries`.~~ **Resolved (task 4.3):** not opted in; see the decision and `EXPLAIN` evidence in `docs/patterns/computation-versioning.md`.
- Should `ne` / `in` forms of the sentinel ever be supported? Not needed for the dashboard; revisit only on demand.
