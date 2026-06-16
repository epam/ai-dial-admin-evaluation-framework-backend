## Why

The `MetricProviderRestClientConfiguration` builds its `RestClient` instances without a W3C `traceparent` injection interceptor. As a result, `MetricEvaluationWorker` creates a proper OTel span (`metric.tsmd.evaluate`) and makes it current, but the trace context never crosses the wire to the metric provider service. The metrics service starts a fresh trace on each `/evaluate` call, breaking the distributed call chain visible in Grafana Tempo.

All other outbound HTTP clients (`DialCoreRestClient`, `DialCoreDeploymentInvokerRestClient`, `DialFileRestClient`) already propagate trace context via a shared `tracingInterceptor`. The metric provider client was omitted — likely because auth token propagation was intentionally skipped (metrics service uses app identity), and the unrelated tracing interceptor was accidentally left out along with it.

## What Changes

- Add the OTel `tracingInterceptor` to `MetricProviderRestClientConfiguration.buildRestClient()`, injecting `traceparent`/`tracestate` headers on every outbound `/evaluate` and `/metrics` call.
- Make `DialCoreClientConfiguration.tracingInterceptor(OpenTelemetry)` public so it can be referenced from `MetricProviderRestClientConfiguration` in the `client.metricprovider` package (it is already used by two other config classes in the same `client.dialcore` package).

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `observability-and-logging`: Add requirement for W3C traceparent propagation on outgoing metric provider calls (analogous to existing DIAL Core propagation requirement).

## Impact

- **Code**: `MetricProviderRestClientConfiguration` (add interceptor + inject `OpenTelemetry`), `DialCoreClientConfiguration` (`tracingInterceptor` visibility changed to `public`).
- **APIs**: No API changes.
- **Dependencies**: No new dependencies — uses existing `io.opentelemetry.api.OpenTelemetry` already on classpath.
- **Systems**: Metric provider services will now receive `traceparent` headers, enabling end-to-end distributed tracing from EF through to metric evaluation.
