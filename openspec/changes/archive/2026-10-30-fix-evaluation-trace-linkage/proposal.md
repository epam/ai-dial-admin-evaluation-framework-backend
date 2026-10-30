## Why

Evaluation dispatched through DIAL Core loses its initiating trace at the asynchronous job boundary, making run startup difficult to connect to execution. Simply repairing propagation would put every test case and metric in one large trace; instead, each test-case repetition needs a self-contained trace linked to run orchestration, with its metrics as children.

## What Changes

- Reliably propagate native OpenTelemetry context from the public start request through the route-trigger thread and from the internal execute request into the asynchronous run job, including direct dispatch.
- Add a run orchestration span that continues the incoming trace and carries run/suite identifiers.
- Create an independent test-case root trace for each `(testCaseId, runIndex)`, linked to the orchestration span. Preserve `eval.testcase.execute` and its existing downstream hierarchy beneath that root.
- Retain test-case tracing state only in run-scoped memory so Phase 2 `metric.tsmd.evaluate` spans become children of the corresponding test-case root, including every request/turn result.
- Preserve phase-specific baggage, outbound W3C propagation, and existing `traceId` storage/API/CSV contracts. Do not persist span IDs or tracing context.
- Preserve the custom request correlation ID across the DIAL trigger and run-job boundaries, separately from each span's native trace ID, with scoped cleanup.
- Add executable trace-shape and application-context regressions, plus operator guidance for Grafana span links and run-scoped TraceQL search.

All changes above are **Planned**. Existing HTTP propagation and per-worker spans are implemented; reliable job propagation and cross-phase parenting are not established by existing tests.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `observability-and-logging`: Native async trace propagation, run orchestration continuity, independent linked test-case traces, metric parenting, transient tracing lifecycle, and request correlation continuity.

## Impact

- Backend: `TestSuiteRunService`, `TestSuiteEvaluationJob`, `AsyncConfiguration`, `RunHandle`, metric evaluation contexts/workers, DIAL trigger client configuration, and correlation-ID handling.
- Shared runner: `EvaluationContext`, `EvaluationWorker`, `TracingConstants`, and a new DB-free run-scoped `EvaluationTraceSession` in `runner.job` to own test-case roots and contexts.
- CLI: adapt its evaluation-context construction and run-resource ownership to the shared tracing session, without changing CLI contracts.
- No REST/CSV field changes, database migrations, new deployment properties, authentication changes, or required production dependencies. Test-only OTel SDK/exporter support may be added.
- Non-goals: changing DIAL Core itself, historical/restarted trace reconstruction from stored `traceId`, changing sampling policy, or automatically stitching separate traces into a Grafana waterfall.
- Risks: lifecycle leaks, late metric tasks after timeout/cancellation, accidental cross-run/repetition parenting, and links to unsampled or expired traces. Address these with run-owned cleanup and exported-span assertions.
- Rollout: deploy as an observability fix under the existing OTel opt-in; validate one direct run and one Core-routed run in the target environment. Rollback requires no data migration.
- Verification: unit tests with an in-memory span exporter and tracing-enabled Testcontainers functional tests covering the actual HTTP/job/worker/metric path; formatting, architecture, and Checkstyle checks before completion.
