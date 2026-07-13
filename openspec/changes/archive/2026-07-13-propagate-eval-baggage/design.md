## Context

During an evaluation run the framework calls DIAL Core (deployment/MCP) and metric providers. It already opens OTel spans (`eval.testcase.execute`, `metric.tsmd.evaluate`) carrying `eval.run.id`/`eval.suite.id` attributes and injects `traceparent` on the outgoing request via a `RestClient` interceptor that uses `openTelemetry.getPropagators().getTextMapPropagator().inject(...)`.

Span attributes are exported out-of-band to the tracing backend and never travel on the wire, and each `eval.testcase.execute` is a **new root trace** (workers run on virtual threads with no ambient parent span), so `traceparent`'s trace id is per-test-case. DIAL Core's analytics (`GfLogStore`) logs the **inbound** request headers of each hop (`context.getRequest().headers()`) and can capture a `baggage` header verbatim. W3C Baggage is therefore the mechanism that lets a run-level identifier ride the whole downstream chain and be grouped in analytics.

Verified facts that shape this design:
- Eval `application.yml` does **not** override `otel.propagators`; the OTel SDK autoconfigure default is `tracecontext,baggage`, so the existing tracing interceptor's propagator already serializes baggage — no new wiring.
- DIAL Core forwards `baggage` verbatim (`ProxyUtil.copyHeaders`) and does not strip inbound trace/baggage headers; `GfLogStore.appendHeaders` reads `context.getRequest().headers()`, so a sent `baggage` header is visible to analytics.

## Goals / Non-Goals

**Goals:**
- Put `eval.run.id` and `eval.suite.id` into OTel Baggage on run-scoped outbound calls (eval worker DIAL Core call; metric provider call) so they serialize into the `baggage` header and propagate downstream.
- Zero new configuration; no-op when OTel is disabled; no leakage of baggage across the async/thread boundary.
- Reuse the existing propagator/interceptor path — no new HTTP wiring, no new dependency.

**Non-Goals:**
- No DIAL Core code change (analytics capture is an operator config step).
- No try-it-out support (no run id), no additional keys beyond run/suite id, no DB/endpoint/schema change.
- No change to existing span attributes or `traceparent` propagation.

## Decisions

### D1: Use the OTel Baggage API, not a manually-set `baggage` header
Populate `Baggage` on the OTel context (`Baggage.current().toBuilder().put("eval.run.id", ...).put("eval.suite.id", ...).build().makeCurrent()`), letting the existing tracing interceptor's baggage propagator emit the header.

- **Why:** (1) It's the standard mechanism — OTel-instrumented downstream apps auto-relay baggage across boundaries, which a hand-set header does not survive. (2) It reuses the interceptor already in place for both the DIAL Core client and the metric-provider client, so both paths are covered by one mechanism. (3) Keeps a single source of truth (context) rather than duplicating identifier plumbing into per-request header maps.
- **Alternative rejected — set `baggage` in `EvaluationWorker.buildHeaders`:** only covers the eval DIAL path (not metric calls), only rides hops DIAL itself forwards (no cross-app auto-relay), and re-implements what the propagator already does.

### D2: Set/clear baggage inside the existing per-execution OTel `Scope` via a small shared helper
Introduce a thin helper that returns an `AutoCloseable`/`Scope`, used with try-with-resources immediately after the span is made current in each worker:

```java
try (Scope span = span.makeCurrent();
     Scope baggage = EvalBaggage.withRunContext(runId, suiteId)) {
    ...
}
```

- **Why:** Symmetric set-on-open / clear-on-close guarantees no baggage entry lingers on a reused virtual/pooled thread (spec: no-leak scenario). Mirrors the existing `span.makeCurrent()` scoping the workers already use, so it's a familiar shape.
- **Disabled-OTel behavior (corrected):** `Baggage` lives in `opentelemetry-api`/`-context` and is **independent of the SDK** — `withRunContext` *will* create real baggage entries in the thread context even when `otel.sdk.disabled=true`. Suppression of the outgoing `baggage` header therefore does **not** happen at the baggage-put; it happens at the **injection layer**: the tracing interceptor injects via `openTelemetry.getPropagators().getTextMapPropagator()`, and when the SDK is disabled the starter provides `OpenTelemetry.noop()`, whose `getPropagators()` is `ContextPropagators.noop()` → `inject(...)` is a no-op, so no `baggage` header is written. This is the *same* mechanism that already suppresses `traceparent` when OTel is off (existing spec: "the tracing interceptor SHALL be a no-op"), and it is verified to hold here: there is **no custom `OpenTelemetry` or propagator bean** in `src/main/java` (`application.yml` also does not override `otel.propagators`), so the bean is the starter default. Note this is distinct from the traceparent case at the *reason* level: `traceparent` is empty because the span context is invalid, whereas baggage rides even an invalid span — only the no-op propagator stops it. A dedicated disabled-case test asserts no `baggage` header is injected (it is NOT covered by the existing traceparent test).
- **Alternative rejected — set baggage without scoping / rely on GC:** risks stale baggage bleeding into unrelated work dispatched on the same thread.

### D3: Helper placement — static utility in `utils`, consistent with `TraceContextUtils`
Add `EvalBaggage` (or extend the existing tracing utility) as a static helper in `com.epam.aidial.evaluation.utils`, returning the baggage `Scope`.

- **Why:** It's a thin, stateless wrapper over the OTel API with no injectable collaborators — directly analogous to the existing `TraceContextUtils`. Unit-testable with an in-memory OTel SDK. Avoids adding a bean just to hold two `put` calls.
- **Alternative considered — `@Component` in `service.infrastructure`:** AGENTS.md prefers injectable components for reuse/testing, but that guidance targets parsing/validation/conversion logic; a trace-context helper has direct precedent as a static util. If DI is later preferred for test seams, promoting to a component is trivial and localized.

### D4: Keys `eval.run.id` + `eval.suite.id`; no config toggle
Exactly two members, both UUID strings. Population is unconditional whenever OTel is active (OTel is off by default, so nothing is emitted otherwise).

- **Why:** Matches the identifiers already on the spans and the grouping the analytics use-case needs (by run, optionally by suite). A toggle adds a property with no clear operator need since OTel already gates emission.

### D5: Rely on the default `tracecontext,baggage` propagator set
No code sets the propagator; the interceptor consumes whatever `openTelemetry.getPropagators()` provides. Default includes baggage (verified — not overridden).

- **Why:** Least surprise, no new wiring. Documented so deployments don't narrow `OTEL_PROPAGATORS` to trace-context-only.

## Risks / Trade-offs

- **[Propagator narrowed to `tracecontext` only in some deployment]** → baggage silently not emitted. Mitigation: document that `OTEL_PROPAGATORS` must include `baggage` (the default); the design does not change it, so a stock deployment is correct. An interceptor slice test (MockRestServiceServer + real OTel SDK) asserts the `baggage` header is present when OTel is on.
- **[Baggage is broadcast to every downstream, including the upstream model provider]** → data-exposure concern. Mitigation: spec limits members to two non-sensitive UUIDs; explicit scenario forbids tokens/api-key/content. Reviewer check on the helper.
- **[Continuity across a non-OTel-instrumented external application]** → the app→DIAL leg may drop baggage. Mitigation: documented limitation; unavoidable from either side. Direct deployment/MCP/interceptor hops are unaffected.
- **[Analytics capture depends on DIAL Core config not owned here]** → no rows unless `collectHeaders=true` + `baggage` allowlisted. Mitigation: captured as a rollout step; `baggage` (two UUIDs) is well under DIAL's `MAX_HEADER_VALUE_LENGTH` truncation.
- **[Value logged is the per-hop inbound baggage]** → nested app rows carry the run id only if the app relays it. Mitigation: documented; top-level and MCP rows are unaffected.

## Migration Plan

1. Deploy the eval framework with baggage population. No behavioral change when OTel is disabled (default) — safe to ship independently.
2. Enable OTel at deploy time (existing mechanism) — baggage begins riding outgoing calls.
3. Operator step on ai-dial-core: set `analytics.collectHeaders=true` and add `baggage` to `analytics.headersAllowlist`. Analytics rows then carry `baggage` with `eval.run.id=…,eval.suite.id=…`.
4. Consumers group/join analytics rows by parsing the `eval.run.id` member out of the `baggage` field.

**Rollback:** revert the eval change or disable OTel; the DIAL Core config toggle is independent and harmless if left enabled.

## Open Questions

- Final helper name/location (`EvalBaggage` in `utils` vs folding into an existing tracing util) — cosmetic; resolve during implementation.
- Whether to later extend baggage with `eval.testcase.id`/`run.index` if per-test-case analytics grouping is ever needed — deferred; out of scope now.
