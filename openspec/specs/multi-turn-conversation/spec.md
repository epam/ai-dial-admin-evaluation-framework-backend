# Multi-Turn Conversation

## Purpose
This spec defines the multi-turn (conversational) evaluation contract for `DEPLOYMENT` suites: how a single chat-completions `requestTemplate` is reused across sequential turns, how conversation history accumulates and is re-sent each turn, how assistant replies are extracted, and fail-fast behavior on turn failure. Each turn produces its own `TestCaseRunResult` row, keyed by `turn_index`/`total_turns`; a turn's `extractedColumns` is a plain object of scalars (identical in shape to a single-turn result — no column-major arrays), and metric bindings resolve those scalars directly (no JSONata/array turn-selection machinery). The turn loop is owned by `MultiTurnConversationExecutor`, delegated from `EvaluationWorker`.

Status: **Implemented**

## Requirements

### Requirement: Multi-turn conversation contract (chat-completions `messages`)
When a `DEPLOYMENT` suite has `multiTurn == true`, the request body resolved from `requestTemplate` MUST be JSON containing a top-level `messages` array (chat-completions shape). The engine SHALL derive each turn's new messages from the resolved template body. Bindings are the suite's single `inputBindings` (the same field a single-turn suite uses); per-turn variation comes from the **data**, not from per-turn binding definitions. Non-chat bodies (multipart, url-encoded, or JSON without a top-level `messages` array) are not supported for multi-turn.
Status: **Implemented**

#### Scenario: Body without a messages array is unsupported
- **WHEN** a suite has `multiTurn == true` and its `requestTemplate` body has no top-level `messages` array
- **THEN** suite validation SHALL mark the suite invalid (see test-suites multi-turn validation)
- **AND** the suite SHALL NOT be runnable as a multi-turn conversation

#### Scenario: Single template and single bindings reused across turns
- **WHEN** a multi-turn suite is configured
- **THEN** the engine SHALL reuse the single, unchanged `requestTemplate` and the single `inputBindings` for every turn
- **AND** the values that differ between turns SHALL come from the per-turn element of the array-valued bound columns in the test case's data

### Requirement: Per-turn loop with full-history resend
For a multi-turn test case, the engine SHALL maintain a running `messages` history `H` (initially empty) and execute turns sequentially for `i` in `0 .. N-1`, where `N` is the turn count derived per test case (see the turn-count requirement). For each turn it SHALL: (1) build the per-turn data by projecting each array-valued bound column to its `i`-th element (leaving scalar columns and `constantValue` bindings unchanged); (2) resolve `requestTemplate` with the single `inputBindings` and that per-turn data; (3) append the resolved body's `messages` to `H`; (4) send the request with its `messages` field overwritten by the full `H` (all other body fields as resolved for that turn); (5) append the assistant reply — the full `choices[0].message` object of the response, verbatim — to `H`; (6) extract response columns for that turn and **persist that turn as its own `TestCaseRunResult`** (see the per-turn result requirement). The full accumulated history MUST be re-sent on every turn.
Status: **Implemented**

#### Scenario: Two-turn conversation accumulates history
- **WHEN** a test case whose array-valued bound column has length 2 runs and both turns succeed
- **THEN** turn 0 SHALL send `messages` = [turn-0 user message]
- **AND** turn 1 SHALL send `messages` = [turn-0 user, turn-0 assistant, turn-1 user]
- **AND** the full assistant message (`choices[0].message`) from each turn SHALL be appended verbatim to history before the next turn

#### Scenario: Template messages represent the new turn only
- **WHEN** a turn's resolved template body contains messages
- **THEN** those messages SHALL be appended verbatim to the running history as that turn's new messages
- **AND** the engine SHALL NOT special-case turn 0 versus later turns

#### Scenario: Each turn is extracted and persisted independently
- **WHEN** a 3-turn conversation completes
- **THEN** the engine SHALL emit three `TestCaseRunResult` rows, one per turn, each with that turn's own extracted columns, request body, and response body

### Requirement: Turn count derived per test case from array-valued bound columns
For a multi-turn suite, the number of turns `N` SHALL be derived per test case from that test case's data: `N` equals the common length of all array-valued columns referenced by the suite's `inputBindings` `dataField`s (when there are no array-valued bound columns, `N` is undefined and the no-array failure scenario applies). Columns whose value is a scalar, and `constantValue` bindings, SHALL be reused (broadcast) on every turn. Because `N` is computed per test case, two test cases in the same suite MAY run different numbers of turns. The engine SHALL cap `N` at `MAX_CONVERSATION_TURNS`. A test-case-level data problem SHALL fail only that test case (result `executionStatus = ERROR` with a descriptive message) while other test cases in the run proceed.
Status: **Implemented**

#### Scenario: Turn count comes from the array column length
- **WHEN** a multi-turn test case binds a template variable to a column whose value is an array of length 3
- **THEN** the engine SHALL run exactly 3 turns, using element `i` of the array on turn `i`

#### Scenario: Different test cases run different turn counts
- **WHEN** one test case's bound array column has length 2 and another's has length 3 in the same suite run
- **THEN** the first SHALL run 2 turns and the second SHALL run 3 turns

#### Scenario: Scalar and constant bindings broadcast across turns
- **WHEN** a multi-turn test case has one array-valued bound column and one scalar bound column (or a `constantValue` binding)
- **THEN** the array column SHALL iterate per turn
- **AND** the scalar column / constant SHALL be used unchanged on every turn

#### Scenario: Mismatched array lengths fail only that test case
- **WHEN** a multi-turn test case has two array-valued bound columns of different lengths
- **THEN** that test case's result SHALL be `ERROR` with a message identifying the mismatch
- **AND** other test cases in the run SHALL still execute

#### Scenario: No array-valued bound column fails only that test case
- **WHEN** a multi-turn test case has no array-valued bound column
- **THEN** that test case's result SHALL be `ERROR` with a descriptive message
- **AND** other test cases in the run SHALL still execute

#### Scenario: Turn count over the cap fails only that test case
- **WHEN** a multi-turn test case's derived `N` exceeds `MAX_CONVERSATION_TURNS`
- **THEN** that test case's result SHALL be `ERROR` with a message referencing the cap
- **AND** other test cases in the run SHALL still execute

### Requirement: Assistant reply extraction (hardcoded OpenAI path, non-streaming)
The engine SHALL append each turn's assistant reply to the running history as the full `choices[0].message` object of the response, verbatim — preserving the fields it contains (e.g. `role`, `content`, `tool_calls`, `refusal`, `reasoning_content`, structured/array `content`, and any provider-specific fields). It SHALL NOT reconstruct a reduced `{ "role": "assistant", "content": <value> }` message from `choices[0].message.content`. The `choices[0].message` path is hardcoded (OpenAI-shaped). Turns SHALL always be invoked non-streaming regardless of any streaming hint. A turn is considered to have no usable reply — aborting the conversation per the fail-fast requirement — only when the response has no `choices[0].message` object (missing `choices`, an empty `choices` array, or a `message` that is not a JSON object); a `message` object without a string `content` (e.g. a tool-call turn) is a valid turn. Because the response body is serialized with the shared `NON_NULL` object mapper before the message is read, a reply whose `content` is JSON `null` is appended with `content` absent (functionally equivalent for resend); all present fields are preserved.
Status: **Implemented**

#### Scenario: Full assistant message appended verbatim
- **WHEN** a turn returns a 2xx response whose `choices[0].message` contains fields beyond `role`/`content` (e.g. `tool_calls`, `refusal`)
- **THEN** the engine SHALL append that `message` object to the running history verbatim, preserving those extra fields

#### Scenario: Tool-call turn without string content is a valid turn
- **WHEN** a turn returns a 2xx response whose `choices[0].message` is a message object with no string `content` (e.g. a tool-call-only message)
- **THEN** the engine SHALL append that `message` object to history and SHALL NOT abort the conversation

#### Scenario: Plain-content message appended verbatim
- **WHEN** a turn returns a 2xx response whose `choices[0].message` is `{ "role": "assistant", "content": "..." }`
- **THEN** the engine SHALL append that `message` object to history verbatim

#### Scenario: Missing message object aborts the conversation
- **WHEN** a turn returns a 2xx response with no `choices[0].message` object (missing `choices`, an empty `choices` array, or a `message` that is not a JSON object)
- **THEN** the engine SHALL treat the turn as failed (history cannot continue)
- **AND** the conversation SHALL abort per the fail-fast requirement

### Requirement: Fail-fast on turn failure
If any turn fails after retries — a non-2xx final status, a timeout/network error, an oversized (truncated) response, or a 2xx response with no assistant `message` object — the engine SHALL stop the conversation at that turn and SHALL NOT send subsequent turns. Turns completed before the failure SHALL each be persisted as their own SUCCESS `TestCaseRunResult`; the failing turn SHALL be persisted as its own ERROR `TestCaseRunResult` carrying that turn's request/response. All rows of the conversation SHALL carry `total_turns` equal to the planned turn count `N` (known upfront), so a conversation that dies before turn `N-1` legitimately has no row at the last turn index.
Status: **Implemented**

#### Scenario: Failure at turn k stops remaining turns
- **WHEN** turn `k` of an `N`-turn conversation fails after exhausting retries
- **THEN** turns `0 .. k-1` SHALL each be persisted as a SUCCESS result with `turn_index = i`, `total_turns = N`
- **AND** turn `k` SHALL be persisted as one ERROR result with `turn_index = k`, `total_turns = N`, its `response_status_code`/`response_body` set to the failing turn's values (or absent when no response was received)
- **AND** turns `k+1 .. N-1` SHALL NOT be sent and SHALL have no rows

#### Scenario: Failure at turn 0 yields a single ERROR row
- **WHEN** a conversation fails at turn 0 (before any turn completes)
- **THEN** exactly one ERROR result SHALL be persisted with `turn_index = 0`, `total_turns = N`, and an empty `extracted_columns` object `{}`

### Requirement: Each turn is persisted as its own result row
A multi-turn run SHALL persist one `TestCaseRunResult` per turn, keyed uniquely by `(runId, testCaseId, runIndex, turnIndex)`. Each turn row SHALL carry: `turn_index` = the 0-based turn number; `total_turns` = the planned turn count `N`; `test_case_data` = the per-turn projected scalar view (element `i` of each array-valued bound column; scalar columns and constants broadcast unchanged); `request_body` = the full accumulated request actually sent for that turn (the `messages` history through that turn's user message); `response_body` = that turn's raw response body (technical fields preserved); `extracted_columns` = that turn's scalar object (identical shape to a single-turn result); `extraction_warnings` = that turn's warnings; timing, retry_count, and log_details scoped to that turn; `trace_id` = the shared conversation span id on every turn row. A single-turn (non-multi-turn) result SHALL be persisted exactly as before with `turn_index = 0`, `total_turns = 1`.
Status: **Implemented**

#### Scenario: Per-turn projected scalar data
- **WHEN** a test case binds a template variable to an array column `["hi","and then?"]` and a scalar column `topic = "geo"`
- **THEN** turn 0's `test_case_data` SHALL contain `{ "<col>": "hi", "topic": "geo" }` and turn 1's SHALL contain `{ "<col>": "and then?", "topic": "geo" }`

#### Scenario: Per-turn extracted columns are scalar objects
- **WHEN** a 3-turn conversation with response columns `answer` and `score` completes
- **THEN** each of the three result rows SHALL have `extracted_columns` = a JSON object of scalars (e.g. `{ "answer": "Paris", "score": 0.8 }`), NOT a column-major array

#### Scenario: Single-turn result shape unchanged
- **WHEN** a suite has `multiTurn == false`
- **THEN** exactly one result row SHALL be persisted with `turn_index = 0`, `total_turns = 1`, `extracted_columns` a JSON object of scalars, and be otherwise byte-identical to prior behavior

### Requirement: Data-shape failure yields a single degenerate ERROR row
When a multi-turn test case cannot produce any turn — no array-valued bound column, mismatched array lengths across bound columns, or a derived turn count exceeding `MAX_CONVERSATION_TURNS` — the engine SHALL persist exactly one ERROR `TestCaseRunResult` with `turn_index = 0` and `total_turns = 0` (distinguishing "conversation never started" from a real single turn, which is `0/1`) and a descriptive message. Other test cases in the run SHALL proceed. Because non-SUCCESS rows skip metric/condition evaluation, the `0/0` values never reach condition logic.
Status: **Implemented**

#### Scenario: No array-valued bound column
- **WHEN** a multi-turn test case has no array-valued bound column
- **THEN** exactly one ERROR result SHALL be persisted with `turn_index = 0`, `total_turns = 0`, and a descriptive message
- **AND** other test cases in the run SHALL still execute

#### Scenario: Mismatched array lengths
- **WHEN** a multi-turn test case has two array-valued bound columns of different lengths
- **THEN** exactly one ERROR result SHALL be persisted with `turn_index = 0`, `total_turns = 0`, and a message identifying the mismatch

#### Scenario: Turn count over the cap
- **WHEN** a multi-turn test case's derived `N` exceeds `MAX_CONVERSATION_TURNS`
- **THEN** exactly one ERROR result SHALL be persisted with `turn_index = 0`, `total_turns = 0`, and a message referencing the cap

## Implementation Notes
- Turn loop lives in `service.domain.job.MultiTurnConversationExecutor`, delegated to from `EvaluationWorker.execute` (which returns a `List<TestCaseRunResult>`, one element per turn).
- `service.domain.job.ConversationTurnPlanner` derives the per-test-case `TurnPlan` (turn count `N`, iterating fields; `TurnPlan.project(data, i)` builds each turn's scalar view). A mismatch / empty-array / over-cap plan is an error plan, surfaced as the single degenerate `0/0` ERROR row.
- Per-turn invocation is handled by `service.domain.job.DeploymentTurnInvoker`, returning a `TurnOutcome` (final status, HTTP status code, raw response body, retry count) reusing the existing retry policy.
- Template resolution reuses `service.domain.ResolvedRequestService.resolve`; per-turn extraction reuses `service.domain.ResponseColumnExtractor.extract`, producing that turn's scalar `extracted_columns` object and its extraction warnings (identical shape to the single-turn path — no per-turn index tag, no column-major transposition).
- There is **no** column-major array shape and **no** metric-boundary normalization: each turn row already stores scalar `extracted_columns`, so metric bindings resolve the scalar column value directly (see metric-evaluation binding-resolution). The former `ExtractedColumnsNormalizer` and the `stepIndex`-tagged warning aggregation were removed.
