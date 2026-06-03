## Why

When users view eval summaries (the primary analytics grid — test case results enriched with metric scores), there is no way to jump to Grafana traces. The `EvalSummaryResponseDto` and `EvalSummaryDetailResponseDto` lack any Grafana URL. Additionally, metric evaluation spans (`metric.tsmd.evaluate`) are missing key attributes (`testcase.id`, `testcase.name`, `eval.suite.id`, `metric.declaration.name`) that would enable per-test-case trace aggregation and human-readable navigation in Grafana Tempo.

## What Changes

- **Enrich OTel span attributes** on `metric.tsmd.evaluate` spans: add `testcase.id`, `testcase.name`, `eval.suite.id`, and `metric.declaration.name`. Add `testcase.name` to `eval.testcase.execute` spans.
- **New `GrafanaLinkBuilder` method**: `testCaseAggregateUrl(runId, testCaseId, fromMs, toMs)` that builds a TraceQL query `{.eval.run.id="<runId>" && .testcase.id="<testCaseId>"}` scoped to the time window `createdAtMs → computedAtMs + buffer`.
- **Add `grafanaTraceUrl` to eval summary DTOs**: `EvalSummaryResponseDto` and `EvalSummaryDetailResponseDto` get a `grafanaTraceUrl` field populated via the new aggregate URL builder. No DB schema change needed — `testSuiteRunId`, `testCaseId`, `createdAtMs`, and `computedAtMs` are already on the entity.

## Capabilities

### New Capabilities

_(none — this extends existing capabilities)_

### Modified Capabilities

- `grafana-deep-links`: Add per-test-case aggregate Grafana URL to eval summary responses (new `GrafanaLinkBuilder.testCaseAggregateUrl` method, new fields on `EvalSummaryResponseDto` / `EvalSummaryDetailResponseDto`).
- `observability-and-logging`: Enrich OTel span attributes — add `testcase.id`, `testcase.name`, `eval.suite.id`, `metric.declaration.name` to `metric.tsmd.evaluate` spans; add `testcase.name` to `eval.testcase.execute` spans.

## Impact

- **Code**: `GrafanaLinkBuilder` (new method), `MetricEvaluationWorker` (new span attributes), `EvaluationWorker` (add `testcase.name`), `EvalSummaryMapper` (new `@AfterMapping`), `EvalSummaryResponseDto` / `EvalSummaryDetailResponseDto` (new field).
- **API**: New `grafanaTraceUrl` field on eval summary list and detail responses (`@JsonInclude(NON_NULL)` — backward compatible, null when Grafana disabled).
- **No DB schema changes**: All data needed for the Grafana URL already exists on the `EvalSummary` entity.
- **No config changes**: Uses existing `app.grafana.*` properties.
- **Testing**: Update Grafana functional tests to verify eval summary responses include/exclude the URL. Unit tests for new `GrafanaLinkBuilder` method and enriched span attributes.
