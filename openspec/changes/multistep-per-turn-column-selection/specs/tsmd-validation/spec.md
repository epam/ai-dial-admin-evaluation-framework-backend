## ADDED Requirements

### Requirement: Response binding jsonataExpression syntax validation
When a TSMD's `Response` binding source carries a non-blank `jsonataExpression`, `MetricDefinitionValidationService` SHALL validate its JSONata syntax via `JsonataEvaluationService.validateExpression`. On a syntax error the service SHALL set `is_valid = false` and add a validation warning with `code = INVALID_EXPRESSION`, `path = "$.configBindings"` (when the binding is in `configBindings`) or `"$.inputBindings"` (when in `inputBindings`), and a `message` identifying the invalid expression. A syntactically valid or absent `jsonataExpression` SHALL NOT add a warning. This validation SHALL NOT block the save (soft validation, consistent with binding reference validation).
Status: **Planned**

#### Scenario: Invalid jsonataExpression adds a warning
- **WHEN** a TSMD is created with a `Response` binding whose `jsonataExpression` is syntactically invalid (e.g. `"$[("`)
- **THEN** the system SHALL set `is_valid = false` and add a warning with `code = INVALID_EXPRESSION`, the appropriate `path`, and a message identifying the invalid expression

#### Scenario: Valid jsonataExpression adds no warning
- **WHEN** a TSMD is created with a `Response` binding whose `jsonataExpression` is syntactically valid (e.g. `"$[-1]"`) and all references resolve
- **THEN** the system SHALL NOT add an `INVALID_EXPRESSION` warning for that binding

#### Scenario: Absent jsonataExpression adds no warning
- **WHEN** a TSMD is created with a `Response` binding that has no `jsonataExpression`
- **THEN** the system SHALL NOT add an `INVALID_EXPRESSION` warning for that binding

### Requirement: INVALID_EXPRESSION validation warning code
The `ValidationWarningCode` enum SHALL include a value `INVALID_EXPRESSION` with the description "A JSONata expression that is syntactically invalid".

#### Scenario: Code appears in TSMD validation warnings
- **WHEN** a TSMD has a `Response` binding with a syntactically invalid `jsonataExpression`
- **THEN** the validation warning in `validationWarnings` SHALL have `code = "INVALID_EXPRESSION"`
