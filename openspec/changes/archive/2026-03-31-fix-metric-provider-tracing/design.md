## Context

The `MetricProviderRestClientConfiguration` builds `RestClient` instances for calling metric provider `/evaluate` and `/metrics` endpoints. Unlike all other outbound HTTP client configurations (`DialCoreClientConfiguration`, `DialCoreDeploymentInvokerConfiguration`, `DialFileClientConfiguration`), it does not attach a tracing interceptor. The `MetricEvaluationWorker` creates an OTel span (`metric.tsmd.evaluate`) and makes it current, but the trace context is never propagated to the metric provider service via the `traceparent` HTTP header.

The tracing interceptor factory method (`tracingInterceptor(OpenTelemetry)`) is a 3-line static method on `DialCoreClientConfiguration` with package-private visibility. Two other config classes in the same `client.dialcore` package already reference it.

## Goals / Non-Goals

**Goals:**
- Propagate W3C `traceparent`/`tracestate` headers on all outbound metric provider HTTP calls
- Make the existing tracing interceptor factory method accessible cross-package

**Non-Goals:**
- Propagating user auth tokens to metric providers (intentionally uses app identity)
- Refactoring the tracing interceptor into a separate utility class (the method is 3 lines; a new class would be premature abstraction)
- Adding OTel spans inside `MetricProviderClient` (spans already exist in `MetricEvaluationWorker`)

## Decisions

### Decision 1: Make `tracingInterceptor` public static

Change `DialCoreClientConfiguration.tracingInterceptor(OpenTelemetry)` from package-private to `public`. This allows `MetricProviderRestClientConfiguration` (in `client.metricprovider`) to reference it.

**Alternatives considered:**
- *Extract to a `TracingInterceptors` utility class* — rejected; creating a new class for a 3-line method is over-engineering. Per project convention: "Three similar lines of code is better than a premature abstraction."
- *Duplicate the lambda in `MetricProviderRestClientConfiguration`* — rejected; the method is already shared by 3 callers, adding a 4th copy introduces drift risk. Making it public is a one-word change.

### Decision 2: Inject `OpenTelemetry` into `MetricProviderRestClientConfiguration`

Pass `OpenTelemetry` as a parameter to the `@Bean` method (same pattern as `DialCoreClientConfiguration`, `DialCoreDeploymentInvokerConfiguration`, `DialFileClientConfiguration`). Thread it into `buildRestClient()`.

### Decision 3: No changes to `MetricProviderClient`

The client class itself stays unchanged — it uses the `RestClient` built by the configuration, and the interceptor is transparent to the calling code.

## Risks / Trade-offs

- [Risk] Metric provider service does not expect/handle `traceparent` → **Mitigation**: `traceparent` is a standard W3C header; services that don't support distributed tracing simply ignore it. No behavioral change for the provider.
- [Risk] Making `tracingInterceptor` public exposes an internal method → **Mitigation**: It's a pure utility factory with no state. Public visibility is appropriate for a cross-package shared concern in the `client` layer.
