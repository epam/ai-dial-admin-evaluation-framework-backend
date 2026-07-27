# Multi-turn Test Case

## Purpose
This spec defines multi-turn test cases: a single `test_cases` row carrying an ordered `multiTurnData` turn array (mutually exclusive with single-turn `data`), executed as one sequential chat-completions test-case run with full-history resend, emitting one result row per turn. It covers the data model discriminator, turn-count bounds, the sequential turn loop, fail-fast on turn failure, flat CSV import/export multiplication, and the MCP-suite rejection guard. The authoring/validation surface is specified in `test-cases`; per-turn result/summary storage in `analytics-eval-results` / `metrics-storage`; snapshot freezing in `suite-run-snapshot`; dispatch in `eval-execution-engine`.

Status: **Implemented**

## Requirements

### Requirement: Multi-turn is a single test case carrying an ordered turn array
A test case SHALL support an optional `multiTurnData` field — an ordered array of maps, where each element is one turn's per-turn data. A test case is single-turn when `multiTurnData` is absent/null (all fields in `data`) and multi-turn when `multiTurnData` is non-empty. The two fields are NOT mutually exclusive: a multi-turn case MAY also populate `data` with the dataset's **shared** (`perTurn=false`) fields, which are constant across turns, while each turn map carries the **per-turn** (`perTurn=true`) fields. The multi-turn discriminator is the presence of `multiTurnData` alone (independent of whether `data` is empty). Multi-turn behavior is emergent from the data — there is no suite-level flag.
Status: **Implemented**

#### Scenario: Multi-turn case is identified by multiTurnData
- **WHEN** a test case is stored with a non-empty `multiTurnData` array
- **THEN** it is treated as a multi-turn test case whose turns are the array elements in order (`turn_index` = array position, `0..N-1`), regardless of whether `data` is empty or carries shared fields

#### Scenario: Single-turn case is unaffected
- **WHEN** a test case has `multiTurnData` absent/null
- **THEN** it behaves exactly as today, using its `data` map as a single turn

#### Scenario: Shared data coexists with turns
- **WHEN** a multi-turn case has `data` carrying shared fields and `multiTurnData` carrying per-turn fields
- **THEN** both are stored and the case is multi-turn; the shared fields are visible to every turn (see the merged-view execution requirement)

### Requirement: Turn-count bounds
`multiTurnData`, when present, MUST contain at least one element. The maximum number of turns SHALL be configurable (default 10); exceeding it does not reject the request but marks the case `is_valid=false` with an invalidating warning.
Status: **Implemented**

#### Scenario: Empty multiTurnData rejected
- **WHEN** a request supplies `multiTurnData: []`
- **THEN** the request is rejected with HTTP 400

#### Scenario: Over-cap case is invalidated, not rejected
- **WHEN** a case is stored with more turns than the configured maximum
- **THEN** it is persisted with `is_valid=false` and a warning, and it is excluded from runnable selection

### Requirement: Sequential turn-loop execution with full-history resend
A multi-turn case SHALL execute as one sequential unit. The engine maintains a running `messages` history; for each turn in order it resolves the suite's single `requestTemplate`/`inputBindings` against that turn's **effective view** — the merge of the case's shared `data` map with that turn's own per-turn map (per-turn keys take precedence on any overlap) — appends the resolved `messages` to the history, sends the request with the full accumulated history (non-streaming), appends the assistant reply `choices[0].message` verbatim to the history, extracts that turn's response columns, and persists that turn as its own result row. The merged effective view is also the `data` namespace supplied to conditional-metric evaluation for that turn.
Status: **Implemented**

#### Scenario: Two-turn test case accumulates history
- **WHEN** a 2-turn case runs successfully
- **THEN** turn 0 is sent with its own messages, turn 1 is sent with turn 0's messages + turn 0's assistant reply + turn 1's messages, and two SUCCESS result rows are persisted with `turn_index` 0 and 1 and `total_turns=2`

#### Scenario: Shared field is visible on every turn
- **WHEN** a template placeholder is bound to a shared field and the case is multi-turn
- **THEN** every turn resolves that placeholder from the shared `data` value (the merged effective view), without the value being repeated in each turn map

#### Scenario: Turns run sequentially under one permit
- **WHEN** a multi-turn case executes
- **THEN** its turns run strictly in order under a single concurrency permit (concurrency applies across cases, not across turns of one case)

### Requirement: Fail-fast on turn failure
If a turn fails (non-2xx after retries, timeout/network error, oversized/streaming response, or a 2xx response with no `choices[0].message` object, or a resolved body without a top-level `messages` array), the run SHALL stop. Earlier turns MUST persist as SUCCESS rows; the failing turn MUST persist as one ERROR row; later turns MUST NOT be sent.
Status: **Implemented**

#### Scenario: Failure at turn k
- **WHEN** turn k of N fails
- **THEN** turns `0..k-1` persist as SUCCESS rows, turn k persists as one ERROR row, and turns `k+1..N-1` produce no rows

#### Scenario: Non-chat body fails the run at runtime
- **WHEN** a resolved turn body has no top-level `messages` array
- **THEN** that run persists one ERROR row and other cases continue (this is not a suite-validation failure)

### Requirement: Flat CSV import/export multiplication
CSV import/export SHALL remain flat: a multi-turn case is represented as one row per turn. A reserved `turnIndex` header groups and orders turns; it and `testCaseName` are excluded from `data` and from schema auto-detection. Per-turn columns vary per row. Shared columns SHALL be repeated on every turn row of a case; on import the shared columns of a case's rows MUST be identical, and a mismatch SHALL be reported as a conflict warning that invalidates the case. Single-turn cases export one row with a blank `turnIndex`.
Status: **Implemented**

#### Scenario: Export multiplies turns to rows
- **WHEN** a multi-turn case with N turns is exported
- **THEN** it produces N contiguous rows sharing `testCaseName`, with `turnIndex` `0..N-1` in order; shared columns carry the same value on every row; single-turn cases export one row with a blank `turnIndex`

#### Scenario: Import assembles a contiguous run into one case
- **WHEN** consecutive import rows share a `testCaseName` and carry non-blank `turnIndex` values
- **THEN** they are assembled into one multi-turn test case whose `multiTurnData` is the per-turn columns sorted by `turnIndex`, and whose shared `data` is taken from the (identical) shared columns

#### Scenario: Conflicting shared columns are a conflict
- **WHEN** two turn rows of the same case carry different values for a shared column
- **THEN** a conflict warning is reported and the case is invalidated

#### Scenario: Non-contiguous name is a conflict
- **WHEN** a `testCaseName` reappears non-contiguously, or a `turnIndex` is duplicated within a run
- **THEN** a row/conflict error is reported

### Requirement: MCP suites reject multi-turn datasets
Multi-turn is supported only for HTTP chat-completions deployment suites. A run creation for an MCP suite bound to a dataset containing any multi-turn case SHALL be rejected.
Status: **Implemented**

#### Scenario: MCP + multi-turn rejected at run creation
- **WHEN** a run is created for an `MCP_TOOL` suite whose dataset contains at least one case with `multi_turn_data`
- **THEN** it is rejected with HTTP 409 `INVALID_OPERATION`

## Implementation Notes
- Executor `service.domain.job.MultiTurnExecutor` (+ `DeploymentTurnInvoker`, `DeploymentInvocationSupport`, `TurnOutcome`), dispatched from `EvaluationWorker.execute` (returns `List<TestCaseRunResult>`)
- CSV grouping in `service.domain.CsvImportService` / `CsvExportService`
- Guard via `existsMultiTurnByDatasetId` in `TestCaseRepository`, wired into `TestSuiteRunService` run-creation guards
- Assistant reply path is the hardcoded OpenAI `choices[0].message`; turns are always non-streaming
