## Context

Metric values are produced per test case during a run's metric-evaluation phase and persisted in the analytics table `test_case_eval_summaries` (`metric_values` JSONB = `{metricName: {outputField: numeric}}`), tagged with a `computation_id`. The only existing aggregation, `EvalSummaryService.aggregate()` / `PostgresEvalSummaryRepository.aggregate()`, is on-demand and limited to AVG/MIN/MAX/COUNT — it cannot produce median or percentiles, which the Single-Run dashboard's `avg / med / p10 / p90 / min / max` toggle and "Overall score" card require.

The experimental Query DSL (`com.epam.aidial.evaluation.experimental.query`) already:
- supports `avg`, `min`, `max`, `percentile_cont`, `percentile_disc` aggregate functions;
- registers `eval_summaries` as a queryable analytics entity whose **detailed schema** flattens `metric_values` into numeric `metric:<name>:<field>` columns (resolved via `JsonbFieldResolver`, the JSONB-numeric path pattern);
- serializes a `StructuredQuery` to/from JSON end-to-end.

The one missing DSL feature is `ParamExpr`: it exists in the sealed `Expr` model but `ExprTranslator` rejects it ("not yet implemented"). This change implements it and builds the metric-score feature on top.

This is a cross-cutting change: a DSL capability extension, an analytics data model addition, a new run phase, and a new API surface.

## Goals / Non-Goals

**Goals:**
- Persist reusable Query-DSL metric-score definitions (`DEFAULT` predefined stats + `TEST_SUITE` user-defined) as self-contained `StructuredQuery` JSON, with no separate `kind` discriminator.
- Compute statistics (AVG, P10, P90, MIN, MAX) per numeric metric output field automatically at run-end, plus a DSL-computed `overall` (unweighted mean of the per-metric averages), into a new `metric_score_result` analytics table keyed by the run's `computation_id`.
- Implement `ParamExpr` in the DSL via parameter = expression substitution, and make the function catalog registry-driven (adding the `mean` reduction), without changing behaviour for existing entity repositories or the public `/queries/execute` endpoint.
- Expose computed results to the frontend via a read API. Definitions are seed-only — no management API.

**Non-Goals:**
- Standalone "recompute scores only" endpoint (deferred; storage is designed to enable it later with zero rework).
- Per-test-case overall score and the pass/fail "Cases passed" threshold (per-case concern, not a run-level aggregate).
- Weighted / group-selectable `overall` (needs arithmetic functions + per-suite config) and prev/base run comparison deltas (frontend concern, satisfied by per-run queryability).
- Exposing `ParamExpr` on the public `POST /api/v1/queries/execute` endpoint (stays paramless).

## Decisions

### D1. ParamExpr via expression substitution (not a separate bind channel)
A translation-time `Map<String,Expr>` binds each param name to a concrete `Expr`. `ExprTranslator`, on a `ParamExpr`, looks up the bound `Expr` and translates **that** recursively: a bound `FieldExpr` resolves to a column (incl. JSONB `metric:<name>:<field>` paths), a bound `ValueExpr` becomes a jOOQ bind value. Unbound param → `ValidationException` (HTTP 400); param→param bindings are forbidden (cycle guard).
- **Why:** one uniform mechanism handles both structurally different cases (identifier substitution vs. literal value). FieldExpr and ValueExpr already have correct translation paths — substitution reuses them.
- **Alternative considered:** a typed bind-variable channel (`ParamBinding = ValueBinding | FieldBinding`) dispatched in the translator. Rejected — duplicates the FieldExpr/ValueExpr translation logic and complicates the translator.
- **Threading:** add an optional `Map<String,Expr> params` (default `Map.of()`) through `ExprTranslator.toField`, `FilterTranslator.toCondition`, `StructuredQueryBuilder.build`, `StructuredQueryExecutor.execute`, and a `StructuredQueryService.execute(query, params)` overload + a `StructuredQueryRepository.execute` default-method overload. Existing callers pass the empty map → byte-identical SQL (regression-tested). Only `toField` paths need params; name-based `resolveField`/`resolveGroupKey` do not.

### D2. Definitions store a full, self-contained query with params (filter in DB)
The stored `expression` is a complete `StructuredQuery` — aggregate select **and** the run-scoping filter (`test_suite_run_id eq :runId AND computation_id eq :computationId`) — with `:runId`, `:computationId` as `ParamExpr`, plus either `:metricField` (per-metric leaf stats) or `:metricAvgs` (the run-level `overall`), and the percentile fraction baked as a literal `ValueExpr`.
- **Why:** a future independent recalculation is fully data-driven (load definition → bind params → execute) with no engine-side filter-injection logic. `runId`/`computationId` remain *params* so a definition is a reusable template, never bound to a specific run.
- **Alternative considered:** store only the aggregate select and have the engine programmatically AND the run filter onto the query. Rejected — pushes run-scoping into engine code, making recalc non-data-driven (user explicitly chose DB-stored filter for independent recalculation).

### D3. Both new tables in the ANALYTICS datasource
`metric_score_definition` (config-like) and `metric_score_result` (computed) both live in analytics.
- **Why:** the compute path reads `eval_summaries` (analytics) and writes results (analytics); co-locating definitions avoids a cross-datasource read on the hot path and keeps the DSL `eval_summaries` repository consistent. No cross-DB FK issue — UUIDs are app-level (`VARCHAR(36)`).
- **Trade-off:** bends the usual "meta = config, analytics = computed" split; accepted for cohesion and simplicity.
- Repositories use `@Qualifier("analyticsDsl") DSLContext` + `@Transactional("analyticsTransactionManager")`; results written append-only via the `PostgresEvalSummaryRepository.saveAll` batch pattern.

### D7. Phase 3 executes the persisted DSL query; layering kept intact via an inverted trigger interface
Phase 3 executes each applicable definition's stored `StructuredQuery` `expression` through `StructuredQueryService.execute(query, params)` (binding `runId`/`computationId` plus `metricField` or `metricAvgs` via `ParamExpr`, D1) — this is the feature's original premise ("use the query DSL for metric statistics"), so the stored expressions and `ParamExpr` are live, not vestigial. Dispatch is **param-driven, with no `kind` column**: the executor walks each expression's `ParamExpr` names once — referencing `:metricField` ⇒ per-metric (one execution per field), otherwise run-level (one execution, with `:metricAvgs` bound).
- The computation must touch `experimental.query.service` (the DSL), but the run job (`TestSuiteEvaluationJob`) is stable `service` and cannot move. **Resolution (chosen): dependency inversion** — the executor (`MetricScoreComputationExecutor`) lives in `experimental.query.service.metricscore`, where its use of `StructuredQueryService` is intra-layer; it implements a one-method `MetricScoreComputation` interface declared in `service.domain.job`. The job depends only on that interface (its `MetricScoreComputationContext` also stays in `service`), so there is **no `service → experimental.query.service` bytecode edge** and `LayeredArchitectureTest` is left **unchanged** (no rule relaxed). `experimentalService → service`/`data` (the executor reading repos and the interface/context) is already permitted by the guard.
- **Trade-off:** one thin trigger interface in `service` is the seam. Accepted as the minimal, boundary-preserving inversion; preferred over relaxing the architecture guard.
- **Alternatives considered & rejected:** (a) relax `LayeredArchitectureTest` to allow `service → experimentalService` — rejected to keep the experimental boundary enforced; (b) decouple the trigger via a Spring `ApplicationEvent` — works with zero interface but adds indirection and synchronous-ordering concerns around the COMPLETED transition; (c) a dedicated raw-jOOQ `MetricScoreAggregationRepository` bypassing the DSL — rejected because it left `ParamExpr` and the stored `expression` unused, defeating the feature's purpose.

### D4. Phase 3 in the run job, reusing Phase 2's computation_id
A new `MetricScoreComputationExecutor` (`@Component` in `experimental.query.service.metricscore`, implementing the `service`-layer `MetricScoreComputation` interface — see D7) runs as Phase 3 in `TestSuiteEvaluationJob`, after metric evaluation (Phase 2). It reuses the **same** `computation_id` produced for Phase 2, so results join the run's computation. It discovers numeric metric output fields from `RunMetricSnapshot.outputSchema`, then for each definition either binds `:metricField` per field (per-metric stats) or binds `:metricAvgs` once to an `ArrayExpr` of the run's per-metric `avg(...)` terms (run-level scores like `overall`), executes via `StructuredQueryService`, reads the single aliased `value`, and batch-writes results.
- **Why reuse computation_id:** readers resolve `latest` via the existing `ComputationResolver`; a mismatched id would orphan the scores.
- **Failure handling:** Phase 3 is **non-fatal** — any failure logs and the run still transitions to `COMPLETED` (scores are regenerable). Per-`(def × field)` execution catches `ValidationException` (e.g. JSONB numeric-cast failure surfaced as HTTP 400 by `StructuredQueryExecutor`) and continues, so one bad metric field doesn't abort the whole computation. Honors the run's `cancellationSignal`.

### D5. `overall` computed through the DSL (v1)
`overall` = unweighted mean of the run's per-metric averages, computed **through the Query DSL** — not in Java. It is a seeded `DEFAULT` definition whose `expression` selects `mean(:metricAvgs)`; the executor binds `:metricAvgs` to an `ArrayExpr` of `avg(metric:<tsmd>:<field>)` terms for the run's discovered metrics, and `MeanFunction` folds them as `(e₁+…+eₙ)/n`. Written as a single `metric_score_result` row (`metric_score_name = metric_name = overall`).
- **Why:** keeps the feature's premise (everything flows through the DSL — no hardcoded `addOverall`), while matching "unweighted average for now". The static stored expression works over the run's dynamic metric set because the executor builds the array binding at runtime.
- **Extension window:** a per-suite weighted/selective `overall` becomes a `TEST_SUITE` definition once arithmetic functions (`add/subtract/multiply/divide`, optional `weighted_mean`) are added as `QueryFunction` beans (D8) — no engine changes, just new function components and an authored expression listing metrics + weights.

### D8. Function catalog is registry-driven (`QueryFunction` SPI), not a hardcoded switch
`ExprTranslator`'s former name→jOOQ `switch` is replaced by a `QueryFunctionRegistry` that collects `QueryFunction` beans by name (mirrors the bean-collection pattern of `StructuredQueryService`/`QueryEntityRegistry`). Each built-in (`lower/upper/length/trim/abs/width_bucket/count/sum/avg/min/max/percentile_cont/percentile_disc`) is a bean (`BuiltInQueryFunctions`); `mean` is added as `MeanFunction`. A `FunctionContext` exposes the recursive `toField`, param substitution, bindings, and args. Unknown name → `ValidationException`.
- **Why:** the user requirement — adding a function later must be "drop in a `@Component` and the engine uses it," with no central edits. Makes the weighted-`overall` extension (D5) purely additive.
- **Trade-off:** more classes than a switch; accepted for the extensibility payoff and testability (each function is unit-testable in isolation). Existing render tests act as a migration regression guard.

### D6. API shape
- Read: `GET /api/v1/analytics/metric-score-results?testSuiteRunId=<uuid>&computation=latest|<uuid>` → list of `MetricScoreResultResponseDto` (`Double` value; no timestamps). Plain `@RequestParam` (no `@FilterParam` resolver) so no `OpenApiQueryParamCustomizer` entry is needed.
- Definitions: **seed-only**, no management API. `MetricScoreDefinitionRepository` exposes only `findApplicable(suiteId)`, consumed by the Phase-3 executor.
- `MetricScoreService` exposes only `listResults`; DTO ↔ model via MapStruct; `metric_name` stored/returned as the frontend-friendly `<metricGroup>.<outputField>` (DSL token `metric:<tsmd>:<field>` stays internal).
- New `MetricScoreConstants` (types, reserved param names, stat names, `value` alias, entity); reuse `EvalSummaryExportColumnConstants` for metric-field tokens.

## Risks / Trade-offs

- **Threading `params` through the DSL breaks existing entity queries** → add overloads defaulting to `Map.of()`; add a regression unit test asserting paramless queries produce byte-identical SQL; existing `test_suites`/`eval_summaries` repos forward the empty map.
- **Percentile/AVG over JSONB-extracted numeric may be unstable in aggregate mode** (`percentile_cont … WITHIN GROUP` over a `JsonbFieldResolver` numeric path) → verified structurally during exploration; per-`(def × field)` fault isolation contains runtime cast errors. Fallback: compute P10/P90 engine-side from a ROW-mode query that selects raw `metric:<name>:<field>` values + percentile in Java — a localized change behind the seed/engine seam.
- **computation_id reuse depends on hoisting it out of `buildMetricEvaluationContext`** → if missed, Phase 3 writes under a new id and scores won't resolve as `latest`. Covered by a functional test asserting result `computation_id` equals the run's.
- **Seed JSON drift from the Jackson wire contract / `MetricScoreConstants`** → add a guard test deserializing every seeded `expression` into a `StructuredQuery`; cross-check constants against seed literals.
- **Append-only with `ON CONFLICT DO NOTHING`** means a same-computation re-insert is silently skipped (fine for v1: each run computes once). A future recompute feature will require upsert/replace semantics — noted, not built.
- **Non-fatal Phase 3** means a run can be `COMPLETED` with missing scores → surfaced via logs (exception as last SLF4J arg) and the absence of result rows; acceptable since scores are regenerable.

## Migration Plan

1. Add `V1.9__CreateMetricScoreDefinitionTable.sql`, `V1.10__CreateMetricScoreResultTable.sql`, `V1.11__SeedGlobalMetricScoreDefinitions.sql` under `db/migration/analytics/POSTGRES/` (partial unique indexes on definition; PK `id` + unique `(test_suite_run_id, computation_id, metric_score_name, metric_name)` on result). Neither table carries audit/computation timestamps — the result joins its computation via `computation_id`, and "latest" is resolved from `run_metric_snapshots`.
2. Run `./gradlew generateJooq`; commit generated `MetricScoreDefinition*` / `MetricScoreResult*` sources under `src/main/java-generated/.../jooq/analytics/`.
3. Ship DSL `ParamExpr` support (D1) and analytics layer (D3) before wiring Phase 3 (D4).
4. **Rollback:** drop the two tables / revert migrations; Phase 3 is additive and non-fatal, so reverting the executor leaves runs functioning. The `ParamExpr` translator change is backward compatible (empty-param default).
5. Update `docs/database-schema.md`, `openspec/specs/README.md`, and note `ParamExpr` support in `AGENTS.md`. No new config property expected.

## Open Questions

- Final stored token for `metric_name`: `<metricGroup>.<outputField>` (proposed) vs. the raw tsmd name — confirm with frontend during spec/implementation.
- *(Resolved)* The seeded `overall` row is a `DEFAULT` definition (`target_id IS NULL`) whose value is **computed through the DSL** via `mean(:metricAvgs)`, not engine-derived.
