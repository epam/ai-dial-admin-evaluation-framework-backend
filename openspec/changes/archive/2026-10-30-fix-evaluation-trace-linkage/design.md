## Context

See [proposal.md](proposal.md) for motivation and scope. The current route-trigger thread explicitly wraps native OTel context, and its HTTP client injects W3C context. The internal controller delegates dispatch without intentionally detaching tracing. The run-job executor, however, uses Micrometer's `ContextPropagatingTaskDecorator`; no native OTel accessor registration was found. An isolated cached-library probe lost the span through that decorator and preserved it through `Context.wrap`. This is evidence for a boundary defect, not proof of deployed Core header behavior or complete Spring-context behavior.

Workers already use `Context.taskWrapping`. `EvaluationWorker` implicitly parents `eval.testcase.execute` to its current span and ends it after Phase 1. Phase 2 dispatches metrics from the job context, not from the execution context. Result rows store only `traceId`. `RunHandle` owns executor shutdown, including timed-out worker cleanup; it does not wait indefinitely for termination.

This is a cross-module design: shared tracing ownership belongs in DB-free `runner.job`; the backend remains the owner of job orchestration and Phase 2. No new top-level package is required.

## Goals / Non-Goals

**Goals:** Explicit, testable trace parent/link choices; preservation of existing execution/metric subtrees; run-owned cleanup across normal and abnormal completion; compatibility with direct, Core-routed, imported-result, and CLI execution.

**Non-Goals:** Global tracing rewrites unrelated to evaluation; persisted span-context recovery; changing sampling/export settings; changing DIAL Core or Grafana configuration; redesigning cancellation or phase scheduling.

## Decisions

### 1. Capture native OTel context explicitly at the job boundary

Wrap the submitted job runnable with `Context.current().wrap(...)` in `TestSuiteEvaluationJob.dispatch`, before crossing threads. Keep existing Micrometer task decoration for other registered contexts; do not mistake it for native OTel propagation. The shared worker executor remains OTel-wrapped. Capture the request correlation ID separately and restore its MDC scope around job/trigger tasks, without replacing the active span's trace ID.

Alternative: register a global native OTel Micrometer accessor. Rejected here because it adds global registry behavior and affects unrelated task execution. Explicit wrapping at the known evaluation boundary is easier to verify and scope.

`CorrelationIdInterceptor` must clean up at synchronous completion and asynchronous handoff. The DIAL trigger must explicitly send the resolved correlation header. Keep this header independent from `traceparent`; the normal OTel instrumentation extracts incoming W3C context. Do not manually override server spans or invent backward propagation to a completed start request.

### 2. Add `eval.run` orchestration and `eval.testcase` lifetime roots

Create an `eval.run` span on the job thread as a child of the captured dispatch context. Set `eval.run.id` immediately and `eval.suite.id` once the run is resolved. Keep its scope current through orchestration. End it on every job exit, and set error/cancellation information consistent with the existing run outcome; do not rewrite terminal-status precedence.

Each repetition gets a new `eval.testcase` span using `setNoParent()` and `addLink(orchestrationSpanContext)` when valid. Existing `eval.testcase.execute` becomes its child and retains its current Phase 1 duration, attributes, baggage, exception handling, and downstream subtree. Metrics later become direct children of `eval.testcase`, not children of the already-ended execution span.

Alternative: use `eval.testcase.execute` as the lifetime root. Keeping it open through metrics would change the meaning of its execution duration; ending it after Phase 1 would place metric children outside its time range. A separate lifetime root preserves the existing execution span while giving Grafana a parent interval covering both phases.

```text
Trace A: request --> Core --> internal request --> eval.run
                                                   ^
                                                   | link
Trace B: eval.testcase -----------------------------+
           +-- eval.testcase.execute --> existing invocation subtree
           +-- metric.tsmd.evaluate --> existing provider subtree
           +-- metric.tsmd.evaluate --> existing provider subtree
```

### 3. Own transient roots in `EvaluationTraceSession`

Introduce a non-Spring, run-scoped `AutoCloseable` session in `runner.job`. It owns the orchestration link context and a thread-safe map keyed by `(testCaseId, runIndex)`; the session instance itself provides run isolation. Entries contain the root span and its context, never credentials or test-case content. Create roots lazily when execution starts; all request/turn rows of a repetition reuse that root.

Pass the same session through backend Phase 1 `EvaluationContext` and Phase 2 `MetricEvaluationContext`; update the latter's misleading serializable-only javadoc because it already contains an executor. The owner allocates the session once per executing run and closes it in unconditional terminal cleanup. CLI creates and closes its own session around shared-runner execution; its roots cover Phase 1 only because CLI does not run local Phase 2.

Root lifetime spans execution through run cleanup, including intervening phase scheduling. Retain O(number of started test-case repetitions) small tracing entries, not results/responses or persisted context. Avoid a static/global registry. Do not serialize the runtime session into snapshots, models, REST DTOs, or CSV.

Alternative: store span IDs alongside `traceId`. Rejected by the agreed storage contract and unnecessary for same-process Phase 2. Alternative: reconstruct a parent from only `traceId`. Invalid because parenting requires the actual parent span ID.

### 4. Select metric parents explicitly and handle metric-only runs honestly

Before constructing a metric span, resolve the active session root using the result's test-case ID and repetition index. Use that root as the explicit parent regardless of the context inherited from the job executor. Open the metric's existing baggage scope using current metric identifiers, not stale execution baggage. Existing provider HTTP propagation then emits the metric span's context.

For imported/metrics-only results, lazily create fresh `eval.testcase` roots linked to the current run. Imported historical execution `traceId` values remain untouched. Do not claim these roots are the historical execution parent. The same fallback supports a result that legitimately has no live execution root, without changing evaluation outcomes.

Metric discovery and Phase 3 aggregate work stay under orchestration: they are not an individual test-case metric invocation. Conditions that skip metrics do not introduce phantom metric spans. Existing `result.id` and metric attributes continue to distinguish row-level evaluations.

### 5. Make close and late access race-safe

Session close must atomically reject new root creation, end every existing root exactly once, and release its map. A lookup racing with close cannot insert after the cleanup snapshot. Late workers use no-op tracing access after close; they must not reopen roots, resurrect map entries, or turn tracing cleanup into a new evaluation failure. Existing worker spans end in their own `finally` blocks.

Shutdown the run executor before closing the session, but do not wait indefinitely: per-result timeouts can leave tasks alive until run shutdown. Root cleanup remains bounded and idempotent even if a late child span ends after its parent. On normal completion, all invoked metrics are finished before close. Rejection before job execution must release any resources allocated before submission; preferably allocate orchestration/session state inside the accepted job.

### 6. Preserve API, data, security, and transaction contracts

No database/schema changes, migrations, REST fields, CSV columns, new properties, or new production dependencies are required. Execution result `traceId` continues to identify its test-case trace, now distinct from orchestration. Imported trace IDs remain historical values.

Do not change transaction boundaries, snapshot ordering, `afterCommit` dispatch, SSE emitter-before-dispatch ordering, PRK lifetime, bearer/API-key selection, or cancel-on-disconnect behavior. Tracing scopes do not open database transactions. No secrets enter baggage, links, or span attributes; runtime session state stores identifiers and tracing handles only.

## Risks / Trade-offs

- **Linked traces sampled independently** -> retain current sampling policy; document that a link does not guarantee its target was exported or retained.
- **Grafana reverse navigation varies** -> document case-to-run span links and `{ span.eval.run.id = "<run-id>" }` search for run-to-all-cases discovery; do not promise automatic stitched waterfalls.
- **Root state grows with started repetitions** -> retain only small tracing handles for the run lifetime and release on every exit; avoid building a result-row index or copying datasets.
- **Root spans export late** -> roots cover the entire case lifecycle and end at cleanup; existing execution/metric children still end promptly. Document that full roots appear after completion.
- **Late metric workers after timeout** -> race-safe closure plus existing executor shutdown; test with deterministic latches rather than sleeps.
- **Tests pass without actual context propagation** -> assert exported trace IDs, parent span IDs, links, and attributes, not only that a decorator or wrapper exists.
- **External Core drops headers** -> functional tests emulate forwarding and prove the EF path; deployment validation checks actual Core headers separately.

## Migration Plan

Implement in task groups of at most five tasks, preserving the repository's iterative limit. First establish propagation and orchestration; then implement shared case-root ownership; then Phase 2 parenting; finally validate the routed end-to-end path and document the pattern. Execute each group's unit tests and booted functional tests before checking off tasks.

Enable tracing in test contexts with an in-memory exporter and deterministic span lookup by run/case identifiers. Avoid real OTLP network export. Verify direct and Core-routed requests, multiple cases/repetitions, multi-request/multi-turn metrics, metric-only imports, tracing disabled, and failure/cancellation cleanup.

Update the existing observability spec by delta merge only after implementation is verified. Document the new run-owned tracing pattern in `docs/patterns/`, link it from AGENTS.md, and add a concise context note in `openspec/config.yaml` because this introduces a cross-phase tracing ownership pattern. No configuration-reference or database-schema update is needed absent deployment/schema changes. Review the spec index; its summary should mention linked per-case traces if materially changed.

Deploy without data migration under existing OTel opt-in. Validate a Core-routed and direct run in Grafana using run attributes and a case-to-run link. Roll back the application version if necessary; existing stored execution trace IDs require no conversion.
