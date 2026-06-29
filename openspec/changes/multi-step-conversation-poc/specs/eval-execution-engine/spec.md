## ADDED Requirements

### Requirement: Multi-step execution branch
When executing a test case whose snapshot has `multiStep == true`, `EvaluationWorker` SHALL delegate to a dedicated `MultiStepConversationExecutor` that runs the conversation turn loop and returns a single `TestCaseRunResult`. When `multiStep == false`, `EvaluationWorker` SHALL follow the existing single-request path unchanged.
Status: **Planned**

#### Scenario: Worker delegates multi-step conversations
- **WHEN** the worker executes a test case for a suite snapshot with `multiStep == true`
- **THEN** it SHALL delegate the conversation loop to `MultiStepConversationExecutor`
- **AND** it SHALL produce exactly one `TestCaseRunResult` for that `(runId, testCaseId, runIndex)`

#### Scenario: Single-step path unaffected
- **WHEN** the worker executes a test case for a suite snapshot with `multiStep == false`
- **THEN** it SHALL use the existing single-request execution path with no behavior change

### Requirement: One concurrency permit per conversation
A multi-step conversation SHALL execute within a single worker task holding a single concurrency permit for the entire conversation; its steps SHALL run sequentially within that task. Per-step call pacing relies on the existing per-call retry and upstream 429 handling. Each step's retries SHALL reuse the existing retry policy independently.
Status: **Planned**

#### Scenario: Conversation holds one permit
- **WHEN** a multi-step conversation with multiple steps executes
- **THEN** the engine SHALL acquire exactly one concurrency permit for the whole conversation
- **AND** the steps SHALL execute sequentially without acquiring additional permits per step

#### Scenario: Per-step retries reuse existing policy
- **WHEN** a step receives a retryable status (e.g. 429 or 5xx)
- **THEN** that step SHALL be retried per the existing retry policy
- **AND** retry exhaustion SHALL trigger the multi-step fail-fast behavior

### Requirement: Multi-step result carries the last step's trace id
For a multi-step result, the persisted `traceId` SHALL be the trace id of the last attempted step. (Per-step trace correlation is out of scope for the POC.)
Status: **Planned**

#### Scenario: Last step's trace id persisted
- **WHEN** a multi-step conversation attempts several steps
- **THEN** the resulting `TestCaseRunResult.traceId` SHALL be the last attempted step's trace id

### Implementation notes
- New injectable `service.domain.job.MultiStepConversationExecutor`; branch added in `EvaluationWorker.execute` keyed on the snapshot `multiStep` flag.
- Concurrency permit acquisition remains in `InProcessEvaluationExecutor` at the per-(test case, run index) task granularity — multi-step changes only what happens inside the task, not the permit model.
