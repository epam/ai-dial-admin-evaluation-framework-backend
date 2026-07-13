## 1. Baggage helper

- [x] 1.1 Add an `EvalBaggage` helper in `com.epam.aidial.evaluation.utils` (consistent with `TraceContextUtils`) exposing `withRunContext(UUID runId, UUID suiteId)` that puts `eval.run.id` and `eval.suite.id` into `Baggage.current()` and returns the resulting `Scope` (AutoCloseable) for try-with-resources.
- [x] 1.2 Add constants for the key names `eval.run.id` and `eval.suite.id` to the relevant constants class (per bounded context, per AGENTS.md). NOTE: these keys are currently inline string literals (no existing constant to reuse). Reference the new constants from `EvalBaggage` AND from the span `setAttribute(...)` calls in the two workers being touched (`EvaluationWorker.java:95-96`, `MetricEvaluationWorker.java:62,66`) so attribute and baggage keys share one source of truth for those files. Out of scope: the same literals in `TryItOutService`/`GrafanaLinkBuilder` (not modified by this change).

## 2. Wire into run-scoped outbound paths

- [x] 2.1 In `EvaluationWorker#execute`, open `EvalBaggage.withRunContext(context.getRunId(), context.getSuiteId())` as a try-with-resources scope alongside the existing `span.makeCurrent()` scope (covers both non-streaming and streaming DIAL Core invocations and retries within that scope).
- [x] 2.2 In `MetricEvaluationWorker` (`metric.tsmd.evaluate` span), apply the same baggage scope so metric-provider calls carry the header.

## 3. Unit tests

- [x] 3.1 Unit-test `EvalBaggage`: within the returned scope, `Baggage.current()` contains both entries; after close, the entries are absent (no-leak). (Baggage is SDK-independent, so this needs no SDK.)
- [x] 3.2 Unit-test that `withRunContext` does not throw for null/edge inputs. Do NOT assert "no baggage entries when disabled" — baggage entries are created regardless of SDK state; the disabled-case suppression is a header-injection concern, verified in task 4.3, not here.

## 4. Interceptor slice tests (real OTel SDK + MockRestServiceServer)

- [x] 4.1 Following the `MockRestServiceServer` pattern in `DialCoreClientTest` (`bindTo(RestClient.builder())`), build a `RestClient` wired with the real `tracingInterceptor(openTelemetry)` using a real OTel SDK (W3C `tracecontext,baggage` propagator). With `eval.run.id`/`eval.suite.id` set in baggage, execute a request and assert the recorded outgoing request has a `baggage` header whose members include `eval.run.id=<runId>` and `eval.suite.id=<suiteId>`. This exercises the interceptor directly — the `@SpringBootTest` functional harness mocks the DIAL clients as `@MockitoBean` and bypasses the interceptor, so a boot-context test cannot observe the header.
- [x] 4.2 Assert the `baggage` header carries only the two UUID members — no `authorization`/`api-key` value and no test-case content.
- [x] 4.3 Disabled-case slice test: build the interceptor with the SDK-disabled `OpenTelemetry` (no-op propagator), set run/suite baggage in context, execute, and assert the recorded request has **no `baggage` header** injected and no error is raised (covers the spec's "No baggage header when OTel disabled" scenario).
- [x] 4.4 Metric-provider path: assert the metric-provider client's `tracingInterceptor` serializes the same `baggage` members (same slice approach against `MetricProviderRestClientConfiguration`'s interceptor), covering the spec's "Baggage set on metric provider call" scenario.

## 5. Docs & verification

- [x] 5.1 Update the `observability-and-logging` one-line summary in `openspec/specs/README.md` to add baggage propagation of run/suite id (the current summary lists traceparent propagation + span attributes but not baggage, so it is materially incomplete after this change).
- [x] 5.2 Operator guidance that DIAL Core analytics capture requires `analytics.collectHeaders=true` and `baggage` in `analytics.headersAllowlist` (no eval-side config change) is documented in `design.md` (Migration Plan) and the delta spec's Implementation notes. NOTE: a root `README.md` Observability-bullet note was intentionally omitted (reverted before commit) — the guidance lives in the change artifacts instead.
- [x] 5.3 Run `./gradlew spotlessApply checkstyleMain checkstyleTest` and the new tests (the `EvalBaggage` unit test and the interceptor slice tests from section 4); confirm the `baggage` header is observed on the enabled-case slice test and absent on the disabled-case slice test.
