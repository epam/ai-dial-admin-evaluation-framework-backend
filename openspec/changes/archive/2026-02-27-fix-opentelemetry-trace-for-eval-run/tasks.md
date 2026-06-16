## 1. Dependencies and Configuration

- [x] 1.1 Add `io.micrometer:micrometer-tracing-bridge-otel` to `build.gradle` (no version — managed by Spring Boot BOM); verify no OTel SDK version conflict with existing `opentelemetry-log4j-appender-2.17` using `./gradlew dependencies`
- [x] 1.2 Add Micrometer tracing and OTLP export config to `application.yml`: `management.tracing.sampling.probability=1.0` and `management.otlp.tracing.endpoint=${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318/v1/traces}`
- [x] 1.3 Update `test-suite-run.execution.header-blacklist` in `application.yml`: remove `X-Correlation-Id`, add `traceparent` and `tracestate`

## 2. OTel Context Propagation Through Async Boundaries

- [x] 2.1 In `AsyncConfiguration.java`: add `executor.setTaskDecorator(new ContextPropagatingTaskDecorator())` to the `testSuiteRunExecutor` bean so OTel context propagates through `@Async` dispatch
- [x] 2.2 In `InProcessEvaluationExecutor.java`: replace `Executors.newVirtualThreadPerTaskExecutor()` with `Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor())` so OTel context propagates to virtual threads

## 3. traceparent Injection on Outgoing DIAL Core Calls

- [x] 3.1 In `DialCoreClientConfiguration.java`: add a `static ClientHttpRequestInterceptor tracingInterceptor(OpenTelemetry openTelemetry)` factory method that calls `openTelemetry.getPropagators().getTextMapPropagator().inject(Context.current(), request, (r, key, value) -> r.getHeaders().set(key, value))`
- [x] 3.2 In `DialCoreClientConfiguration.java`: inject `OpenTelemetry openTelemetry` parameter into `dialCoreRestClient()` bean method and add `.requestInterceptor(tracingInterceptor(openTelemetry))` to the `RestClient.builder()`
- [x] 3.3 In `DialCoreDeploymentInvokerConfiguration.java`: inject `OpenTelemetry openTelemetry` into `dialCoreTryOutRestClient()` bean method and add `.requestInterceptor(DialCoreClientConfiguration.tracingInterceptor(openTelemetry))` to the `RestClient.builder()`

## 4. OTel Log4J Appender Initialization

- [x] 4.1 In `log4j2.xml`: add `<OpenTelemetry name="OpenTelemetryAppender"/>` to the `<Appenders>` section; add `<AppenderRef ref="OpenTelemetryAppender"/>` to the `Root` logger and the `com.epam.aidial` logger — without this XML declaration `OpenTelemetryAppender.install()` is a no-op
- [x] 4.2 Create `OtelAppenderInitializer` `@Component` in `com.epam.aidial.evaluation.configuration.logging` that injects `OpenTelemetry openTelemetry` and implements `ApplicationListener<ApplicationReadyEvent>`, calling `OpenTelemetryAppender.install(openTelemetry)` on event

## 5. EvaluationWorker — OTel Span per Test Case

- [x] 5.1 In `EvaluationWorker.java`: inject `io.micrometer.tracing.Tracer tracer` via constructor
- [x] 5.2 In `EvaluationWorker.execute()`: replace `UUID traceId = UUID.randomUUID()` with span creation: `var span = tracer.nextSpan().name("eval.testcase.execute").tag("testcase.id", testCase.getId().toString()).tag("run.index", String.valueOf(runIndex)).start()`; open `try (var scope = tracer.withSpan(span))` around the execution; end span with error flag in `finally`
- [x] 5.3 In `EvaluationWorker.execute()`: extract `String traceId = span.context().traceId()` from the span (32-char hex); use it wherever `traceId.toString()` was used previously
- [x] 5.4 In `EvaluationWorker.buildHeaders()`: remove the `headers.set("X-Correlation-Id", traceId.toString())` line; remove the now-unused `UUID traceId` parameter from the `buildHeaders()` signature; update the call site in `execute()` to `buildHeaders(resolved.getHeaders(), context)` — `traceparent` is injected by the `RestClient` interceptor
- [x] 5.5 Change `UUID traceId` → `String traceId` in ALL four method signatures that carry it: `invokeWithRetries()`, `invokeSingle()`, and both `buildResult()` overloads (lines 351 and 363); in `buildResult()` change `.traceId(traceId.toString())` → `.traceId(traceId)`
- [x] 5.6 Ensure correct span lifecycle: place `span.end()` ONLY in `finally` (never in catch); call `span.error(e)` in the outer catch block (request resolution failure) before returning the error result — do NOT call `span.end()` in any catch block (double-end)

## 6. Try-it-out — Span and traceId in Response

- [x] 6.1 In `TryItOutResponseDto.java`: add `@JsonInclude(JsonInclude.Include.NON_NULL) private String traceId;` field (and update builder/constructor via Lombok `@Builder` / `@AllArgsConstructor`)
- [x] 6.2 In `TryItOutService.java`: inject `io.micrometer.tracing.Tracer tracer` via constructor
- [x] 6.3 In `TryItOutService.invokeAndBuildResponse()`: start a child span `tracer.nextSpan().name("try-it-out.invoke").start()`; open `try (var scope = tracer.withSpan(span))`; extract `String traceId = span.context().traceId()`; add a catch for `Exception` that calls `span.error(e)` and re-throws; end span in `finally` (NOT in catch)
- [x] 6.4 In `TryItOutService.invokeAndBuildResponse()`: set `traceId` on the `TryItOutResponseDto.builder()` — use `null` if the trace ID equals the all-zeros OTel no-op value (`"00000000000000000000000000000000"`)
- [x] 6.5 Update OpenAPI example response files under `src/main/resources/openapi/examples/` for try-it-out endpoints (both test-case and variables variants) to include `"traceId": "4bf92f3577b34da6a3ce929d0e0e4736"` — pretty-formatted per project convention

## 7. Verification

- [x] 7.1 Run `./gradlew checkstyleMain checkstyleTest` — no violations
- [x] 7.2 Run `./gradlew test` — all tests pass
- [x] 7.3 Run `./gradlew clean build` — full build passes
