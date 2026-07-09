## MODIFIED Requirements

### Requirement: Per-step turn loop with full-history resend
For a multi-turn test case, the engine SHALL maintain a running `messages` history `H` (initially empty) and execute turns sequentially for `i` in `0 .. N-1`, where `N` is the turn count derived per test case (see the turn-count requirement). For each turn it SHALL: (1) build the per-turn data by projecting each array-valued bound column to its `i`-th element (leaving scalar columns and `constantValue` bindings unchanged); (2) resolve `requestTemplate` with the single `inputBindings` and that per-turn data; (3) append the resolved body's `messages` to `H`; (4) send the request with its `messages` field overwritten by the full `H` (all other body fields as resolved for that turn); (5) append the assistant reply — the full `choices[0].message` object of the response, verbatim — to `H`; (6) extract response columns for that turn and **persist that turn as its own `TestCaseRunResult`** (see the per-turn result requirement). The full accumulated history MUST be re-sent on every turn.
Status: **Planned**

#### Scenario: Two-turn conversation accumulates history
- **WHEN** a test case whose array-valued bound column has length 2 runs and both turns succeed
- **THEN** turn 0 SHALL send `messages` = [turn-0 user message]
- **AND** turn 1 SHALL send `messages` = [turn-0 user, turn-0 assistant, turn-1 user]
- **AND** the full assistant message (`choices[0].message`) from each turn SHALL be appended verbatim to history before the next turn

#### Scenario: Each turn is extracted and persisted independently
- **WHEN** a 3-turn conversation completes
- **THEN** the engine SHALL emit three `TestCaseRunResult` rows, one per turn, each with that turn's own extracted columns, request body, and response body

### Requirement: Fail-fast on step failure
If any turn fails after retries — a non-2xx final status, a timeout/network error, an oversized (truncated) response, or a 2xx response with no assistant `message` object — the engine SHALL stop the conversation at that turn and SHALL NOT send subsequent turns. Turns completed before the failure SHALL each be persisted as their own SUCCESS `TestCaseRunResult`; the failing turn SHALL be persisted as its own ERROR `TestCaseRunResult` carrying that turn's request/response. All rows of the conversation SHALL carry `total_turns` equal to the planned turn count `N` (known upfront), so a conversation that dies before turn `N-1` legitimately has no row at the last turn index.
Status: **Planned**

#### Scenario: Failure at turn k stops remaining turns
- **WHEN** turn `k` of an `N`-turn conversation fails after exhausting retries
- **THEN** turns `0 .. k-1` SHALL each be persisted as a SUCCESS result with `turn_index = i`, `total_turns = N`
- **AND** turn `k` SHALL be persisted as one ERROR result with `turn_index = k`, `total_turns = N`, its `response_status_code`/`response_body` set to the failing turn's values (or absent when no response was received)
- **AND** turns `k+1 .. N-1` SHALL NOT be sent and SHALL have no rows

#### Scenario: Failure at turn 0 yields a single ERROR row
- **WHEN** a conversation fails at turn 0 (before any turn completes)
- **THEN** exactly one ERROR result SHALL be persisted with `turn_index = 0`, `total_turns = N`, and an empty `extracted_columns` object `{}`

## ADDED Requirements

### Requirement: Each turn is persisted as its own result row
A multi-turn run SHALL persist one `TestCaseRunResult` per turn, keyed uniquely by `(runId, testCaseId, runIndex, turnIndex)`. Each turn row SHALL carry: `turn_index` = the 0-based turn number; `total_turns` = the planned turn count `N`; `test_case_data` = the per-turn projected scalar view (element `i` of each array-valued bound column; scalar columns and constants broadcast unchanged); `request_body` = the full accumulated request actually sent for that turn (the `messages` history through that turn's user message); `response_body` = that turn's raw response body (technical fields preserved); `extracted_columns` = that turn's scalar object (identical shape to a single-turn result); `extraction_warnings` = that turn's warnings; timing, retry_count, and log_details scoped to that turn; `trace_id` = the shared conversation span id on every turn row. A single-turn (non-multi-turn) result SHALL be persisted exactly as before with `turn_index = 0`, `total_turns = 1`.
Status: **Planned**

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
Status: **Planned**

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

## REMOVED Requirements

### Requirement: Multi-step result shape reuses existing columns
**Reason**: Replaced by per-turn rows. A conversation no longer collapses into one row with column-major `extractedColumns` arrays; each turn is its own `TestCaseRunResult` with a scalar `extracted_columns` object (see "Each turn is persisted as its own result row").

### Requirement: Per-step extraction warnings carry a step index
**Reason**: With one row per turn, each turn's warnings live on that turn's row (the row's `turn_index` identifies the turn). The flat `stepIndex`-tagged aggregation and `ExtractionWarningDto.stepIndex` are removed; warnings revert to the single-step shape.

### Requirement: Metric evaluation reads raw multi-step columns (no normalization)
**Reason**: Each turn row already holds scalar `extracted_columns`, so there is nothing to normalize and no per-turn selection to perform. Metric bindings resolve the scalar column value directly (see metric-evaluation "Binding resolution"); the `jsonataExpression` turn-selector is removed.
