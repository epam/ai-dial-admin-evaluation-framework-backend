# Evaluation tracing: short linked traces, no long-lived spans

The backend's `eval.run` span continues the context captured at job dispatch, so one trace covers
the whole run: FE call → EF → DIAL Core → EF → orchestration until the result. Test-case work is
**not** parented to it. Each repetition's execution and each result row's metrics are separate short
traces, connected to `eval.run` and to each other by span links.

```text
Trace A: start request --> DIAL Core --> internal execute request --> eval.run (whole run)
                                                                      ^  ^
                                               link (eval.link.type=run) |
Trace X: eval.testcase.execute (testCaseId, runIndex) -------------------+  |
           +-- deployment / MCP invocation spans          ^                 |
                                                          | link (execution)|
Trace Y: eval.testcase.metrics (one result row) ----------+-----------------+ (run)
           +-- metric.tsmd.evaluate (per dispatched TSMD)
                 +-- provider / downstream spans
```

## Why no common parent

Execution and metrics can only be siblings in one trace under a shared parent. Phase 2 starts after
all of Phase 1, so that parent (and its trace) would stretch from a case's execution to its metrics:
up to the whole run. Linked peer traces keep every trace as long as its own work.

## Boundaries

- **Execution trace:** one per `(testCaseId, runIndex)`. All requests and turns of the repetition
  share it. Its trace ID is the persisted result `traceId`.
- **Metrics trace:** one per result row whose metrics are dispatched. Phase 2 handles rows one at a
  time with their TSMDs in parallel, so the root is short. The executor makes it current for the row's
  TSMD tasks and ends it once they settle. Status is ERROR if any TSMD failed or timed out. No root when
  every TSMD was condition-skipped/errored or the row did not succeed.
- Discovery calls and Phase 3 run-level aggregates stay under `eval.run`.

## Runtime ownership

`runner.job.EvaluationTraceSession` is a DB-free, non-Spring `AutoCloseable`. It holds the orchestration
`SpanContext` and a map of execution `SpanContext`s keyed by `(testCaseId, runIndex)`: immutable
contexts, never open spans. The backend passes one session through `EvaluationContext` and
`MetricEvaluationContext`. The CLI's context factory links to the current context when valid; CLI runs
only Phase 1.

Workers set span attributes before calling `startExecutionSpan` / `startMetricsSpan`, so samplers see
them. The run owner closes the session in unconditional cleanup after executor shutdown. Close clears the
map; a later span still starts and links to the run. State is O(started repetitions).

Legacy library consumers may omit the session: spans then use implicit parenting from the current
context. Never add span IDs or serialized contexts to persisted run/result objects.

## Incoming tracing and custom correlation IDs

`TestSuiteEvaluationJob.dispatch` captures native `Context.current()` and explicitly wraps its submitted
job. `ContextPropagatingTaskDecorator` remains useful for Micrometer-registered contexts but is not, by
itself, a native OTel bridge. `RunExecutorFactory` wraps worker submissions with `Context.taskWrapping`.
The DIAL trigger thread is explicitly wrapped too, and its HTTP client injects W3C trace context.

`X-Correlation-Id` is independent of a span's trace ID. `CorrelationIdContext` captures/restores that
single MDC value around job/trigger tasks; the trigger explicitly forwards the header.
`CorrelationIdInterceptor` clears its request value at synchronous completion and async HTTP handoff.

Core must forward W3C context for distributed linkage and the custom header for correlation continuity.
EF cannot retroactively reparent an earlier request if Core drops context.

## Stored results and imports

The execution `traceId` field remains the only persisted tracing field. REST and CSV shapes are
unchanged. Imported results have no live execution span: their metrics roots link only to `eval.run`;
imported trace IDs are not rewritten.

## Grafana / Tempo navigation

- From any test-case trace, follow its span link to `eval.run` (`eval.link.type=run`); from a metrics
  trace, follow `eval.link.type=execution` to the execution trace.
- From a run, search `{ span.eval.run.id = "<run-id>" }` to list orchestration and every test-case trace.
- Narrow by `span.testcase.id`, `span.run.index`, or `span.result.id`.
- Do not assume reverse-link discovery or one stitched waterfall across traces.

Links do not force sampling or guarantee retention; each root is sampled under the deployment policy.

## Verification

`EvaluationTraceSessionTest` asserts root/link identity, case/repetition matching, isolation, close and
disabled tracing. Worker and executor tests assert exported parent IDs, links, baggage and metrics-span
scoping. Tracing-enabled `PostgresFunctionalTests` nested suites cover direct dispatch,
multi-request/multi-turn metrics, imports, a local HTTP Core proxy, disabled tracing, duplicate dispatch,
trigger rejection, and disconnect cancellation. A deployment smoke check must still verify real DIAL
Core propagation and the Grafana span-link UI.
