## Why

The `jsonataExpression` selector added for `Response` metric bindings (change
`multistep-per-turn-column-selection`) lets a metric pick a specific turn/element out of a response
column. Test-case **data** columns have the same need: in a multi-step suite the data column bound to
each turn's user message is an **array**, so a metric that scores, e.g., relevance of the turn-N user
message against the turn-N answer must be able to select `dataColumn[N]`. Today a `TestCase` binding
can only pass the whole column value.

## What Changes

- Add the optional `jsonataExpression` (max 2000 chars) to `TestCaseBindingSourceDto`, mirroring
  `ResponseBindingSourceDto`. When present, `BindingResolver` evaluates it against the resolved
  test-case column value (via the existing `JsonataEvaluationService`) to select an element — arrays
  (`$[0]`, `$[-1]`) or object paths. When absent, the raw column value is used unchanged. An
  expression matching nothing yields `null`; a missing `columnName` still throws (unchanged).
- Config-time: `TestCase` binding `jsonataExpression` **syntax** is validated in
  `MetricDefinitionValidationService`, surfaced as the existing `INVALID_EXPRESSION` soft warning
  (same as Response).
- `Constant` bindings are unaffected (no `jsonataExpression`).

## Capabilities

### New Capabilities
- _(none)_

### Modified Capabilities
- `metric-evaluation`: `BindingResolver` applies the optional `jsonataExpression` selector to
  `TestCase` bindings as well as `Response` bindings (same semantics: raw value when absent, graceful
  `null` on no-match).
- `tsmd-validation`: JSONata syntax validation (`INVALID_EXPRESSION` warning) covers `TestCase`
  bindings in addition to `Response` bindings.

## Impact

- **DTO**: `TestCaseBindingSourceDto` gains optional `jsonataExpression` (persists transparently via
  the existing polymorphic Jackson serialization of metric bindings — no DB migration).
- **BindingResolver**: reuse the existing `applyJsonataSelector` helper in the `TestCase` branch.
- **MetricDefinitionValidationService**: syntax-check the `TestCase` binding `jsonataExpression`.
- **No DB schema, config, or dependency changes.** OpenAPI example for `TestCaseBindingSourceDto`
  and the AGENTS.md metric-binding note are updated.
- Builds on `multistep-per-turn-column-selection` (archive that change first, then this one).
