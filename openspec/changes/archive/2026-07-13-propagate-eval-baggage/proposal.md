## Why

During an evaluation run, DIAL Core writes one analytics row (`GfLogStore`) per inbound request in the chain (top-level deployment call, MCP tool call, and any nested application → model / application → application calls). Today none of those rows carry the eval **run id**: the framework only sets `eval.run.id` as an OTel *span attribute*, which is exported out-of-band to the tracing backend and never travels on the wire. The only in-band identifier that reaches DIAL Core is `traceparent`, whose trace id is minted **per test case** (each `eval.testcase.execute` is a new root trace) — so correlating DIAL Core analytics back to a run requires a per-test-case join through `test_case_run_results.trace_id`, and there is no single key that groups a whole run.

W3C **Baggage** is the standard mechanism for carrying a correlation value across an arbitrary distributed call chain. DIAL Core forwards the `baggage` header verbatim on every hop it originates (model, MCP, interceptor, application) and OTel-instrumented apps auto-relay it, so placing `eval.run.id` into OTel Baggage lets it ride the whole chain and be captured directly in DIAL Core analytics — enabling `GROUP BY` on the actual run id with no eval-DB round-trip.

## What Changes

- Populate OTel **Baggage** with the eval **run id** (key `eval.run.id`) on the current OTel context at the start of an evaluation test-case execution, so it is serialized into the outgoing `baggage` header on the DIAL Core call and propagated downstream.
- Apply the same baggage population on the other run-scoped outbound paths that already set `eval.run.id` as a span attribute: the metric-evaluation worker (metric provider calls). Try-it-out is **out of scope** (it is ad-hoc and has no run id).
- Baggage is set/cleared within the existing per-execution OTel `Scope` (mirrors how the span is made current today), so it does not leak across pooled/virtual threads and is a no-op when OTel is disabled.
- Keys are `eval.run.id` and `eval.suite.id` — a small, non-sensitive set of UUIDs only. No tokens, PII, or free-form data go into baggage (it is broadcast to every downstream service, including the upstream model provider).
- No new configuration property. Baggage population is unconditional whenever OTel is active; since OTel is disabled by default, baggage is emitted only when tracing is switched on. No `docs/configuration.md` change.

Non-goals:
- No change to DIAL Core. Capturing baggage into DIAL Core analytics requires DIAL Core config (`analytics.collectHeaders=true` + `baggage` in `analytics.headersAllowlist`); that is an operational/rollout dependency, documented but not implemented here.
- No new analytics table, endpoint, or DB schema change on this service.
- Does not remove or alter the existing `eval.run.id` span attribute or `traceparent` propagation — baggage is additive.

## Capabilities

### New Capabilities

_None._ This extends an existing observability capability.

### Modified Capabilities

- `observability-and-logging`: add a requirement that the service SHALL populate OTel Baggage with `eval.run.id` and `eval.suite.id` within the active OTel context on run-scoped outbound calls (eval worker DIAL Core call; metric provider calls), so the identifiers propagate downstream via the `baggage` header. Includes scenarios for baggage-set-when-tracing-active, no-op-when-OTel-disabled, and no-leak-across-async-boundaries.

## Impact

- **Code (this service):**
  - `service.domain.job.EvaluationWorker` — set `eval.run.id` baggage in the `execute(...)` OTel scope (the span already carries the attribute).
  - `service.domain.job.MetricEvaluationWorker` — same, in its `metric.tsmd.evaluate` scope.
  - Likely a small shared helper (e.g. `service.infrastructure`/`utils` component) to set baggage inside a try-with-resources `Scope`, keeping set/clear symmetric and testable — final placement decided in design.
- **Dependencies:** none new. The Baggage API ships in the already-present `opentelemetry-api`; W3C baggage is part of the default OTel propagator set (`tracecontext,baggage`), so the existing `tracingInterceptor` serializes it with no extra wiring — to be verified in design.
- **Configuration:** none. No new property; OTel remains disabled by default, so baggage is emitted only when tracing is on.
- **External / downstream (DIAL Core — ai-dial-core):** already forwards `baggage` verbatim (`ProxyUtil.copyHeaders`) and can log it via `GfLogStore` when `analytics.collectHeaders=true` and `baggage` is allowlisted. No code change required there; the capture is an operator configuration step captured in the rollout/test plan.
- **Consumers:** DIAL Core analytics rows gain a parseable `eval.run.id=<uuid>` member inside the `baggage` field, enabling run-level grouping across model/MCP/interceptor/app hops (with the known caveat that continuity across a non-OTel-instrumented external application depends on that app relaying baggage).
