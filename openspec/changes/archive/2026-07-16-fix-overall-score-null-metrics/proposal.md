## Why

When a `mean` or `weighted_mean` `overall_score` definition references a metric field that is absent
from a given run's data, that field's `avg(...)` resolves to SQL `NULL`. Because none of the `add`/
`multiply`/`divide` structured-query DSL functions coalesce null operands, Postgres arithmetic-null
propagation (`NULL + x = NULL`) means a single missing metric currently nulls out the run's **entire**
`overall` score, not just that one term. This is worse than the spec's own documented intent
("that entry's term resolves to a SQL NULL that propagates through the arithmetic") — in practice the
propagation isn't scoped to the entry's term, it destroys the whole computed value. The fix: a missing
metric's average SHALL be treated as `0` for its term, so the rest of the mean/weighted-mean composition
still produces a real number.

## What Changes

- Add a new `coalesce(value, default)` built-in structured-query DSL function to the registry-driven
  function catalog (`experimental.query.service.translate.function`), wrapping `DSL.coalesce(...)`.
- In `OverallScoreDefinitionResolver`, wrap every `avg(field)` term (used by both `mean` and
  `weighted_mean`) as `coalesce(avg(field), 0)`, so a metric missing from a run's data contributes `0`
  to that term instead of propagating `NULL` through the surrounding `add`/`divide` and nulling the
  whole `overall` result.
- Update the `metric-score-statistics` spec's "Weighted mean metric references are not validated at
  write time" scenario (and the "Overall score" requirement text) to state the corrected behavior: a
  missing/unbound metric term is coalesced to `0`, not left as a NULL that poisons the full expression.
- No change to `add`/`multiply`/`divide` themselves — coalescing stays scoped to the `avg()`
  construction site inside `OverallScoreDefinitionResolver`, so `custom_function` definitions and any
  other DSL caller keep today's standard SQL null-arithmetic semantics.

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `metric-score-statistics`: the `weighted_mean`/`mean` overall-score computation now coalesces a
  missing metric's average to `0` for that term instead of letting a SQL `NULL` propagate through the
  whole `overall` arithmetic expression.

## Impact

- **Code**: `experimental.query.service.translate.function.BuiltInQueryFunctions` (new `coalesceFunction`
  bean), `experimental.query.service.metricscore.OverallScoreDefinitionResolver` (wrap `avg()` helper).
- **Tests**: `OverallScoreDefinitionResolverTest` (expected-tree assertions updated to the coalesced
  shape), a new functional test case under `MetricScoreComputationFunctionalTests` exercising an actually
  missing metric and asserting a non-null, zero-substituted `overall` result.
- **API/DB**: none — no schema, endpoint, or DTO changes; purely a computation-semantics fix scoped to
  Phase-3 metric-score computation.
- **Docs**: `metric-score-statistics` spec scenario wording; no `docs/configuration.md` or
  `docs/database-schema.md` changes needed.
