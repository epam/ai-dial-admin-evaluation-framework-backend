## Context

The Query DSL's function catalog is registry-driven: each function is a `QueryFunction` Spring `@Bean` (`experimental.query.service.translate.function.QueryFunction`), collected by name into `QueryFunctionRegistry`, and dispatched from `ExprTranslator.toField` for any `FnExpr` node. A function's contract is narrow and stateless:

```java
public interface QueryFunction {
    String name();
    Field<?> translate(FnExpr fn, FunctionContext ctx);
}
```

`FunctionContext` gives a function `toField(Expr)` (translate any argument, recursively resolving field bindings), `args(FnExpr)` (raw argument list), and `singleArg(FnExpr)` (arity-1 convenience) — but no handle to `DSLContext`, the `Table<?>` being queried, or the surrounding `SELECT`. `StructuredQueryBuilder` builds exactly one flat `SELECT ... [GROUP BY ... HAVING ...]` per `StructuredQuery` (`AGGREGATE` mode: one `OutputColumn` aliased `value` for custom-overall use); there is no CTE, derived-table, or multi-statement support anywhere in the builder or `StructuredQueryExecutor`.

Existing multi-arg built-ins (`BuiltInQueryFunctions.java`) already establish the pattern this change follows: `percentileContFunction`/`percentileDiscFunction` take 2 args (`fraction`, `column`) and build an ordered-set aggregate (`DSL.percentileCont(fraction).withinGroupOrderBy(column)`); `widthBucketFunction` takes 4 args. All are single jOOQ built-in calls — no custom SQL function is invoked anywhere in the codebase today, and `array_agg` is unused.

ROC AUC (Mann–Whitney rank-sum formulation) needs, per group, a rank pass over rows ordered by `p` before summing ranks by class — two SQL levels, not expressible in one `Field<?>` built purely from jOOQ built-ins.

## Goals / Non-Goals

**Goals:**
- Add `roc_auc(labelField, probabilityField)` to the DSL function catalog so it is usable anywhere a `FnExpr` is valid — in particular, in a suite's custom `overallScore` structured-query expression, without any new config surface.
- Keep the implementation entirely within the existing single-`SELECT` aggregate-mode shape (`StructuredQueryBuilder`/`StructuredQueryExecutor`/`FunctionContext` unchanged).
- Make the function generic (works on any two numeric-castable fields of any entity), not hardcoded to `eval_summaries`/`data::y`/`metric:...`.

**Non-Goals:**
- No CTE/window-function support is added to the Query DSL translator/builder — this change does not generalize "ranking" as a DSL primitive.
- No new suite-level config property for naming `y`/`p` — the two columns are ordinary `FieldExpr` arguments a suite author supplies directly in the custom `overallScore` JSON, same as any other function call.
- No per-test-case ROC AUC value; this is a single scalar aggregate over all rows a query scans (a whole run, by construction of the existing custom-overall filter).

## Decisions

**1. Rank-sum math lives in a Postgres stored function, not in jOOQ/Java.**
`roc_auc_score(y double precision[], p double precision[]) RETURNS double precision` (`LANGUAGE sql IMMUTABLE`, analytics DB migration `V1.11__CreateRocAucScoreFunction.sql`) reproduces the reference rank-sum SQL internally: pair the two input arrays back into rows via `unnest(y) WITH ORDINALITY` joined to `unnest(p) WITH ORDINALITY` on ordinal position, then `DENSE_RANK()`/`ROW_NUMBER() OVER (ORDER BY p)` + average-rank-per-tie-group + `SUM(avg_rank) FILTER (WHERE y = 1)` exactly as the user's reference query, finally `(rank_sum_pos - n_pos*(n_pos+1)/2.0) / NULLIF(n_pos*n_neg, 0)`.
- *Alternative considered — inline jOOQ scalar subquery*: build the whole nested rank+aggregate computation as a jOOQ-constructed derived-table `Field<?>` (jOOQ does support scalar subqueries and derived tables as expressions). Rejected: `FunctionContext` deliberately has no `Table`/`DSLContext` handle, so the function would need to reconstruct the outer query's `FROM`/`WHERE` scope inside the subquery — duplicating filter logic and coupling the function to one entity (`eval_summaries`), instead of staying a generic 2-arg function usable on any entity/fields.
- *Alternative considered — Java-side computation* (fetch raw rows, compute in Java): rejected because it requires a second code path outside the DSL's `Field<?>`-per-function abstraction (a bespoke row-mode query + Java loop instead of a reusable catalog entry), and the user explicitly asked for this to be a Query DSL function usable via custom overall, not a special-cased Java computation.

**2. The `QueryFunction` wraps the stored function as `roc_auc_score(array_agg(y), array_agg(p))`.**
Registered as a standalone `@Component` class (see Decision #3), the `translate` implementation is:
```java
@Override
public Field<?> translate(FnExpr fn, FunctionContext ctx) {
    final List<Expr> args = ctx.args(fn);
    if (args.size() != 2) {
        throw new ValidationException("function 'roc_auc' expects exactly two arguments (label, probability)");
    }
    final Field<Double> label = ctx.toField(args.get(0)).cast(Double.class);
    final Field<Double> probability = ctx.toField(args.get(1)).cast(Double.class);
    return DSL.function("roc_auc_score", Double.class, DSL.arrayAgg(label), DSL.arrayAgg(probability));
}
```
`array_agg` is a plain jOOQ-built-in aggregate; two `array_agg` calls scanning the same row set in one query stay index-aligned (same underlying iteration), so `y[i]`/`p[i]` correctly correspond to the same row. Explicit `.cast(Double.class)` on both arguments guards against feeding `array_agg` a non-numeric JSONB-derived field type (e.g. if a `data::<field>` binding resolves to `TEXT`/`NUMERIC` rather than `DOUBLE PRECISION`).
- *Alternative considered — register as a Postgres `CREATE AGGREGATE`* (custom two-argument aggregate with its own state/final functions, invoked directly without `array_agg`): more "native," but higher migration complexity (transition function, state type) for no behavioral benefit over the `array_agg` + scalar-function approach, which is simpler to write, test, and reason about in isolation.

**3. `roc_auc` is a standalone `@Component QueryFunction` class (`RocAucFunction.java`), not a bean method in `BuiltInQueryFunctions`.**
Unlike the other built-ins (short lambdas registered as `@Bean` methods in `BuiltInQueryFunctions`), `roc_auc` gets its own top-level class implementing `QueryFunction` directly. This follows the SPI's other supported registration path (`AGENTS.md`: "drop in a new `@Component QueryFunction`") and this project's general preference for specialized, injectable components over inline/private logic — appropriate here since `roc_auc` is the first catalog entry backed by a custom stored function rather than a plain jOOQ built-in, giving it a distinct enough shape (and Javadoc-worthy invariant, see Risks below) to warrant its own file. `QueryFunctionTestSupport` (unit-test registry) constructs it directly (`new RocAucFunction()`) rather than via a bean method.

**4. No changes to `MetricScoreComputationExecutor`, persistence, or suite validation.**
The existing custom-overall path (`computeOverall`, parsing the suite's `overallScore` JSON into a `StructuredQuery` and executing it via `StructuredQueryService.execute`) already accepts any valid `FnExpr`; `roc_auc` needs no special-casing there. Suite-write-time validation of the custom `overallScore` expression (unknown-field/unbound-suite checks in `TestSuiteService`) already validates arbitrary function calls generically — a `roc_auc(...)` call is validated the same way `percentile_cont(...)` is today.

## Risks / Trade-offs

- **[Risk] First custom Postgres function + `array_agg` usage in this codebase — no established test/maintenance pattern.** → Mitigation: cover `roc_auc_score` with a dedicated SQL-level test (via a `@PostgresFunctionalTests` case or a lightweight jOOQ-driven fetch) exercising perfect classifier (1.0), coin-toss (≈0.5), tied `p` values, and degenerate single-class input (`NULL`); document the function's contract in the migration file header comment.
- **[Risk] `array_agg` over a full run's test cases loads all paired values into one aggregate result row** — for very large runs this is a larger single-row payload than the existing scalar `AVG`/`percentile_cont` aggregates, though still bounded by run size (same order of magnitude as existing per-run `eval_summaries` scans). → Mitigation: none needed initially; existing custom-overall queries already scan the full run's `eval_summaries` rows for `AVG`, so this is consistent with current per-run computation cost, not a new scaling concern.
- **[Risk] Silent wrong-pairing if a future refactor computes `array_agg(y)` and `array_agg(p)` in separate queries instead of one.** → Mitigation: the design requires both `array_agg` calls to be arguments of the *same* `SELECT`'s function call (enforced by construction in `RocAucFunction.translate(...)` — both come from the same `FunctionContext`/query); this invariant is documented in the class Javadoc so it isn't broken by a later "optimization."
- **[Trade-off] Casting both arguments to `Double` inside the function loses type-checking specificity** (e.g. a caller could pass two arbitrary numeric-castable fields, including ones with no real "0/1 label" semantics — the DSL cannot enforce that the first argument is boolean-like). Accepted: the same is true of every existing DSL function (e.g. nothing stops `avg` on a nonsensical field); this is a DSL-wide trust boundary, not new to `roc_auc`.

## Migration Plan

1. Add Flyway migration `V1.11__CreateRocAucScoreFunction.sql` under `analytics/POSTGRES` defining `roc_auc_score`. Applied automatically on next application startup (standard Flyway behavior); no data backfill needed (function only, no table/column changes).
2. Add the `RocAucFunction` `@Component`. No feature flag needed — the function is additive to the catalog and inert until a suite references it in a custom `overallScore` expression.
3. Rollback: drop the migration (Flyway `undo` is not configured in this project, so rollback means a follow-up migration `DROP FUNCTION roc_auc_score`) and remove the bean; since no suite can reference `roc_auc` before this change ships, rollback has no data-migration concerns.

## Open Questions

- Should `roc_auc_score`'s tie-breaking/rank-averaging exactly mirror the reference SQL's `DENSE_RANK`-grouped average-rank approach, or is a simpler `PERCENT_RANK`/direct concordant-pair-count formulation acceptable? (Design assumes exact port of the reference SQL for correctness confidence; revisit only if performance on large arrays becomes a concern.)
