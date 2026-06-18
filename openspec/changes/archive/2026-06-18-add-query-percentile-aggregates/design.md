## Context

The structured query DSL accepts a closed set of functions resolved in
`ExprTranslator.toFunction` (`experimental.query.service.translate`): scalar (`lower`, `upper`,
`length`, `trim`, `abs`, `width_bucket`) and aggregate (`count`, `sum`, `avg`, `min`, `max`). There
is no percentile support, so a common analytics question — "the p10/p90 of a metric's score across a
run" — can only be answered with hand-written SQL such as
`percentile_cont(0.1) WITHIN GROUP (ORDER BY (metric_values -> 'Ragas Answer Relevancy' -> 'score')::decimal)`.

`percentile_cont`/`percentile_disc` are PostgreSQL **ordered-set aggregates**: they collapse the
ordered set of a column's values to a single value per group, with the quantile `fraction` supplied
as a direct argument and the aggregated column inside `WITHIN GROUP (ORDER BY …)`. jOOQ models this
natively as `DSL.percentileCont(fraction).withinGroupOrderBy(orderField)`.

## Goals / Non-Goals

**Goals:**
- Add `percentile_cont(fraction, column)` and `percentile_disc(fraction, column)` to the supported
  function set, classified as aggregates.
- Validate `fraction` as a decimal literal in `[0, 1]` and the call as exactly two arguments.
- Translate to `percentile_cont(fraction) WITHIN GROUP (ORDER BY column)` with the fraction bound as a
  parameter; `column` resolves through the existing field/JSONB resolution (so `metric:<name>:score`
  works unchanged).

**Non-Goals:**
- No request-envelope or object-model change (`FnExpr` already carries name + args).
- No `StructuredQueryBuilder` change — aggregate mode already supports aliased select entries and the
  GROUP-BY-less whole-table form.
- No mode-coherence enforcement (still out of scope, consistent with the rest of the DSL).
- No other window/ordered-set functions (`mode`, `rank`, …) in this change.

## Decisions

### Classification: aggregate, not scalar
Percentiles collapse N rows → 1 value, the defining trait of an aggregate — unlike `width_bucket`,
which is per-row. They run in AGGREGATE mode; the user's GROUP-BY-less p10/p90 query is whole-table
aggregation (one row), which `buildAggregate` already supports (empty `group_by`, aliased selects).
Classifying them as scalar would misrepresent the contract and place a row-collapsing function in the
per-row group.

### Translation via a dedicated two-arg handler
Add cases to `ExprTranslator.toFunction`:
```
case "percentile_cont" -> percentile(name, args, bindings, /*continuous=*/true);
case "percentile_disc" -> percentile(name, args, bindings, /*continuous=*/false);
```
The handler:
1. Requires exactly two args (else `ValidationException` → 400).
2. Parses arg0 as the `fraction`: it MUST be a `ValueExpr` numeric literal (reuse
   `ValueExprToObjectMapper`); a non-literal or non-numeric `fraction` → `ValidationException`.
   Validate `0 ≤ fraction ≤ 1` → else `ValidationException`. PostgreSQL requires the fraction to be a
   per-group constant, so binding a literal is correct.
3. Resolves arg1 as the order field via `toField(args.get(1), bindings)` — reusing field/JSONB
   resolution so `metric:<name>:score` maps to the numeric-cast JSONB path.
4. Emits `DSL.percentileCont(fraction).withinGroupOrderBy(orderField)` (or `percentileDisc`). The
   `fraction` is passed as a bound value, not concatenated.

`fn.distinct()` does not apply to ordered-set aggregates and is ignored.

### Why no other layer changes
`buildAggregate` translates each select entry through `ExprTranslator.toField` and requires an alias
(the user's `AS p10`/`AS p90`). An ordered-set aggregate is just another `Field<?>` returned by
`toFunction`, so it flows through unchanged. `group_by` may be empty → no GROUP BY clause → single
row. `having`/`sort` resolution is unaffected.

## Error handling

All rejections are `ValidationException` (→ HTTP 400): wrong arity, non-literal/non-numeric/out-of-range
`fraction`, or an unresolvable `column`. A semantically odd-but-translatable query that PostgreSQL
rejects is still caught by `StructuredQueryExecutor` and surfaced as 400, per the existing contract.

## Risks / Trade-offs

- **Fraction must be a literal.** Accepting only a `ValueExpr` (not a `param`/`fn`) matches
  PostgreSQL's constant requirement and keeps validation simple; a future param registry could relax
  it.
- **Operand typing.** `percentile_cont` expects a numeric ordering column; a non-numeric `column`
  produces a DB type error surfaced as 400 (the translator does not pre-check operand type, consistent
  with the existing permissive validation). `metric:<name>:<field>` paths are already numeric-cast, so
  the common case is clean.
- **`percentile_disc` return type.** Returns an actual member of the set (type of `column`), unlike
  `percentile_cont` which interpolates to numeric — documented in the function catalog so clients pick
  the right one.
