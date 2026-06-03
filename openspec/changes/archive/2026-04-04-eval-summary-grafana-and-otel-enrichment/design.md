## Context

Eval summaries are the primary analytics view — each row combines a test case execution result with metric scores. Currently, `EvalSummaryResponseDto` and `EvalSummaryDetailResponseDto` have no Grafana link, forcing users to navigate to the underlying `TestCaseRunResult` to get a trace URL — and even then, that URL only shows the deployment call, not the metric evaluation calls.

Meanwhile, `metric.tsmd.evaluate` spans lack `testcase.id`, making it impossible to build a TraceQL query that aggregates the eval call and all metric calls for a single test case.

## Goals / Non-Goals

**Goals:**
- Enable one-click navigation from eval summary grid rows to a Grafana Explore view showing all traces for that test case (deployment call + metric calls)
- Make Grafana Tempo traces human-navigable by adding readable attributes (`testcase.name`, `metric.declaration.name`)
- Maintain backward compatibility (new fields are `@JsonInclude(NON_NULL)`)

**Non-Goals:**
- Changing the existing per-trace `grafanaTraceUrl` on `TestCaseRunResultResponseDto` (stays as-is)
- Adding Grafana links for individual metric evaluations (per-TSMD links)
- Storing `trace_id` on `eval_summaries` table (not needed — aggregate URL uses TraceQL query, not a single trace ID)
- Modifying DB schema

## Decisions

### Decision 1: Aggregate TraceQL URL instead of single-trace URL for eval summaries

**Choice**: Build Grafana URL using TraceQL `{.eval.run.id="<runId>" && .testcase.id="<testCaseId>"}` rather than linking to a single `traceId`.

**Rationale**: An eval summary represents the combined outcome of a deployment call plus N metric calls. These are separate traces (different spans, potentially different trace IDs). A TraceQL query aggregates all of them into one Grafana Explore view. This avoids needing `trace_id` on the eval summary entity (no DB change) and provides a richer view.

**Alternative considered**: Denormalize `trace_id` from `test_case_run_results` into `eval_summaries` and use `GrafanaLinkBuilder.traceUrl()`. Rejected because it would only show the deployment call trace, not the metric calls — missing the whole point.

### Decision 2: Time window uses `(createdAtMs - buffer) → (computedAtMs + buffer)`

**Choice**: Use `from = createdAtMs - TIME_BUFFER_MS` and `to = computedAtMs + TIME_BUFFER_MS` (where `TIME_BUFFER_MS` is the existing 5-minute constant in `GrafanaLinkBuilder`). When `computedAtMs` is null, `to` defaults to `now`. This matches the existing `runExploreUrl` pattern (`startedAt - TIME_BUFFER_MS` to `completedAt + TIME_BUFFER_MS`).

**Rationale**: The buffer on both sides accounts for clock skew between the application and the tracing backend. The `from` buffer ensures spans emitted slightly before `createdAtMs` (e.g., due to clock drift) are captured. The `to` buffer covers the same concern after metric computation completes. This is consistent with `runExploreUrl` which applies `TIME_BUFFER_MS` symmetrically.

### Decision 3: Add attributes to metric spans from already-available data

**Choice**: Enrich `metric.tsmd.evaluate` spans with `testcase.id`, `testcase.name`, `eval.suite.id` from the `TestCaseRunResult` passed to `MetricEvaluationWorker.evaluate()`, and `metric.declaration.name` from `AggregatedMetricDefinition.getMetricDeclarationName()`.

**Rationale**: All data is already available in the method signature — `TestCaseRunResult` has `testCaseId` and `testCaseName`, and `MetricEvaluationContext` has `testSuiteId`. No additional queries or data plumbing needed.

### Decision 4: New `GrafanaLinkBuilder.testCaseAggregateUrl()` method

**Choice**: Add a new method to the existing `GrafanaLinkBuilder` rather than a new class.

**Rationale**: Same class, same `GrafanaProperties`, same `buildExploreUrl()` helper. The new method differs only in query construction (TraceQL with two attributes vs. single trace ID).

## Risks / Trade-offs

- **[Metric spans must carry `testcase.id` for the aggregate URL to work]** → OTel attribute enrichment and Grafana URL are coupled. Both must ship together for the URL to return useful results. Implementation order: enrich spans first, then add Grafana URL.
- **[Time window may be too narrow for long-running metric evaluations]** → The 5-minute buffer after `computedAtMs` should be sufficient since `computedAtMs` is set after all metrics complete. If needed, the buffer is already a constant in `GrafanaLinkBuilder` and easy to adjust.
- **[TraceQL query returns empty when OTel was disabled during the run]** → Same as existing behavior for `runExploreUrl`. The link is present but results in no traces. Acceptable — the user knows OTel state.
