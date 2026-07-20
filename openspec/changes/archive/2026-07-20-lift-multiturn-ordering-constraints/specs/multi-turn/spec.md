## MODIFIED Requirements

### Requirement: Multi-turn is an ordered group of test-case rows
A multi-turn SHALL be modeled as MULTIPLE `test_cases` rows — one row per turn — grouped by the top-level column `multi_turn_id` (`VARCHAR(36)`, nullable) and ordered by the top-level column `turn_index` (`INTEGER`, nullable). Both columns live outside the `data` JSONB. A row with both columns NULL is a standalone single-turn test case (backward compatible). A multi-turn's surviving turns (valid ∧ not-disabled ∧ filter-matching) SHALL run in ascending authored `turn_index` order; the authored `turn_index` SHALL be preserved on results (there SHALL be no renumbering). The surviving turns need NOT be contiguous and need NOT start at `turn_index = 0` — a turn disabled or filtered out at the start, middle, or end simply drops from the multi-turn, and the remaining turns run in order. The assembled turn count is the number of surviving turns. There is no separate multi-turn resource — `multiTurnId`/`turnIndex` are raw fields on the test-case request/response DTOs.

#### Scenario: Grouped rows form one multi-turn
- **WHEN** three `test_cases` rows share the same `multi_turn_id` with `turn_index` 0, 1, 2
- **THEN** the engine SHALL treat them as one multi-turn of three turns, ordered by `turn_index`

#### Scenario: NULL multi-turn columns are single-turn
- **WHEN** a `test_cases` row has both `multi_turn_id` and `turn_index` NULL
- **THEN** the engine SHALL treat it as a standalone single-turn test case (a length-1 multi-turn)

#### Scenario: Non-contiguous surviving turns run in order with authored indices preserved
- **WHEN** multi-turn `conv-A` has authored turns `0,1,2,3` and turns `1` and `2` are disabled or filtered out, leaving survivors with authored `turn_index` `0` and `3`
- **THEN** the engine SHALL run the two surviving turns in ascending authored order (`0` then `3`)
- **AND** the persisted result rows SHALL carry the authored `turn_index` values `0` and `3` (not renumbered to `0,1`)

#### Scenario: Missing turn 0 no longer breaks the multi-turn
- **WHEN** multi-turn `conv-A`'s only surviving turns have authored `turn_index` `1` and `2` (turn `0` filtered out or disabled)
- **THEN** the multi-turn SHALL run its surviving turns `1,2` in order and SHALL NOT be treated as broken on account of the missing turn 0

### Requirement: Each turn is persisted as its own result row
A multi-turn run SHALL persist one `TestCaseRunResult` per turn, keyed uniquely by `(runId, testCaseId, runIndex, turnIndex)`. Each turn row SHALL carry: `turn_index` = the authored 0-based turn number (from the row's `turn_index`, preserved as authored even when surviving turns are non-contiguous); `total_turns` = the multi-turn's surviving turn count `N`; `last_turn_index` = the maximum authored `turn_index` among the multi-turn's surviving turns (used to evaluate turn position correctly under gaps — see `conditional-metric-execution`); `test_case_data` = that turn's own scalar row `data`; `request_body` = the full accumulated request actually sent for that turn (the `messages` history through that turn's user message); `response_body` = that turn's raw response body (technical fields preserved); `extracted_columns` = that turn's scalar object (identical shape to a single-turn result); `extraction_warnings` = that turn's warnings; timing, retry_count, and log_details scoped to that turn; `trace_id` = the shared multi-turn span id on every turn row. A single-turn (standalone) result SHALL be persisted exactly as before with `turn_index = 0`, `total_turns = 1`, `last_turn_index = 0`. `last_turn_index` is an internal correctness column: it SHALL NOT be exposed on response DTOs or in the CSV export.

#### Scenario: Per-turn scalar data from the row
- **WHEN** turn 0's row has `data = {question:"hi", topic:"geo"}` and turn 1's row has `data = {question:"and then?", topic:"geo"}`
- **THEN** turn 0's `test_case_data` SHALL be `{ "question": "hi", "topic": "geo" }` and turn 1's SHALL be `{ "question": "and then?", "topic": "geo" }`

#### Scenario: Per-turn extracted columns are scalar objects
- **WHEN** a 3-turn multi-turn with response columns `answer` and `score` completes
- **THEN** each of the three result rows SHALL have `extracted_columns` = a JSON object of scalars (e.g. `{ "answer": "Paris", "score": 0.8 }`)

#### Scenario: last_turn_index equals the max authored surviving index
- **WHEN** a multi-turn's surviving turns have authored `turn_index` `0` and `3` (turns `1,2` dropped)
- **THEN** both persisted result rows SHALL carry `total_turns = 2` and `last_turn_index = 3`

#### Scenario: Single-turn result shape unchanged
- **WHEN** a standalone single-turn test case runs
- **THEN** exactly one result row SHALL be persisted with `turn_index = 0`, `total_turns = 1`, `last_turn_index = 0`, `extracted_columns` a JSON object of scalars, and be otherwise byte-identical to prior single-turn behavior (aside from the internal `last_turn_index` column, which is not exposed)
