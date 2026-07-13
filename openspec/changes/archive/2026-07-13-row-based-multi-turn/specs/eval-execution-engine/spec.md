# Eval Execution Engine

## MODIFIED Requirements

### Requirement: In-process evaluation execution
The `InProcessEvaluationExecutor` SHALL read test inputs from the `test_case_run_inputs` table (populated at snapshot phase) in pages, dispatch execution tasks (**one per conversation per run index**) bounded by the configured concurrency level, collect results, and flush them to analytics DB in batches. For legacy runs without a snapshot (no `test_case_run_inputs` rows), it SHALL fall back to reading live test cases from the suite, treating each live test case as a length-1 (single-turn) conversation. The **execution unit is a CONVERSATION**: one assembled `test_case_run_inputs` row holds the ordered turns of a conversation (a single-turn test case is a length-1 conversation), and one such row maps to exactly one worker task per run index.
Status: **Planned**

#### Scenario: Snapshot path — pages from inputs table
- **WHEN** `test_case_run_inputs` rows exist for the run (snapshot phase committed)
- **THEN** the executor SHALL page through `testCaseRunInputRepository.findByRunId()` instead of `testCaseRepository.findEnabledValidByTestSuiteId()`. The `testCaseRepository` SHALL NOT be called.

#### Scenario: Legacy path — falls back to live test cases
- **WHEN** `test_case_run_inputs` rows do NOT exist for the run (legacy run without snapshot)
- **THEN** the executor SHALL fall back to paging through live test cases from `testCaseRepository.findEnabledValidByTestSuiteId()`, wrapping each `TestCase` as a length-1 conversation `TestCaseRunInput` struct for a uniform worker interface.

#### Scenario: Sequential execution (default)
- **WHEN** `concurrencyLevel` is 1 (default)
- **THEN** the executor SHALL process conversations one at a time, in order (page by page, conversation by conversation, run index by run index); the turns within a conversation always run sequentially regardless of `concurrencyLevel`.

#### Scenario: Parallel execution
- **WHEN** `concurrencyLevel` is greater than 1 (e.g., 10)
- **THEN** the executor SHALL process up to `concurrencyLevel` conversation tasks concurrently using a semaphore-bounded virtual thread executor (one permit per conversation, not per turn).

#### Scenario: One assembled input is one worker task
- **WHEN** the executor dispatches work for the run
- **THEN** it SHALL dispatch exactly one worker task per `(conversation × runIndex)`; the worker task owns the whole conversation and emits one `TestCaseRunResult` per surviving turn.

#### Scenario: All runnable conversations are executed
- **WHEN** the executor runs for a suite with N runnable conversations and `numberOfRuns = M`
- **THEN** the executor SHALL dispatch exactly N * M evaluation tasks (one per conversation per run index 0..M-1)

#### Scenario: Excluded turns and conversations are skipped
- **WHEN** the bound dataset contains test cases with `enabled = false` or `isValid = false`, or conversations excluded/truncated by disable/filter rules
- **THEN** those rows SHALL NOT be dispatched for execution (they are excluded, truncated, or marked broken at snapshot phase — see the snapshot/selection spec)

#### Scenario: Inputs read in pages
- **WHEN** the suite has more runnable conversations than fit in a single page
- **THEN** the executor SHALL read inputs using paginated queries (a page boundary never straddles a conversation)

### Requirement: Single test case evaluation (worker)
The `EvaluationWorker` SHALL accept a `TestCaseRunInput` (a **conversation** carrying one or more ordered, frozen turns plus optional overrides) plus an `EvaluationContext` (carrying snapshot fields), resolve the request(s) without DB reads, invoke the target, extract response columns, and return a **`List<TestCaseRunResult>`** — **one element per surviving turn**. A single-turn conversation (one turn) SHALL return a one-element list. A multi-turn conversation SHALL delegate turn iteration to `MultiTurnConversationExecutor` and return one result per turn, per the multi-turn-conversation spec.
Status: **Planned**

#### Scenario: Single-turn worker returns one result
- **WHEN** a length-1 (single-turn) conversation is dispatched
- **THEN** the worker SHALL return a list containing exactly one `TestCaseRunResult` with `turnIndex = 0`, `totalTurns = 1`

#### Scenario: Multi-turn worker returns one result per turn
- **WHEN** a conversation with `N` ordered turns is dispatched
- **THEN** the worker SHALL delegate to `MultiTurnConversationExecutor` and return `N` results (fewer on early abort — see the multi-turn-conversation fail-fast requirement), each carrying its `turnIndex` (authored, contiguous 0-based prefix) and `totalTurns` (surviving turn count)

#### Scenario: Snapshot-based request resolution
- **WHEN** a conversation turn is resolved for execution
- **THEN** the worker SHALL resolve the request using `ResolvedRequestService.resolve(effectiveTemplate, effectiveBindings, turnData)` where:
  - `effectiveTemplate` = `input.getRequestTemplateOverride()` if non-null, else `context.getSnapshotRequestTemplate()`
  - `effectiveBindings` = `input.getInputBindingsOverride()` if non-null, else `context.getSnapshotInputBindings()`
  - `turnData` = that turn's **scalar** `data` snapshot (deserialized from the assembled input); there is no array projection
  - `deploymentRef` and `endpointRef` read from `context.getSnapshotDeploymentRef()` and `context.getSnapshotEndpointRef()`
  - The worker SHALL NOT call `resolveRequest(suiteId, tcId)` (no DB reads during execution)

### Requirement: Multi-turn execution branch
When the assembled input carries more than one turn, the worker SHALL delegate to `MultiTurnConversationExecutor` and return a **`List<TestCaseRunResult>` with one element per surviving turn**, per the multi-turn-conversation spec. Turns run **strictly sequentially** within the single worker task: each turn re-sends the accumulated chat-completions `messages` history and appends the target's `choices[0].message` before the next turn. A single-turn conversation SHALL return a one-element list; a conversation of `N` authored turns SHALL return up to `N` results (fewer on early abort). Multi-turn is derived from the presence of ordered conversation turns in the assembled input — **not** from a suite-level `multiTurn` flag and **not** from array-valued test-case columns. Each turn's `test_case_data` and `extractedColumns` are **scalar** (one turn's row = the single-turn shape).
Status: **Planned**

#### Scenario: Multi-turn branch returns one result per turn
- **WHEN** a conversation with `N` turns is dispatched
- **THEN** the worker SHALL delegate to `MultiTurnConversationExecutor` and return a `List<TestCaseRunResult>` of up to `N` elements, each carrying its own `turnIndex` (0-based) and `totalTurns` (= surviving turn count)
- **AND** on an abort at turn `k` the list SHALL contain `k` SUCCESS rows plus one ERROR row (fewer than `N`), with `total_turns` = the surviving count

#### Scenario: Turns run sequentially re-sending accumulated messages
- **WHEN** turn `i` (i > 0) of a conversation executes
- **THEN** the request body SHALL carry the accumulated `messages` history (prior user turns plus each prior turn's appended `choices[0].message`), so the target sees the full conversation so far
- **AND** `turn.last` SHALL be true only on the last surviving turn

#### Scenario: No array projection
- **WHEN** a multi-turn conversation executes
- **THEN** turn variation SHALL come from distinct per-turn rows in the assembled input (each with its own scalar `data`), NOT from unwrapping array-valued columns of a single row

### Requirement: Batch result writing
The executor SHALL buffer completed `TestCaseRunResult` records and flush them to the analytics database in configurable row batches, performing exactly one final flush after all virtual threads terminate. Results SHALL be added per conversation via `addResults(buffer, List<TestCaseRunResult>)`: each call buffers all of one conversation's turn rows and increments a **completed-conversation** counter by one (a single-turn conversation is a one-element list; a broken conversation contributes a one-element ERROR list). Progress SHALL be reported as `notifyProgress(conversationsCompleted, totalCases)` where `totalCases = numberOfTestCases * numberOfRuns` and `numberOfTestCases` is the **runnable-conversation** count, keeping progress in the 0–100% range even though a conversation contributes multiple rows.
Status: **Planned**

#### Scenario: Row-batch flush on size
- **WHEN** the buffered row count reaches `result-batch-size` (default 100)
- **THEN** the executor SHALL flush the buffered rows to the analytics DB in an analytics transaction

#### Scenario: Conversation-granular progress
- **WHEN** a 3-turn conversation completes in a run of 5 conversations
- **THEN** three rows SHALL be buffered but the progress numerator SHALL advance by exactly one (e.g. `1/5`), never exceeding `totalCases`

#### Scenario: Single-turn progress unchanged
- **WHEN** a run of 5 standalone single-turn conversations (1 run each) completes
- **THEN** progress SHALL advance `1/5 … 5/5`, identical to prior behavior

#### Scenario: Broken conversation advances progress by one
- **WHEN** a broken conversation is processed and yields a single sentinel ERROR row
- **THEN** the completed-conversation counter SHALL advance by exactly one, the same as any other conversation

#### Scenario: Final flush on completion
- **WHEN** all conversations have been executed (run completes)
- **THEN** the executor SHALL flush any remaining buffered results — exactly once, AFTER worker shutdown has completed

## ADDED Requirements

### Requirement: Runnable-conversation counting and zero-runnable guard
The unit of "runnable" work SHALL be a **CONVERSATION**, not an individual test-case row. A runnable conversation is either a standalone single-turn row or a multi-turn group whose enabled turns form a contiguous prefix `0..k` and pass the suite `testCaseFilter` atomically (see the selection spec). `RunnableTestCaseCounter.countRunnable(...)` SHALL count runnable **conversations**; that count drives `number_of_test_cases` on the run and the zero-runnable guard (guard #4) in `TestSuiteRunService.createRun`. Broken conversations are NOT counted as runnable (they still surface as one ERROR row at execution — see the broken-conversation requirement).
Status: **Planned**

#### Scenario: Runnable count is conversation-granular
- **WHEN** a bound dataset has 2 multi-turn conversations (3 turns and 4 turns) and 5 standalone single-turn rows, all runnable
- **THEN** `RunnableTestCaseCounter.countRunnable(...)` SHALL return 7 (2 conversations + 5 single-turn), NOT 12 (turn rows)
- **AND** the run's `number_of_test_cases` SHALL be set to 7

#### Scenario: Zero-runnable guard on no runnable conversations
- **WHEN** run creation runs guard #4 and the runnable-conversation count is zero (every row disabled/invalid/filtered/broken)
- **THEN** `createRun` SHALL throw `InvalidOperationException("Suite has no valid and enabled test cases")` (→ 409 `INVALID_OPERATION`), preserving the existing guard order (1.not-found 2.unbound 3.config-invalid 4.zero-runnable 5.rate-limits)

#### Scenario: Progress denominator uses conversation count
- **WHEN** the run has `number_of_test_cases = 7` runnable conversations and `numberOfRuns = 2`
- **THEN** `totalCases` for progress SHALL be `14` (7 × 2), and progress advances one per completed conversation × run index

### Requirement: Broken conversation yields one sentinel ERROR row
A conversation that the snapshot phase marked **broken** (missing turn 0, non-contiguous/gap, duplicate `turn_index`, any invalid turn, disable-created middle hole, or surviving turn count > `MAX_CONVERSATION_TURNS`) SHALL be frozen into a marker `test_case_run_inputs` row. At execution the worker SHALL turn that marker into **exactly ONE** `TestCaseRunResult` with `executionStatus = ERROR` and the sentinel `turn_index = 0, total_turns = 0`, **without invoking the model** (no HTTP/MCP call). The run SHALL continue; other conversations proceed normally.
Status: **Planned**

#### Scenario: Broken conversation produces one ERROR row, no model call
- **WHEN** the worker receives an assembled input flagged as a broken conversation
- **THEN** the worker SHALL return a one-element `List<TestCaseRunResult>` with `executionStatus = ERROR`, `turnIndex = 0`, `totalTurns = 0`
- **AND** the worker SHALL NOT resolve or send any deployment/MCP request for that conversation

#### Scenario: Run continues past a broken conversation
- **WHEN** a run contains a broken conversation alongside runnable ones
- **THEN** the broken conversation's single ERROR row SHALL be persisted and the run SHALL still reach `COMPLETED` (the broken row does not mark the whole run FAILED)

### Requirement: MCP suite rejected when dataset contains conversation rows
Multi-turn conversations are **HTTP-deployment only** this round. `TestSuiteRunService.createRun` SHALL reject an `MCP_TOOL` suite bound to a dataset that contains ANY conversation rows (any row with a non-null `conversation_id`) with HTTP 409 `INVALID_OPERATION`. This guard is forward-compatible: it reserves multi-turn tool-call sequences for a later change rather than silently mis-executing them.
Status: **Planned**

#### Scenario: MCP suite with conversation rows rejected at run creation
- **WHEN** `createRun` is called for an `MCP_TOOL` suite whose bound dataset contains at least one row with a non-null `conversation_id`
- **THEN** it SHALL throw `InvalidOperationException` (→ 409 `INVALID_OPERATION`) with a message indicating multi-turn conversations are not supported for MCP suites yet
- **AND** no run SHALL be created and no snapshot SHALL be taken

#### Scenario: MCP suite with only single-turn rows unaffected
- **WHEN** `createRun` is called for an `MCP_TOOL` suite whose bound dataset contains only single-turn rows (all `conversation_id` NULL)
- **THEN** run creation SHALL proceed unchanged

## REMOVED Requirements

### Requirement: Multi-turn array-column projection
**Reason:** The array-per-column multi-turn model (a single test-case row whose bound columns hold arrays, unwrapped per turn by `TurnPlan`/`ConversationTurnPlanner` via `turnPlan.project(data, i)`) is replaced by the row-based model — a conversation is now MULTIPLE rows grouped by `conversation_id` and ordered by `turn_index`, each turn carrying scalar `data`. There is no longer a suite-level `multiTurn` flag, no `SuiteSnapshotDto.multiTurn` field, and no array-projection branch in `MultiTurnConversationExecutor`.
**Migration:** This is an isolated feature branch (the prior array-based multi-turn was never released to production). The branch's existing multi-turn migrations are reshaped in place; `flyway_history` is cleared locally. Datasets express multi-turn by adding per-turn rows with `conversation_id`/`turn_index` instead of array-valued columns. The analytics `turn_index`/`total_turns` result/summary columns are retained. No production data migration is required.

#### Scenario: Array projection is no longer used
- **WHEN** a multi-turn conversation executes
- **THEN** the engine SHALL NOT invoke `TurnPlan`/`ConversationTurnPlanner` or unwrap array-valued bound columns; turn data comes exclusively from distinct scalar per-turn rows in the assembled input

## Implementation Notes
- Executor: `com.epam.aidial.evaluation.service.domain.job.InProcessEvaluationExecutor` — dispatch granularity moves from `(test case × runIndex)` to `(conversation × runIndex)`; one concurrency permit per conversation task.
- Worker: `com.epam.aidial.evaluation.service.domain.job.EvaluationWorker` — accepts a conversation-shaped `TestCaseRunInput`; branches to `MultiTurnConversationExecutor` when the input carries >1 turn; emits the sentinel ERROR row (turn_index=0, total_turns=0) without a model call when the input is flagged broken.
- Multi-turn: `com.epam.aidial.evaluation.service.domain.job.MultiTurnConversationExecutor` — iterates the assembled turns sequentially, re-sending accumulated `messages` and appending `choices[0].message`; `total_turns` = surviving count, `turn_index` = authored prefix, `turn.last` on last surviving turn. The `TurnPlan`/`ConversationTurnPlanner` array-projection path and the suite `multiTurn` flag (incl. `SuiteSnapshotDto.multiTurn` and `SuiteValidationService.validateMultiTurnBody`) are removed.
- Batch writer: `com.epam.aidial.evaluation.service.domain.job.ResultBatchWriter` — `addResults(buffer, List<TestCaseRunResult>)` buffers a conversation's rows and advances the completed-conversation counter by one; progress denominator `totalCases = numberOfTestCases (runnable conversations) × numberOfRuns`.
- Counting: `com.epam.aidial.evaluation.service.domain.job.RunnableTestCaseCounter` — `countRunnable(...)` counts runnable conversations (multi-turn groups + standalone single-turn rows), driving `number_of_test_cases` and guard #4.
- Run creation guards: `com.epam.aidial.evaluation.service.domain.TestSuiteRunService.createRun` — guard order unchanged (1 not-found, 2 unbound, 3 config-invalid, 4 zero-runnable, 5 rate-limits); new guard rejects `MCP_TOOL` suites bound to datasets containing any `conversation_id` row with 409 `INVALID_OPERATION`.
