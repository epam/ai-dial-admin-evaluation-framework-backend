## Context

`OverallScoreDefinitionResolver` (`experimental.query.service.metricscore`) turns a suite's typed
`OverallScoreDefinition` (`Mean`/`WeightedMean`/`CustomFunction`) into a `StructuredQuery` at Phase-3
metric-score computation time:

- `Mean` → `divide(add(avg(f1), avg(f2), ...), n)` over the run's discovered numeric metric fields.
- `WeightedMean` → `divide(add(multiply(w1, avg(m1)), ...), add(w1, ...))` over the suite's stored
  `WeightedMetric` list.

Both funnel through a single private `avg(String fieldName)` helper (line 94-96) that builds a raw
`FnExpr("avg", ...)`. The structured-query DSL's `avg` function (`BuiltInQueryFunctions.avgFunction`,
line 97-104) delegates to `DSL.avg(field)`/`DSL.avgDistinct(field)` — standard SQL `AVG`, which returns
SQL `NULL` when the field has no rows (metric absent from the run's data).

`add`/`multiply`/`divide` (`BuiltInQueryFunctions`, line 129-146, via the shared `reduce`/`binary`
helpers at line 149-173) do plain jOOQ `Field::add`/`Field::mul`/`Field::div` with a `BigDecimal` cast —
no null handling. Postgres arithmetic-null propagation means `NULL + x = NULL` and `NULL / x = NULL`, so
one missing metric currently nulls the *entire* `add(...)`/`divide(...)` result, not just its own term.
This is stricter than what the `metric-score-statistics` spec currently describes ("that entry's term
resolves to a SQL NULL that propagates through the arithmetic") and worse than the desired behavior: a
missing metric's contribution should be `0`, so the rest of the mean/weighted-mean composition still
resolves to a real number.

## Goals / Non-Goals

**Goals:**
- A metric average that is SQL `NULL` (metric missing from the run) contributes `0` to its `Mean` or
  `WeightedMean` term, so `overall` still computes a real number when at least one metric is present.
- Keep the fix scoped to `overall`-score composition; do not change the general null-arithmetic
  semantics of the `add`/`multiply`/`divide`/`avg` DSL functions for other callers (e.g.
  `custom_function` definitions, ad-hoc `/api/v1/queries/execute` callers).
- Follow the existing registry-driven DSL function catalog pattern (a new `QueryFunction` bean, no
  translator/registry code changes).

**Non-Goals:**
- Changing `add`/`multiply`/`divide` to be null-safe globally — that would silently change semantics for
  every DSL caller, including the `custom_function` escape hatch, which is out of scope here.
- Validating `WeightedMetric` references against the suite's actually-configured metrics at write time —
  that remains explicitly permissive per the existing spec and is unaffected by this change.
- Any change to the built-in default `overall` (single-metric `avg(:metricField)`, used when
  `overall_score` is `NULL`) — that path only ever has one metric field and does not exercise this
  multi-term arithmetic.

## Decisions

**Add a new `coalesce(value, default)` built-in `QueryFunction`, used only at the `avg()` construction
site in `OverallScoreDefinitionResolver`.**

- `BuiltInQueryFunctions` gets a `coalesceFunction()` `@Bean`, mirroring the existing `binary()`
  arity-check convention used by `subtract`/`divide` (exactly 2 args, both cast to `Field<BigDecimal>`),
  delegating to `DSL.coalesce(value, fallback)`. No `QueryFunctionRegistry`/`ExprTranslator` changes
  needed — the registry-driven catalog picks up new `@Bean`s automatically, and duplicate-name
  collision is rejected at startup.
- `OverallScoreDefinitionResolver.avg(String fieldName)` changes from returning a raw
  `FnExpr("avg", ...)` to returning `FnExpr("coalesce", false, [FnExpr("avg", ...), decimal(0)])`. Both
  `meanExpr` and `weightedMeanExpr` already call this single helper, so no other call sites change.

**Alternative considered — make `add`/`multiply`/`divide` coalesce their operands to `0` unconditionally.**
Rejected: this is a global semantic change affecting every DSL caller (including `custom_function`
expressions supplied by users and any future `/api/v1/queries/execute` caller), not just the
overall-score composition this change targets. Scoping the fix to the `avg()` construction site inside
`OverallScoreDefinitionResolver` keeps the blast radius to exactly the `Mean`/`WeightedMean` overall-score
path.

**Alternative considered — coalesce at the SQL/repository layer instead of the DSL.**
Rejected: there is no repository-layer touchpoint here — Phase-3 computation goes entirely through the
structured-query DSL (`StructuredQueryService.execute`), so the DSL function catalog is the correct (and
only) layer to intercept this.

## Risks / Trade-offs

- **A missing metric silently contributes `0` rather than surfacing as an anomaly** → acceptable and
  matches the requirement; the existing spec already treats a missing/unbound `WeightedMetric` reference
  as non-fatal (no validation at write time), so this is a natural extension of that permissiveness, not
  a new risk class.
- **`coalesce` becoming part of the general DSL catalog means any `custom_function` caller could also use
  it** → acceptable; it is a standard, safe SQL function (mirrors `DSL.coalesce`), consistent with other
  general-purpose built-ins (`abs`, `width_bucket`) already exposed the same way.

## Migration Plan

No schema, API, or data migration — this is a computation-logic-only change inside Phase-3 metric-score
computation. No feature flag: the corrected coalescing takes effect for all `Mean`/`WeightedMean`
computations from deploy time forward; historical `metric_score_results` rows are append-only and are
not recomputed retroactively (consistent with the existing computation-versioning model — a new
computation naturally picks up the fix).

## Open Questions

None — the approach directly extends the existing registry-driven DSL function catalog pattern with no
unresolved design decisions.
