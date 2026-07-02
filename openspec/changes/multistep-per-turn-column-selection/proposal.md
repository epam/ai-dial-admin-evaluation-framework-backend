## Why

Multi-step (`multiStep == true`) runs already extract response columns per turn, but store them
**row-major** (`[{answer:"Paris"},{answer:"Tokio"}]`) and the metric boundary collapses that array to
its **last turn only**, so a metric can never score any turn but the final one. Evaluators need to score
*any* turn of a conversation (e.g. the first assistant reply, or a specific middle turn), and to reach
into a column that extracted a JSON object — not just the last scalar.

## What Changes

- **BREAKING** (POC, unreleased): multi-step `extractedColumns` becomes **column-major** — one array per
  column keyed by name: `{ "answer": ["Paris","Tokio"], "score": [0.90,0.85] }` (each element still
  type-reconciled per step). Single-step keeps its object-of-scalars shape. This also removes the
  row-major duplication of column keys.
- **BREAKING** (POC, unreleased): the "normalize multi-step columns to the last step" behavior is
  **removed**. `ExtractedColumnsNormalizer` and both its call sites are deleted; metric bindings resolve
  against the **raw** stored `extractedColumns`, and `EvalSummary.extractedColumns` stores the full arrays.
- New optional `jsonataExpression` on `ResponseBindingSourceDto`. When present, the metric binding
  evaluates it (via the existing `JsonataEvaluationService`) **against the resolved column value** to select
  an element — arrays (`$[0]`, `$[-1]`) or object paths. When absent, the binding resolves to the raw column
  value (the whole array in multi-step). This is the sole turn/element selector — no implicit "last turn".
- Runtime: a syntactically valid `jsonataExpression` that matches nothing (e.g. `$[2]` on a 2-turn
  conversation — legal because turn counts vary per test case) yields **`null`** to the metric. A missing
  `columnName` still throws, as today.
- Config-time: `jsonataExpression` **syntax** is validated in `MetricDefinitionValidationService` and
  surfaced as a validation **warning** (consistent with that service's warning-based binding validation),
  not a hard 400.
- Fail-at-step-0 now yields an empty **object** `{}` for `extractedColumns` (was empty array `[]`).
- CSV export / filter over `EvalSummary.extractedColumns` get a verification pass to tolerate
  array-valued cells for multi-step results.

## Capabilities

### New Capabilities
- _(none)_

### Modified Capabilities
- `multi-step-conversation`: per-result `extractedColumns` shape changes from a row-major array of
  per-step maps to a column-major object of per-column arrays; the "normalize to last step" requirement
  is removed; fail-at-step-0 yields `{}` not `[]`.
- `metric-evaluation`: `BindingResolver` resolves Response bindings against the raw `extractedColumns`
  (no normalization) and applies an optional `jsonataExpression` selector (graceful `null` on no-match);
  `EvalSummary.extractedColumns` stores the raw value (arrays for multi-step).
- `tsmd-validation`: adds a warning for a syntactically invalid Response-binding `jsonataExpression`.
- `eval-summary-export`: `extractedColumns` cell values may be arrays (multi-step); cell serialization
  must render them per the existing serialization rules.

## Impact

- **DTO**: `ResponseBindingSourceDto` gains optional `jsonataExpression` (persisted transparently via the
  existing polymorphic Jackson serialization of metric bindings — no DB migration).
- **Executor**: `MultiStepConversationExecutor` transposes per-step extraction to column-major.
- **Removed class**: `service.domain.job.ExtractedColumnsNormalizer` (+ its test); two call sites in
  `MetricEvaluationWorker.buildRequest` and `InProcessMetricEvaluationExecutor.buildItem` simplified.
- **Binding resolution**: `BindingResolver` injects `JsonataEvaluationService` for the selector.
- **Validation**: `MetricDefinitionValidationService` injects `JsonataEvaluationService`.
- **Export/filter**: verification pass over the `eval-summary-export` CSV path for array-valued cells.
- **No DB schema, config, or dependency changes.** OpenAPI examples for `ResponseBindingSourceDto` and
  multi-step `extractedColumns` are updated. AGENTS.md multi-step convention is updated.
