## MODIFIED Requirements

### Requirement: Parameter binding model
Each TSMD SHALL store `configBindings` and `inputBindings` as JSONB arrays. Each binding entry SHALL have a `property` field (String, the flat top-level key in the metric's config or input schema) and a `source` object with a `$type` discriminator. The system SHALL support four source types:
- `TestCase`: with `columnName` (String) referencing a column from the test suite's `testCaseSchema`
- `Response`: with `columnName` (String) referencing a column from the test suite's `responseColumns`
- `Constant`: with `value` (any JSON value — string, number, boolean, object, array, or `null`). A `null` value is accepted as stored state; soft validation determines whether it is valid based on the target property's required status in the metric schema.
- `Expression`: with `expression` (String, a JSONata expression), evaluated at metric-evaluation time against a per-row frame `{data, response, _metrics}` — `data` is the row's test case data, `response` is the row's extracted columns, and `_metrics` is the run's accumulated `$_metrics` frame (populated only for an inline run; an empty map for Phase 2's propagate-only pass and for any non-inline evaluation). An expression that evaluates to `undefined` or that throws fails that metric for the row; `expression` syntax is validated at write time (HTTP 400 on malformed JSONata). See `metric-evaluation` and `tsmd-validation` for the full runtime/validation semantics.

Status: **Planned**

#### Scenario: TestCase binding source
- **WHEN** a TSMD is created with a binding `{"property": "reference", "source": {"$type": "TestCase", "columnName": "expected_output"}}`
- **THEN** system SHALL persist and return the binding with the `TestCase` source type

#### Scenario: Response binding source
- **WHEN** a TSMD is created with a binding `{"property": "actual", "source": {"$type": "Response", "columnName": "model_answer"}}`
- **THEN** system SHALL persist and return the binding with the `Response` source type

#### Scenario: Constant binding source
- **WHEN** a TSMD is created with a binding `{"property": "threshold", "source": {"$type": "Constant", "value": 0.8}}`
- **THEN** system SHALL persist and return the binding with the `Constant` source type

#### Scenario: Constant with complex JSON value
- **WHEN** a TSMD is created with a binding `{"property": "options", "source": {"$type": "Constant", "value": {"key": "val"}}}`
- **THEN** system SHALL persist and return the binding with the object value intact

#### Scenario: Constant with null value accepted
- **WHEN** a TSMD is created with a binding `{"property": "model", "source": {"$type": "Constant", "value": null}}`
- **THEN** system SHALL accept the request (HTTP 201) and persist the null constant value

#### Scenario: Expression binding source
- **WHEN** a TSMD is created with a binding `{"property": "reference", "source": {"$type": "Expression", "expression": "$_metrics.`judge`.score.details.reason"}}`
- **THEN** system SHALL persist and return the binding with the `Expression` source type, and SHALL reject the create/update with HTTP 400 if `expression` is not syntactically valid JSONata
