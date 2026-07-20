# Multi-Turn

## Purpose
This spec defines the multi-turn (multiTurnal) evaluation contract for `DEPLOYMENT` suites. A multi-turn is an ordered group of `test_cases` rows (one row per turn) grouped by `multi_turn_id` and ordered by `turn_index`. Multi-turn is **emergent** from the presence of grouped rows — there is no suite-level flag. A multi-turn is one sequential execution unit that reuses the suite's single chat-completions `requestTemplate` and `inputBindings` across turns, accumulating and re-sending the multi-turn history each turn. Each turn produces its own `TestCaseRunResult` (keyed by `turn_index`/`total_turns`) whose `test_case_data` and `extractedColumns` are scalar objects identical in shape to a single-turn result; metric bindings resolve those scalars directly. The turn loop is owned by `MultiTurnExecutor`, delegated from `EvaluationWorker`.

Status: **Implemented**

## Requirements

### Requirement: Multi-turn is an ordered group of test-case rows
A multi-turn SHALL be modeled as MULTIPLE `test_cases` rows — one row per turn — grouped by the top-level column `multi_turn_id` (`VARCHAR(36)`, nullable) and ordered by the top-level column `turn_index` (`INTEGER`, nullable). Both columns live outside the `data` JSONB. A row with both columns NULL is a standalone single-turn test case (backward compatible). A multi-turn's turns SHALL form a contiguous prefix `0..k` starting at turn `0`; the assembled turn count is the number of surviving turns in that prefix. There is no separate multi-turn resource — `multiTurnId`/`turnIndex` are raw fields on the test-case request/response DTOs.
Status: **Implemented**

#### Scenario: Grouped rows form one multi-turn
- **WHEN** three `test_cases` rows share the same `multi_turn_id` with `turn_index` 0, 1, 2
- **THEN** the engine SHALL treat them as one multi-turn of three turns, ordered by `turn_index`

#### Scenario: NULL multi-turn columns are single-turn
- **WHEN** a `test_cases` row has both `multi_turn_id` and `turn_index` NULL
- **THEN** the engine SHALL treat it as a standalone single-turn test case (a length-1 multi-turn)

### Requirement: Multi-turn is emergent — no suite-level flag
Multi-turn behavior SHALL be emergent from the presence of multi-turn rows in the bound dataset; there SHALL be no suite-level `multiTurn` flag and no `multiTurn` field on the suite snapshot. A suite runs a multi-turn whenever the selected, runnable rows for a given `multi_turn_id` number more than one; otherwise it runs a single-turn test case. Suite configuration SHALL NOT gate multi-turn behavior.
Status: **Implemented**

#### Scenario: Same suite runs single- and multi-turn side by side
- **WHEN** a suite's bound dataset contains both standalone single-turn rows and grouped multi-turn rows
- **THEN** the run SHALL execute the single rows as single-turn test cases and each group as a multi-turn, with no suite flag distinguishing them

#### Scenario: No multiTurn field on the snapshot
- **WHEN** a suite is snapshotted for a run
- **THEN** the snapshot SHALL NOT carry a `multiTurn` field, and no run-creation step SHALL read one

### Requirement: A multi-turn is one sequential execution unit with full-history resend
Each multi-turn (including a length-1 single-turn case) SHALL be one execution unit = one worker task per `runIndex`. The engine SHALL maintain a running `messages` history `H` (initially empty) and execute the multi-turn's turns strictly sequentially in ascending `turn_index`, for `i` in `0 .. N-1`, where `N` is the surviving turn count. For each turn it SHALL: (1) resolve `requestTemplate` with the suite's single `inputBindings` against that turn's own scalar row `data`; (2) append the resolved body's `messages` to `H`; (3) send the request with its `messages` field overwritten by the full `H` (all other body fields as resolved for that turn); (4) append the assistant reply — the full `choices[0].message` object, verbatim — to `H`; (5) extract that turn's response columns and persist that turn as its own `TestCaseRunResult` (see the per-turn result requirement). The full accumulated history MUST be re-sent on every turn. Per-turn variation comes from each turn's discrete row `data`, not from per-turn binding definitions.
Status: **Implemented**

#### Scenario: Two-turn multi-turn accumulates history
- **WHEN** a multi-turn of two turns runs and both turns succeed
- **THEN** turn 0 SHALL send `messages` = [turn-0 user message]
- **AND** turn 1 SHALL send `messages` = [turn-0 user, turn-0 assistant, turn-1 user]
- **AND** the full assistant message (`choices[0].message`) from each turn SHALL be appended verbatim to history before the next turn

#### Scenario: Each turn resolves from its own row
- **WHEN** turn 0's row has `data = {question:"hi"}` and turn 1's row has `data = {question:"and then?"}`
- **THEN** turn 0 SHALL resolve the template against `{question:"hi"}` and turn 1 against `{question:"and then?"}`

#### Scenario: Template messages represent the new turn only
- **WHEN** a turn's resolved template body contains messages
- **THEN** those messages SHALL be appended verbatim to the running history as that turn's new messages
- **AND** the engine SHALL NOT special-case turn 0 versus later turns

#### Scenario: Each turn is extracted and persisted independently
- **WHEN** a 3-turn multi-turn completes
- **THEN** the engine SHALL emit three `TestCaseRunResult` rows, one per turn, each with that turn's own extracted columns, request body, and response body

### Requirement: HTTP-deployment only; MCP suites reject multi-turn rows
Multi-turns SHALL be supported for `DEPLOYMENT` suites invoked over the chat-completions HTTP path only. An `MCP_TOOL` suite bound to a dataset that contains any multi-turn rows (any row with a non-NULL `multi_turn_id`) SHALL be REJECTED at run creation with HTTP 409 `INVALID_OPERATION` and a message indicating multi-turns are not supported for MCP suites yet.
Status: **Implemented**

#### Scenario: MCP suite with multi-turn rows rejected at run creation
- **WHEN** a run is created for an `MCP_TOOL` suite whose bound dataset contains at least one row with a non-NULL `multi_turn_id`
- **THEN** run creation SHALL fail with 409 `INVALID_OPERATION` referencing unsupported multi-turn for MCP suites

#### Scenario: HTTP deployment suite proceeds
- **WHEN** a run is created for a `DEPLOYMENT` (chat-completions HTTP) suite with multi-turn rows
- **THEN** run creation SHALL proceed and each multi-turn SHALL execute over the HTTP turn loop

### Requirement: Multi-turn contract (chat-completions `messages`)
For a `DEPLOYMENT` suite executing a multi-turn (a group of more than one runnable row for a `multi_turn_id`), the request body resolved from `requestTemplate` MUST be JSON containing a top-level `messages` array (chat-completions shape). The engine SHALL derive each turn's new messages from the resolved template body. Bindings are the suite's single `inputBindings` (the same field a single-turn suite uses); per-turn variation comes from each turn's discrete row `data`, not from per-turn binding definitions. Non-chat bodies (multipart, url-encoded, or JSON without a top-level `messages` array) are not supported for multi-turn. There is no run-creation template-capability guard: a template that resolves to a body lacking a top-level `messages` array SHALL fail per-multi-turn at run time as an ERROR row, not at suite validation.
Status: **Implemented**

#### Scenario: Body without a messages array fails the multi-turn at run time
- **WHEN** a multi-turn's resolved `requestTemplate` body has no top-level `messages` array
- **THEN** that multi-turn SHALL fail at run time as an ERROR result row
- **AND** other multi-turns in the run SHALL still execute

#### Scenario: Single template and single bindings reused across turns
- **WHEN** a multi-turn runs across multiple turn rows
- **THEN** the engine SHALL reuse the single, unchanged `requestTemplate` and the single `inputBindings` for every turn
- **AND** the values that differ between turns SHALL come from each turn's discrete row `data`

### Requirement: Assistant reply extraction (hardcoded OpenAI path, non-streaming)
The engine SHALL append each turn's assistant reply to the running history as the full `choices[0].message` object of the response, verbatim — preserving the fields it contains (e.g. `role`, `content`, `tool_calls`, `refusal`, `reasoning_content`, structured/array `content`, and any provider-specific fields). It SHALL NOT reconstruct a reduced `{ "role": "assistant", "content": <value> }` message from `choices[0].message.content`. The `choices[0].message` path is hardcoded (OpenAI-shaped). Turns SHALL always be invoked non-streaming regardless of any streaming hint. A turn is considered to have no usable reply — aborting the multi-turn per the fail-fast requirement — only when the response has no `choices[0].message` object (missing `choices`, an empty `choices` array, or a `message` that is not a JSON object); a `message` object without a string `content` (e.g. a tool-call turn) is a valid turn. Because the response body is serialized with the shared `NON_NULL` object mapper before the message is read, a reply whose `content` is JSON `null` is appended with `content` absent (functionally equivalent for resend); all present fields are preserved.
Status: **Implemented**

#### Scenario: Full assistant message appended verbatim
- **WHEN** a turn returns a 2xx response whose `choices[0].message` contains fields beyond `role`/`content` (e.g. `tool_calls`, `refusal`)
- **THEN** the engine SHALL append that `message` object to the running history verbatim, preserving those extra fields

#### Scenario: Tool-call turn without string content is a valid turn
- **WHEN** a turn returns a 2xx response whose `choices[0].message` is a message object with no string `content` (e.g. a tool-call-only message)
- **THEN** the engine SHALL append that `message` object to history and SHALL NOT abort the multi-turn

#### Scenario: Plain-content message appended verbatim
- **WHEN** a turn returns a 2xx response whose `choices[0].message` is `{ "role": "assistant", "content": "..." }`
- **THEN** the engine SHALL append that `message` object to history verbatim

#### Scenario: Missing message object aborts the multi-turn
- **WHEN** a turn returns a 2xx response with no `choices[0].message` object (missing `choices`, an empty `choices` array, or a `message` that is not a JSON object)
- **THEN** the engine SHALL treat the turn as failed (history cannot continue)
- **AND** the multi-turn SHALL abort per the fail-fast requirement

### Requirement: Each turn is persisted as its own result row
A multi-turn run SHALL persist one `TestCaseRunResult` per turn, keyed uniquely by `(runId, testCaseId, runIndex, turnIndex)`. Each turn row SHALL carry: `turn_index` = the authored 0-based turn number (from the row's `turn_index`); `total_turns` = the multi-turn's surviving turn count `N`; `test_case_data` = that turn's own scalar row `data`; `request_body` = the full accumulated request actually sent for that turn (the `messages` history through that turn's user message); `response_body` = that turn's raw response body (technical fields preserved); `extracted_columns` = that turn's scalar object (identical shape to a single-turn result); `extraction_warnings` = that turn's warnings; timing, retry_count, and log_details scoped to that turn; `trace_id` = the shared multi-turn span id on every turn row. A single-turn (standalone) result SHALL be persisted exactly as before with `turn_index = 0`, `total_turns = 1`.
Status: **Implemented**

#### Scenario: Per-turn scalar data from the row
- **WHEN** turn 0's row has `data = {question:"hi", topic:"geo"}` and turn 1's row has `data = {question:"and then?", topic:"geo"}`
- **THEN** turn 0's `test_case_data` SHALL be `{ "question": "hi", "topic": "geo" }` and turn 1's SHALL be `{ "question": "and then?", "topic": "geo" }`

#### Scenario: Per-turn extracted columns are scalar objects
- **WHEN** a 3-turn multi-turn with response columns `answer` and `score` completes
- **THEN** each of the three result rows SHALL have `extracted_columns` = a JSON object of scalars (e.g. `{ "answer": "Paris", "score": 0.8 }`)

#### Scenario: Single-turn result shape unchanged
- **WHEN** a standalone single-turn test case runs
- **THEN** exactly one result row SHALL be persisted with `turn_index = 0`, `total_turns = 1`, `extracted_columns` a JSON object of scalars, and be otherwise byte-identical to prior single-turn behavior

### Requirement: Fail-fast on turn failure
If any turn fails after retries — a non-2xx final status, a timeout/network error, an oversized (truncated) response, or a 2xx response with no assistant `message` object — the engine SHALL stop the multi-turn at that turn and SHALL NOT send subsequent turns. Turns completed before the failure SHALL each be persisted as their own SUCCESS `TestCaseRunResult`; the failing turn SHALL be persisted as its own ERROR `TestCaseRunResult` carrying that turn's request/response. All persisted rows of the multi-turn SHALL carry `total_turns` equal to the multi-turn's surviving turn count `N`, so a multi-turn that dies at turn `k < N-1` legitimately has no row at later turn indices.
Status: **Implemented**

#### Scenario: Failure at turn k stops remaining turns
- **WHEN** turn `k` of an `N`-turn multi-turn fails after exhausting retries
- **THEN** turns `0 .. k-1` SHALL each be persisted as a SUCCESS result with `turn_index = i`, `total_turns = N`
- **AND** turn `k` SHALL be persisted as one ERROR result with `turn_index = k`, `total_turns = N`, its `response_status_code`/`response_body` set to the failing turn's values (or absent when no response was received)
- **AND** turns `k+1 .. N-1` SHALL NOT be sent and SHALL have no rows

#### Scenario: Failure at turn 0 yields a single ERROR row
- **WHEN** a multi-turn fails at turn 0 (before any turn completes)
- **THEN** exactly one ERROR result SHALL be persisted with `turn_index = 0`, `total_turns = N`, and an empty `extracted_columns` object `{}`

## Implementation Notes
- Turn loop lives in `service.domain.job.MultiTurnExecutor`, delegated from `EvaluationWorker.execute` (which returns a `List<TestCaseRunResult>`, one element per turn).
- Multi-turn grouping is established during the snapshot phase: page by distinct `multi_turn_id` (never straddle a multi-turn across a page boundary), validate contiguity/completeness, and freeze each multi-turn into one assembled input holding the ordered turns (each turn: `test_case_id`, `turn_index`, scalar `data` snapshot); single-turn rows are length-1 units. Deterministic multi-turn ordering (e.g. `min(created_at_ms)`, then `multi_turn_id`).
- Per-turn invocation reuses `service.domain.job.DeploymentTurnInvoker` (`TurnOutcome`) and the existing retry policy; template resolution reuses `service.domain.ResolvedRequestService.resolve`; per-turn extraction reuses `service.domain.ResponseColumnExtractor.extract`, producing that turn's scalar `extracted_columns` object.
- `turn_index`/`total_turns` columns on `test_case_run_results` and `test_case_eval_summaries` are retained; metric bindings resolve each turn's scalar column value directly. `ConditionContext.turnIndex`/`totalTurns` are sourced from the multi-turn grouping.
