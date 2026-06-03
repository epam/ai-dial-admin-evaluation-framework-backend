## Context

The service currently uses `micrometer-tracing-bridge-otel` — Spring Boot's native approach where Micrometer acts as the tracing API and bridges to OTel SDK only for trace export. This results in a partial OTel setup: traces export via `management.otlp.tracing.*`, but `OpenTelemetryAppender` in `log4j2.xml` silently drops logs (the SDK has no log exporter configured), and metrics only go to Prometheus (no OTLP push).

The sibling service `ai-dial-admin-deployment-manager-backend` uses `opentelemetry-spring-boot-starter` (OTel SDK first), giving a unified `otel.*` configuration namespace and full three-signal export (traces + logs + metrics) through a single collector endpoint. Aligning with this approach fixes the silent log export bug and simplifies DevOps configuration.

Existing Java infrastructure is already largely compatible: `CorrelationIdInterceptor` uses the OTel API directly (`Span.current()`), `LogConfiguration` + `CustomizableTraceInterceptorProperties` exist but are not yet wired, and `OtelAppenderInitializer` manually installs the Log4j appender (redundant with the starter).

## Goals / Non-Goals

**Goals:**
- Replace `micrometer-tracing-bridge-otel` with `opentelemetry-spring-boot-starter` as the sole OTel SDK owner
- Export all three signals (traces, logs, metrics) via OTLP to a single collector endpoint
- Keep Prometheus scrape working alongside OTLP metrics push
- Align env var naming with the sibling service (`OTEL_*` standard variables)
- Default to disabled (`OTEL_SDK_DISABLED=true`); opt-in per deployment environment
- Add `traceparent` to error responses and `trace_id`/`span_id` to log lines
- Wire the already-implemented AOP trace interceptor via `CUSTOMIZABLE_TRACE_INTERCEPTOR_ENABLED`

**Non-Goals:**
- Adding custom OTel spans beyond what the starter provides automatically (HTTP, JDBC auto-instrumentation is a free bonus, not a goal)
- Changing distributed tracing propagation format (W3C traceparent already in use)
- Modifying test infrastructure (no test code changes required)
- Changing the `X-Correlation-Id` mechanism or response header contract

## Decisions

### Decision 1: Starter owns the full OTel SDK (Approach B)

**Chosen:** `opentelemetry-spring-boot-starter` with the starter's Micrometer→OTel bridge (`opentelemetry-micrometer-1.5`) routing Micrometer metrics into the OTel SDK pipeline.

**Alternatives considered:**
- *Approach A (split namespaces)*: Keep `management.otlp.metrics.export.*` for metrics, `otel.*` for traces/logs. Rejected — two namespaces means DevOps must know which namespace controls which signal.
- *Approach C (`micrometer-registry-otlp`)*: Add a second Micrometer registry for OTLP metrics push alongside Prometheus. Rejected — requires a separate endpoint config for metrics, breaking the single-endpoint model.

**Rationale:** Single `OTEL_EXPORTER_OTLP_ENDPOINT` for everything. Same model as the sibling service. The Micrometer→OTel bridge has the same maturity level (alpha) as the `opentelemetry-log4j-appender-2.17` we already use.

### Decision 2: Remove `micrometer-tracing-bridge-otel` to prevent dual SDK creation

**Chosen:** Removing the bridge from the classpath deactivates Spring Boot's `management.otlp.tracing` autoconfiguration automatically — it only activates when the bridge is present. The starter becomes the sole creator of the `OpenTelemetry` bean, eliminating any conflict.

**Alternative:** Exclude Spring Boot's `OpenTelemetryAutoConfiguration` explicitly. Rejected — fragile and brittle across Spring Boot upgrades; removing the bridge is the clean cut.

### Decision 3: Remove `OtelAppenderInitializer`

**Chosen:** Delete `OtelAppenderInitializer`. The `opentelemetry-spring-boot-starter` installs the Log4j appender automatically via its own `ApplicationListener`.

**Rationale:** Keeping it would run `OpenTelemetryAppender.install()` twice — harmless but misleading.

### Decision 4: `TraceContextUtils` utility extracted from `CorrelationIdInterceptor`

**Chosen:** New `TraceContextUtils` class in the `.utils` package with three static methods (`getTraceId()`, `getSpanId()`, `formatTraceParent()`). `CorrelationIdInterceptor` delegates to it; `ErrorView` uses it for the new `traceparent` field.

**Rationale:** Prevents duplicating `Span.current().getSpanContext()` boilerplate across two classes. Mirrors the pattern in the sibling service.

### Decision 5: Log pattern uses OTel MDC keys (`trace_id`, `span_id` with underscores)

**Chosen:** `%X{trace_id}` and `%X{span_id}` (OTel SDK convention, underscores).

**Rationale:** With the starter, the OTel SDK populates MDC — not Micrometer. Micrometer used camelCase (`traceId`/`spanId`); OTel SDK uses underscore keys. Using the wrong keys would silently produce empty values.

## Risks / Trade-offs

- **Micrometer→OTel bridge is alpha** → The `opentelemetry-micrometer-1.5` module is alpha, same as `opentelemetry-log4j-appender-2.17` we already accept. Monitor for API changes on BOM upgrades.

- **OTel SDK version management shifts to BOM** → Previously `opentelemetry-sdk` and `opentelemetry-exporter-otlp` were pinned directly. Now they're managed by `opentelemetry-instrumentation-bom`. Run `./gradlew dependencies` after the change to confirm no version conflicts with remaining direct OTel deps (if any).

- **JDBC auto-tracing is a new behaviour** → The starter automatically instruments JDBC calls (Spring JDBC via `opentelemetry-spring-boot-starter`). This adds DB query spans to traces — useful signal, but operators may notice new span types in the collector.

- **`management.otlp.tracing.endpoint` env var no longer works** → Any deployment using `MANAGEMENT_OTLP_TRACING_ENDPOINT` must switch to `OTEL_EXPORTER_OTLP_ENDPOINT`. This is a migration step for existing deployments.

## Migration Plan

1. Update `build.gradle`: swap dependencies
2. Update `application.yml`: remove `management.otlp.tracing.*`, `management.tracing.sampling.*`; add `otel.*` block and `management.server.port: 9464`
3. Update `log4j2.xml`: add `trace_id`/`span_id` to pattern
4. Delete `OtelAppenderInitializer`
5. Add `TraceContextUtils` utility
6. Update `CorrelationIdInterceptor` to delegate to `TraceContextUtils`
7. Add `traceparent` field to `ErrorView`
8. Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew test`
9. Run `./gradlew dependencies` to verify no OTel version conflicts
10. Smoke test: start with `OTEL_SDK_DISABLED=false`, verify all three signals reach the collector

**Rollback:** Revert the dependency swap (re-add `micrometer-tracing-bridge-otel`, remove starter) and restore `management.otlp.tracing.endpoint`. No DB migrations, no data changes — fully reversible.

## Open Questions

None — all decisions resolved during design review.
