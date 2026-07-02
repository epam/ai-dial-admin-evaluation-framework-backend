## Context

`MultiStepConversationExecutor` runs a multi-turn conversation for one test case, extracting response
columns per turn via `ResponseColumnExtractor.extract(...)` and today accumulating them **row-major**:
`extractedColumns = [{answer:"Paris"},{answer:"Tokio"}]` (one object per completed step), serialized at
`MultiStepConversationExecutor.java:~234`. At the result→metric boundary, `ExtractedColumnsNormalizer`
collapses that array to its **last element** at two sites — `MetricEvaluationWorker.buildRequest` (the
map passed to `BindingResolver`) and `InProcessMetricEvaluationExecutor.buildItem` (the value copied into
`EvalSummary`). `BindingResolver.resolveSource` then does a plain `extractedColumns.get(columnName)`;
`ResponseBindingSourceDto` carries only `columnName`.

Consequences of the current design: (1) a metric can only ever score the **last** turn; (2) the stored
shape duplicates column keys across steps; (3) there is no way to select a specific turn or index into a
column that extracted a JSON object.

JSONata is already the project's expression engine: `com.dashjoin:jsonata:0.9.9`, wrapped by
`JsonataEvaluationService` (`evaluate(expression, jsonString)` + `validateExpression(expression)`), used
today by `ResponseColumnExtractor` and validated on save by `TestSuiteRequestValidator`. Per-turn type
reconciliation already happens inside `extract(...)` via `ResponseColumnTypeReconciler`.

## Goals / Non-Goals

**Goals:**
- Store multi-step `extractedColumns` **column-major**: `{ col: [step0, step1, ...] }`, single-step
  unchanged (`{ col: scalar }`).
- Let a metric select any turn/element via an optional `jsonataExpression` on Response bindings, reusing
  the existing `JsonataEvaluationService`.
- Uniform, no-magic resolution: `columnName` → raw column value; `jsonataExpression` → the only selector.
- Remove the now-obsolete last-step normalization; keep the analytics summary faithful (full arrays).

**Non-Goals:**
- Generalizing `jsonataExpression` to `TestCaseBindingSourceDto` / `ConstantBindingSourceDto` (base class).
- Changing single-step storage, `responseBody`/`requestBody` semantics, retries, fail-fast, non-streaming,
  full-history resend, or `SuiteSnapshotDto` version (`"2"`).
- Any DB schema, configuration, or dependency change.
- Hard-failing (HTTP 400) on an invalid binding `jsonataExpression`.

## Decisions

**1. Column-major storage, built by transposing per-step extraction.** Keep calling
`responseColumnExtractor.extract(...)` per step (unchanged), then transpose its per-step object into a
`Map<String, ArrayNode>` (append each column's value each step; `putNull` when a step's extraction lacks
the column, to keep indices aligned). Serialize a single `{col:[...]}` object at the end. All columns share
length = number of completed steps. *Alternative considered:* have `extract` emit column-major directly —
rejected, it would fork the single/multi-step extraction contract for no gain.

**2. Fail-at-step-0 → `{}` (empty object), not `[]`.** With a column-major object shape, the "no completed
steps" case is a natural empty object. Downstream readers (binding resolution, EvalSummary, export) then
treat single-step and multi-step uniformly as objects. *Alternative:* keep `[]` — rejected, it reintroduces
shape-branching that this change is removing.

**3. Remove `ExtractedColumnsNormalizer` entirely.** Its sole job (array→last element) is obsolete: metrics
now select via `jsonataExpression`, and the summary stores the full arrays. Both call sites parse the raw
`extractedColumns` straight through (`parseJsonMap` / `parseJsonNode`). *Alternative:* repurpose it to
last-element-per-column for the summary — rejected, it would silently drop non-final turns from analytics
and contradict Decision on faithful summary.

**4. `jsonataExpression` on `ResponseBindingSourceDto` only; applied in `BindingResolver`.** After the
existing missing-column guard and `get(columnName)`, if `getJsonataExpression()` is non-blank, serialize the
raw value to JSON and call `jsonataEvaluationService.evaluate(expr, json)`; return the result (**may be
`null`**). Expression root is the **column value** (so `$[0]`/`$[-1]`/object paths operate on it directly).
No expression → return the raw value (whole array in multi-step). *Alternative:* put it on the shared base
`MetricBindingSourceDto` — deferred by explicit scope decision; keeps the resolver's other branches
untouched.

**5. Graceful `null` on empty JSONata result; missing column still throws.** Turn counts vary per test
case, so a fixed selector (`$[2]`) is legitimately out of range for shorter conversations; erroring would
break otherwise-valid runs. A `null` result is passed to the metric, which decides. The pre-existing
missing-`columnName` `IllegalArgumentException` is preserved as defense-in-depth. *Alternative:* fail-fast
on empty — rejected for the variable-turn-count reason.

**6. Warning-based config-time syntax validation.** `MetricDefinitionValidationService.validateBindings`
calls `validateExpression(...)` for a non-blank Response-binding `jsonataExpression` and, on
`ValidationException`, emits a validation **warning** (a new code, e.g. `INVALID_EXPRESSION`) via the
existing `buildWarning(...)`. This matches the service's soft, non-blocking binding-validation model
(UNRESOLVED_REFERENCE etc.). *Alternative:* hard 400 like response-column expressions — rejected to stay
consistent with the metric-binding validation surface, and because bindings are already soft-validated.

**7. Persistence is transparent.** Metric bindings persist as JSON (`configBindings`/`inputBindings`) via
polymorphic Jackson (`@JsonTypeInfo $type`). Adding a field to `ResponseBindingSourceDto` round-trips
automatically as long as mappers serialize the whole DTO (not a field allowlist) — verified as an implementation
step; no migration.

## Risks / Trade-offs

- **Export/filter assuming scalar cells** → Verify the `eval-summary-export` CSV path and any filter over
  `extractedColumns`; render array cells via the existing CSV cell-serialization rules (JSON-encode).
  Numeric filtering on an array-valued column is not meaningful and is left as-is.
- **Existing multi-step metric configs omitting `jsonataExpression` now receive whole arrays** (previously
  the last turn) → Acceptable: multi-step is an unreleased POC; the no-magic model is intentional. Documented
  in AGENTS.md and specs.
- **A `jsonataExpression` typo silently yields `null` at runtime** → Config-time syntax validation catches
  malformed expressions; a valid-but-wrong selector is a user error surfaced as `null`, consistent with the
  extraction layer's graceful degradation.
- **Two consumers must agree on the new shape** (binding resolution + summary copy) → Both now read the raw
  value with no normalization, which is simpler and removes the prior two-call-site coupling.

## Migration Plan

No runtime migration (unreleased POC, no persisted-shape guarantees, no DB change). Deploy is code-only.
Rollback = revert the change; no data backfill. Delete `ExtractedColumnsNormalizer` and its test in the same
change; update AGENTS.md and OpenAPI examples so the documented contract matches.

## Open Questions

_(none — all six design decisions locked with the user during grilling.)_
