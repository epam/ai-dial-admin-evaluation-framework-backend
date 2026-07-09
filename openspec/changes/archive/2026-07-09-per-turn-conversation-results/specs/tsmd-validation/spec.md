## REMOVED Requirements

### Requirement: INVALID_EXPRESSION validation warning code
**Reason**: The only producer of `INVALID_EXPRESSION` was binding-source `jsonataExpression` syntax validation, which is removed with the full JSONata/array rollback (per-turn result rows make binding-time turn selection unnecessary). Binding sources no longer carry a `jsonataExpression`, so no such warning can be produced.

### Requirement: Response binding jsonataExpression syntax validation
**Reason**: `ResponseBindingSourceDto.jsonataExpression` is removed; `MetricDefinitionValidationService` no longer validates a binding-level JSONata selector. Response bindings resolve their scalar column value directly (see metric-evaluation "Binding resolution").

### Requirement: TestCase binding jsonataExpression syntax validation
**Reason**: `TestCaseBindingSourceDto.jsonataExpression` is removed; `MetricDefinitionValidationService` no longer validates a binding-level JSONata selector. TestCase bindings resolve their scalar column value directly.
