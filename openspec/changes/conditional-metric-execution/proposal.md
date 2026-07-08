## Why

Today every enabled + valid Test Suite Metric Definition (TSMD) is evaluated against **every**
test-case result in a run. There is no way to say "only score answer relevancy when the model
actually produced an answer" — users must split a suite into narrower suites or accept metric
errors/noise on rows the metric doesn't apply to. This change lets each TSMD carry an optional
**condition** expression, evaluated per test case at run time, that decides whether the metric runs
for that case.

## What Changes

- Add an optional `condition` string field to a TSMD (create/update request, response DTO, DB model,
  table column). Null/blank ⇒ metric always runs (**backward compatible**).
- Evaluate the condition **once per test-case result** (per whole conversation — same granularity as
  metric evaluation today) against a **namespaced dictionary** `{ "data": {…test-case columns},
  "response": {…extracted/response columns} }`, reusing the CSV-export namespace tokens
  (`data`, `response`).
- Two ways to write a condition:
  - **JSONata** (default) — e.g. `$exists(response.answer)`.
  - **Custom/system function** — a bare `name()` call (e.g. `isLastTurn()`). Detection: the whole
    trimmed condition matches a bare identifier + `()`. Ship the recognizer + an **extensible,
    initially empty** function registry (mirroring the existing `QueryFunctionRegistry` SPI). No
    built-in functions in this change — `isLastTurn()` is inherently per-turn and deferred.
- **Write-time validation → HTTP 400** on a malformed condition (JSONata syntax error, or a bare
  `name()` referencing an unregistered function). The condition does **not** participate in the soft
  `is_valid` flag and no warning is persisted (warnings aren't surfaced anywhere).
- **Runtime outcome** of the condition:
  - clean boolean **true** → run the metric normally.
  - clean boolean **false** → **skip and omit entirely** (no entry in `metricValues`/`metricInfos`);
    "absent = intentionally skipped" (present-with-null still means error, unchanged).
  - **error / non-boolean / throws** → fail-closed skip, but **surfaced**: write a metric-level
    `metricInfos[tsmd] = {"error": …}` (renders as the existing `metricError::<name>` export column),
    write nothing to `metricValues`, and keep the test-case result `executionStatus` **SUCCESS** (a
    broken condition is a per-metric concern, not a run failure — it must not inflate failure counts).
- New service-layer component `ConditionExpressionEvaluator` and an extensible `ConditionContext`
  carrier (2nd param of `evaluate`, also passed to custom functions) so per-turn fields can be added
  later without changing signatures.

Not changing: metric binding resolution, response extraction, the `is_valid` soft-validation model,
or the `eval-summary-export` rendering (it already renders wholesale `{error}` nodes as
`metricError::`).

## Capabilities

### New Capabilities
- `conditional-metric-execution` — the feature: the `condition` semantics, the namespaced evaluation
  dictionary, JSONata-vs-custom-function detection, the empty extensible custom-function registry +
  `ConditionContext` seam, and the run-time RUN / SKIP-omit / error-surface behavior.

### Modified Capabilities
- `test-suite-metric-definitions` — the create/update/response contract gains the optional
  `condition` field and hard-400 rejection of a malformed condition.
- `metric-evaluation` — evaluation now honors a TSMD's condition: skipped metrics are omitted from
  the eval summary, condition errors are surfaced under `metricError` without failing the result.

## Impact

- **API**: `TestSuiteMetricDefinitionRequestDto` / `TestSuiteMetricDefinitionResponseDto` gain
  `condition`; POST/PUT can now return **400** (`VALIDATION_ERROR`) for a malformed condition. OpenAPI
  schema + examples updated.
- **DB**: Flyway meta migration `V{n}__AddTsmdCondition.sql` adding
  `test_suite_metric_definitions.condition VARCHAR(2000)` (nullable); regenerate jOOQ; update
  `docs/database-schema.md`.
- **Code**: new `ConditionExpressionEvaluator`, `ConditionContext`, `ConditionFunction` (SPI),
  `ConditionFunctionRegistry` in `service.domain`; new `TsmdEvaluationResult.ConditionError` variant;
  edits to `InProcessMetricEvaluationExecutor`, `MetricOutputMapper`, `checkForErrors`,
  `TestSuiteMetricDefinitionService`, the mapper/model/repository. Reuses `JsonataEvaluationService`.
- **Config**: none expected.
- **Docs**: AGENTS.md inline convention; `docs/database-schema.md`; `openspec/specs/README.md` (new
  capability entry).
