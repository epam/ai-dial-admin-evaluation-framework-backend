## Context

`overallScore` on a test suite is executed once per run by `MetricScoreComputationExecutor.computeOverall`
as a single aggregate-mode query over that run's `eval_summaries` rows (scoped only by
`:runId`/`:computationId`, no `group_by` — the whole row-set collapses to one row). The Query DSL's
function catalog is registry-driven: each function is a `QueryFunction` bean
(`experimental.query.service.translate.function`), collected by name at startup by
`QueryFunctionRegistry`; `ExprTranslator` never hardcodes the function set.

**Phase 1 (shipped)** added `add`/`subtract`/`multiply`/`divide`/`mean` to that catalog, letting a client
compose "mean of all metrics" and "weighted mean of specific metrics" as nested `FnExpr`s over
`avg(metric::...)` terms, with `overallScore` remaining an opaque `Map<String, Object>` holding a raw
`StructuredQuery`. Verified end-to-end (including a genuine repeating-decimal `Σw=3 → 1/3` normalization
case, proving Postgres's `numeric` division — not Java `BigDecimal.divide`, which would throw
`ArithmeticException: Non-terminating decimal expansion` — resolves the arithmetic without error).

**Phase 2 (this design)** replaces that opaque `Map` with a small typed, sealed `OverallScoreDefinition`
model, so the frontend never builds DSL JSON for the "mean" or "weighted mean" UI radios — only the
"specific function" radio still sends a raw expression, via `CustomFunction` (today's escape hatch,
unchanged). The backend resolves the typed definition into a `StructuredQuery` server-side, still
composing from the Phase-1 primitives internally.

## Goals / Non-Goals

**Goals:**
- Typed, sealed `OverallScoreDefinition` (`Mean` / `WeightedMean` / `CustomFunction`) replacing the
  untyped `overallScore` `Map<String, Object>` on the suite request/response DTOs and the frozen run
  snapshot.
- `Mean` carries zero parameters; at Phase-3 computation time it resolves against **whatever metric
  fields the run actually has** (the same list already discovered for the per-metric AVG/P10/P90/MIN/MAX
  statistics) — not anything persisted on the suite or supplied by the frontend.
- `WeightedMean` carries an explicit `List<WeightedMetric>` (`metricName`, `outputField`, `weight`)
  mirroring the UI table's rows 1:1, composed into `divide(add(multiply(w, avg(...)), ...), add(w, ...))`
  by a new resolver — reusing, not duplicating, the Phase-1 arithmetic functions.
- `Mean`/`WeightedMean` aggregate over **every test case in the run** — each `avg(metric::name::field)`
  term is scoped only by the standard `test_suite_run_id`/`computation_id` filter (same as every other
  built-in statistic), with no additional per-test-case narrowing.
- `CustomFunction` preserves today's free-form escape hatch exactly (a raw `StructuredQuery` expression
  Map), just wrapped in the new discriminator.
- No DB migration — `TestSuite.overallScore` stays a `String`/JSONB column; only the JSON shape inside it
  changes.

**Non-Goals:**
- No write-time cross-validation of `WeightedMean`'s `metricName`/`outputField` against the suite's
  actually-configured TSMDs (confirmed with the user) — stays permissive; only structural/shape
  validation (non-empty weight list, non-blank names, non-null weight) via Bean Validation. A metric
  reference absent from a given run's actual data simply yields a SQL `NULL` that propagates through the
  arithmetic (same null-handling philosophy already relied on for `roc_auc`).
- No change to the null-`overallScore` default behavior (confirmed with the user) — still
  `BuiltInMetricStatistics.defaultOverall()`, computed only for single-metric runs, skipped otherwise.
- No `mean`/`weighted_mean` DSL function — both compositions are entirely internal to the new resolver,
  built from `add`/`multiply`/`divide` alone; it was previously debated whether a dedicated
  `weighted_mean` catalog function (or keeping the shipped `mean` function) would help, but with the
  frontend no longer touching DSL JSON at all for either case, neither earns its keep as public DSL
  surface — the shipped `mean` function was removed once the resolver existed to replace it.

## Decisions

### 1. Sealed hierarchy lives in `service.domain.dto.overallscore`
New subpackage (mirrors the existing `service.domain.dto.analytics` bounded-subpackage precedent):
```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Mean.class, name = "mean"),
    @JsonSubTypes.Type(value = WeightedMean.class, name = "weighted_mean"),
    @JsonSubTypes.Type(value = CustomFunction.class, name = "custom_function")
})
sealed interface OverallScoreDefinition permits Mean, WeightedMean, CustomFunction {}

record Mean() implements OverallScoreDefinition {}

record WeightedMetric(@NotBlank String metricName, @NotBlank String outputField, @NotNull BigDecimal weight) {}

record WeightedMean(@NotEmpty @Valid List<WeightedMetric> weights) implements OverallScoreDefinition {}

record CustomFunction(@NotNull Map<String, Object> expression) implements OverallScoreDefinition {}
```
Same polymorphism idiom as `experimental.query.model.Expr` (`@JsonTypeInfo`/`@JsonSubTypes`), so
`JsonbMapper`'s existing generic `read(json, Class<T>, label)`/`write(Object, label)` helpers work as a
direct drop-in — no bespoke (de)serialization code. `@Valid` on `TestSuiteRequestDto.overallScore`
cascades into `WeightedMean.weights`.

Alternative considered: keep `overallScore` as `Map<String, Object>` and add a *separate* typed field for
the structured cases. Rejected — two fields for one logical concept is more surface area for callers and
for `SuiteSnapshotDto`/mapper wiring than one sealed type with a discriminator.

### 2. Resolver composes internally from the Phase-1 arithmetic primitives only; no `mean`/`weighted_mean` DSL function
`experimental.query.service.metricscore.OverallScoreDefinitionResolver` (plain `@Component` — no
interface inversion needed, since it's a same-package collaborator of `MetricScoreComputationExecutor`,
which is already the inversion boundary via `MetricScoreComputation`). Method:
`StructuredQuery resolve(OverallScoreDefinition definition, List<String> metricFieldNames)`:
- `Mean` → `divide(add(avg(field) for each name in metricFieldNames), n)` — composed from `add`/`divide`
  alone; a single-field run degenerates to `divide(add(avg(field)), 1)` ≡ that field's own average.
  **The shipped `mean` DSL function was removed** once the resolver existed to build this composition
  server-side — it added no value once nothing outside the resolver needed a one-call "mean of N
  already-resolved expressions" convenience (it was never reachable through `overallScore`'s new typed
  model, and the public generic Query DSL had no other caller for it either).
- `WeightedMean(weights)` → `divide(add(multiply(w_i, avg(metric::name_i::field_i)) for each weight),
  add(w_i for each weight))`, built directly from the stored `WeightedMetric` list — independent of
  `metricFieldNames` (permissive per the confirmed non-goal above).
- `CustomFunction(expression)` → `objectMapper.convertValue(expression, StructuredQuery.class)` — a
  Map→POJO conversion, simpler than today's JSON-string round trip since the value is already parsed;
  same log+skip fault isolation as today's `parseExpression` on malformed input.

### 3. `MetricScoreComputationContext`/`TestSuiteEvaluationJob` drop the JSON-string round trip
`overallExpression: String` becomes `overallScoreDefinition: OverallScoreDefinition` — both stable-layer
DTOs (`service.domain.job`/`service.domain.dto.overallscore`), no layering issue.
`TestSuiteEvaluationJob.resolveOverallExpression` simplifies to a direct snapshot field read (the
snapshot's `overallScore` is already typed via the whole-snapshot JSON deserialization), dropping the
`objectMapper.writeValueAsString` re-stringify step entirely.

## Risks / Trade-offs

- **Wire-format break** — any existing suite with a raw-`StructuredQuery`-shaped `overallScore` (Phase
  1's interim shape) must be re-expressed as `{"type":"custom_function","expression":{...}}`. Acceptable
  — this feature has no external consumers yet (frontend work hasn't started), and there's no data
  migration needed (no persisted suites reference the old shape in any real environment).
- **Permissive `WeightedMean` validation** — a typo'd `metricName`/`outputField` silently produces a null
  term rather than a write-time 400. Accepted per the confirmed decision; a follow-up could add
  cross-validation against the suite's live TSMD list if this proves an issue in practice.
- **`Mean`'s dependence on per-run metric discovery** — if a run's metric set differs from a later run's
  (TSMDs added/removed/renamed between runs), `Mean`'s "current metrics" resolve differently each time by
  design — this is the explicitly requested behavior ("use NOW stored metrics"), not a defect.
