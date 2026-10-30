# Observability and Logging

## Purpose
This spec defines logging and traceability behavior (correlation IDs, request logging, runtime log-level changes, OTel distributed tracing).

Status: **Implemented**

## Key Terms
- **Correlation ID**: request identifier propagated via `X-Correlation-Id` header and logging MDC.
- **Dynamic log level**: runtime log configuration updated from a JSON file.

## Requirements

### Requirement: Correlation ID on all HTTP requests
The service SHALL assign a correlation ID to each HTTP request and include it in logs and responses. With OpenTelemetry enabled, the incoming HTTP request SHALL have an active server span. The service SHALL use that span's trace ID as the correlation ID when no valid `X-Correlation-Id` is provided by the client. The custom correlation ID SHALL remain separate from native trace IDs and SHALL NOT determine span parenting.
Status: **Implemented**

#### Scenario: Client-provided correlation id
- **WHEN** request includes header `X-Correlation-Id` with a valid value (16–32 alphanumeric characters)
- **THEN** system SHALL use it for logging and echo it in the response

#### Scenario: Generated correlation id from OTel trace
- **WHEN** request does not include a valid `X-Correlation-Id`
- **AND** OpenTelemetry is enabled and a span is active on the request thread
- **THEN** system SHALL use the current OTel trace ID (32-char lowercase hex) as the correlation ID for logging and echo it in the response

#### Scenario: Fallback when OTel span is not active
- **WHEN** request does not include a valid `X-Correlation-Id`
- **AND** no OTel span is active (e.g., tracing disabled or request pre-empted)
- **THEN** system SHALL generate a random 16-char alphanumeric correlation ID as before

#### Scenario: Correlation continuity through DIAL dispatch
- **WHEN** a run-start request dispatches evaluation through a DIAL Core route
- **THEN** the outgoing route-trigger request SHALL carry the resolved `X-Correlation-Id`
- **AND** the trigger-thread logs SHALL retain that correlation ID
- **AND** when Core forwards that header, the internal execute request and its asynchronous run job SHALL retain the same correlation ID

#### Scenario: Correlation cleanup at request and task boundaries
- **WHEN** request processing returns its thread, including asynchronous HTTP handoff, or a run-scoped task completes
- **THEN** its correlation-ID scope SHALL be cleared or the thread's prior logging context restored
- **AND** unrelated work SHALL NOT inherit the completed request's correlation ID

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
The service SHALL propagate the active native OpenTelemetry context through route-trigger, run-job, and worker submission boundaries. Run orchestration SHALL continue the context active at dispatch, while test-case traces and metric parents SHALL follow the explicit hierarchy requirements below rather than accidental thread inheritance.
Status: **Implemented**

#### Scenario: Context propagated through @Async thread pool
- **WHEN** an evaluation run is submitted to its job executor with an active OTel context
- **THEN** the run job thread SHALL receive the same native OTel span context and baggage in both virtual and platform thread modes
- **AND** propagation SHALL NOT depend on the presence of a Micrometer Tracing bridge

#### Scenario: Context propagated through virtual thread executor
- **WHEN** a test-case task or metric evaluation task is submitted to the run's shared worker executor
- **THEN** the worker SHALL receive the native OTel context active at submission in both virtual and platform thread modes
- **AND** explicitly selected test-case parents SHALL take precedence over the orchestration span

#### Scenario: Route-trigger context propagation
- **WHEN** the run-start request launches the DIAL route-trigger task
- **THEN** its active native OTel context SHALL reach the trigger task and outbound W3C context headers

#### Scenario: Task scope restoration
- **WHEN** an asynchronous task completes or throws
- **THEN** its prior thread context SHALL be restored without leaking spans or baggage to unrelated tasks

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

### Requirement: OTel Baggage propagation of eval run, suite, test case ids, run index, phase and metric context
The service SHALL populate the OTel `Baggage` of the active OTel context on every run-scoped outbound execution path that already opens an OTel span carrying those attributes — the evaluation worker's DIAL Core call and the metric-evaluation worker's metric provider call — with a phase-specific set of entries:

- **Both paths** SHALL carry `eval.run.id`, `eval.suite.id`, `testcase.id` (all UUID strings), `run.index` (the zero-based run index) and `eval.phase` (the value `execution` on the evaluation-worker path, `metric-evaluation` on the metric-evaluation-worker path). The `eval.phase` member lets downstream telemetry distinguish a test-case execution call from a judge-model evaluation call, which otherwise carry identical run/suite/testcase context.
- **The metric-evaluation path** SHALL additionally carry `result.id` (the `TestCaseRunResult` UUID, so downstream telemetry can key back to the exact result row) and `metric.declaration.name` (so judge-model spend can be attributed to a specific metric). These two members SHALL NOT be present on the evaluation-worker path, because on that path the result is produced by the call itself and does not yet exist.

Because the default OTel propagator set includes the W3C baggage propagator, the existing tracing `RestClient` interceptor SHALL then serialize these entries into a `baggage` header on the outgoing HTTP request, allowing DIAL Core and downstream services to attribute each analytics/log event to the originating eval run, suite, test case, phase and (on the metric path) result and metric.
Status: **Implemented**

Baggage SHALL carry only these non-sensitive identifiers (UUIDs, a zero-based index, a low-cardinality phase token and a metric name). Tokens, credentials, PII, or free-form/content-bearing data (e.g. `testcase.name`) SHALL NOT be placed into baggage, since baggage is broadcast verbatim to every downstream service including the upstream model provider.

Baggage entries SHALL be scoped to the current execution: set when the per-execution OTel scope is opened and removed when it closes, so no baggage entry leaks onto a pooled or virtual thread after the execution completes.

This requirement is additive: it does not alter the existing span attributes or the W3C `traceparent` propagation, which remain unchanged.

#### Scenario: Baggage set on eval worker DIAL Core call when tracing active
- **WHEN** the evaluation worker executes a test case with OTel enabled and opens the `eval.testcase.execute` span scope
- **THEN** the current OTel Baggage SHALL contain `eval.run.id` = the run id, `eval.suite.id` = the suite id, `testcase.id` = the test case id, `run.index` = the run index and `eval.phase` = `execution`
- **AND** the outgoing DIAL Core request SHALL include a `baggage` header whose members include `eval.run.id=<runId>`, `eval.suite.id=<suiteId>`, `testcase.id=<testCaseId>`, `run.index=<runIndex>` and `eval.phase=execution`
- **AND** the header SHALL NOT include `result.id` or `metric.declaration.name`

#### Scenario: Baggage set on metric provider call when tracing active
- **WHEN** the metric-evaluation worker evaluates a metric with OTel enabled and opens the `metric.tsmd.evaluate` span scope
- **THEN** the current OTel Baggage SHALL contain `eval.run.id`, `eval.suite.id`, `testcase.id`, `run.index` (all resolved from the test case run result), `eval.phase` = `metric-evaluation`, `result.id` = the result id and `metric.declaration.name` = the metric declaration name
- **AND** the outgoing metric provider request SHALL include a `baggage` header carrying those members

#### Scenario: No baggage header when OTel disabled
- **WHEN** OTel is disabled (default configuration) and an eval or metric execution runs
- **THEN** the tracing interceptor's propagator SHALL be a no-op so that **no `baggage` header is injected** on the outgoing HTTP request, and no error is raised
- **AND** this holds even though setting the baggage entries in the OTel context is itself SDK-independent — suppression occurs at the injection layer, not at the baggage-put

#### Scenario: Baggage does not leak across the async boundary
- **WHEN** a test-case execution completes and its OTel scope closes on a pooled/virtual worker thread
- **THEN** the `eval.run.id`/`eval.suite.id`/`testcase.id`/`run.index`/`eval.phase` baggage entries (and, on the metric path, `result.id`/`metric.declaration.name`) SHALL no longer be present on that thread's OTel context for subsequent unrelated work

#### Scenario: Baggage carries only non-sensitive identifiers
- **WHEN** the `baggage` header is constructed for an outgoing run-scoped call
- **THEN** its members SHALL be limited to `eval.run.id`, `eval.suite.id`, `testcase.id`, `run.index`, `eval.phase` and — on the metric-evaluation path only — `result.id` and `metric.declaration.name`
- **AND** SHALL NOT include the caller's authorization token, api-key, `testcase.name`, or any test-case content

### Requirement: Run orchestration continues the incoming distributed trace
Each executing evaluation run SHALL create an orchestration span carrying `eval.run.id` and `eval.suite.id`. With a valid incoming span context, orchestration SHALL remain in that trace and SHALL be parented to the dispatch-time span. With no valid incoming context and tracing enabled, orchestration SHALL start a new trace.
Status: **Implemented**

#### Scenario: Core-routed run continuation
- **WHEN** the internal execute endpoint receives a valid W3C trace context from DIAL Core and dispatches a run
- **THEN** the orchestration span SHALL share the incoming trace ID and descend from the internal request span

#### Scenario: Direct run continuation
- **WHEN** a public run-start request dispatches evaluation directly with an active request span
- **THEN** orchestration SHALL remain in that request's trace

#### Scenario: Run without an incoming trace
- **WHEN** a run starts with tracing enabled but no valid incoming span
- **THEN** orchestration SHALL have a valid new trace with run/suite attributes

### Requirement: Independent linked test-case traces
Each executed `(testCaseId, runIndex)` within a run SHALL have one independent root span and trace. Its root SHALL have no parent and SHALL contain a span link to the run orchestration span when that context is valid. Root attributes SHALL include `eval.run.id`, `eval.suite.id`, `testcase.id`, `testcase.name`, and `run.index`.
Status: **Implemented**

#### Scenario: Independent repetitions
- **WHEN** multiple test cases or repetitions execute with tracing enabled
- **THEN** each `(testCaseId, runIndex)` SHALL have a different trace ID from orchestration and from every other pair
- **AND** each root SHALL link to the same run orchestration span, not be its child

#### Scenario: Existing execution subtree preserved
- **WHEN** a test case performs HTTP or MCP execution, including multiple requests, turns, or retries
- **THEN** its existing `eval.testcase.execute` span and downstream hierarchy SHALL remain beneath its independent test-case root
- **AND** every result row from that repetition SHALL retain that test-case trace ID

#### Scenario: Run-scoped trace discovery
- **WHEN** retained and exported traces are searched by `eval.run.id`
- **THEN** the orchestration trace and all matching independent test-case traces SHALL be discoverable

### Requirement: Metric spans are children of their test-case root
Each invoked metric span SHALL be directly parented to the root for its result's `(testCaseId, runIndex)` in the active run. Metrics SHALL share that test-case trace ID, not the orchestration trace ID. Existing metric attributes, phase-specific baggage, and provider/downstream parent-child hierarchies SHALL be preserved.
Status: **Implemented**

#### Scenario: Phase 2 follows Phase 1
- **WHEN** metrics execute after the deployment-execution phase has completed
- **THEN** their parent span ID SHALL equal the corresponding test-case root span ID
- **AND** their trace ID SHALL equal the execution result's trace ID

#### Scenario: Multiple requests and turns
- **WHEN** one repetition produces multiple request/turn result rows with metrics
- **THEN** all invoked metric spans for those rows SHALL be children of the same test-case root
- **AND** `result.id` and existing phase-specific attributes/baggage SHALL distinguish the rows and metrics

#### Scenario: No cross-run or repetition parenting
- **WHEN** the same test case appears in concurrent runs or multiple repetitions
- **THEN** each metric SHALL be parented only to its own active run and repetition's root

#### Scenario: Imported results have no live execution parent
- **WHEN** a metrics-only run evaluates imported results without their original runtime span contexts
- **THEN** it SHALL create a new metric-only root for each evaluated `(testCaseId, runIndex)`, linked to current orchestration
- **AND** it SHALL NOT fabricate a historical parent span ID or overwrite an imported execution trace ID to claim historical continuity

### Requirement: Tracing state is transient and run-owned
Span contexts needed for cross-phase parenting SHALL remain in run-scoped runtime memory only. Test-case roots SHALL cover execution and metric evaluation, and SHALL be ended and released when the run finishes, fails, or is cancelled. Closing tracing state SHALL be idempotent and SHALL prevent late tasks from retaining or creating new run-owned roots.
Status: **Implemented**

#### Scenario: Persistent contract unchanged
- **WHEN** results are stored, returned by REST, or exported/imported as CSV
- **THEN** existing execution `traceId` fields SHALL remain unchanged in shape
- **AND** no parent span ID, serialized OTel context, or additional tracing-storage field SHALL be required

#### Scenario: Terminal cleanup
- **WHEN** a run completes, fails, is cancelled, or encounters submission rejection after tracing resources are allocated
- **THEN** all owned root spans SHALL be ended exactly once and retained tracing state SHALL be released

#### Scenario: Late worker after shutdown
- **WHEN** a timed-out or interrupted worker races with tracing-session cleanup
- **THEN** it SHALL NOT recreate run-owned roots or leave tracing resources retained after session closure

#### Scenario: Tracing disabled
- **WHEN** OTel is disabled
- **THEN** evaluation, cancellation, and result persistence SHALL continue normally without exported spans, fabricated trace IDs, or tracing-state leaks

## Implementation Notes
- Run/case tracing ownership and Grafana navigation: [evaluation tracing](../../../docs/patterns/evaluation-tracing.md); `runner.job.EvaluationTraceSession`, `service.domain.job.TestSuiteEvaluationJob`, `runner.job.EvaluationWorker`, and `service.domain.job.MetricEvaluationWorker`.
- Native job context and custom correlation capture: `TestSuiteEvaluationJob.dispatch`, `utils.CorrelationIdContext`, and the DIAL App route trigger path.
- Docs: `docs/configuration.md` (Logging Configuration section)
- Request/response logging: `com.epam.aidial.evaluation.configuration.logging.RequestResponseLoggingFilter`
- Correlation ID: `com.epam.aidial.evaluation.configuration.logging.CorrelationIdInterceptor`
- Dynamic logger: `com.epam.aidial.evaluation.service.infrastructure.logger.*`
- OTel trace context utilities: `com.epam.aidial.evaluation.utils.TraceContextUtils`
- Error view with traceparent: `com.epam.aidial.evaluation.web.handler.ErrorView`
- AOP trace interceptor config: `com.epam.aidial.evaluation.configuration.logging.LogConfiguration`
- Tracing interceptor factory: `DialCoreClientConfiguration.tracingInterceptor(OpenTelemetry)` (public static, reused by metric provider config)
- Metric provider tracing: `MetricProviderRestClientConfiguration` — injects `OpenTelemetry`, attaches tracing interceptor via `buildRestClient()`
- OTel Baggage helper: `com.epam.aidial.evaluation.runner.util.EvalBaggage` — builds phase-specific baggage combined with each worker's span in one scoped OTel context.
- Shared OTel key constants: `com.epam.aidial.evaluation.runner.util.TracingConstants` — single source of truth for run/case/metric span names, attributes, and phase-specific baggage keys.
