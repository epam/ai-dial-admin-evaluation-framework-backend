## Why

A repetition's `eval.testcase.execute` trace lists every request turn's outbound call as a flat, unlabelled sibling, so a multi-turn or multi-request test case cannot be read turn by turn in Grafana. Its per-row `eval.testcase.metrics` traces carry no request/turn position either, so the metrics traces of one repetition's turns look identical and read as duplicates.

## What Changes

Status: **Implemented** (after-the-fact change; code, tests and pattern doc already landed in the working tree).

- Every HTTP request turn of a repetition runs inside its own `eval.testcase.turn` span, a child of `eval.testcase.execute` and the parent of that turn's outbound deployment call. It carries `request.index`, `request.total`, `request.name` (when labelled), `turn.index`, `turn.total` and `eval.phase`. A turn that aborts the loop marks its span ERROR.
- The turn span deliberately omits `eval.run.id` and `testcase.id`, so run/test-case TraceQL searches keep returning one span per execution trace.
- `eval.testcase.metrics` and `metric.tsmd.evaluate` spans additionally carry the result row's `request.index`, `request.total`, `request.name` (when labelled), `turn.index` and `turn.total`.
- Position attribute values are zero-based strings, consistent with `run.index`.
- MCP suites are unchanged (single invocation, no turn span).

Non-goals: one trace per turn; changing the persisted `traceId` (all rows of a repetition keep sharing the execution trace ID); any REST, CSV, DB or configuration change.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `observability-and-logging`: adds requirements for per-turn spans inside execution traces and for request/turn position attributes on metrics spans.

## Impact

- New class `runner.job.TurnTracer` (Spring component in `evaluation-runner-core`); `TurnLoopExecutor` runs each turn through it.
- `MetricEvaluationWorker` stamps row position on metrics and TSMD spans.
- `TracingConstants` gains `SPAN_EVAL_TESTCASE_TURN` and the `request.*` / `turn.*` attribute keys.
- No API, DB schema, Flyway, or configuration changes; no new dependency. Slightly more spans per repetition (one per request turn).
- Docs: `docs/patterns/evaluation-tracing.md`.
- Test plan: `TurnLoopExecutorTest` (span per turn, parenting, ERROR on abort), `MetricEvaluationWorkerTest` (position attributes on both metrics spans), tracing functional suites (context boot with the new bean).
