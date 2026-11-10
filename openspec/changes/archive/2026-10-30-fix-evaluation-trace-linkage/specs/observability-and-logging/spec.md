## MODIFIED Requirements

### Requirement: Correlation ID on all HTTP requests
The service SHALL assign a correlation ID to each HTTP request and include it in logs and responses. With OpenTelemetry enabled, the incoming HTTP request SHALL have an active server span. The service SHALL use that span's trace ID as the correlation ID when no valid `X-Correlation-Id` is provided by the client. The custom correlation ID SHALL remain separate from native trace IDs and SHALL NOT determine span parenting.
Status: **Implemented**

#### Scenario: Client-provided correlation id
- **WHEN** request includes header `X-Correlation-Id` with a valid value (16–32 alphanumeric characters)
- **THEN** system SHALL use it for logging and echo it in the response

#### Scenario: Generated correlation id from OTel trace
- **WHEN** request does not include a valid `X-Correlation-Id`
- **AND** OpenTelemetry is enabled and a span is active on the request thread
- **THEN** system SHALL use the current OTel trace ID (32-char lowercase hex) as the correlation ID for logging and echo it in the response

#### Scenario: Fallback when OTel span is not active
- **WHEN** request does not include a valid `X-Correlation-Id`
- **AND** no OTel span is active (e.g., tracing disabled or request pre-empted)
- **THEN** system SHALL generate a random 16-char alphanumeric correlation ID as before

#### Scenario: Correlation continuity through DIAL dispatch
- **WHEN** a run-start request dispatches evaluation through a DIAL Core route
- **THEN** the outgoing route-trigger request SHALL carry the resolved `X-Correlation-Id`
- **AND** the trigger-thread logs SHALL retain that correlation ID
- **AND** when Core forwards that header, the internal execute request and its asynchronous run job SHALL retain the same correlation ID

#### Scenario: Correlation cleanup at request and task boundaries
- **WHEN** request processing returns its thread, including asynchronous HTTP handoff, or a run-scoped task completes
- **THEN** its correlation-ID scope SHALL be cleared or the thread's prior logging context restored
- **AND** unrelated work SHALL NOT inherit the completed request's correlation ID

### Requirement: OTel context propagation through async evaluation execution
The service SHALL propagate the active native OpenTelemetry context through route-trigger, run-job, and worker submission boundaries. Run orchestration SHALL continue the context active at dispatch, while test-case traces and metric parents SHALL follow the explicit hierarchy requirements below rather than accidental thread inheritance.
Status: **Implemented**

#### Scenario: Context propagated through @Async thread pool
- **WHEN** an evaluation run is submitted to its job executor with an active OTel context
- **THEN** the run job thread SHALL receive the same native OTel span context and baggage in both virtual and platform thread modes
- **AND** propagation SHALL NOT depend on the presence of a Micrometer Tracing bridge

#### Scenario: Context propagated through virtual thread executor
- **WHEN** a test-case task or metric evaluation task is submitted to the run's shared worker executor
- **THEN** the worker SHALL receive the native OTel context active at submission in both virtual and platform thread modes
- **AND** explicitly selected test-case parents SHALL take precedence over the orchestration span

#### Scenario: Route-trigger context propagation
- **WHEN** the run-start request launches the DIAL route-trigger task
- **THEN** its active native OTel context SHALL reach the trigger task and outbound W3C context headers

#### Scenario: Task scope restoration
- **WHEN** an asynchronous task completes or throws
- **THEN** its prior thread context SHALL be restored without leaking spans or baggage to unrelated tasks

## ADDED Requirements

### Requirement: Run orchestration continues the incoming distributed trace
Each executing evaluation run SHALL create an orchestration span carrying `eval.run.id` and `eval.suite.id`. With a valid incoming span context, orchestration SHALL remain in that trace and SHALL be parented to the dispatch-time span. With no valid incoming context and tracing enabled, orchestration SHALL start a new trace.
Status: **Implemented**

#### Scenario: Core-routed run continuation
- **WHEN** the internal execute endpoint receives a valid W3C trace context from DIAL Core and dispatches a run
- **THEN** the orchestration span SHALL share the incoming trace ID and descend from the internal request span

#### Scenario: Direct run continuation
- **WHEN** a public run-start request dispatches evaluation directly with an active request span
- **THEN** orchestration SHALL remain in that request's trace

#### Scenario: Run without an incoming trace
- **WHEN** a run starts with tracing enabled but no valid incoming span
- **THEN** orchestration SHALL have a valid new trace with run/suite attributes

### Requirement: Independent linked test-case traces
Each executed `(testCaseId, runIndex)` within a run SHALL have one independent root span and trace. Its root SHALL have no parent and SHALL contain a span link to the run orchestration span when that context is valid. Root attributes SHALL include `eval.run.id`, `eval.suite.id`, `testcase.id`, `testcase.name`, and `run.index`.
Status: **Implemented**

#### Scenario: Independent repetitions
- **WHEN** multiple test cases or repetitions execute with tracing enabled
- **THEN** each `(testCaseId, runIndex)` SHALL have a different trace ID from orchestration and from every other pair
- **AND** each root SHALL link to the same run orchestration span, not be its child

#### Scenario: Existing execution subtree preserved
- **WHEN** a test case performs HTTP or MCP execution, including multiple requests, turns, or retries
- **THEN** its existing `eval.testcase.execute` span and downstream hierarchy SHALL remain beneath its independent test-case root
- **AND** every result row from that repetition SHALL retain that test-case trace ID

#### Scenario: Run-scoped trace discovery
- **WHEN** retained and exported traces are searched by `eval.run.id`
- **THEN** the orchestration trace and all matching independent test-case traces SHALL be discoverable

### Requirement: Metric spans are children of their test-case root
Each invoked metric span SHALL be directly parented to the root for its result's `(testCaseId, runIndex)` in the active run. Metrics SHALL share that test-case trace ID, not the orchestration trace ID. Existing metric attributes, phase-specific baggage, and provider/downstream parent-child hierarchies SHALL be preserved.
Status: **Implemented**

#### Scenario: Phase 2 follows Phase 1
- **WHEN** metrics execute after the deployment-execution phase has completed
- **THEN** their parent span ID SHALL equal the corresponding test-case root span ID
- **AND** their trace ID SHALL equal the execution result's trace ID

#### Scenario: Multiple requests and turns
- **WHEN** one repetition produces multiple request/turn result rows with metrics
- **THEN** all invoked metric spans for those rows SHALL be children of the same test-case root
- **AND** `result.id` and existing phase-specific attributes/baggage SHALL distinguish the rows and metrics

#### Scenario: No cross-run or repetition parenting
- **WHEN** the same test case appears in concurrent runs or multiple repetitions
- **THEN** each metric SHALL be parented only to its own active run and repetition's root

#### Scenario: Imported results have no live execution parent
- **WHEN** a metrics-only run evaluates imported results without their original runtime span contexts
- **THEN** it SHALL create a new metric-only root for each evaluated `(testCaseId, runIndex)`, linked to current orchestration
- **AND** it SHALL NOT fabricate a historical parent span ID or overwrite an imported execution trace ID to claim historical continuity

### Requirement: Tracing state is transient and run-owned
Span contexts needed for cross-phase parenting SHALL remain in run-scoped runtime memory only. Test-case roots SHALL cover execution and metric evaluation, and SHALL be ended and released when the run finishes, fails, or is cancelled. Closing tracing state SHALL be idempotent and SHALL prevent late tasks from retaining or creating new run-owned roots.
Status: **Implemented**

#### Scenario: Persistent contract unchanged
- **WHEN** results are stored, returned by REST, or exported/imported as CSV
- **THEN** existing execution `traceId` fields SHALL remain unchanged in shape
- **AND** no parent span ID, serialized OTel context, or additional tracing-storage field SHALL be required

#### Scenario: Terminal cleanup
- **WHEN** a run completes, fails, is cancelled, or encounters submission rejection after tracing resources are allocated
- **THEN** all owned root spans SHALL be ended exactly once and retained tracing state SHALL be released

#### Scenario: Late worker after shutdown
- **WHEN** a timed-out or interrupted worker races with tracing-session cleanup
- **THEN** it SHALL NOT recreate run-owned roots or leave tracing resources retained after session closure

#### Scenario: Tracing disabled
- **WHEN** OTel is disabled
- **THEN** evaluation, cancellation, and result persistence SHALL continue normally without exported spans, fabricated trace IDs, or tracing-state leaks

## Implementation Notes
- Runtime ownership: `evaluation-runner-core/src/main/java/com/epam/aidial/evaluation/runner/job/EvaluationTraceSession.java`.
- Wiring: `TestSuiteEvaluationJob`, `EvaluationWorker`, `MetricEvaluationWorker`, and CLI `EvaluationContextFactory`/`RunOrchestrationService`.
- Operator guidance: `docs/patterns/evaluation-tracing.md`.
