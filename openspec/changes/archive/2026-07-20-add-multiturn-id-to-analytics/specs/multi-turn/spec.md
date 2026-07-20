## MODIFIED Requirements

### Requirement: Each turn is persisted as its own result row
A multi-turn run SHALL persist one `TestCaseRunResult` per turn, keyed uniquely by `(runId, testCaseId, runIndex, turnIndex)`. Each turn row SHALL carry: `turn_index` = the authored 0-based turn number (from the row's `turn_index`); `total_turns` = the multi-turn's surviving turn count `N`; `multi_turn_id` = the id of the originating multi-turn (shared by every turn row of the multi-turn; NULL only for a standalone single-turn result); `test_case_data` = that turn's own scalar row `data`; `request_body` = the full accumulated request actually sent for that turn (the `messages` history through that turn's user message); `response_body` = that turn's raw response body (technical fields preserved); `extracted_columns` = that turn's scalar object (identical shape to a single-turn result); `extraction_warnings` = that turn's warnings; timing, retry_count, and log_details scoped to that turn; `trace_id` = the shared multi-turn span id on every turn row. A single-turn (standalone) result SHALL be persisted exactly as before with `turn_index = 0`, `total_turns = 1`, and `multi_turn_id = NULL`.
Status: **Implemented**

#### Scenario: Per-turn scalar data from the row
- **WHEN** turn 0's row has `data = {question:"hi", topic:"geo"}` and turn 1's row has `data = {question:"and then?", topic:"geo"}`
- **THEN** turn 0's `test_case_data` SHALL be `{ "question": "hi", "topic": "geo" }` and turn 1's SHALL be `{ "question": "and then?", "topic": "geo" }`

#### Scenario: Per-turn extracted columns are scalar objects
- **WHEN** a 3-turn multi-turn with response columns `answer` and `score` completes
- **THEN** each of the three result rows SHALL have `extracted_columns` = a JSON object of scalars (e.g. `{ "answer": "Paris", "score": 0.8 }`)

#### Scenario: All turn rows carry the shared multi_turn_id
- **WHEN** a 3-turn multi-turn with source multi-turn id `M` completes
- **THEN** all three result rows SHALL carry `multi_turn_id = M`, and the API SHALL expose it as `multiTurnId` on each row so a client can group them without relying on `trace_id`

#### Scenario: Single-turn result shape unchanged
- **WHEN** a standalone single-turn test case runs
- **THEN** exactly one result row SHALL be persisted with `turn_index = 0`, `total_turns = 1`, `multi_turn_id = NULL` (and the response DTO omits `multiTurnId`), `extracted_columns` a JSON object of scalars, and be otherwise byte-identical to prior single-turn behavior

### Requirement: Fail-fast on turn failure
If any turn fails after retries — a non-2xx final status, a timeout/network error, an oversized (truncated) response, or a 2xx response with no assistant `message` object — the engine SHALL stop the multi-turn at that turn and SHALL NOT send subsequent turns. Turns completed before the failure SHALL each be persisted as their own SUCCESS `TestCaseRunResult`; the failing turn SHALL be persisted as its own ERROR `TestCaseRunResult` carrying that turn's request/response. All persisted rows of the multi-turn SHALL carry `total_turns` equal to the multi-turn's surviving turn count `N` and the shared `multi_turn_id`, so a multi-turn that dies at turn `k < N-1` legitimately has no row at later turn indices.
Status: **Implemented**

#### Scenario: Failure at turn k stops remaining turns
- **WHEN** turn `k` of an `N`-turn multi-turn fails after exhausting retries
- **THEN** turns `0 .. k-1` SHALL each be persisted as a SUCCESS result with `turn_index = i`, `total_turns = N`, and the shared `multi_turn_id`
- **AND** turn `k` SHALL be persisted as one ERROR result with `turn_index = k`, `total_turns = N`, the shared `multi_turn_id`, its `response_status_code`/`response_body` set to the failing turn's values (or absent when no response was received)
- **AND** turns `k+1 .. N-1` SHALL NOT be sent and SHALL have no rows

#### Scenario: Failure at turn 0 yields a single ERROR row
- **WHEN** a multi-turn fails at turn 0 (before any turn completes)
- **THEN** exactly one ERROR result SHALL be persisted with `turn_index = 0`, `total_turns = N`, the shared `multi_turn_id`, and an empty `extracted_columns` object `{}`

## ADDED Requirements

### Requirement: Broken and degenerate multi-turn rows carry multi_turn_id
A multi-turn that is broken at snapshot (one `0/0` sentinel ERROR row) or degenerate at execution (a "no readable turns" ERROR row) SHALL still carry the originating multi-turn's `multi_turn_id`, because such a row represents a real multi-turn and must group with any sibling rows.
Status: **Implemented**

#### Scenario: Broken multi-turn sentinel carries multi_turn_id
- **WHEN** a multi-turn is detected as broken at snapshot and emitted as a single `0/0` sentinel ERROR row
- **THEN** that row SHALL carry `multi_turn_id` equal to the broken multi-turn's id (not NULL)

#### Scenario: Degenerate no-turns error carries multi_turn_id
- **WHEN** an assembled multi-turn input reaches the executor with no readable frozen turns and is emitted as a single ERROR row
- **THEN** that row SHALL carry `multi_turn_id` equal to the multi-turn's id (not NULL)
