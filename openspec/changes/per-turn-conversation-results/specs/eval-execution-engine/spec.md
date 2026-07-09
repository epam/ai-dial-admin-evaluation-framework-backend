## MODIFIED Requirements

### Requirement: Single test case evaluation (worker)
The `EvaluationWorker` SHALL accept a `TestCaseRunInput` (carrying frozen test case data and optional overrides) plus an `EvaluationContext` (carrying snapshot fields), resolve the request without DB reads, invoke the target, extract response columns, and return a **`List<TestCaseRunResult>`**. A single-turn (non-multi-turn) execution SHALL return a one-element list. A multi-turn execution SHALL delegate to `MultiTurnConversationExecutor` (renamed from `MultiStepConversationExecutor`) and return one result per turn, per the multi-step-conversation spec.
Status: **Planned**

#### Scenario: Single-turn worker returns one result
- **WHEN** a non-multi-turn test case is dispatched
- **THEN** the worker SHALL return a list containing exactly one `TestCaseRunResult` with `turnIndex = 0`, `totalTurns = 1`

#### Scenario: Multi-turn worker returns one result per turn
- **WHEN** a multi-turn test case with `N` turns is dispatched
- **THEN** the worker SHALL delegate to `MultiTurnConversationExecutor` and return `N` results (fewer on early abort — see the multi-step-conversation fail-fast requirement), each carrying its `turnIndex` and `totalTurns`

#### Scenario: Snapshot-based request resolution (unchanged)
- **WHEN** a test case is dispatched
- **THEN** the worker SHALL resolve the request via `ResolvedRequestService.resolve(effectiveTemplate, effectiveBindings, testCaseData)` using snapshot fields and per-case overrides, with no DB reads during execution

### Requirement: Batch result writing
The executor SHALL buffer completed `TestCaseRunResult` records and flush them to the analytics database in configurable row batches, performing exactly one final flush after all virtual threads terminate. Results SHALL be added per conversation via `addResults(buffer, List<TestCaseRunResult>)`: each call buffers all of one conversation's turn rows and increments a **completed-conversation** counter by one (a single-turn conversation is a one-element list). Progress SHALL be reported as `notifyProgress(conversationsCompleted, totalCases)` where `totalCases = numberOfTestCases * numberOfRuns`, keeping progress in the 0–100% range even though a conversation contributes multiple rows.
Status: **Planned**

#### Scenario: Row-batch flush on size
- **WHEN** the buffered row count reaches `result-batch-size` (default 100)
- **THEN** the executor SHALL flush the buffered rows to the analytics DB in an analytics transaction

#### Scenario: Conversation-granular progress
- **WHEN** a 3-turn conversation completes in a run of 5 conversations
- **THEN** three rows SHALL be buffered but the progress numerator SHALL advance by exactly one (e.g. `1/5`), never exceeding `totalCases`

#### Scenario: Single-turn progress unchanged
- **WHEN** a non-multi-turn run of 5 test cases (1 run each) completes
- **THEN** progress SHALL advance `1/5 … 5/5`, identical to prior behavior

#### Scenario: Final flush on completion
- **WHEN** all conversations have executed
- **THEN** the executor SHALL flush any remaining buffered rows exactly once, after worker shutdown completes

### Requirement: Multi-step execution branch
When the snapshot indicates a multi-turn suite, the worker SHALL delegate to `MultiTurnConversationExecutor` (renamed from `MultiStepConversationExecutor`) and return a **`List<TestCaseRunResult>` with one element per turn**, per the multi-step-conversation spec. A single-turn (non-multi-turn) execution SHALL return a one-element list; a multi-turn execution SHALL return `N` results for a conversation of `N` turns (fewer on early abort — see the multi-step-conversation fail-fast requirement). The engine no longer collapses a conversation into a single aggregated row.
Status: **Planned**

#### Scenario: Multi-turn branch returns one result per turn
- **WHEN** a multi-turn test case with `N` turns is dispatched
- **THEN** the worker SHALL delegate to `MultiTurnConversationExecutor` and return a `List<TestCaseRunResult>` of `N` elements, each carrying its own `turnIndex` (0-based) and `totalTurns = N`
- **AND** on an abort at turn `k` the list SHALL contain `k` SUCCESS rows plus one ERROR row (fewer than `N`)

### Requirement: Multi-step result carries the last step's trace id
Each per-turn `TestCaseRunResult` of a multi-turn conversation SHALL carry the **shared conversation trace id** — the single conversation span id — on every turn row (not "the last attempted step's trace id"). Because a conversation now produces one row per turn rather than one aggregated row, the trace id is not collapsed to a single final value; every turn row references the same conversation span, per the multi-step-conversation "Each turn is persisted as its own result row" requirement.
Status: **Planned**

#### Scenario: Every turn row carries the shared conversation trace id
- **WHEN** a 3-turn conversation completes with conversation span id `T`
- **THEN** each of the three `TestCaseRunResult` rows SHALL have `trace_id = T`
- **AND** an abort at turn `k` SHALL still stamp `trace_id = T` on all persisted rows (the completed SUCCESS rows and the failing ERROR row)
