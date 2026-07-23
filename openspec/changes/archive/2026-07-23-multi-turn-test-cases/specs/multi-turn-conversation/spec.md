## ADDED Requirements

### Requirement: Multi-turn is a single test case carrying an ordered turn array
A test case SHALL support an optional `multiTurnData` field — an ordered array of maps, where each element is one turn's data with the same shape as the single-turn `data` map. A test case is either single-turn (`data` populated, `multiTurnData` absent) or multi-turn (`multiTurnData` non-empty, `data` empty `{}`); the two fields are mutually exclusive. Multi-turn behavior is emergent from the presence of `multiTurnData` — there is no suite-level flag.

#### Scenario: Multi-turn case is identified by multiTurnData
- **WHEN** a test case is stored with a non-empty `multiTurnData` array
- **THEN** it is treated as a multi-turn conversation whose turns are the array elements in order (`turn_index` = array position, `0..N-1`)

#### Scenario: Single-turn case is unaffected
- **WHEN** a test case has `multiTurnData` absent/null
- **THEN** it behaves exactly as today, using its `data` map as a single turn

#### Scenario: data and multiTurnData are mutually exclusive
- **WHEN** a create/update supplies a non-empty `data` and a non-empty `multiTurnData` together
- **THEN** the request is rejected with HTTP 400

### Requirement: Turn-count bounds
`multiTurnData`, when present, MUST contain at least one element. The maximum number of turns SHALL be configurable (default 10); exceeding it does not reject the request but marks the case `is_valid=false` with an invalidating warning.

#### Scenario: Empty multiTurnData rejected
- **WHEN** a request supplies `multiTurnData: []`
- **THEN** the request is rejected with HTTP 400

#### Scenario: Over-cap case is invalidated, not rejected
- **WHEN** a case is stored with more turns than the configured maximum
- **THEN** it is persisted with `is_valid=false` and a warning, and it is excluded from runnable selection

### Requirement: Sequential turn-loop execution with full-history resend
A multi-turn case SHALL execute as one sequential unit. The engine maintains a running `messages` history; for each turn in order it resolves the suite's single `requestTemplate`/`inputBindings` against that turn's own map, appends the resolved `messages` to the history, sends the request with the full accumulated history (non-streaming), appends the assistant reply `choices[0].message` verbatim to the history, extracts that turn's response columns, and persists that turn as its own result row.

#### Scenario: Two-turn conversation accumulates history
- **WHEN** a 2-turn case runs successfully
- **THEN** turn 0 is sent with its own messages, turn 1 is sent with turn 0's messages + turn 0's assistant reply + turn 1's messages, and two SUCCESS result rows are persisted with `turn_index` 0 and 1 and `total_turns=2`

#### Scenario: Turns run sequentially under one permit
- **WHEN** a multi-turn case executes
- **THEN** its turns run strictly in order under a single concurrency permit (concurrency applies across cases, not across turns of one case)

### Requirement: Fail-fast on turn failure
If a turn fails (non-2xx after retries, timeout/network error, oversized/streaming response, or a 2xx response with no `choices[0].message` object, or a resolved body without a top-level `messages` array), the conversation SHALL stop. Earlier turns MUST persist as SUCCESS rows; the failing turn MUST persist as one ERROR row; later turns MUST NOT be sent.

#### Scenario: Failure at turn k
- **WHEN** turn k of N fails
- **THEN** turns `0..k-1` persist as SUCCESS rows, turn k persists as one ERROR row, and turns `k+1..N-1` produce no rows

#### Scenario: Non-chat body fails the conversation at runtime
- **WHEN** a resolved turn body has no top-level `messages` array
- **THEN** that conversation persists one ERROR row and other cases continue (this is not a suite-validation failure)

### Requirement: Flat CSV import/export multiplication
CSV import/export SHALL remain flat: a multi-turn case is represented as one row per turn. A reserved `turnIndex` header groups and orders turns; it and `testCaseName` are excluded from `data` and from schema auto-detection.

#### Scenario: Export multiplies turns to rows
- **WHEN** a multi-turn case with N turns is exported
- **THEN** it produces N contiguous rows sharing `testCaseName`, with `turnIndex` `0..N-1` in order; single-turn cases export one row with a blank `turnIndex`

#### Scenario: Import assembles a contiguous run into one case
- **WHEN** consecutive import rows share a `testCaseName` and carry non-blank `turnIndex` values
- **THEN** they are assembled into one multi-turn test case whose `multiTurnData` is the turns sorted by `turnIndex`

#### Scenario: Non-contiguous name is a conflict
- **WHEN** a `testCaseName` reappears non-contiguously, or a `turnIndex` is duplicated within a run
- **THEN** a row/conflict error is reported

### Requirement: MCP suites reject multi-turn datasets
Multi-turn is supported only for HTTP chat-completions deployment suites. A run creation for an MCP suite bound to a dataset containing any multi-turn case SHALL be rejected.

#### Scenario: MCP + multi-turn rejected at run creation
- **WHEN** a run is created for an `MCP_TOOL` suite whose dataset contains at least one case with `multi_turn_data`
- **THEN** it is rejected with HTTP 409 `INVALID_OPERATION`

## Implementation notes

Planned. Executor `service.domain.job.MultiTurnExecutor` (+ `DeploymentTurnInvoker`, `DeploymentInvocationSupport`, `TurnOutcome`), dispatched from `EvaluationWorker.execute` (returns `List<TestCaseRunResult>`). CSV grouping in `service.domain.CsvImportService`/`CsvExportService`. Guard via `existsMultiTurnByDatasetId` in `TestCaseRepository`, wired into `TestSuiteRunService` run-creation guards. Assistant reply path is the hardcoded OpenAI `choices[0].message`; turns are always non-streaming.
