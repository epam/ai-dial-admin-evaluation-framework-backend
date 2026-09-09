## MODIFIED Requirements

### Requirement: OTel context propagation through async evaluation execution
The service SHALL propagate the active OpenTelemetry context through both async execution boundaries so that spans created during test case execution are correctly parented.
Status: **Implemented**

#### Scenario: Context propagated through @Async thread pool
- **WHEN** `TestSuiteEvaluationJob` submits a run to the `testSuiteRunExecutor` task executor
- **THEN** the OTel context active at dispatch time SHALL be propagated to the pool thread via `ContextPropagatingTaskDecorator`

#### Scenario: Context propagated through virtual thread executor
- **WHEN** a test case task (Phase 1) or a metric evaluation task (Phase 2) is submitted to the run's shared virtual thread executor
- **THEN** the OTel context active at submission time SHALL be propagated to the virtual thread via `Context.taskWrapping()` — the run's executor is created wrapped, once, when the run is registered, and every phase dispatches onto that same executor
