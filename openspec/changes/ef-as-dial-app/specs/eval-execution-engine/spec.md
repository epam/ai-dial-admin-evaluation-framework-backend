## MODIFIED Requirements

### Requirement: Evaluation executor interface
The system SHALL define an `EvaluationExecutor` interface with a single `execute(EvaluationContext)` method. The `EvaluationContext` SHALL carry: `runId`, `testSuiteId`, execution settings (concurrency, timeout, retry, rate limit), a cancellation signal, a progress callback, and a result sink. The `EvaluationContext` SHALL NOT carry a JWT token field — token concerns are handled by the auth layer (`AuthorizationTokenHolder` in JWT mode, `PerRequestKeyStore` in DIAL App mode). This interface enables swapping in-process execution with K8s Job submission without changing orchestration code.
Status: **Implemented** (interface), **Planned** (token field removal)

#### Scenario: In-process executor is the default
- **WHEN** the application starts with default configuration
- **THEN** the `InProcessEvaluationExecutor` bean SHALL be the active `EvaluationExecutor` implementation

#### Scenario: Executor receives fully populated context
- **WHEN** `TestSuiteEvaluationJob` dispatches a run
- **THEN** it SHALL construct an `EvaluationContext` from the run's `RunConfigDto` (with system defaults for omitted fields) and pass it to the executor
- **AND** context construction and cancellation signal registration SHALL occur before async dispatch to prevent race conditions

#### Scenario: EvaluationContext carries no JWT token in DIAL App mode
- **WHEN** `ef.dial-app.enabled=true`
- **THEN** the `EvaluationContext` constructed for the run SHALL NOT contain a JWT field
- **AND** deployment invocations SHALL obtain credentials from `PerRequestKeyStore` (via `runId`)

### Requirement: In-process evaluation execution — dispatch path
The `InProcessEvaluationExecutor` SHALL read enabled and valid test cases from the suite in pages, dispatch execution tasks (one per test case per run index) bounded by the configured concurrency level, collect results, and flush them to analytics DB in batches. When `ef.dial-app.enabled=true`, the executor is started from `EvalExecuteInternalController` (triggered by DIAL Core route). When `ef.dial-app.enabled=false`, the executor is started by the legacy async dispatch via `TokenPropagationHelper`.
Status: **Implemented** (core execution), **Planned** (dispatch path branching)

#### Scenario: Sequential execution (default)
- **WHEN** `concurrencyLevel` is 1 (default)
- **THEN** the executor SHALL process test case calls one at a time, in order (page by page, case by case, run index by run index)

#### Scenario: Parallel execution
- **WHEN** `concurrencyLevel` is greater than 1 (e.g., 10)
- **THEN** the executor SHALL process up to `concurrencyLevel` test case calls concurrently using a semaphore-bounded virtual thread executor

#### Scenario: DIAL App mode — executor started from internal endpoint
- **WHEN** `ef.dial-app.enabled=true`
- **AND** `EvalExecuteInternalController` receives a valid trigger for `runId`
- **THEN** `InProcessEvaluationExecutor.execute(context)` SHALL be called on a virtual thread from the controller
- **AND** the JWT propagation path (`TokenPropagationHelper`, `AuthorizationTokenHolder`) SHALL NOT be used

#### Scenario: Legacy mode — executor started with JWT propagation
- **WHEN** `ef.dial-app.enabled=false`
- **THEN** eval execution SHALL be started via `CompletableFuture.supplyAsync` with `TokenPropagationHelper.withToken(jwt, task)` (existing behavior, unchanged)
