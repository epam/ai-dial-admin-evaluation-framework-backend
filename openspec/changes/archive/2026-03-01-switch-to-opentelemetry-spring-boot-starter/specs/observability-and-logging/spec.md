## MODIFIED Requirements

### Requirement: OTel Log4J appender initialization
The `opentelemetry-spring-boot-starter` automatically installs the `OpenTelemetryAppender` with the Spring-managed `OpenTelemetry` bean at startup. No manual `ApplicationListener` is needed.

#### Scenario: Appender installed automatically by starter
- **WHEN** the Spring application context starts with `opentelemetry-spring-boot-starter` on the classpath
- **THEN** the starter SHALL call `OpenTelemetryAppender.install(openTelemetry)` automatically via its built-in `ApplicationListener`, without any custom initializer component

#### Scenario: Log export pipeline active when OTel enabled
- **WHEN** `OTEL_SDK_DISABLED=false` (OTel enabled)
- **THEN** log entries emitted by Log4j SHALL be forwarded to the OTel SDK log pipeline and exported via OTLP to the configured collector endpoint

## ADDED Requirements

### Requirement: All three OTel signals exported via OTLP
The service SHALL export traces, logs, and metrics via OTLP to a single configurable collector endpoint. Prometheus scrape remains available alongside OTLP metrics push.

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

#### Scenario: OTel disabled in default configuration
- **WHEN** `OTEL_SDK_DISABLED` is not set (or set to `true`)
- **THEN** the OTel SDK SHALL operate in no-op mode: no spans created, no logs exported via OTLP, no metrics pushed via OTLP
- **AND** the application SHALL start and function normally

#### Scenario: OTel enabled at deploy time
- **WHEN** `OTEL_SDK_DISABLED=false` and `OTEL_EXPORTER_OTLP_ENDPOINT` is set
- **THEN** all three signals SHALL be exported to the configured collector

### Requirement: OTLP transport protocol selection
The service SHALL support both gRPC and HTTP/protobuf OTLP transport protocols, selectable via environment variable.

#### Scenario: HTTP/protobuf protocol (default)
- **WHEN** `OTEL_EXPORTER_OTLP_PROTOCOL` is not set or set to `http/protobuf`
- **THEN** all OTLP exporters SHALL use HTTP/protobuf transport

#### Scenario: gRPC protocol
- **WHEN** `OTEL_EXPORTER_OTLP_PROTOCOL=grpc`
- **THEN** all OTLP exporters SHALL use gRPC transport

### Requirement: trace_id and span_id in every log line
Every log line emitted within an active OTel span SHALL include the trace ID and span ID as structured MDC fields visible in the console output.

#### Scenario: Log line within an active span
- **WHEN** a log statement executes while an OTel span is active
- **THEN** the console log line SHALL include `trace_id=<32-char-hex>` and `span_id=<16-char-hex>` from the OTel MDC context populated by the starter

#### Scenario: Log line outside any span
- **WHEN** a log statement executes with no active OTel span (e.g., application startup)
- **THEN** `trace_id` and `span_id` fields SHALL render as empty strings in the log line

### Requirement: traceparent in error responses
All API error responses SHALL include the W3C `traceparent` value of the current OTel span, enabling clients and support teams to correlate an error to a backend trace without log access.

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

#### Scenario: Prometheus available on management port
- **WHEN** the application is running
- **THEN** `/actuator/prometheus` SHALL be accessible on port 9464
- **AND** SHALL NOT be accessible on port 8080

#### Scenario: Application API unaffected
- **WHEN** the application is running
- **THEN** all `/api/v1/*` endpoints SHALL remain on port 8080

### Requirement: AOP method trace logging opt-in
The existing `CustomizableTraceInterceptor` AOP infrastructure SHALL be activatable via environment variable, enabling entry/exit/exception logging with timing for all `@LogExecution`-annotated components.

#### Scenario: AOP tracing disabled by default
- **WHEN** `CUSTOMIZABLE_TRACE_INTERCEPTOR_ENABLED` is not set or set to `false`
- **THEN** no method entry/exit log lines SHALL be emitted by the AOP interceptor

#### Scenario: AOP tracing enabled
- **WHEN** `CUSTOMIZABLE_TRACE_INTERCEPTOR_ENABLED=true`
- **THEN** all classes annotated with `@LogExecution` SHALL emit entry, exit, and exception log lines with method name, arguments, return value, and elapsed time
