## MODIFIED Requirements

### Requirement: Evaluation executor interface
The system SHALL define an `EvaluationExecutor` interface with a single `execute(EvaluationContext)` method. The `EvaluationContext` SHALL carry: `runId`, `testSuiteId`, execution settings (concurrency, timeout, retry, rate limit), a cancellation signal, a progress callback, a result sink, and a `credential` (`CallerCredential`, carrying either a bearer JWT or an API key — including a DIAL Core per-request key, which is simply an `API_KEY`-kind credential). The `EvaluationContext` has never carried a raw JWT string field — credential concerns were already generalized to `CallerCredential` before this change. This interface enables swapping in-process execution with K8s Job submission without changing orchestration code.

Status: **Implemented**

#### Scenario: In-process executor is the default
- **WHEN** the application starts with default configuration
- **THEN** the `InProcessEvaluationExecutor` bean SHALL be the active `EvaluationExecutor` implementation

#### Scenario: Executor receives fully populated context
- **WHEN** `TestSuiteEvaluationJob` dispatches a run
- **THEN** it SHALL construct an `EvaluationContext` from the run's `RunConfigDto` (with system defaults for omitted fields) and pass it to the executor
- **AND** context construction and cancellation signal registration SHALL occur before async dispatch to prevent race conditions

#### Scenario: EvaluationContext credential is kind-agnostic
- **WHEN** `dial-app-proxy.enabled=true` and a run is dispatched from `EvalExecuteInternalController` with a PRK
- **THEN** the `EvaluationContext` constructed for the run SHALL carry an `API_KEY`-kind `CallerCredential` (the PRK)
- **AND** deployment invocations SHALL use that credential's `headerName()`/`headerValue()` exactly as they already do for a `BEARER`-kind credential — no branching logic is added to `DialCoreDeploymentInvoker`, `EvaluationWorker`, `DeploymentTurnInvoker`, or `McpToolInvoker`

### Requirement: In-process evaluation execution — dispatch path
The `InProcessEvaluationExecutor` SHALL read enabled and valid test cases from the suite in pages, dispatch execution tasks (one per test case per run index) bounded by the configured concurrency level, collect results, and flush them to analytics DB in batches. When `dial-app-proxy.enabled=true`, the executor is started from `EvalExecuteInternalController` (triggered by DIAL Core route, with an `API_KEY`-kind credential) via the existing `TestSuiteEvaluationJob.dispatch(runId, credential, skipDeploymentPhase)` method. When `dial-app-proxy.enabled=false`, the executor is started by the legacy dispatch from `TestSuiteRunService.dispatchEvaluation` with a `BEARER`-kind credential. Both paths call the same `dispatch` method — only the caller and the credential's kind differ.

Status: **Implemented** (core execution), **Planned** (dispatch path branching)

#### Scenario: Sequential execution (default)
- **WHEN** `concurrencyLevel` is 1 (default)
- **THEN** the executor SHALL process test case calls one at a time, in order (page by page, case by case, run index by run index)

#### Scenario: Parallel execution
- **WHEN** `concurrencyLevel` is greater than 1 (e.g., 10)
- **THEN** the executor SHALL process up to `concurrencyLevel` test case calls concurrently using a semaphore-bounded virtual thread executor

#### Scenario: DIAL App mode — executor started from internal endpoint
- **WHEN** `dial-app-proxy.enabled=true`
- **AND** `EvalExecuteInternalController` receives a valid, already-authenticated trigger for `runId`
- **THEN** `TestSuiteEvaluationJob.dispatch(runId, credential, false)` SHALL be called directly from the controller, with `credential` sourced from `AuthorizationTokenHolder.getCredential()`
- **AND** `DialRouteTriggerClient` (not `TokenPropagationHelper`/`AuthorizationTokenHolder`-based in-process dispatch) SHALL have been the mechanism that caused DIAL Core to call this endpoint

#### Scenario: Legacy mode — executor started with JWT propagation
- **WHEN** `dial-app-proxy.enabled=false`
- **THEN** eval execution SHALL be started via `TestSuiteRunService.dispatchEvaluation`'s existing direct call to `TestSuiteEvaluationJob.dispatch(runId, credential, skipDeploymentPhase)` with a `BEARER`-kind credential (existing behavior, unchanged)
