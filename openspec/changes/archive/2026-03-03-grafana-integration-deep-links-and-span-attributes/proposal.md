## Why

When an eval run produces unexpected results or errors, engineers must manually copy a `traceId` from the API response and paste it into Grafana Explore to see what the service called and what DIAL Core returned. There is no direct navigation from the eval result to the trace. Similarly, there is no way to see all traces for an entire run in one click because the `eval.run.id` is not a span attribute — it cannot be used as a Tempo search filter.

## What Changes

- **New `GRAFANA_BASE_URL` config property** (`app.grafana.base-url`, default empty/disabled): when set, the service generates Grafana Explore deep links alongside `traceId` fields in API responses.
- **`grafanaTraceUrl` field on `TestCaseRunResultResponseDto.executionInfo`**: when `traceId` is present and Grafana is configured, returns a ready-to-click Tempo deep link for that test case's trace.
- **`grafanaTraceUrl` field on `TryItOutResponseDto`**: same, for try-it-out invocations.
- **`grafanaExploreUrl` field on `TestSuiteRunResponseDto`**: a Tempo TraceQL deep link scoped to all test case traces for that run (requires `eval.run.id` span attribute — see below), pre-set to the run's time range.
- **`eval.run.id` and `eval.suite.id` span attributes** added to `eval.testcase.execute` in `EvaluationWorker`: enables Tempo search and TraceQL queries scoped to a specific run or suite without knowing individual trace IDs.
- **`eval.suite.id` span attribute** added to `try-it-out.invoke` in `TryItOutService`.

## Capabilities

### New Capabilities

- `grafana-deep-links`: Configurable Grafana Explore URL generation injected into eval run and test case result responses, enabling one-click navigation from API results to Grafana traces.

### Modified Capabilities

- `observability-and-logging`: OTel span attributes on `eval.testcase.execute` and `try-it-out.invoke` enriched with `eval.run.id` and `eval.suite.id` to enable run-scoped and suite-scoped trace search in Grafana Tempo.

## Impact

- **`build.gradle`**: no new dependencies (pure URL string construction + existing OTel API)
- **`application.yml`**: new `app.grafana.base-url` property (empty by default → feature disabled)
- **`docs/configuration.md`**: new Grafana configuration section
- **Java — new**: `GrafanaLinkBuilder` service in `service.domain` (constructs Explore URLs)
- **Java — modified**: `EvaluationWorker` (add span attributes), `TryItOutService` (add span attribute)
- **Java — modified**: `ExecutionInfoResponseDto` (add `grafanaTraceUrl` field), `TryItOutResponseDto` (add `grafanaTraceUrl` field), `TestSuiteRunResponseDto` (add `grafanaExploreUrl` field)
- **Java — modified**: `AnalyticsResultService` or mapper (inject `GrafanaLinkBuilder` to populate URL), `TryItOutService` (populate URL in response), `TestSuiteRunService` (populate URL in response)
- **API contract**: additive — new nullable fields on existing responses; no breaking changes
- **Tests**: unit tests for `GrafanaLinkBuilder`; functional tests verify field presence/absence based on config
