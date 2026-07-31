## MODIFIED Requirements

### Requirement: Single test case evaluation (worker)
The `EvaluationWorker` SHALL accept a `TestCaseRunInput` (carrying frozen test case data and optional overrides) plus an `EvaluationContext` (carrying snapshot fields). It SHALL resolve the request body using snapshot fields from context + test case data + per-case overrides (no DB read), call the target deployment endpoint, capture the response (including streaming), extract response columns, and build a `TestCaseRunResult`. Resolution SHALL be performed by injecting `RequestResolver` (from `evaluation-runner-core`) — not `ResolvedRequestService` (which remains in the EF backend and carries DB dependencies for the Try-It-Out path). `EvaluationWorker` itself now resides in the shared module, under package `com.epam.aidial.evaluation.runner.job`.

Status: **Planned**

#### Scenario: Snapshot-based request resolution via RequestResolver
- **WHEN** a test case is dispatched for execution
- **THEN** the worker SHALL resolve the full request using `requestResolver.resolve(effectiveTemplate, effectiveBindings, testCaseData)` where:
  - `effectiveTemplate` = `input.getRequestTemplateOverride()` if non-null, else `context.getSnapshotRequestTemplate()`
  - `effectiveBindings` = `input.getInputBindingsOverride()` if non-null, else `context.getSnapshotInputBindings()`
  - `testCaseData` = deserialized from `input.getTestCaseData()`
  - `deploymentRef` and `endpointRef` are read from `context.getSnapshotDeploymentRef()` and `context.getSnapshotEndpointRef()`
  - The worker SHALL NOT call `resolveRequest(suiteId, tcId)` (no DB reads during execution) and SHALL NOT call `ResolvedRequestService.resolve(...)` — that method no longer exists; the equivalent logic lives on the injected `RequestResolver`

#### Scenario: EvaluationWorker injects RequestResolver, not ResolvedRequestService
- **WHEN** the shared module's `EvaluationWorker` bean is created
- **THEN** its constructor SHALL accept `RequestResolver` (from `runner.service`) as the resolution dependency — not `ResolvedRequestService` (which is an EF backend class)

#### Scenario: Package paths corrected for evaluation-runner-core move
- **WHEN** locating the classes referenced by this spec's "Implementation Notes" section (baseline `eval-execution-engine/spec.md`)
- **THEN** the following corrected fully-qualified names apply (superseding the baseline's now-stale paths):
  - `EvaluationWorker`: `com.epam.aidial.evaluation.runner.job.EvaluationWorker` (was `com.epam.aidial.evaluation.service.domain.job.EvaluationWorker`)
  - `EvaluationContext`: `com.epam.aidial.evaluation.runner.job.EvaluationContext` (was `com.epam.aidial.evaluation.service.domain.job.EvaluationContext`)
  - `StreamingResponseAccumulator`: `com.epam.aidial.evaluation.runner.job.StreamingResponseAccumulator` (was `com.epam.aidial.evaluation.service.domain.job.StreamingResponseAccumulator`)
  - `TestCaseRunResultFactory`: `com.epam.aidial.evaluation.runner.job.TestCaseRunResultFactory` (was `com.epam.aidial.evaluation.service.domain.job.TestCaseRunResultFactory`)
  - `ResultBatchWriter`: the name now denotes an interface at `com.epam.aidial.evaluation.runner.job.ResultBatchWriter` (`addResults(List<TestCaseRunResult>)`, `flush()`). The baseline's concrete `com.epam.aidial.evaluation.service.domain.job.ResultBatchWriter` class and its `ResultBatchWriterTransactional` collaborator no longer exist; they are replaced by `com.epam.aidial.evaluation.service.domain.job.PostgresResultBatchWriterFactory` (Spring bean, creates one writer per run) and `com.epam.aidial.evaluation.service.domain.job.PostgresResultBatchWriter` (plain per-run instance, not a Spring bean), both in the EF backend, implementing the shared interface
  - `EvaluationRunProperties`: `com.epam.aidial.evaluation.runner.config.properties.EvaluationRunProperties` (was `com.epam.aidial.evaluation.configuration.properties.testsuite.EvaluationRunProperties`)
  - `DeploymentInvocationResult`: `com.epam.aidial.evaluation.runner.client.dialcore.DeploymentInvocationResult` (was `com.epam.aidial.evaluation.client.dialcore.DeploymentInvocationResult`)
  - `EvaluationExecutor`, `InProcessEvaluationExecutor`, `ExecutionSettingsValidator` remain unmoved, in `com.epam.aidial.evaluation.service.domain.job` (EF backend) — these classes have DB dependencies and were not part of the shared-module extraction

## ADDED Requirements

### Requirement: EvaluationWorker resides in `evaluation-runner-core`
The `EvaluationWorker` class SHALL reside in the `evaluation-runner-core` module under package `com.epam.aidial.evaluation.runner.job`. The EF backend's `InProcessEvaluationExecutor` SHALL inject it from the shared module.

Status: **Planned**

#### Scenario: EvaluationWorker is a shared-module bean
- **WHEN** the EF backend application context starts
- **THEN** the `EvaluationWorker` bean SHALL originate from `evaluation-runner-core` (via `EvaluationRunnerAutoConfiguration`) and be injectable into `InProcessEvaluationExecutor` without the EF backend declaring it as a bean

#### Scenario: EvaluationWorker has no EF backend import
- **WHEN** ArchUnit's `RunnerModuleConstraintsTest` is run in the shared module
- **THEN** `EvaluationWorker` SHALL have no import from `com.epam.aidial.evaluation` (the EF backend package)

### Requirement: TestCaseRunResult type used by EvaluationWorker comes from `evaluation-runner-core`
The `TestCaseRunResult` type produced by `EvaluationWorker.execute(...)` and consumed by `InProcessEvaluationExecutor` and the EF backend's `PostgresResultBatchWriter` (implementing the shared module's `ResultBatchWriter` interface) SHALL be `com.epam.aidial.evaluation.runner.model.TestCaseRunResult`. The EF backend's analytics `TestCaseRunResultRecordMapper` SHALL map this shared type to the jOOQ-generated `TestCaseRunResultsRecord`.

Status: **Planned**

#### Scenario: EvaluationWorker returns shared TestCaseRunResult
- **WHEN** `EvaluationWorker.execute(input, context, runIndex, responseColumns, traceId, execStartedAtMs)` completes
- **THEN** it SHALL return `List<com.epam.aidial.evaluation.runner.model.TestCaseRunResult>`

#### Scenario: InProcessEvaluationExecutor consumes shared TestCaseRunResult
- **WHEN** `InProcessEvaluationExecutor` collects results from `EvaluationWorker` (via `TestCaseRunner`)
- **THEN** it SHALL pass them to a `com.epam.aidial.evaluation.runner.job.ResultBatchWriter` instance (created per-run by `PostgresResultBatchWriterFactory`) whose `addResults(List<TestCaseRunResult>)` parameter is `List<com.epam.aidial.evaluation.runner.model.TestCaseRunResult>`

#### Scenario: Analytics RecordMapper maps from shared model
- **WHEN** `TestCaseRunResultRecordMapper.from(TestCaseRunResult result)` is called in the EF backend
- **THEN** it SHALL produce a `TestCaseRunResultsRecord` by reading fields from `com.epam.aidial.evaluation.runner.model.TestCaseRunResult` — no data conversion, only field mapping
