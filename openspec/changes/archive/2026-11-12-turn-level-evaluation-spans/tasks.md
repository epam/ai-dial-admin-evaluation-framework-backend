## 1. Execution turn spans

- [x] 1.1 Add `SPAN_EVAL_TESTCASE_TURN` and `request.index`/`request.total`/`request.name`/`turn.index`/`turn.total` keys to `TracingConstants` (done: compiles)
- [x] 1.2 Add `runner.job.TurnTracer` component and run each turn of `TurnLoopExecutor` through it, ERROR on an aborting turn (done: `TurnLoopExecutorTest` "Each turn runs in its own eval.testcase.turn span" passes)

## 2. Metrics span position

- [x] 2.1 Stamp row request/turn position on `eval.testcase.metrics` and `metric.tsmd.evaluate` in `MetricEvaluationWorker` (done: `MetricEvaluationWorkerTest` "metrics root and TSMD spans carry the result row's request and turn position" passes)

## 3. Verification and docs

- [x] 3.1 Run `TurnLoopExecutorTest`, `EvaluationWorkerTest`, `RequestChainExecutorTest`, `MetricEvaluationWorkerTest`, `InProcessMetricEvaluationExecutorTest` and the `EvaluationTracingTests` / `CoreRoutedTracingTests` functional suites (done: all pass, context boots with the new bean)
- [x] 3.2 Run Checkstyle main/test for root and `evaluation-runner-core` (done: no violations)
- [x] 3.3 Update `docs/patterns/evaluation-tracing.md` with turn spans and position attributes (done: diagram, boundaries and Grafana navigation updated)
- [x] 3.4 Sync delta into `openspec/specs/observability-and-logging/spec.md` at archive (done: `/opsx:archive` applies the ADDED requirements)
