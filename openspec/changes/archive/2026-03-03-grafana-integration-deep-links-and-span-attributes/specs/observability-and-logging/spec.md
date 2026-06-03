## ADDED Requirements

### Requirement: eval.run.id and eval.suite.id span attributes on eval.testcase.execute
The `eval.testcase.execute` span created by `EvaluationWorker` SHALL include `eval.run.id` (the test suite run UUID) and `eval.suite.id` (the test suite UUID) as span attributes. These attributes enable run-scoped and suite-scoped trace search in Grafana Tempo via TraceQL (e.g., `{.eval.run.id="<uuid>"}`).

#### Scenario: Span attributes set on test case execution
- **WHEN** `EvaluationWorker.execute()` creates the `eval.testcase.execute` span
- **THEN** the span SHALL include attribute `eval.run.id` set to the test suite run ID (UUID string)
- **AND** the span SHALL include attribute `eval.suite.id` set to the test suite ID (UUID string)
- **AND** existing attributes `testcase.id` and `run.index` SHALL remain unchanged

#### Scenario: Attributes enable TraceQL run-scoped search
- **WHEN** `OTEL_SDK_DISABLED=false` and a test suite run executes N test cases
- **THEN** a Grafana Tempo TraceQL query `{.eval.run.id="<run-uuid>"}` SHALL return all N test-case traces for that run

### Requirement: eval.suite.id span attribute on try-it-out.invoke
The `try-it-out.invoke` span created by `TryItOutService` SHALL include `eval.suite.id` (the test suite UUID) as a span attribute. This enables suite-scoped filtering of try-it-out traces in Grafana Tempo.

#### Scenario: Suite ID attribute set on try-it-out invocation
- **WHEN** `TryItOutService.invokeAndBuildResponse()` creates the `try-it-out.invoke` span
- **THEN** the span SHALL include attribute `eval.suite.id` set to the test suite ID (UUID string)

#### Scenario: No attribute when OTel disabled
- **WHEN** `OTEL_SDK_DISABLED=true` (no-op span)
- **THEN** span attribute operations are no-ops and no data is exported
