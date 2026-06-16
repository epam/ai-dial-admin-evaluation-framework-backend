## ADDED Requirements

### Requirement: testcase.name span attribute on eval.testcase.execute
The `eval.testcase.execute` span created by `EvaluationWorker` SHALL include `testcase.name` (the human-readable test case name) as a span attribute for improved readability in Grafana Tempo trace views.

#### Scenario: testcase.name attribute set on test case execution
- **WHEN** `EvaluationWorker.execute()` creates the `eval.testcase.execute` span
- **THEN** the span SHALL include attribute `testcase.name` set to the test case name string
- **AND** existing attributes (`testcase.id`, `run.index`, `eval.run.id`, `eval.suite.id`) SHALL remain unchanged

### Requirement: testcase.id and testcase.name span attributes on metric.tsmd.evaluate
The `metric.tsmd.evaluate` span created by `MetricEvaluationWorker` SHALL include `testcase.id` (the test case UUID) and `testcase.name` (the human-readable test case name) as span attributes. These attributes enable per-test-case trace aggregation in Grafana Tempo via TraceQL (e.g., `{.eval.run.id="<uuid>" && .testcase.id="<uuid>"}`).

#### Scenario: testcase.id and testcase.name attributes set on metric evaluation
- **WHEN** `MetricEvaluationWorker.evaluate()` creates the `metric.tsmd.evaluate` span
- **THEN** the span SHALL include attribute `testcase.id` set to `result.getTestCaseId()` (UUID string)
- **AND** the span SHALL include attribute `testcase.name` set to `result.getTestCaseName()` (string)
- **AND** existing attributes (`tsmd.name`, `tsmd.provider.id`, `eval.run.id`, `result.id`) SHALL remain unchanged

#### Scenario: Attributes enable per-test-case TraceQL aggregation
- **WHEN** `OTEL_SDK_DISABLED=false` and a test suite run executes N test cases with M TSMDs each
- **THEN** a Grafana Tempo TraceQL query `{.eval.run.id="<run-uuid>" && .testcase.id="<tc-uuid>"}` SHALL return the eval span and all M metric spans for that test case

### Requirement: eval.suite.id span attribute on metric.tsmd.evaluate
The `metric.tsmd.evaluate` span SHALL include `eval.suite.id` (the test suite UUID) as a span attribute, enabling suite-scoped trace filtering for metric evaluations.

#### Scenario: eval.suite.id attribute set on metric evaluation
- **WHEN** `MetricEvaluationWorker.evaluate()` creates the `metric.tsmd.evaluate` span
- **THEN** the span SHALL include attribute `eval.suite.id` set to the test suite ID from `MetricEvaluationContext.getTestSuiteId()` (UUID string)

### Requirement: metric.declaration.name span attribute on metric.tsmd.evaluate
The `metric.tsmd.evaluate` span SHALL include `metric.declaration.name` (the metric declaration name from the provider) as a span attribute for human-readable identification of which metric is being evaluated.

#### Scenario: metric.declaration.name attribute set on metric evaluation
- **WHEN** `MetricEvaluationWorker.evaluate()` creates the `metric.tsmd.evaluate` span
- **THEN** the span SHALL include attribute `metric.declaration.name` set to `tsmd.getMetricDeclarationName()` (string)

#### Scenario: Attribute aids Grafana trace navigation
- **WHEN** a user views traces in Grafana Tempo for a specific run
- **THEN** `metric.declaration.name` SHALL be visible as a span attribute, allowing the user to identify which metric provider metric was evaluated without needing to cross-reference TSMD names
