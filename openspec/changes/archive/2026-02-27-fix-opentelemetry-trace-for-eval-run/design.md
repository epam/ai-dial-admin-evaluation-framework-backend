## Context

The evaluation framework currently generates a `UUID.randomUUID()` as `traceId` in `EvaluationWorker` and sets it as `X-Correlation-Id` on outgoing DIAL Core calls. This ID has three problems: (1) it is in UUID format (`xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`) rather than OTel trace ID format (32 lowercase hex chars, no dashes), so it cannot be found in DIAL Core traces; (2) it is disconnected from any OTel span, so there is no distributed trace linking eval execution to DIAL Core; (3) it is regenerated for each retry attempt, losing continuity. Try-it-out sends no trace context at all and returns no trace ID. The `CorrelationIdInterceptor` already falls back to `Span.current().getSpanContext().getTraceId()`, but without OTel auto-instrumentation that span is always INVALID (all zeros), so it falls through to a random 16-char string.

The project already has `opentelemetry-sdk` and `opentelemetry-exporter-otlp` on the classpath but no Spring auto-instrumentation — meaning `Span.current()` is INVALID in all contexts. The async execution uses two boundaries where OTel context would be lost: the `@Async` thread pool (`testSuiteRunExecutor`) and the virtual thread executor inside `InProcessEvaluationExecutor`.

## Goals / Non-Goals

**Goals:**
- Each test case execution creates a proper OTel child span; its 32-char hex trace ID is stored in `TestCaseRunResult.traceId` and sent to DIAL Core via `traceparent`
- DIAL Core log entries can be found by the `traceId` stored in the database
- OTel context propagates correctly through both async boundaries (`@Async` pool and virtual threads)
- Try-it-out propagates `traceparent` to DIAL Core and returns the trace ID in the response
- `CorrelationIdInterceptor` works correctly on incoming HTTP requests (gets a real span ID instead of falling back to random)
- The existing Log4J appender gets the Spring-autoconfigured `OpenTelemetry` instance so trace/span IDs appear in log output

**Non-Goals:**
- Adding the OTel Java agent (`-javaagent`) — Spring Boot auto-configuration handles instrumentation
- Instrumenting JDBC queries, Flyway, or other infrastructure (out of scope)
- Changing the OTel exporter (OTLP remains)
- End-to-end distributed trace from HTTP client → eval run start (the eval run is async; only the test case execution spans link to DIAL Core)

## Decisions

### Decision 1: Micrometer Tracing bridge over manual OTel SDK or OTel Java agent

**Chosen**: Add `io.micrometer:micrometer-tracing-bridge-otel`. Spring Boot 3.5 auto-configures `OpenTelemetry`, `SdkTracerProvider`, W3C propagator, and OTLP export from `management.tracing.*` / `management.otlp.tracing.*` properties.

**Alternatives considered**:
- *OTel Java agent*: zero-code but requires `-javaagent` in `JAVA_OPTS` in every deployment and is not yet part of the project's deployment story. Also makes testing harder.
- *Manual OTel SDK configuration*: more control but duplicates what Spring Boot auto-configuration already provides; conflicts with `micrometer-tracing-bridge-otel`'s expectation that it owns the `OpenTelemetry` bean.
- *No tracing library, just fix the UUID format*: solves the format mismatch but gives no real spans — DIAL Core traces would still not link to our spans.

**Rationale**: Micrometer Tracing is the Spring Boot 3.x idiomatic path, is BOM-managed, and requires minimal configuration. It also enables correct `CorrelationIdInterceptor` behavior as a side-effect without any change to that class.

### Decision 2: `ContextPropagatingTaskDecorator` for the `@Async` boundary

**Chosen**: Set `ContextPropagatingTaskDecorator` on `testSuiteRunExecutor` in `AsyncConfiguration`. This class is part of `io.micrometer:context-propagation` (transitive dependency), and it captures all registered `ThreadLocal` storage — including OTel's — before the task is submitted and restores it on the pool thread.

**Alternatives considered**:
- *Manual `Context.current()` capture in caller + `makeCurrent()` in task*: works but couples the caller to OTel API; `ContextPropagatingTaskDecorator` is the Spring-idiomatic abstraction.
- *Extending `TokenPropagationHelper`*: the existing pattern only handles the auth token ThreadLocal; adding OTel would conflate two concerns.

### Decision 3: `Context.taskWrapping()` for virtual threads in `InProcessEvaluationExecutor`

**Chosen**: Replace `Executors.newVirtualThreadPerTaskExecutor()` with `Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor())`. This one-line change wraps every submitted runnable with the OTel `Context.current()` at submission time, propagating it into each virtual thread.

**Alternatives considered**:
- *`ContextSnapshot.captureAll()` from Micrometer*: captures all contexts (OTel, MDC, etc.) but adds more complexity; `Context.taskWrapping()` from the OTel API is simpler and sufficient since we only need OTel context at this layer (MDC is handled by the Log4J appender via `OpenTelemetryAppender`).

### Decision 4: `ClientHttpRequestInterceptor` for `traceparent` injection into RestClients

**Chosen**: Add a shared `tracingInterceptor(OpenTelemetry)` factory method to `DialCoreClientConfiguration`. The interceptor calls `openTelemetry.getPropagators().getTextMapPropagator().inject(Context.current(), request, setter)` to inject `traceparent` (and `tracestate` if present) into every outgoing request. Both `dialCoreRestClient` and `dialCoreTryOutRestClient` receive this interceptor.

**Alternatives considered**:
- *Micrometer's `ObservationRestClientCustomizer`*: auto-instruments `RestClient` via `RestClient.Builder` customizer bean; cleaner for new code but would also create an OTel span for each HTTP call, adding overhead and potentially complicating the span hierarchy. The manual interceptor gives more control: it only propagates context, not create new spans at the HTTP level.

### Decision 5: `EvaluationWorker` creates one OTel span per test case execution

**Chosen**: Inject `io.micrometer.tracing.Tracer` into `EvaluationWorker`. At the start of `execute()`, call `tracer.nextSpan().name("eval.testcase.execute").start()` and open a `SpanInScope`. Extract `span.context().traceId()` (32 hex chars) as the trace ID. This span is the parent for the DIAL Core HTTP call (via `traceparent`). The span wraps the entire execution including retries. Replace `UUID.randomUUID()` with the span's trace ID; remove the manual `X-Correlation-Id` header.

**Rationale**: The trace ID in `TestCaseRunResult` should be the same ID visible in DIAL Core's OTel export. Creating a span per test case places each execution in the distributed trace at the right granularity.

### Decision 6: Keep `CorrelationIdInterceptor` unchanged

**Chosen**: No changes to `CorrelationIdInterceptor`. Once Micrometer Tracing is active, Spring MVC auto-creates a span per incoming HTTP request. `Span.current().getSpanContext().getTraceId()` then returns a valid 32-char hex ID (not all zeros), so the interceptor correctly uses it for `X-Correlation-Id` on responses and MDC. No code change needed.

**Note**: The existing pattern validation regex `^[a-zA-Z0-9]{16,32}$` accepts 32 lowercase hex characters, so it remains compatible.

### Decision 7: OTel Log4J appender initialization via `ApplicationReadyEvent`

**Chosen**: Create a new `@Component` (`OtelAppenderInitializer`) that listens for `ApplicationReadyEvent` and calls `OpenTelemetryAppender.install(openTelemetry)` with the Spring-autoconfigured `OpenTelemetry` bean. Without this, the Log4J appender is a no-op (it has no `OpenTelemetry` instance to read from).

**Rationale**: The Log4J appender ships as a separate artifact (`opentelemetry-log4j-appender-2.17`) and is wired via `log4j2.xml`. It needs to be explicitly told about the SDK instance. `ApplicationReadyEvent` ensures Spring context is fully ready.

## Risks / Trade-offs

**[Risk] OTel version conflicts between `opentelemetry-log4j-appender-2.17:2.25.0-alpha` and Spring Boot BOM**
→ Mitigation: Spring Boot 3.5 BOM manages `io.opentelemetry.*` to a specific version (1.44.x range). The instrumentation artifact `opentelemetry-log4j-appender-2.17` at `2.25.0-alpha` pulls in OTel SDK 1.x transitively. If the pinned version conflicts with the BOM version, use `implementation 'io.opentelemetry.instrumentation:opentelemetry-log4j-appender-2.17'` without a version and let the Spring Boot BOM manage it (Spring Boot 3.5 includes this in its BOM). Verify at build time with `./gradlew dependencies`.

**[Risk] Virtual thread OTel context may not propagate through `TokenPropagationHelper.withTokenRunnable()` wrapper**
→ Mitigation: `Context.taskWrapping()` wraps the entire `Runnable` including the `TokenPropagationHelper` outer wrapper, so OTel context is captured at `CompletableFuture.runAsync()` call time and available in the virtual thread before `TokenPropagationHelper` executes. Order matters: `Context.taskWrapping()` is on the executor, not the runnable, so it applies regardless of runnable wrapping.

**[Risk] Span not ended on exception in `EvaluationWorker`**
→ Mitigation: Wrap span lifecycle in `try/finally` — `span.end()` always called in `finally`. Set `span.error(e)` on exception before ending. The existing exception handling in `execute()` (catch-all returning an ERROR result) ensures the span is ended correctly.

**[Risk] `traceId` format change in `TestCaseRunResult` (UUID with dashes → 32 hex chars)**
→ Mitigation: The DB column `trace_id VARCHAR(128)` is wide enough. Existing stored data retains old UUID-format values — this is accepted (no migration). New data will have proper OTel trace IDs.

**[Risk] `traceId` is null when OTel is not configured (e.g., in tests with `management.tracing.enabled=false`)**
→ Mitigation: Micrometer's `Tracer` returns a no-op span (with all-zeros trace ID) when tracing is disabled. Use `span.context().traceId()` — it returns the 32-char all-zeros string, not null. Store as-is; it won't be meaningful but won't NPE. In test profile, tracing is disabled by default (sampling probability 0), so test assertions should not depend on traceId format.

## Migration Plan

1. Add `micrometer-tracing-bridge-otel` to `build.gradle`; verify dependency tree for OTel version conflicts
2. Add `management.tracing.*` and `management.otlp.tracing.*` to `application.yml`; set `sampling.probability=1.0`
3. Implement all code changes (see tasks)
4. Run `./gradlew clean build` — all tests pass (tracing is a no-op in test profile)
5. Deploy to staging; verify:
   - Try-it-out response contains `traceId` (32 hex chars)
   - `TestCaseRunResult.traceId` in DB is 32 hex chars
   - Jaeger/Tempo shows a span for each test case execution with `traceparent` linking to DIAL Core spans
6. No rollback concern: the `trace_id` column format change is backward-compatible (old rows keep UUID format, new rows get OTel format)

## Open Questions

- Should `management.otlp.tracing.endpoint` default to `http://localhost:4318/v1/traces` or be required via env var? → Recommendation: default to localhost (matching existing OTLP exporter pattern); production overrides via `OTEL_EXPORTER_OTLP_ENDPOINT` env var.
- Should the `EvaluationWorker` span include test case ID / run index as span attributes for richer Jaeger search? → Recommended yes (add `span.tag("testcase.id", ...)` and `span.tag("run.index", ...)`); low effort, high observability value.
