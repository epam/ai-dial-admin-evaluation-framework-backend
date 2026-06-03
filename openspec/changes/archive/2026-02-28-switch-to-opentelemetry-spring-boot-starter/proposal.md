## Why

The service uses `micrometer-tracing-bridge-otel` (Spring Boot's Micrometer-native path), which only exports traces via OTLP and silently drops logs from `OpenTelemetryAppender` because the SDK instance has no log exporter configured. The sibling service `ai-dial-admin-deployment-manager-backend` uses `opentelemetry-spring-boot-starter` (OTel SDK first), giving DevOps a single `OTEL_EXPORTER_OTLP_ENDPOINT` for all three signals and a standard `OTEL_SDK_DISABLED` toggle — aligning the two services simplifies configuration and fixes the silent log export bug.

## What Changes

- **Dependencies**: remove `micrometer-tracing-bridge-otel`, `opentelemetry-sdk`, `opentelemetry-exporter-otlp` (manually managed); add `opentelemetry-instrumentation-bom` + `opentelemetry-spring-boot-starter`; `opentelemetry-log4j-appender-2.17` version now managed by BOM
- **Metrics**: Prometheus scrape kept; OTLP metrics push added via the starter's Micrometer→OTel bridge
- **Logs**: `OpenTelemetryAppender` now actually exports logs (starter wires the log exporter on the SDK)
- **Configuration namespace**: `management.otlp.tracing.*` replaced by `otel.*`; standard `OTEL_*` env vars throughout
- **OTel disabled by default**: `OTEL_SDK_DISABLED=true`; opt-in per environment
- **Protocol**: gRPC and HTTP/protobuf supported via `OTEL_EXPORTER_OTLP_PROTOCOL`
- **Management port**: `management.server.port: 9464` added (port was already exposed in Dockerfile but management ran on 8080)
- **Log pattern**: `trace_id=%X{trace_id} span_id=%X{span_id}` added to `log4j2.xml` console pattern
- **`traceparent` in error responses**: `ErrorView` gains a nullable `traceparent` field (W3C format); absent when tracing is disabled
- **`TraceContextUtils`**: new utility class extracting `getTraceId()`, `getSpanId()`, `formatTraceParent()` from inline `CorrelationIdInterceptor` logic
- **`OtelAppenderInitializer` removed**: the starter's built-in `ApplicationListener` handles appender installation automatically
- **AOP trace interceptor wired**: `LogConfiguration` + `CustomizableTraceInterceptorProperties` already exist but were never enabled in YAML; `CUSTOMIZABLE_TRACE_INTERCEPTOR_ENABLED` env var added (default `false`)

## Capabilities

### New Capabilities

None — all changes are within existing observability capability.

### Modified Capabilities

- `observability-and-logging`: OTel SDK approach changes (starter vs Micrometer bridge); all three signals now export via OTLP; `traceparent` added to error response body; `trace_id`/`span_id` in log lines; new env vars (`OTEL_SDK_DISABLED`, `OTEL_EXPORTER_OTLP_PROTOCOL`, `OTEL_LOGS_EXPORTER`, `OTEL_METRICS_EXPORTER`, `OTEL_TRACES_EXPORTER`, `CUSTOMIZABLE_TRACE_INTERCEPTOR_ENABLED`); `management.server.port` separation

## Impact

- **Dependencies**: removes 3 direct OTel deps, adds starter BOM + starter artifact
- **`build.gradle`**: dependency block changes only; no version catalog impact
- **`application.yml`**: `management.otlp.tracing.*` and `management.tracing.sampling.*` removed; `otel.*` block added; `management.server.port: 9464` added; `app.customizable-trace-interceptor` block updated
- **`log4j2.xml`**: console `PatternLayout` updated
- **Java**: `OtelAppenderInitializer` deleted; `TraceContextUtils` added; `CorrelationIdInterceptor` refactored (no behaviour change); `ErrorView` gains `traceparent` field
- **API contract**: `ErrorView` JSON gains optional `traceparent` field on all error responses when tracing is active — non-breaking addition
- **DevOps**: env var set changes; existing `OTEL_EXPORTER_OTLP_ENDPOINT` continues to work; `MANAGEMENT_OTLP_TRACING_ENDPOINT` no longer effective
- **Tests**: unaffected — `OTEL_SDK_DISABLED=true` by default gives no-op SDK in test context
