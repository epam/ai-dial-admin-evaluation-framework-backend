## ADDED Requirements

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

## Implementation Notes
- Interceptor: reuse `DialCoreClientConfiguration.tracingInterceptor(OpenTelemetry)` (made public)
- Configuration: `MetricProviderRestClientConfiguration` — inject `OpenTelemetry`, add interceptor to `buildRestClient()`
