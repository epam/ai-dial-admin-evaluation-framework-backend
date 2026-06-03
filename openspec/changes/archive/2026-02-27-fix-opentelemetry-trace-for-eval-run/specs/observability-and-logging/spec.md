## MODIFIED Requirements

### Requirement: Correlation ID on all HTTP requests
The service SHALL assign a correlation ID to each HTTP request and include it in logs and responses. With Micrometer Tracing active, Spring MVC auto-creates an OTel span per incoming request, so `Span.current().getSpanContext().getTraceId()` SHALL return a valid 32-char hex OTel trace ID. The `CorrelationIdInterceptor` SHALL use this trace ID as the correlation ID when no valid `X-Correlation-Id` is provided by the client.

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

## ADDED Requirements

### Requirement: OTel context propagation through async evaluation execution
The service SHALL propagate the active OpenTelemetry context through both async execution boundaries so that spans created during test case execution are correctly parented.

#### Scenario: Context propagated through @Async thread pool
- **WHEN** `TestSuiteEvaluationJob.executeRunAsync()` is dispatched via `@Async("testSuiteRunExecutor")`
- **THEN** the OTel context active at dispatch time SHALL be propagated to the pool thread via `ContextPropagatingTaskDecorator`

#### Scenario: Context propagated through virtual thread executor
- **WHEN** `InProcessEvaluationExecutor` submits a test case task to its virtual thread executor
- **THEN** the OTel context active at submission time SHALL be propagated to the virtual thread via `Context.taskWrapping()`

### Requirement: OTel Log4J appender initialization
The existing `OpenTelemetryAppender` in `log4j2.xml` SHALL be initialized with the Spring-autoconfigured `OpenTelemetry` instance so that trace ID and span ID appear in log output alongside log messages.

#### Scenario: Appender wired at startup
- **WHEN** the Spring application context is fully started
- **THEN** `OpenTelemetryAppender.install(openTelemetry)` SHALL be called with the Spring-managed `OpenTelemetry` bean, enabling log entries to include the active trace ID and span ID

### Requirement: W3C traceparent propagation on outgoing DIAL Core calls
The service SHALL inject the W3C `traceparent` (and `tracestate` if present) header on all outgoing HTTP calls to DIAL Core, allowing DIAL Core to continue the distributed trace.

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
