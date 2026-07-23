## ADDED Requirements

### Requirement: Condition syntax is validated at write time
When a Test Suite Metric Definition is created or updated, a non-blank `condition` SHALL be validated as a syntactically valid JSONata expression. Malformed expressions are rejected with HTTP 400. A blank/null condition is a no-op.

#### Scenario: Malformed condition rejected on create
- **WHEN** a metric definition is created with a syntactically invalid JSONata `condition`
- **THEN** the request is rejected with HTTP 400

#### Scenario: Malformed condition rejected on update
- **WHEN** an existing metric definition is updated with a malformed `condition`
- **THEN** the request is rejected with HTTP 400

## Implementation notes

Planned. `TestSuiteMetricDefinitionService` create/update calls `ConditionExpressionEvaluator.validate(condition)`, which delegates to `JsonataEvaluationService.validateExpression`.
