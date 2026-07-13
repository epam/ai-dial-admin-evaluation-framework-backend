## ADDED Requirements

### Requirement: OTel Baggage propagation of eval run and suite ids
The service SHALL populate the OTel `Baggage` of the active OTel context with the entries `eval.run.id` and `eval.suite.id` (both UUID strings) on every run-scoped outbound execution path that already opens an OTel span carrying those attributes — the evaluation worker's DIAL Core call and the metric-evaluation worker's metric provider call. Because the default OTel propagator set includes the W3C baggage propagator, the existing tracing `RestClient` interceptor SHALL then serialize these entries into a `baggage` header on the outgoing HTTP request, allowing DIAL Core and downstream services to attribute each analytics/log event to the originating eval run and suite.
Status: **Planned**

Baggage SHALL carry only these non-sensitive UUID identifiers. Tokens, credentials, PII, or free-form data SHALL NOT be placed into baggage, since baggage is broadcast verbatim to every downstream service including the upstream model provider.

Baggage entries SHALL be scoped to the current execution: set when the per-execution OTel scope is opened and removed when it closes, so no baggage entry leaks onto a pooled or virtual thread after the execution completes.

This requirement is additive: it does not alter the existing `eval.run.id`/`eval.suite.id` span attributes or the W3C `traceparent` propagation, which remain unchanged.

#### Scenario: Baggage set on eval worker DIAL Core call when tracing active
- **WHEN** the evaluation worker executes a test case with OTel enabled and opens the `eval.testcase.execute` span scope
- **THEN** the current OTel Baggage SHALL contain `eval.run.id` = the run id and `eval.suite.id` = the suite id
- **AND** the outgoing DIAL Core request SHALL include a `baggage` header whose members include `eval.run.id=<runId>` and `eval.suite.id=<suiteId>`

#### Scenario: Baggage set on metric provider call when tracing active
- **WHEN** the metric-evaluation worker evaluates a metric with OTel enabled and opens the `metric.tsmd.evaluate` span scope
- **THEN** the current OTel Baggage SHALL contain `eval.run.id` and `eval.suite.id`
- **AND** the outgoing metric provider request SHALL include a `baggage` header carrying those members

#### Scenario: No baggage header when OTel disabled
- **WHEN** OTel is disabled (default configuration) and an eval or metric execution runs
- **THEN** the tracing interceptor's propagator SHALL be a no-op so that **no `baggage` header is injected** on the outgoing HTTP request, and no error is raised
- **AND** this holds even though setting run/suite baggage in the OTel context is itself SDK-independent — suppression occurs at the injection layer, not at the baggage-put

#### Scenario: Baggage does not leak across the async boundary
- **WHEN** a test-case execution completes and its OTel scope closes on a pooled/virtual worker thread
- **THEN** the `eval.run.id`/`eval.suite.id` baggage entries SHALL no longer be present on that thread's OTel context for subsequent unrelated work

#### Scenario: Baggage carries only non-sensitive identifiers
- **WHEN** the `baggage` header is constructed for an outgoing run-scoped call
- **THEN** its members SHALL be limited to `eval.run.id` and `eval.suite.id`
- **AND** SHALL NOT include the caller's authorization token, api-key, or any test-case content

## Implementation notes

- Eval path: `com.epam.aidial.evaluation.service.domain.job.EvaluationWorker#execute` already opens the `eval.testcase.execute` span with `eval.run.id`/`eval.suite.id` attributes and `span.makeCurrent()`. Baggage is set within that same scope.
- Metric path: `com.epam.aidial.evaluation.service.domain.job.MetricEvaluationWorker` (`metric.tsmd.evaluate` span) mirrors the eval path.
- Serialization relies on the W3C baggage propagator being part of the OTel propagator set used by the tracing `RestClient` interceptor (`DialCoreClientConfiguration#tracingInterceptor` and the metric-provider client). Design verifies the propagator set is `tracecontext,baggage` (not overridden to trace-context-only).
- Baggage set/clear uses a small shared helper opened as a try-with-resources scope; final placement (`service.infrastructure` or `utils`) decided in design.
- DIAL Core (ai-dial-core) forwards `baggage` verbatim (`ProxyUtil.copyHeaders`) and logs it via `GfLogStore.appendHeaders` (reads `context.getRequest().headers()`) when `analytics.collectHeaders=true` and `baggage` is allowlisted — an operator configuration step, not part of this service's code.
