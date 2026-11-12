## ADDED Requirements

### Requirement: Per-turn spans within execution traces
Within an HTTP test case's `eval.testcase.execute` trace, each request turn SHALL run inside its own `eval.testcase.turn` span, a child of `eval.testcase.execute`, under which that turn's outbound deployment invocation spans SHALL nest. The turn span SHALL carry `request.index`, `request.total`, `turn.index`, `turn.total` and `eval.phase` (`execution`), and `request.name` when the request is labelled. Index values SHALL be zero-based and, like `run.index`, string-valued; `turn.total` SHALL be the request's planned turn count, not the number of turns actually run. A turn whose request building or invocation aborts the request's turn loop SHALL give its span ERROR status with a short description, recording verbose failure details as a span event and a thrown exception as an exception event. The turn span SHALL NOT carry `eval.run.id` or `testcase.id`, so a run- or test-case-scoped span search keeps matching one span per execution trace. MCP test-case executions SHALL NOT get turn spans.
Status: **Implemented**

#### Scenario: Multi-turn repetition
- **WHEN** a repetition runs three turns of one request with tracing enabled
- **THEN** its execution trace SHALL contain three `eval.testcase.turn` children of `eval.testcase.execute` with `turn.index` `0`, `1`, `2` and `turn.total` `3`
- **AND** each turn's deployment invocation spans SHALL descend from that turn's span

#### Scenario: Multi-request chain
- **WHEN** a repetition runs a chain of requests
- **THEN** every turn span SHALL carry its request's `request.index` and the chain's `request.total`, plus `request.name` when that request is labelled

#### Scenario: Aborting turn
- **WHEN** a turn fails and stops the loop
- **THEN** that turn's span SHALL have ERROR status, earlier turns' spans SHALL NOT, and no span SHALL be created for turns that never ran
- **AND** the spans' `turn.total` SHALL still be the planned turn count

#### Scenario: Turn throws during request building
- **WHEN** building a turn's request throws
- **THEN** that turn's span SHALL have ERROR status with the exception recorded
- **AND** the repetition SHALL still persist an ERROR result row for that turn

#### Scenario: Run-scoped search unaffected
- **WHEN** spans are searched by `eval.run.id` and `testcase.id`
- **THEN** turn spans SHALL NOT match

### Requirement: Request and turn position on metrics spans
`eval.testcase.metrics` and `metric.tsmd.evaluate` spans SHALL carry their result row's `request.index`, `request.total`, `turn.index` and `turn.total`, and `request.name` when the row's request is labelled, using the same zero-based string values and planned-count `turn.total` semantics as turn spans.
Status: **Implemented**

#### Scenario: Distinguishable turn metrics traces
- **WHEN** one repetition produces several turn result rows whose metrics are dispatched
- **THEN** each row's metrics root and its TSMD spans SHALL carry that row's own `turn.index`
- **AND** within a run, a TraceQL filter on `testcase.id`, `run.index`, `request.index` and `turn.index` together SHALL select exactly that row's metrics trace

#### Scenario: Labelled chained request
- **WHEN** a result row comes from the second request of a chain labelled `second`
- **THEN** its metrics spans SHALL carry `request.index` `1` and `request.name` `second`

## Implementation notes
- [evaluation tracing pattern](../../../../../docs/patterns/evaluation-tracing.md); `TurnTracer`, `TurnLoopExecutor`, `MetricEvaluationWorker`, `TracingConstants`.
