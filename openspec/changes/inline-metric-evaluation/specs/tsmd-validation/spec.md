## ADDED Requirements

### Requirement: `Expression` metric-binding source
A TSMD's `configBindings`/`inputBindings` SHALL support a fourth binding source type, `Expression` (`$type: "Expression"`), alongside the existing `TestCase`, `Response`, and `Constant` sources. An `Expression` source SHALL carry a single required field `expression` (String, a JSONata expression). At evaluation time the expression SHALL be evaluated against a per-row frame containing `_metrics` (the `$_metrics` accumulator visible at that row — see `metric-evaluation`), `data` (the row's `testCaseData`) and `response` (the row's `extractedColumns`), letting the same source type transform any of those three inputs, not only metric outputs. The bound property's value SHALL be the expression's evaluation result. An expression that evaluates to JSONata `undefined`, or that throws, SHALL be treated identically to the existing `Response`/`TestCase` unresolved-binding failure: the resolver throws `IllegalArgumentException`, which fails that metric for that row (recorded as a FAILED eval-summary entry; under inline evaluation this additionally aborts the chain per the "Inline metric failure aborts the chain" requirement in `metric-evaluation`).

Status: **Planned**

#### Scenario: Expression binding resolves a metrics-frame value
- **WHEN** a TSMD binds `{"property": "reference", "source": {"$type": "Expression", "expression": "$_metrics.`judge`.score.details.reason"}}` and evaluates on a row where an earlier request's `judge` TSMD produced that field
- **THEN** the bound property's value SHALL be that field's string value

#### Scenario: Expression binding can also transform data or response
- **WHEN** a TSMD binds `{"property": "threshold", "source": {"$type": "Expression", "expression": "data.category = \"billing\" ? 0.9 : 0.7"}}`
- **THEN** the bound property's value SHALL be computed from the row's `testCaseData`, exactly as an expression over `_metrics` would be

#### Scenario: Undefined expression result fails the metric
- **WHEN** an `Expression` binding's expression evaluates to JSONata `undefined` (e.g. it references a `$_metrics` path with no value at that row)
- **THEN** `BindingResolver` SHALL throw `IllegalArgumentException`, which fails that TSMD for that row exactly as an unresolved `Response`/`TestCase` binding does today

#### Scenario: Throwing expression fails the metric
- **WHEN** an `Expression` binding's expression throws during evaluation
- **THEN** `BindingResolver` SHALL throw `IllegalArgumentException`, which fails that TSMD for that row

### Requirement: Expression binding syntax is validated at write time
When a Test Suite Metric Definition is created or updated, a non-blank `Expression`-source binding's `expression` SHALL be validated as syntactically valid JSONata, using the same validation path as the existing `condition` syntax check. A malformed expression SHALL be rejected with HTTP 400. No cross-TSMD reference validation SHALL be performed — the validator checks only that the expression parses as JSONata; it makes no attempt to determine whether the fields the expression references (a `$_metrics` path, `data.*`, `response.*`) will actually exist at evaluation time, and no new `ValidationWarningCode` (e.g. an `UNRESOLVED_REFERENCE` variant scoped to metric-to-metric references) SHALL be introduced for this. Runtime resolution failures are loud (see "Expression metric-binding source"), by design.

Status: **Planned**

#### Scenario: Malformed expression rejected on create
- **WHEN** a metric definition is created with an `Expression`-source binding whose `expression` is syntactically invalid JSONata
- **THEN** the request is rejected with HTTP 400

#### Scenario: Malformed expression rejected on update
- **WHEN** an existing metric definition is updated with a malformed `Expression`-source `expression`
- **THEN** the request is rejected with HTTP 400

#### Scenario: Valid expression referencing a not-yet-producing TSMD is accepted at write time
- **WHEN** a metric definition is created with a syntactically valid `Expression` binding referencing `$_metrics.someOtherMetric.value`, and no other TSMD in the suite currently produces `someOtherMetric`
- **THEN** the write SHALL succeed (HTTP 201/200) — no cross-TSMD reference check is performed; any resulting resolution failure surfaces only at evaluation time
