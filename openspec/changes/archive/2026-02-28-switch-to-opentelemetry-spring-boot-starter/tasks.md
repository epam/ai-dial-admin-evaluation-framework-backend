## 1. Dependencies

- [x] 1.1 In `build.gradle`: remove `io.micrometer:micrometer-tracing-bridge-otel`, `io.opentelemetry:opentelemetry-sdk`, `io.opentelemetry:opentelemetry-exporter-otlp`, and the pinned `opentelemetry-log4j-appender-2.17:2.25.0-alpha`
- [x] 1.2 In `build.gradle`: add `implementation platform('io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom:2.12.0')`, `implementation 'io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter'`, and `implementation 'io.opentelemetry.instrumentation:opentelemetry-log4j-appender-2.17'` (version from BOM, no explicit pin)
- [x] 1.3 Run `./gradlew dependencies` and verify no OTel SDK version conflicts between the new BOM and remaining direct deps; resolve any conflicts by letting the BOM manage versions; also confirm that `io.grpc:grpc-netty-shaded` appears in the resolved tree (it must be pulled transitively via the starter → OTLP exporter chain for `OTEL_EXPORTER_OTLP_PROTOCOL=grpc` to work — if absent, add `implementation 'io.opentelemetry:opentelemetry-exporter-otlp'` to ensure gRPC transport is available)

## 2. Configuration (`application.yml`)

- [x] 2.1 Remove `management.tracing.sampling.probability: 1.0` and `management.otlp.tracing.endpoint: ...` from the `management:` block
- [x] 2.2 Add `management.server.port: 9464` to the `management:` block
- [x] 2.3 Add the `otel:` block with `sdk.disabled`, `service.name`, `exporter.otlp.endpoint`, `exporter.otlp.protocol`, `logs.exporter`, `traces.exporter`, `metrics.exporter` — all mapped to standard `${OTEL_*}` env vars with `OTEL_SDK_DISABLED` defaulting to `true` and `OTEL_EXPORTER_OTLP_PROTOCOL` defaulting to `http/protobuf`
- [x] 2.4 Update `app.customizable-trace-interceptor` block: add `enabled: ${CUSTOMIZABLE_TRACE_INTERCEPTOR_ENABLED:false}` and populate the `messages` map with ENTER/EXIT/EXCEPTION templates; add `app.trace-log-advisor.expression` pointing to `@within(com.epam.aidial.evaluation.configuration.logging.LogExecution)`

## 3. Log4j2 Pattern

- [x] 3.1 In `log4j2.xml`: update the `Console` appender `PatternLayout` to include `trace_id=%X{trace_id} span_id=%X{span_id}` in the log line pattern (OTel SDK populates these MDC keys with underscore convention)

## 4. Java — Remove `OtelAppenderInitializer`

- [x] 4.1 Delete `OtelAppenderInitializer.java` from `com.epam.aidial.evaluation.configuration.logging` — the `opentelemetry-spring-boot-starter` installs the Log4j appender automatically

## 5. Java — Add `TraceContextUtils`

- [x] 5.1 Create `TraceContextUtils` in `com.epam.aidial.evaluation.utils` as a `@UtilityClass` (Lombok) with three static methods:
  - `getTraceId()` — returns 32-char hex OTel trace ID from `Span.current()`, or `null` if span context is invalid or equals the all-zeros no-op value
  - `getSpanId()` — returns 16-char hex span ID, or `null` if invalid
  - `formatTraceParent()` — returns W3C `traceparent` string `"00-{traceId}-{spanId}-{flags}"`, or `null` if span context is invalid; flags derived from `SpanContext.getTraceFlags().asByte()`

## 6. Java — Update `CorrelationIdInterceptor`

- [x] 6.1 In `CorrelationIdInterceptor.generateCorrelationId()`: replace the inline `Span.current().getSpanContext().getTraceId()` block and `NOT_VALID_CORRELATION_ID` constant with a call to `TraceContextUtils.getTraceId()`; return the result if non-null, otherwise fall through to `generateRandomCorrelationId()`
- [x] 6.2 Remove the `NOT_VALID_CORRELATION_ID` constant from `CorrelationIdInterceptor` (logic now in `TraceContextUtils`)

## 7. Java — Update `ErrorView`

- [x] 7.1 Add `@JsonInclude(JsonInclude.Include.NON_NULL) private String traceparent;` field to `ErrorView`
- [x] 7.2 Populate `this.traceparent = TraceContextUtils.formatTraceParent()` in the base (5-argument) constructor of `ErrorView` only — the 3 shorter constructors all delegate to it via `this(...)`, so a single assignment in the base covers all construction paths

## 8. Verification

- [x] 8.1 Run `./gradlew checkstyleMain checkstyleTest` — no violations
- [x] 8.2 Run `./gradlew test` — all tests pass
- [x] 8.3 Run `./gradlew clean build` — full build passes
- [ ] 8.4 Smoke test: start the application with `OTEL_SDK_DISABLED=false` and `OTEL_EXPORTER_OTLP_ENDPOINT` pointing at a local collector; verify traces, logs, and metrics arrive in all three signal pipelines
- [ ] 8.5 Verify Prometheus scrape still works: confirm `/actuator/prometheus` is accessible on port 9464 and returns metrics
