## 1. Configuration

- [x] 1.1 Create `GrafanaProperties` @ConfigurationProperties class at `com.epam.aidial.evaluation.configuration.properties.grafana.GrafanaProperties` with `prefix = "app.grafana"`, fields: `baseUrl` (String) and `tempoDatasourceUid` (String)
- [x] 1.2 Add `app.grafana.base-url: ""` and `app.grafana.tempo-datasource-uid: tempo` defaults to `application.yml`
- [x] 1.3 Add Grafana configuration section to `docs/configuration.md`

## 2. GrafanaLinkBuilder Service

- [x] 2.1 Create `GrafanaLinkBuilder` @Component at `com.epam.aidial.evaluation.service.domain.GrafanaLinkBuilder` with constructor injection of `GrafanaProperties`
- [x] 2.2 Implement `traceUrl(String traceId)` — returns Grafana Explore URL for a single trace, or `null` when `traceId` is null or `base-url` is blank
- [x] 2.3 Implement `runExploreUrl(UUID runId, Long startedAt, Long completedAt)` — returns Grafana Explore TraceQL URL for `{.eval.run.id="<runId>"}` with `startedAt - 5 min` / `completedAt + 5 min` (or `now` when `completedAt` is null), or `null` when `base-url` is blank or `startedAt` is null (PENDING runs have no traces yet)
- [x] 2.4 Write unit tests for `GrafanaLinkBuilder` covering: enabled/disabled, null traceId, in-progress run (null completedAt), pending run (null startedAt → null), custom datasource UID

## 3. Span Attributes

- [x] 3.1 Add `eval.run.id` and `eval.suite.id` span attributes to the `eval.testcase.execute` SpanBuilder in `EvaluationWorker.execute()` (after existing `testcase.id` and `run.index`)
- [x] 3.2 Add `eval.suite.id` span attribute to the `try-it-out.invoke` SpanBuilder in `TryItOutService.invokeAndBuildResponse()` — note: `invokeAndBuildResponse()` currently does not receive `testSuiteId`; thread `UUID testSuiteId` parameter from both callers (`tryWithTestCase`, `tryWithVariables`)
- [x] 3.3 Update `EvaluationWorkerTest` — verify new span attributes are set in the existing mock chain
- [x] 3.4 Update `TryItOutServiceTest` — verify `eval.suite.id` attribute is set

## 4. DTO Changes & OpenAPI Examples

- [x] 4.1 Add `@JsonInclude(NON_NULL) @Schema(description = "Grafana Explore URL for this trace (present only when Grafana integration is configured)", example = "http://grafana:3000/explore?...") grafanaTraceUrl` field to `ExecutionInfoResponseDto`
- [x] 4.2 Add `@JsonInclude(NON_NULL) @Schema(description = "Grafana Explore URL for this trace (present only when Grafana integration is configured)", example = "http://grafana:3000/explore?...") grafanaTraceUrl` field to `TryItOutResponseDto`
- [x] 4.3 Add `@JsonInclude(NON_NULL) @Schema(description = "Grafana Explore URL for all traces in this run (present only when Grafana integration is configured)", example = "http://grafana:3000/explore?...") grafanaExploreUrl` field to `TestSuiteRunResponseDto`
- [x] 4.4 Update `api-v1-analytics-test-case-results-get-response-200-full.json` — add `grafanaTraceUrl` to `executionInfo`
- [x] 4.5 Update `api-v1-test-suites-testSuiteId-try-it-out-POST-response-200-full.json` — add `grafanaTraceUrl`
- [x] 4.6 Update `api-v1-test-suites-testSuiteId-test-cases-testCaseId-try-it-out-POST-response-200-full.json` — add `grafanaTraceUrl`
- [x] ~~4.7~~ _Removed_ — POST `/runs` creates a PENDING run (`startedAt` is null), so `grafanaExploreUrl` is always `null` (omitted via `@JsonInclude(NON_NULL)`); no example update needed for this endpoint

## 5. Service Wiring

- [x] 5.1 Inject `GrafanaLinkBuilder` into `AnalyticsResultService` (or its mapper); populate `ExecutionInfoResponseDto.grafanaTraceUrl` by calling `grafanaLinkBuilder.traceUrl(traceId)` when mapping analytics results — must cover both `listByFilter()` and `getById()` code paths (both call `resultMapper::toDto`; prefer mapper-level wiring via `@AfterMapping` or manual post-map step to avoid partial coverage)
- [x] 5.2 Inject `GrafanaLinkBuilder` into `TryItOutService`; set `grafanaTraceUrl` in `TryItOutResponseDto.builder()` call in `invokeAndBuildResponse()`
- [x] 5.3 Inject `GrafanaLinkBuilder` into `TestSuiteRunService` (or its mapper); populate `TestSuiteRunResponseDto.grafanaExploreUrl` by calling `grafanaLinkBuilder.runExploreUrl(run.getId(), run.getStartedAt(), run.getCompletedAt())`

## 6. Tests

- [x] 6.1 Add functional test asserting `grafanaTraceUrl` is present on `GET /api/v1/test-suite-runs/{runId}/results` when `app.grafana.base-url` is configured in test context
- [x] 6.2 Add functional test asserting all Grafana URL fields are absent (`null`) when `app.grafana.base-url` is blank (default)
- [x] 6.3 Add functional test asserting `grafanaExploreUrl` is present on `GET /api/v1/test-suite-runs/{id}` when Grafana is configured
- [x] 6.4 Add functional test asserting `grafanaTraceUrl` is present on `POST /api/v1/test-suites/{id}/try-it-out` (or `POST /api/v1/test-suites/{id}/test-cases/{testCaseId}/try-it-out`) when `app.grafana.base-url` is configured
