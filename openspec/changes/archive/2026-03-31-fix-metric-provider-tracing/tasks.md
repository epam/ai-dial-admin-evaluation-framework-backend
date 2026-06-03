## 1. Make tracing interceptor accessible cross-package

- [x] 1.1 Change `DialCoreClientConfiguration.tracingInterceptor(OpenTelemetry)` visibility from package-private to `public` (done: method visibility changed from package-private to `public`, compiles, existing callers unaffected)

## 2. Add tracing interceptor to metric provider RestClient

- [x] 2.1 Inject `OpenTelemetry` into `MetricProviderRestClientConfiguration.metricProviderRestClientFactory()` bean method and pass it to `buildRestClient()` (done: `OpenTelemetry` parameter added, threaded through)
- [x] 2.2 Add `.requestInterceptor(DialCoreClientConfiguration.tracingInterceptor(openTelemetry))` to the `RestClient.builder()` in `buildRestClient()` (done: interceptor attached, compiles)

## 3. Verification

- [x] 3.1 Run `./gradlew checkstyleMain checkstyleTest` — passes with no new violations
- [x] 3.2 Run `./gradlew test` — all existing tests pass (no behavioral change for tests since OTel is no-op in test context)

## 4. Spec sync

- [x] 4.1 Sync delta spec to `openspec/specs/observability-and-logging/spec.md` — add the new "W3C traceparent propagation on outgoing metric provider calls" requirement (done: requirement and scenarios appended, status set to Implemented)
