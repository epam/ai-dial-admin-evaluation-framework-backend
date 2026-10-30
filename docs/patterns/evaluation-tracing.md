# Evaluation tracing: linked repetitions and cross-phase parenting

The backend's `eval.run` span continues the context captured at job dispatch. Each test-case repetition
has its own independent `eval.testcase` root trace, with a span link to `eval.run` (not a parent edge).

```text
Trace A: start request --> DIAL Core --> internal execute request --> eval.run
                                                                      ^
                                                                      | span link
Trace B: eval.testcase (testCaseId, runIndex) ---------------------------+
           +-- eval.testcase.execute
           |     +-- existing deployment/MCP invocation descendants
           +-- metric.tsmd.evaluate (result row / TSMD)
                 +-- existing provider/downstream descendants
```

## The boundary is a whole repetition

All requests and turns within `(testCaseId, runIndex)` share the same trace. They are sequential parts of
one request chain/conversation, not separate root traces. Result rows carry their request/turn identity;
metric spans carry `result.id` to identify the evaluated row. Repetitions of the same case have distinct
traces. Session ownership isolates the same case/index across concurrent runs.

`eval.testcase.execute` retains its Phase 1 duration. Its lifetime parent remains open until run cleanup,
so subsequent Phase 2 metrics are direct children of the case root, not children of an already-ended
execution span. Discovery calls and Phase 3 run-level aggregates stay under orchestration.

## Runtime ownership

`runner.job.EvaluationTraceSession` is a DB-free, non-Spring `AutoCloseable` object. It holds only an
orchestration span context and a map of root span handles keyed by case ID/repetition. The backend passes
one session through `EvaluationContext` and `MetricEvaluationContext`. The CLI's context factory creates
a session linked to the current context when one is valid; CLI roots cover its local Phase 1 only.

Workers prepare their span attributes before calling `session.startSpan(...)`, so samplers can inspect
them. The session explicitly sets the root as parent rather than relying on the job context inherited
by the worker executor. Execution and metric scopes still populate their existing phase-specific baggage.

The run owner shuts down its worker executor, then closes the session in unconditional cleanup. Root
creation and close share a lock; close releases the map and ends each root once. Late workers receive an
invalid/no-op span and cannot reopen a closed session. Existing in-flight child spans end in their own
`finally` blocks, even when a timeout lets them finish after root cleanup. No unbounded termination wait
is introduced. State is O(started test-case repetitions), not O(result rows/response bodies).

Legacy library consumers may omit the optional session and keep their existing implicit span-parenting
behavior. The backend and CLI always provide owned sessions. Never add span IDs or serialized contexts
to persisted run/result objects to implement same-process cross-phase parenting.

## Incoming tracing and custom correlation IDs

`TestSuiteEvaluationJob.dispatch` captures native `Context.current()` and explicitly wraps its submitted
job. `ContextPropagatingTaskDecorator` remains useful for Micrometer-registered contexts but is not, by
itself, a native OTel bridge. `RunExecutorFactory` wraps worker submissions with `Context.taskWrapping`.
The DIAL trigger thread is explicitly wrapped too, and its HTTP client injects W3C trace context.

`X-Correlation-Id` is independent of a span's trace ID. `CorrelationIdContext` captures/restores that
single MDC value around job/trigger tasks; the trigger explicitly forwards the header.
`CorrelationIdInterceptor` clears its request value at synchronous completion and async HTTP handoff.
The current span's native `trace_id`/`span_id` still identify the active case/metric trace in logs.

Core must continue/forward W3C context for distributed linkage and forward the custom header for its
correlation continuity. EF cannot retroactively reparent an earlier request if Core drops context.

## Stored results and imports

The existing execution `traceId` field remains the only persisted tracing field and identifies the
case trace. REST and CSV shapes are unchanged. Imported results lack the original runtime parent span
ID: a metrics-only run creates fresh linked case roots without fabricating a historical parent or
rewriting imported execution trace IDs. Consequently, a historical imported execution trace and its
new metric-computation trace are not claimed to be one continuous trace.

## Grafana / Tempo navigation

- From a case root, use its span link to open orchestration (UI availability depends on Grafana version).
- From a run, search `{ span.eval.run.id = "<run-id>" }` to find orchestration and all exported case traces.
- Narrow by `span.testcase.id` and `span.run.index` to find a particular case/repetition.
- Do not assume reverse-link discovery or one automatically stitched waterfall across traces.

Links do not force sampling or guarantee retention. Each independent root is sampled under the existing
deployment policy. A target can be unsampled, not yet exported, or expired. Case roots export at cleanup;
execution/metric children end earlier, so a complete root-based view is available after run completion.

## Verification

`EvaluationTraceSessionTest` asserts root/link identity, isolation, disabled tracing, and close/create
races. Worker tests assert actual exported parent IDs and cross-phase baggage. Tracing-enabled
`PostgresFunctionalTests` nested suites cover public direct dispatch, multi-request/multi-turn metrics,
imports, and a local HTTP Core proxy that forwards SSE and a test PRK. Separate routed tests cover
disabled tracing, duplicate dispatch, trigger rejection, and disconnect cancellation.

These tests prove EF-side wiring and emulated forwarding; a deployment smoke check must still verify
the real DIAL Core's propagation and the installed Grafana span-link UI.
