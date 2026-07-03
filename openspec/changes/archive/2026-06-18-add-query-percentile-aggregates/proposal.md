## Why

Analysts need percentile cuts of metric scores (e.g. p10/p90 of a metric's `score` across a run) —
a common quality-distribution question that today can only be answered with hand-written SQL like
`percentile_cont(0.1) WITHIN GROUP (ORDER BY (metric_values -> 'Ragas Answer Relevancy' -> 'score')::decimal)`.
The structured query DSL exposes a closed function set that has no percentile support, so this class
of query is impossible through `/api/v1/queries/execute`.

## What Changes

- Add two **ordered-set aggregate** functions to the structured query DSL's supported function set:
  - `percentile_cont(fraction, column)` → `percentile_cont(fraction) WITHIN GROUP (ORDER BY column)`
    (continuous — interpolates between adjacent values).
  - `percentile_disc(fraction, column)` → `percentile_disc(fraction) WITHIN GROUP (ORDER BY column)`
    (discrete — returns an actual value from the set).
  - `fraction` is a decimal literal validated to the closed interval `[0, 1]`; `column` is any
    resolvable field expression (including flattened JSONB paths such as
    `metric:Ragas Answer Relevancy:score`, which already resolve to the numeric-cast JSONB path).
- Classify both in the **aggregate** group (alongside `count`/`sum`/`avg`/`min`/`max`), not the
  scalar group: they collapse the ordered set of rows to a single value per group. They run in
  AGGREGATE mode, including the GROUP-BY-less whole-table form (one row, e.g. `p10`/`p90` aliases).
  No request-envelope or object-model change is needed — they slot into the existing aggregate path.
- Reject malformed usage with HTTP 400: wrong arity (not exactly two args), a non-literal or
  out-of-range `fraction`, or an unresolvable `column`.
- **Documentation:** elevate the DSL's supported-function set from a prose list embedded in the
  validation requirement into an explicit **function catalog** (per function: group, arity, operand
  types, return type), and add the two percentile functions to it. This keeps the wire contract
  honest as the function set grows.

## Capabilities

### New Capabilities
<!-- None. This extends the existing structured query DSL; no new capability spec. -->

### Modified Capabilities
- `structured-query-model`: the **Query validation and allowlist** and **SQL translation**
  requirements gain the `percentile_cont`/`percentile_disc` ordered-set aggregates (function set +
  fraction-range validation + `WITHIN GROUP (ORDER BY …)` translation), and the supported-function
  set is restated as a function catalog with arity/operand/return types.

## Impact

- **Code:** `experimental.query.service.translate.ExprTranslator` — add the two functions to
  `toFunction` (a dedicated two-arg handler emitting `DSL.percentileCont(fraction)` /
  `DSL.percentileDisc(fraction)` `.withinGroupOrderBy(orderField)`), plus fraction-literal parsing
  and `[0,1]` range validation. No change to `StructuredQueryBuilder` (aggregate mode already
  supports aliased select entries and empty `group_by`), the request model, the controllers, or the
  datasources.
- **APIs:** no new endpoint; `POST /api/v1/queries/execute` accepts the new functions in aggregate
  mode. No change to the legacy list-query DSL.
- **Data / config:** none — read-only, no schema/migration/property changes.
- **Tests:** translator render test (the `WITHIN GROUP (ORDER BY …)` SQL), fraction-range and arity
  rejection unit tests, and a functional test executing a GROUP-BY-less p10/p90 query over a run's
  metric scores against the analytics datasource.
- **Docs:** updates `openspec/specs/structured-query-model/spec.md` (delta) and its function-set
  description. No `specs/README.md` status change (already Implemented).
