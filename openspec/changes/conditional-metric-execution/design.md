## Context

Metric evaluation runs in Phase 2 of `TestSuiteEvaluationJob` via
`InProcessMetricEvaluationExecutor.evaluateAndBuild()`: for each `TestCaseRunResult`, every enabled +
valid TSMD (`AggregatedMetricDefinition`) is dispatched to `MetricEvaluationWorker`, and results are
collected into a `Map<String, TsmdEvaluationResult>` (sealed `Success`/`Failure`). `MetricOutputMapper`
builds `metricValues`/`metricInfos` from **only the entries present in that map**; `checkForErrors`
drives the per-result `executionStatus`. The column dictionary (`testCaseData`, `extractedColumns`) is
already parsed at that point via `BindingResolver.parseJsonMap`. JSONata is fully abstracted behind
`JsonataEvaluationService` (`DashjoinJsonataEvaluationService`, `com.dashjoin:jsonata:0.9.9`). The CSV
export (`EvalSummaryExportColumnConstants` / `EvalSummaryExportRow`) already renders a wholesale
`metricInfos[m] = {error:…}` node as the `metricError::<m>` column, and uses the namespace tokens
`data`/`response` with a `::` separator.

## Goals / Non-Goals

**Goals:**
- Optional per-TSMD `condition` string that gates whether the metric runs for a given test case.
- Support JSONata conditions and an extensible custom-function mechanism (empty for now).
- Reuse existing abstractions (JSONata service, export namespacing, metricError rendering); no new
  external dependency.
- Leave a clean seam for a future **per-turn** conditional evaluation change.

**Non-Goals:**
- Per-turn conditional evaluation and any per-turn custom functions (incl. `isLastTurn()`).
- Changing metric binding resolution, response extraction, or the `is_valid` soft-validation model.
- Static column-existence validation of the condition at write time.

## Decisions

### D1. Condition lives on TSMD as a nullable `VARCHAR(2000)` string
One expression per metric, evaluated per test case. Null/blank ⇒ always run (backward compatible).
*Alternative:* JSONB — rejected; the value is a single scalar expression, not structured data.

### D2. Per-conversation granularity
Evaluate the condition once per `TestCaseRunResult`, matching current metric granularity. Per-turn
gating is a separate future change. *Alternative:* per-turn now — rejected as too large; no per-turn
metric evaluation exists yet.

### D3. Namespaced evaluation dictionary `{ data, response }`
The condition sees `{"data": {…testCaseData}, "response": {…extractedColumns}}`, reusing the export's
`data`/`response` tokens. *Alternative:* flat merge (one namespace) — rejected because a dataset
column and an extracted column can collide; namespacing is unambiguous and matches what users already
see in exports. Multi-step `response.*` values remain column-major arrays (same shape metric bindings
see); a condition can index a turn with JSONata (`response.answer[-1]`).

### D4. Detection: bare `name()` = custom function, else JSONata
If the whole trimmed condition matches `^[A-Za-z_][A-Za-z0-9_]*\(\)$` it is a custom-function call;
otherwise it is JSONata. Safe because JSONata's own functions are always `$`-prefixed, so bare
`name()` never collides with a valid JSONata call. *Alternatives:* exact-set membership (can't yield
an "unknown function" 400), or an explicit sigil like `@fn()` (new syntax users must learn) — both
rejected.

### D5. Extensible, initially empty custom-function registry (SPI)
`ConditionFunction` interface (`String name()`, `boolean evaluate(ConditionContext)`) collected by
`ConditionFunctionRegistry` (rejects duplicate names at startup) — mirrors `QueryFunctionRegistry`.
No beans shipped. `isLastTurn()` therefore currently 400s as "not available".

### D6. `ConditionContext` carrier, not positional params
`ConditionExpressionEvaluator.evaluate(String condition, ConditionContext context)` and
`ConditionFunction.evaluate(ConditionContext)` take one extensible context (builder-backed). Future
per-turn fields (stepIndex/stepCount) are additive — no signature churn, no ripple to callers.
*Alternative:* `evaluate(condition, dataJson, responseJson)` — rejected; every future field breaks
the signature and all call sites.

### D7. Write-time validation → hard 400 (no persisted warning)
`ConditionExpressionEvaluator.validate(condition)` is called in
`TestSuiteMetricDefinitionService.create/update`; a syntax error (via
`JsonataEvaluationService.validateExpression`) or an unregistered bare `name()` throws
`ValidationException` → 400. The condition does not feed the soft `is_valid` flag. *Rationale:*
warnings are not surfaced in any UI, so a persisted warning has no value; reject eagerly.

### D8. Runtime outcome mapping (RUN / SKIP-omit / error-surface)
In the TSMD loop, before dispatch, build one `ConditionContext` per result and call `evaluate`:
- **RUN** (clean boolean true) → existing dispatch path unchanged.
- **SKIP** (clean boolean false) → `continue`; put nothing in the results map → metric omitted from
  `metricValues` and `metricInfos` ("absent = intentionally skipped").
- **error** (throws, non-boolean, or null) → put a new
  `TsmdEvaluationResult.ConditionError(message, outputFieldNames)`; **no** async dispatch.
`MetricOutputMapper` handles `ConditionError` by writing **no** `metricValues` node and a metric-level
`metricInfos[tsmd] = {"error": message}` (→ `metricError::<name>` column). `checkForErrors` **ignores**
`ConditionError`, so the test-case result stays `SUCCESS`. *Alternative:* reuse `Failure` — rejected;
it renders per-field nulls and flips the result to FAILED, inflating failure counts for a per-metric
config issue.

### D9. Strict boolean interpretation
Only a real boolean counts: `true` → RUN, `false` → SKIP. Anything else (non-boolean, null, throw) →
error. Predictable and avoids surprising truthiness coercion.

## Risks / Trade-offs

- **A typo'd condition silently drops a metric for every row (fail-closed).** → Mitigated by D7
  (syntax/unknown-function rejected at write time with 400) and D8 error path (runtime errors are
  surfaced under `metricError::`, not silently swallowed).
- **"Absent = skipped" is an implicit signal.** → Documented; consistent with the existing
  present-with-null = error convention and the export's `metric::`/`metricError::` split.
- **Detection heuristic could misclassify an exotic JSONata expression as a custom function.** →
  Only a *bare* `name()` (no `$`, no args, nothing else) is treated as custom; valid JSONata function
  calls are `$`-prefixed, so real expressions are unaffected.
- **Dictionary serialization cost per metric per result.** → Build the `ConditionContext` and its JSON
  once per test-case result and reuse across its TSMDs; only metrics with a non-blank condition invoke
  the evaluator.

## Migration Plan

1. Flyway meta migration `V{n}__AddTsmdCondition.sql` — `ALTER TABLE test_suite_metric_definitions
   ADD COLUMN condition VARCHAR(2000)` (nullable; existing rows get NULL ⇒ always run).
2. `./gradlew generateJooq`; commit generated sources. Update `docs/database-schema.md`.
3. Ship code additively; no data backfill. Rollback = drop column (no existing rows depend on it).

## Open Questions

- None outstanding — all resolved during grilling (granularity, custom-fn scope, detection, write
  validation, dictionary namespacing, skip/error representation, result status).
