## 1. Enrich OTel Span Attributes

- [x] 1.1 Add `testcase.name` attribute to `eval.testcase.execute` span in `EvaluationWorker.execute()`
- [x] 1.2 Unit test: verify `EvaluationWorker` span includes `testcase.name` attribute (extend existing `EvaluationWorkerTest`)
- [x] 1.3 Add `testcase.id`, `testcase.name`, `eval.suite.id`, and `metric.declaration.name` attributes to `metric.tsmd.evaluate` span in `MetricEvaluationWorker.evaluate()` (data available from `TestCaseRunResult` and `MetricEvaluationContext`)
- [x] 1.4 Unit test: verify `MetricEvaluationWorker` span includes all new attributes (mock `OpenTelemetry`, assert `setAttribute` calls)

## 2. GrafanaLinkBuilder — Aggregate URL Method

- [x] 2.1 Add `testCaseAggregateUrl(UUID runId, UUID testCaseId, Long createdAtMs, Long computedAtMs)` method to `GrafanaLinkBuilder` — applies TIME_BUFFER_MS internally, matching the runExploreUrl pattern; builds TraceQL `{.eval.run.id="<runId>" && .testcase.id="<testCaseId>"}` with time range `(createdAtMs - TIME_BUFFER_MS)` to `(computedAtMs + TIME_BUFFER_MS)` (or `now` when `computedAtMs` is null). Returns `null` when disabled or when `runId`, `testCaseId`, or `createdAtMs` is null.
- [x] 2.2 Unit test: verify `testCaseAggregateUrl` generates correct URL, returns null when disabled/null inputs (extend `GrafanaLinkBuilderTest`)

## 3. Eval Summary DTO and Mapper

- [x] 3.1 Add `grafanaTraceUrl` field (`@JsonInclude(NON_NULL)`) to `EvalSummaryResponseDto` and `EvalSummaryDetailResponseDto`
- [x] 3.2 Add `@AfterMapping` methods in `EvalSummaryMapper` to populate `grafanaTraceUrl` via `GrafanaLinkBuilder.testCaseAggregateUrl()` using entity's `testSuiteRunId`, `testCaseId`, `createdAtMs`, `computedAtMs`. Inject `GrafanaLinkBuilder` as `@Autowired protected` field (MapStruct abstract mapper pattern, matching `TestCaseRunResultMapper`). Two separate `@AfterMapping` methods are needed — one targeting `@MappingTarget EvalSummaryResponseDto` and one targeting `@MappingTarget EvalSummaryDetailResponseDto` — because these DTOs share no common supertype.
- [x] 3.3 Unit test: verify `EvalSummaryMapper` populates `grafanaTraceUrl` when Grafana enabled, leaves null when disabled (extend `EvalSummaryMapperTest`)

## 4. Functional Tests and OpenAPI

- [x] 4.1 Update Grafana-enabled functional tests to assert `grafanaTraceUrl` is present on eval summary list and detail responses
- [x] 4.2 Update Grafana-disabled functional tests to assert `grafanaTraceUrl` is absent on eval summary responses
- [x] 4.3 Add `@Schema` annotation with description and example on the new `grafanaTraceUrl` fields
- [x] 4.4 Update OpenAPI example JSON files for eval summary endpoints if they exist (`src/main/resources/openapi/examples/`)
