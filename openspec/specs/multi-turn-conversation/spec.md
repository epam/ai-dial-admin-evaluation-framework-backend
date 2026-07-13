# Multi-Turn Conversation

## Purpose
This spec defines the multi-turn (conversational) evaluation contract for `DEPLOYMENT` suites under the **row-based** model: a conversation is an ordered group of `test_cases` rows (one row per turn) grouped by `conversation_id` and ordered by `turn_index`, not one row whose columns hold arrays. Multi-turn is **emergent** — there is no suite-level `multiTurn` flag and no snapshot `multiTurn` field. It covers how a conversation is one sequential execution unit that reuses the suite's single chat-completions `requestTemplate` and single `inputBindings` across turns, how conversation history accumulates and is re-sent each turn, how assistant replies are extracted, and fail-fast behavior on turn failure. Each turn produces its own `TestCaseRunResult` row, keyed by `turn_index`/`total_turns`; a turn's `test_case_data` and `extractedColumns` are plain objects of scalars (identical in shape to a single-turn result — no column-major arrays), and metric bindings resolve those scalars directly (no JSONata/array turn-selection machinery). The turn loop is owned by `MultiTurnConversationExecutor`, delegated from `EvaluationWorker`.

Status: **Implemented**

## Requirements

### Requirement: Conversation is an ordered group of test-case rows
A multi-turn conversation SHALL be modeled as MULTIPLE `test_cases` rows — one row per turn — grouped by the top-level column `conversation_id` (`VARCHAR(36)`, nullable) and ordered by the top-level column `turn_index` (`INTEGER`, nullable). Both columns live outside the `data` JSONB. A row with both columns NULL is a standalone single-turn test case (backward compatible). A conversation's turns SHALL form a contiguous prefix `0..k` starting at turn `0`; the assembled turn count is the number of surviving turns in that prefix. There is no separate conversation resource — `conversationId`/`turnIndex` are raw fields on the test-case request/response DTOs.
Status: **Implemented**

#### Scenario: Grouped rows form one conversation
- **WHEN** three `test_cases` rows share the same `conversation_id` with `turn_index` 0, 1, 2
- **THEN** the engine SHALL treat them as one conversation of three turns, ordered by `turn_index`

#### Scenario: NULL conversation columns are single-turn
- **WHEN** a `test_cases` row has both `conversation_id` and `turn_index` NULL
- **THEN** the engine SHALL treat it as a standalone single-turn test case (a length-1 conversation)

### Requirement: Multi-turn is emergent — no suite-level flag
Multi-turn behavior SHALL be emergent from the presence of conversation rows in the bound dataset; there SHALL be no suite-level `multiTurn` flag and no `multiTurn` field on the suite snapshot. A suite runs a conversation whenever the selected, runnable rows for a given `conversation_id` number more than one; otherwise it runs a single-turn test case. Suite configuration SHALL NOT gate multi-turn behavior.
Status: **Implemented**

#### Scenario: Same suite runs single- and multi-turn side by side
- **WHEN** a suite's bound dataset contains both standalone single-turn rows and grouped conversation rows
- **THEN** the run SHALL execute the single rows as single-turn test cases and each group as a multi-turn conversation, with no suite flag distinguishing them

#### Scenario: No multiTurn field on the snapshot
- **WHEN** a suite is snapshotted for a run
- **THEN** the snapshot SHALL NOT carry a `multiTurn` field, and no run-creation step SHALL read one

### Requirement: A conversation is one sequential execution unit
Each conversation (including a length-1 single-turn case) SHALL be one execution unit = one worker task per `runIndex`. Within the task, turns SHALL run strictly sequentially in ascending `turn_index`, reusing the accumulated-messages chat-completions executor: the engine maintains a running `messages` history `H` (initially empty), and for each turn `i` it SHALL (1) resolve `requestTemplate` with the suite's single `inputBindings` against that turn's own scalar row `data`; (2) append the resolved body's `messages` to `H`; (3) send the request with its `messages` field overwritten by the full `H`; (4) append the assistant reply — the full `choices[0].message` object, verbatim — to `H`; (5) extract that turn's response columns and persist that turn as its own `TestCaseRunResult`. The full accumulated history MUST be re-sent on every turn. There SHALL be no array projection: each turn's data is a discrete row's scalar `data`, not element `i` of an array column.
Status: **Implemented**

#### Scenario: Two-turn conversation accumulates history
- **WHEN** a conversation of two turns runs and both turns succeed
- **THEN** turn 0 SHALL send `messages` = [turn-0 user message]
- **AND** turn 1 SHALL send `messages` = [turn-0 user, turn-0 assistant, turn-1 user]
- **AND** the full assistant message (`choices[0].message`) from each turn SHALL be appended verbatim to history before the next turn

#### Scenario: Each turn resolves from its own row
- **WHEN** turn 0's row has `data = {question:"hi"}` and turn 1's row has `data = {question:"and then?"}`
- **THEN** turn 0 SHALL resolve the template against `{question:"hi"}` and turn 1 against `{question:"and then?"}`, with no array unwrapping

### Requirement: HTTP-deployment only; MCP suites reject conversation rows
Multi-turn conversations SHALL be supported for `DEPLOYMENT` suites invoked over the chat-completions HTTP path only. An `MCP_TOOL` suite bound to a dataset that contains any conversation rows (any row with a non-NULL `conversation_id`) SHALL be REJECTED at run creation with HTTP 409 `INVALID_OPERATION` and a message indicating multi-turn conversations are not supported for MCP suites yet.
Status: **Implemented**

#### Scenario: MCP suite with conversation rows rejected at run creation
- **WHEN** a run is created for an `MCP_TOOL` suite whose bound dataset contains at least one row with a non-NULL `conversation_id`
- **THEN** run creation SHALL fail with 409 `INVALID_OPERATION` referencing unsupported multi-turn for MCP suites

#### Scenario: HTTP deployment suite proceeds
- **WHEN** a run is created for a `DEPLOYMENT` (chat-completions HTTP) suite with conversation rows
- **THEN** run creation SHALL proceed and each conversation SHALL execute over the HTTP turn loop

### Requirement: Multi-turn conversation contract (chat-completions `messages`)
For a `DEPLOYMENT` suite executing a conversation (a group of more than one runnable row for a `conversation_id`), the request body resolved from `requestTemplate` MUST be JSON containing a top-level `messages` array (chat-completions shape). The engine SHALL derive each turn's new messages from the resolved template body. Bindings are the suite's single `inputBindings` (the same field a single-turn suite uses); per-turn variation comes from each turn's discrete row `data`, not from per-turn binding definitions and not from array-valued columns. Non-chat bodies (multipart, url-encoded, or JSON without a top-level `messages` array) are not supported for multi-turn. There is no run-creation template-capability guard: a template that resolves to a body lacking a top-level `messages` array SHALL fail per-conversation at run time as an ERROR row, not at suite validation.
Status: **Implemented**

#### Scenario: Body without a messages array fails the conversation at run time
- **WHEN** a conversation's resolved `requestTemplate` body has no top-level `messages` array
- **THEN** that conversation SHALL fail at run time as an ERROR result row
- **AND** other conversations in the run SHALL still execute

#### Scenario: Single template and single bindings reused across turns
- **WHEN** a conversation runs across multiple turn rows
- **THEN** the engine SHALL reuse the single, unchanged `requestTemplate` and the single `inputBindings` for every turn
- **AND** the values that differ between turns SHALL come from each turn's discrete row `data`

### Requirement: Per-turn loop with full-history resend
For a conversation, the engine SHALL maintain a running `messages` history `H` (initially empty) and execute its turns sequentially in ascending `turn_index`, for `i` in `0 .. N-1`, where `N` is the surviving turn count of that conversation's assembled ordered rows. For each turn it SHALL: (1) resolve `requestTemplate` with the single `inputBindings` and that turn's own scalar row `data`; (2) append the resolved body's `messages` to `H`; (3) send the request with its `messages` field overwritten by the full `H` (all other body fields as resolved for that turn); (4) append the assistant reply — the full `choices[0].message` object of the response, verbatim — to `H`; (5) extract response columns for that turn and **persist that turn as its own `TestCaseRunResult`** (see the per-turn result requirement). The full accumulated history MUST be re-sent on every turn. There SHALL be no per-field array projection.
Status: **Implemented**

#### Scenario: Two-turn conversation accumulates history
- **WHEN** a conversation of two turn rows runs and both turns succeed
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

### Requirement: Each turn is persisted as its own result row
A multi-turn run SHALL persist one `TestCaseRunResult` per turn, keyed uniquely by `(runId, testCaseId, runIndex, turnIndex)`. Each turn row SHALL carry: `turn_index` = the authored 0-based turn number (from the row's `turn_index`); `total_turns` = the conversation's surviving turn count `N`; `test_case_data` = that turn's own scalar row `data` (no array element projection); `request_body` = the full accumulated request actually sent for that turn (the `messages` history through that turn's user message); `response_body` = that turn's raw response body (technical fields preserved); `extracted_columns` = that turn's scalar object (identical shape to a single-turn result); `extraction_warnings` = that turn's warnings; timing, retry_count, and log_details scoped to that turn; `trace_id` = the shared conversation span id on every turn row. A single-turn (standalone) result SHALL be persisted exactly as before with `turn_index = 0`, `total_turns = 1`.
Status: **Implemented**

#### Scenario: Per-turn scalar data from the row
- **WHEN** turn 0's row has `data = {question:"hi", topic:"geo"}` and turn 1's row has `data = {question:"and then?", topic:"geo"}`
- **THEN** turn 0's `test_case_data` SHALL be `{ "question": "hi", "topic": "geo" }` and turn 1's SHALL be `{ "question": "and then?", "topic": "geo" }`

#### Scenario: Per-turn extracted columns are scalar objects
- **WHEN** a 3-turn conversation with response columns `answer` and `score` completes
- **THEN** each of the three result rows SHALL have `extracted_columns` = a JSON object of scalars (e.g. `{ "answer": "Paris", "score": 0.8 }`), NOT a column-major array

#### Scenario: Single-turn result shape unchanged
- **WHEN** a standalone single-turn test case runs
- **THEN** exactly one result row SHALL be persisted with `turn_index = 0`, `total_turns = 1`, `extracted_columns` a JSON object of scalars, and be otherwise byte-identical to prior single-turn behavior

### Requirement: Fail-fast on turn failure
If any turn fails after retries — a non-2xx final status, a timeout/network error, an oversized (truncated) response, or a 2xx response with no assistant `message` object — the engine SHALL stop the conversation at that turn and SHALL NOT send subsequent turns. Turns completed before the failure SHALL each be persisted as their own SUCCESS `TestCaseRunResult`; the failing turn SHALL be persisted as its own ERROR `TestCaseRunResult` carrying that turn's request/response. All persisted rows of the conversation SHALL carry `total_turns` equal to the conversation's surviving turn count `N`, so a conversation that dies at turn `k < N-1` legitimately has no row at later turn indices.
Status: **Implemented**

#### Scenario: Failure at turn k stops remaining turns
- **WHEN** turn `k` of an `N`-turn conversation fails after exhausting retries
- **THEN** turns `0 .. k-1` SHALL each be persisted as a SUCCESS result with `turn_index = i`, `total_turns = N`
- **AND** turn `k` SHALL be persisted as one ERROR result with `turn_index = k`, `total_turns = N`, its `response_status_code`/`response_body` set to the failing turn's values (or absent when no response was received)
- **AND** turns `k+1 .. N-1` SHALL NOT be sent and SHALL have no rows

#### Scenario: Failure at turn 0 yields a single ERROR row
- **WHEN** a conversation fails at turn 0 (before any turn completes)
- **THEN** exactly one ERROR result SHALL be persisted with `turn_index = 0`, `total_turns = N`, and an empty `extracted_columns` object `{}`

## Implementation Notes
- Turn loop lives in `service.domain.job.MultiTurnConversationExecutor`, delegated from `EvaluationWorker.execute` (which returns a `List<TestCaseRunResult>`, one element per turn). The array-projection branch is removed.
- The array-era classes `service.domain.job.ConversationTurnPlanner` and `TurnPlan` (turn count from array length, `TurnPlan.project(data, i)`) are REMOVED, along with the JSONata/array-binding metric machinery and `SuiteValidationService.validateMultiTurnBody`; the suite `multiTurn` flag and its `SuiteSnapshotDto` field are REMOVED.
- Conversation grouping is established during the snapshot phase: page by distinct `conversation_id` (never straddle a conversation across a page boundary), validate contiguity/completeness, and freeze each conversation into one assembled input holding the ordered turns (each turn: `test_case_id`, `turn_index`, scalar `data` snapshot); single-turn rows are length-1 units. Deterministic conversation ordering (e.g. `min(created_at_ms)`, then `conversation_id`).
- Per-turn invocation reuses `service.domain.job.DeploymentTurnInvoker` (`TurnOutcome`) and the existing retry policy; template resolution reuses `service.domain.ResolvedRequestService.resolve`; per-turn extraction reuses `service.domain.ResponseColumnExtractor.extract`, producing that turn's scalar `extracted_columns` object.
- `turn_index`/`total_turns` columns on `test_case_run_results` and `test_case_eval_summaries` are retained; metric bindings resolve each turn's scalar column value directly. `ConditionContext.turnIndex`/`totalTurns` are sourced from the conversation grouping.
