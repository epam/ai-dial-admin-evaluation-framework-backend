## ADDED Requirements

### Requirement: TestCase binding jsonataExpression syntax validation
When a TSMD's `TestCase` binding source carries a non-blank `jsonataExpression`, `MetricDefinitionValidationService` SHALL validate its JSONata syntax via `JsonataEvaluationService.validateExpression`. On a syntax error the service SHALL set `is_valid = false` and add a validation warning with `code = INVALID_EXPRESSION`, `path = "$.configBindings"` (when the binding is in `configBindings`) or `"$.inputBindings"` (when in `inputBindings`), and a `message` identifying the invalid expression. A syntactically valid or absent `jsonataExpression` SHALL NOT add a warning. This validation SHALL NOT block the save (soft validation), matching the equivalent `Response` binding rule.
Status: **Planned**

#### Scenario: Invalid TestCase jsonataExpression adds a warning
- **WHEN** a TSMD is created with a `TestCase` binding whose `jsonataExpression` is syntactically invalid (e.g. `"$[("`)
- **THEN** the system SHALL set `is_valid = false` and add a warning with `code = INVALID_EXPRESSION`, the appropriate `path`, and a message identifying the invalid expression

#### Scenario: Valid TestCase jsonataExpression adds no warning
- **WHEN** a TSMD is created with a `TestCase` binding whose `jsonataExpression` is syntactically valid (e.g. `"$[0]"`) and all references resolve
- **THEN** the system SHALL NOT add an `INVALID_EXPRESSION` warning for that binding

#### Scenario: Absent TestCase jsonataExpression adds no warning
- **WHEN** a TSMD is created with a `TestCase` binding that has no `jsonataExpression`
- **THEN** the system SHALL NOT add an `INVALID_EXPRESSION` warning for that binding
