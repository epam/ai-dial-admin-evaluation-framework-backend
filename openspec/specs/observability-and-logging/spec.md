# Observability and Logging

## Purpose
This spec defines logging and traceability behavior (correlation IDs, request logging, runtime log-level changes, OTel distributed tracing).

Status: **Implemented**

## Key Terms
- **Correlation ID**: request identifier propagated via `X-Correlation-Id` header and logging MDC.
- **Dynamic log level**: runtime log configuration updated from a JSON file.

## Requirements

### Requirement: Correlation ID on all HTTP requests
The service SHALL assign a correlation ID to each HTTP request and include it in logs and responses. With Micrometer Tracing active, Spring MVC auto-creates an OTel span per incoming request, so `Span.current().getSpanContext().getTraceId()` SHALL return a valid 32-char hex OTel trace ID. The `CorrelationIdInterceptor` SHALL use this trace ID as the correlation ID when no valid `X-Correlation-Id` is provided by the client.
Status: **Implemented**

#### Scenario: Client-provided correlation id
- **WHEN** request includes header `X-Correlation-Id` with a valid value (16–32 alphanumeric characters)
- **THEN** system SHALL use it for logging and echo it in the response

#### Scenario: Generated correlation id from OTel trace
- **WHEN** request does not include a valid `X-Correlation-Id`
- **AND** Micrometer Tracing is enabled (OTel span is active on the request thread)
- **THEN** system SHALL use the current OTel trace ID (32-char lowercase hex) as the correlation ID for logging and echo it in the response

#### Scenario: Fallback when OTel span is not active
- **WHEN** request does not include a valid `X-Correlation-Id`
- **AND** no OTel span is active (e.g., tracing disabled or request pre-empted)
- **THEN** system SHALL generate a random 16-char alphanumeric correlation ID as before

### Requirement: Optional request/response logging
The service SHALL support enabling/disabling request/response body logging via configuration.
Status: **Implemented**

#### Scenario: Request/response logging disabled by default
- **WHEN** `logging.request-response.enabled=false`
- **THEN** request/response body logging SHALL be disabled

#### Scenario: Request/response logging enabled in dev
- **WHEN** running with `dev` profile
- **THEN** request/response body logging SHALL be enabled

### Requirement: Dynamic log level configuration
The service SHALL support dynamic log level configuration refreshed from a JSON file.
Status: **Implemented**

#### Scenario: Periodic refresh
- **WHEN** `logger.configuration.interval` is set
- **THEN** service SHALL refresh log levels from the configured JSON file path on that interval

### Requirement: OTel context propagation through async evaluation execution
The service SHALL propagate the active OpenTelemetry context through both async execution boundaries so that spans created during test case execution are correctly parented.
Status: **Implemented**

#### Scenario: Context propagated through @Async thread pool
- **WHEN** `TestSuiteEvaluationJob.executeRunAsync()` is dispatched via `@Async("testSuiteRunExecutor")`
- **THEN** the OTel context active at dispatch time SHALL be propagated to the pool thread via `ContextPropagatingTaskDecorator`

#### Scenario: Context propagated through virtual thread executor
- **WHEN** `InProcessEvaluationExecutor` submits a test case task to its virtual thread executor
- **THEN** the OTel context active at submission time SHALL be propagated to the virtual thread via `Context.taskWrapping()`

### Requirement: OTel Log4J appender initialization
The `opentelemetry-spring-boot-starter` automatically installs the `OpenTelemetryAppender` with the Spring-managed `OpenTelemetry` bean at startup. No manual `ApplicationListener` is needed.
Status: **Implemented**

#### Scenario: Appender installed automatically by starter
- **WHEN** the Spring application context starts with `opentelemetry-spring-boot-starter` on the classpath
- **THEN** the starter SHALL call `OpenTelemetryAppender.install(openTelemetry)` automatically via its built-in `ApplicationListener`, without any custom initializer component

#### Scenario: Log export pipeline active when OTel enabled
- **WHEN** `OTEL_SDK_DISABLED=false` (OTel enabled)
- **THEN** log entries emitted by Log4j SHALL be forwarded to the OTel SDK log pipeline and exported via OTLP to the configured collector endpoint

### Requirement: W3C traceparent propagation on outgoing DIAL Core calls
The service SHALL inject the W3C `traceparent` (and `tracestate` if present) header on all outgoing HTTP calls to DIAL Core, allowing DIAL Core to continue the distributed trace.
Status: **Implemented**

#### Scenario: traceparent injected on eval worker DIAL Core call
- **WHEN** `EvaluationWorker` invokes a DIAL Core deployment for a test case
- **AND** an active OTel span exists for that test case execution
- **THEN** the outgoing HTTP request SHALL include `traceparent: 00-<traceId>-<spanId>-<flags>` derived from the current OTel context

#### Scenario: traceparent injected on try-it-out DIAL Core call
- **WHEN** `TryItOutService` invokes a DIAL Core deployment
- **AND** an active OTel span exists for the try-it-out invocation
- **THEN** the outgoing HTTP request SHALL include `traceparent` derived from the current OTel context

#### Scenario: traceparent and tracestate in header blacklist
- **WHEN** a test suite's request template includes `traceparent` or `tracestate` headers
- **THEN** those template-provided values SHALL be stripped by the header blacklist
- **AND** the correct `traceparent` SHALL be injected by the `RestClient` interceptor based on the active OTel span

### Requirement: W3C traceparent propagation on outgoing metric provider calls
The service SHALL inject the W3C `traceparent` (and `tracestate` if present) header on all outgoing HTTP calls to metric provider endpoints (`/evaluate`, `/metrics`), allowing the metric provider to continue the distributed trace started by the Evaluation Framework.
Status: **Implemented**

#### Scenario: traceparent injected on metric evaluation call
- **WHEN** `MetricEvaluationWorker` triggers a `/evaluate` call to a metric provider via `MetricProviderClient`
- **AND** an active OTel span exists (`metric.tsmd.evaluate` created by the worker)
- **THEN** the outgoing HTTP request SHALL include `traceparent: 00-<traceId>-<spanId>-<flags>` derived from the current OTel context

#### Scenario: traceparent injected on metrics discovery call
- **WHEN** `MetricProviderClient.getMetrics()` calls `GET /metrics` on a metric provider
- **AND** an active OTel span exists
- **THEN** the outgoing HTTP request SHALL include `traceparent` derived from the current OTel context

#### Scenario: No traceparent when OTel disabled
- **WHEN** `OTEL_SDK_DISABLED=true` (no-op OTel instance)
- **THEN** the tracing interceptor SHALL be a no-op — no `traceparent` header injected, no errors

#### Scenario: Metric provider ignores traceparent gracefully
- **WHEN** the metric provider service does not support W3C Trace Context
- **THEN** the `traceparent` header is harmlessly ignored by the provider per standard HTTP semantics (no error, no side effect)

### Requirement: All three OTel signals exported via OTLP
The service SHALL export traces, logs, and metrics via OTLP to a single configurable collector endpoint. Prometheus scrape remains available alongside OTLP metrics push.
Status: **Implemented**

#### Scenario: Traces exported via OTLP
- **WHEN** `OTEL_SDK_DISABLED=false` and an OTel span is active
- **THEN** completed spans SHALL be exported via OTLP to `OTEL_EXPORTER_OTLP_ENDPOINT`

#### Scenario: Logs exported via OTLP
- **WHEN** `OTEL_SDK_DISABLED=false`
- **THEN** log entries from `log4j2.xml` `OpenTelemetryAppender` SHALL be exported via OTLP to `OTEL_EXPORTER_OTLP_ENDPOINT`

#### Scenario: Metrics exported via OTLP
- **WHEN** `OTEL_SDK_DISABLED=false`
- **THEN** Micrometer metrics SHALL be bridged into the OTel SDK pipeline and exported via OTLP to `OTEL_EXPORTER_OTLP_ENDPOINT`

#### Scenario: Prometheus scrape unaffected
- **WHEN** `OTEL_SDK_DISABLED=true` or `false`
- **THEN** `/actuator/prometheus` SHALL remain available on the management port for Prometheus scrape regardless of OTel state

### Requirement: OTel disabled by default
The OTel SDK SHALL be disabled by default. DevOps opt-in per deployment environment by setting `OTEL_SDK_DISABLED=false`.
Status: **Implemented**

#### Scenario: OTel disabled in default configuration
- **WHEN** `OTEL_SDK_DISABLED` is not set (or set to `true`)
- **THEN** the OTel SDK SHALL operate in no-op mode: no spans created, no logs exported via OTLP, no metrics pushed via OTLP
- **AND** the application SHALL start and function normally

#### Scenario: OTel enabled at deploy time
- **WHEN** `OTEL_SDK_DISABLED=false` and `OTEL_EXPORTER_OTLP_ENDPOINT` is set
- **THEN** all three signals SHALL be exported to the configured collector

### Requirement: OTLP transport protocol selection
The service SHALL support both gRPC and HTTP/protobuf OTLP transport protocols, selectable via environment variable.
Status: **Implemented**

#### Scenario: HTTP/protobuf protocol (default)
- **WHEN** `OTEL_EXPORTER_OTLP_PROTOCOL` is not set or set to `http/protobuf`
- **THEN** all OTLP exporters SHALL use HTTP/protobuf transport

#### Scenario: gRPC protocol
- **WHEN** `OTEL_EXPORTER_OTLP_PROTOCOL=grpc`
- **THEN** all OTLP exporters SHALL use gRPC transport

### Requirement: trace_id and span_id in every log line
Every log line emitted within an active OTel span SHALL include the trace ID and span ID as structured MDC fields visible in the console output.
Status: **Implemented**

#### Scenario: Log line within an active span
- **WHEN** a log statement executes while an OTel span is active
- **THEN** the console log line SHALL include `trace_id=<32-char-hex>` and `span_id=<16-char-hex>` from the OTel MDC context populated by the starter

#### Scenario: Log line outside any span
- **WHEN** a log statement executes with no active OTel span (e.g., application startup)
- **THEN** `trace_id` and `span_id` fields SHALL render as empty strings in the log line

### Requirement: traceparent in error responses
All API error responses SHALL include the W3C `traceparent` value of the current OTel span, enabling clients and support teams to correlate an error to a backend trace without log access.
Status: **Implemented**

#### Scenario: Error response includes traceparent when tracing active
- **WHEN** the service returns any HTTP error response (4xx or 5xx)
- **AND** `OTEL_SDK_DISABLED=false` and an active span exists
- **THEN** the error JSON body SHALL include `"traceparent": "00-<traceId>-<spanId>-<flags>"`

#### Scenario: traceparent absent when tracing disabled
- **WHEN** the service returns any HTTP error response
- **AND** `OTEL_SDK_DISABLED=true` (no-op span)
- **THEN** the `traceparent` field SHALL be absent from the error JSON body (serialized as null, omitted via `@JsonInclude(NON_NULL)`)

### Requirement: Management endpoint on dedicated port
The Spring Actuator management endpoints SHALL be served on a dedicated port (9464), separate from the application port (8080).
Status: **Implemented**

#### Scenario: Prometheus available on management port
- **WHEN** the application is running
- **THEN** `/actuator/prometheus` SHALL be accessible on port 9464
- **AND** SHALL NOT be accessible on port 8080

#### Scenario: Application API unaffected
- **WHEN** the application is running
- **THEN** all `/api/v1/*` endpoints SHALL remain on port 8080

### Requirement: AOP method trace logging opt-in
The existing `CustomizableTraceInterceptor` AOP infrastructure SHALL be activatable via environment variable, enabling entry/exit/exception logging with timing for all `@LogExecution`-annotated components.
Status: **Implemented**

#### Scenario: AOP tracing disabled by default
- **WHEN** `CUSTOMIZABLE_TRACE_INTERCEPTOR_ENABLED` is not set or set to `false`
- **THEN** no method entry/exit log lines SHALL be emitted by the AOP interceptor

#### Scenario: AOP tracing enabled
- **WHEN** `CUSTOMIZABLE_TRACE_INTERCEPTOR_ENABLED=true`
- **THEN** all classes annotated with `@LogExecution` SHALL emit entry, exit, and exception log lines with method name, arguments, return value, and elapsed time

### Requirement: eval.run.id and eval.suite.id span attributes on eval.testcase.execute
The `eval.testcase.execute` span created by `EvaluationWorker` SHALL include `eval.run.id` (the test suite run UUID) and `eval.suite.id` (the test suite UUID) as span attributes. These attributes enable run-scoped and suite-scoped trace search in Grafana Tempo via TraceQL (e.g., `{.eval.run.id="<uuid>"}`).
Status: **Implemented**

#### Scenario: Span attributes set on test case execution
- **WHEN** `EvaluationWorker.execute()` creates the `eval.testcase.execute` span
- **THEN** the span SHALL include attribute `eval.run.id` set to the test suite run ID (UUID string)
- **AND** the span SHALL include attribute `eval.suite.id` set to the test suite ID (UUID string)
- **AND** existing attributes `testcase.id` and `run.index` SHALL remain unchanged

#### Scenario: Attributes enable TraceQL run-scoped search
- **WHEN** `OTEL_SDK_DISABLED=false` and a test suite run executes N test cases
- **THEN** a Grafana Tempo TraceQL query `{.eval.run.id="<run-uuid>"}` SHALL return all N test-case traces for that run

### Requirement: eval.suite.id span attribute on try-it-out.invoke
The `try-it-out.invoke` span created by `TryItOutService` SHALL include `eval.suite.id` (the test suite UUID) as a span attribute. This enables suite-scoped filtering of try-it-out traces in Grafana Tempo.
Status: **Implemented**

#### Scenario: Suite ID attribute set on try-it-out invocation
- **WHEN** `TryItOutService.invokeAndBuildResponse()` creates the `try-it-out.invoke` span
- **THEN** the span SHALL include attribute `eval.suite.id` set to the test suite ID (UUID string)

#### Scenario: No attribute when OTel disabled
- **WHEN** `OTEL_SDK_DISABLED=true` (no-op span)
- **THEN** span attribute operations are no-ops and no data is exported

### Requirement: testcase.name span attribute on eval.testcase.execute
The `eval.testcase.execute` span created by `EvaluationWorker` SHALL include `testcase.name` (the human-readable test case name) as a span attribute for improved readability in Grafana Tempo trace views.

Status: **Implemented**

#### Scenario: testcase.name attribute set on test case execution
- **WHEN** `EvaluationWorker.execute()` creates the `eval.testcase.execute` span
- **THEN** the span SHALL include attribute `testcase.name` set to the test case name string
- **AND** existing attributes (`testcase.id`, `run.index`, `eval.run.id`, `eval.suite.id`) SHALL remain unchanged

### Requirement: testcase.id and testcase.name span attributes on metric.tsmd.evaluate
The `metric.tsmd.evaluate` span created by `MetricEvaluationWorker` SHALL include `testcase.id` (the test case UUID) and `testcase.name` (the human-readable test case name) as span attributes. These attributes enable per-test-case trace aggregation in Grafana Tempo via TraceQL (e.g., `{.eval.run.id="<uuid>" && .testcase.id="<uuid>"}`).

Status: **Implemented**

#### Scenario: testcase.id and testcase.name attributes set on metric evaluation
- **WHEN** `MetricEvaluationWorker.evaluate()` creates the `metric.tsmd.evaluate` span
- **THEN** the span SHALL include attribute `testcase.id` set to `result.getTestCaseId()` (UUID string)
- **AND** the span SHALL include attribute `testcase.name` set to `result.getTestCaseName()` (string)
- **AND** existing attributes (`tsmd.name`, `tsmd.provider.id`, `eval.run.id`, `result.id`) SHALL remain unchanged

#### Scenario: Attributes enable per-test-case TraceQL aggregation
- **WHEN** `OTEL_SDK_DISABLED=false` and a test suite run executes N test cases with M TSMDs each
- **THEN** a Grafana Tempo TraceQL query `{.eval.run.id="<run-uuid>" && .testcase.id="<tc-uuid>"}` SHALL return the eval span and all M metric spans for that test case

### Requirement: eval.suite.id span attribute on metric.tsmd.evaluate
The `metric.tsmd.evaluate` span SHALL include `eval.suite.id` (the test suite UUID) as a span attribute, enabling suite-scoped trace filtering for metric evaluations.

Status: **Implemented**

#### Scenario: eval.suite.id attribute set on metric evaluation
- **WHEN** `MetricEvaluationWorker.evaluate()` creates the `metric.tsmd.evaluate` span
- **THEN** the span SHALL include attribute `eval.suite.id` set to the test suite ID from `MetricEvaluationContext.getTestSuiteId()` (UUID string)

### Requirement: metric.declaration.name span attribute on metric.tsmd.evaluate
The `metric.tsmd.evaluate` span SHALL include `metric.declaration.name` (the metric declaration name from the provider) as a span attribute for human-readable identification of which metric is being evaluated.

Status: **Implemented**

#### Scenario: metric.declaration.name attribute set on metric evaluation
- **WHEN** `MetricEvaluationWorker.evaluate()` creates the `metric.tsmd.evaluate` span
- **THEN** the span SHALL include attribute `metric.declaration.name` set to `tsmd.getMetricDeclarationName()` (string)

#### Scenario: Attribute aids Grafana trace navigation
- **WHEN** a user views traces in Grafana Tempo for a specific run
- **THEN** `metric.declaration.name` SHALL be visible as a span attribute, allowing the user to identify which metric provider metric was evaluated without needing to cross-reference TSMD names

### Requirement: OTel Baggage propagation of eval run, suite, test case ids and run index
The service SHALL populate the OTel `Baggage` of the active OTel context with the entries `eval.run.id`, `eval.suite.id`, `testcase.id` (all UUID strings) and `run.index` (the zero-based run index) on every run-scoped outbound execution path that already opens an OTel span carrying those attributes — the evaluation worker's DIAL Core call and the metric-evaluation worker's metric provider call. Because the default OTel propagator set includes the W3C baggage propagator, the existing tracing `RestClient` interceptor SHALL then serialize these entries into a `baggage` header on the outgoing HTTP request, allowing DIAL Core and downstream services to attribute each analytics/log event to the originating eval run, suite and test case.
Status: **Implemented**

Baggage SHALL carry only these non-sensitive identifiers. Tokens, credentials, PII, or free-form data SHALL NOT be placed into baggage, since baggage is broadcast verbatim to every downstream service including the upstream model provider.

Baggage entries SHALL be scoped to the current execution: set when the per-execution OTel scope is opened and removed when it closes, so no baggage entry leaks onto a pooled or virtual thread after the execution completes.

This requirement is additive: it does not alter the existing span attributes or the W3C `traceparent` propagation, which remain unchanged.

#### Scenario: Baggage set on eval worker DIAL Core call when tracing active
- **WHEN** the evaluation worker executes a test case with OTel enabled and opens the `eval.testcase.execute` span scope
- **THEN** the current OTel Baggage SHALL contain `eval.run.id` = the run id, `eval.suite.id` = the suite id, `testcase.id` = the test case id and `run.index` = the run index
- **AND** the outgoing DIAL Core request SHALL include a `baggage` header whose members include `eval.run.id=<runId>`, `eval.suite.id=<suiteId>`, `testcase.id=<testCaseId>` and `run.index=<runIndex>`

#### Scenario: Baggage set on metric provider call when tracing active
- **WHEN** the metric-evaluation worker evaluates a metric with OTel enabled and opens the `metric.tsmd.evaluate` span scope
- **THEN** the current OTel Baggage SHALL contain `eval.run.id`, `eval.suite.id`, `testcase.id` and `run.index` (the latter two resolved from the test case run result)
- **AND** the outgoing metric provider request SHALL include a `baggage` header carrying those members

#### Scenario: No baggage header when OTel disabled
- **WHEN** OTel is disabled (default configuration) and an eval or metric execution runs
- **THEN** the tracing interceptor's propagator SHALL be a no-op so that **no `baggage` header is injected** on the outgoing HTTP request, and no error is raised
- **AND** this holds even though setting the baggage entries in the OTel context is itself SDK-independent — suppression occurs at the injection layer, not at the baggage-put

#### Scenario: Baggage does not leak across the async boundary
- **WHEN** a test-case execution completes and its OTel scope closes on a pooled/virtual worker thread
- **THEN** the `eval.run.id`/`eval.suite.id`/`testcase.id`/`run.index` baggage entries SHALL no longer be present on that thread's OTel context for subsequent unrelated work

#### Scenario: Baggage carries only non-sensitive identifiers
- **WHEN** the `baggage` header is constructed for an outgoing run-scoped call
- **THEN** its members SHALL be limited to `eval.run.id`, `eval.suite.id`, `testcase.id` and `run.index`
- **AND** SHALL NOT include the caller's authorization token, api-key, or any test-case content

## Implementation Notes
- Docs: `docs/configuration.md` (Logging Configuration section)
- Request/response logging: `com.epam.aidial.evaluation.configuration.logging.RequestResponseLoggingFilter`
- Correlation ID: `com.epam.aidial.evaluation.configuration.logging.CorrelationIdInterceptor`
- Dynamic logger: `com.epam.aidial.evaluation.service.infrastructure.logger.*`
- OTel trace context utilities: `com.epam.aidial.evaluation.utils.TraceContextUtils`
- Error view with traceparent: `com.epam.aidial.evaluation.web.handler.ErrorView`
- AOP trace interceptor config: `com.epam.aidial.evaluation.configuration.logging.LogConfiguration`
- Tracing interceptor factory: `DialCoreClientConfiguration.tracingInterceptor(OpenTelemetry)` (public static, reused by metric provider config)
- Metric provider tracing: `MetricProviderRestClientConfiguration` — injects `OpenTelemetry`, attaches tracing interceptor via `buildRestClient()`
- OTel Baggage helper: `com.epam.aidial.evaluation.utils.EvalBaggage#withRunContext(UUID, UUID, UUID, Integer)` — sets `eval.run.id`/`eval.suite.id`/`testcase.id`/`run.index` baggage within a try-with-resources `Scope`; opened alongside `span.makeCurrent()` in `EvaluationWorker#execute` and `MetricEvaluationWorker#evaluate`
- Shared OTel key constants: `com.epam.aidial.evaluation.constants.TracingConstants` (`EVAL_RUN_ID`, `EVAL_SUITE_ID`, `TESTCASE_ID`, `RUN_INDEX`, `TESTCASE_NAME`, `RESULT_ID`, `TSMD_NAME`, `TSMD_PROVIDER_ID`, `METRIC_DECLARATION_NAME`) — single source of truth for span attributes and baggage keys
