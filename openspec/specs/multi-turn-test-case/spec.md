# Multi-turn Test Case

## Purpose
This spec defines multi-turn test cases: a single `test_cases` row carrying an ordered `multiTurnData` turn array (coexisting with, not mutually exclusive with, single-turn `data` used for shared fields), executed as one sequential JSONata-frame-driven test-case run — turn count is driven by whether the suite's bindings reference a `perTurn: true` field, history/state accumulation is entirely the author's request-template JSONata expression (no hardcoded `messages`/`choices[0].message` path), and turns stream like single-turn requests — emitting one result row per turn. It covers the data model discriminator, turn-count bounds, the turn-count-vs-per-turn-binding rule, the JSONata-driven turn loop, fail-fast on turn failure, flat CSV import/export multiplication, and the MCP-suite rejection guard. The authoring/validation surface is specified in `test-cases`; the request-template JSONata evaluation seam (frame, history binding, object-contract) is specified in `request-template`; per-turn result/summary storage in `analytics-eval-results` / `metrics-storage`; snapshot freezing in `suite-run-snapshot`; dispatch in `eval-execution-engine`.

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

### Requirement: Turn count is driven by per-turn bindings, not a fixed array length
Turn count `N` for a DEPLOYMENT HTTP suite SHALL be decided **per request in the suite's chain**, from that request's own effective input bindings. For a given request, `N` SHALL be `multiTurnData.length` if and only if **that request's** input bindings reference at least one dataset field declared `perTurn: true`; otherwise `N = 1` for that request. Requests in one chain MAY therefore have different turn counts — none, some, or all of them multi-turn. A single-turn test case (`multiTurnData` absent/null) is always the `N = 1` case for every request, unaffected by this rule. A multi-turn test case (`multiTurnData` non-empty) executed by a request with no per-turn binding SHALL execute that request as one call built from the case's shared `data`, not `multiTurnData.length` repeated calls. A single-turn test case executed by a request that references a `perTurn: true` field SHALL run that request with `N = 1` and resolve that placeholder using the same unresolved-variable behavior as any other unbound template variable (there is no turn array to source a per-turn value from). For a suite with no `additionalRequests` the chain is one request and this rule is exactly the pre-existing suite-level rule.
Status: **Implemented**

#### Scenario: Multi-turn dataset with a per-turn binding runs N turns
- **WHEN** a multi-turn test case has `multiTurnData` with N elements and the request's `requestTemplate` binds at least one placeholder to a dataset field with `perTurn: true`
- **THEN** that request executes N turns, one per `multiTurnData` element, exactly as before this change

#### Scenario: Multi-turn dataset with no per-turn binding collapses to one request
- **WHEN** a multi-turn test case has `multiTurnData` with N > 1 elements, but none of the request's effective input bindings reference a `perTurn: true` field
- **THEN** that request executes exactly one call built from the case's shared `data`, producing one result row with `turnIndex`/`totalTurns` left at the builder/DB defaults `0`/`1` (byte-identical to a single-turn case; `turn_index`/`total_turns` are non-nullable `int` columns, never `null` — see analytics migration `V1.13__AddTurnColumnsToTestCaseRunResults.sql`), not N result rows

#### Scenario: Single-turn case with a per-turn binding still runs once
- **WHEN** a single-turn test case (`multiTurnData` absent) is executed by a request whose template references a `perTurn: true` field
- **THEN** that request runs with `N = 1`; the referenced placeholder resolves as an unbound variable (per the existing unresolved-variable warning behavior), since there is no turn array to source a value from

#### Scenario: Turn count differs between two requests of one chain
- **WHEN** a chain's request #0 binds no per-turn field, request #1 binds one, and the case carries 3 turns
- **THEN** request #0 SHALL run once and request #1 SHALL run 3 turns, producing 4 result rows for that repetition

### Requirement: JSONata-driven turn-loop execution with frame-based history
A multi-turn case SHALL execute as one sequential unit **per request**. For each turn in order, the engine resolves **that request's** `requestTemplate`/`inputBindings` against that turn's effective view — the merge of the case's shared `data` map with that turn's own per-turn map (per-turn keys take precedence on any overlap) — by JSONata-evaluating the resolved request body with a `Frame` carrying the accumulated reconciled extracted response columns bound by name (e.g. a response column named `history` is reachable as `$history` inside the JSONata expression). The accumulated frame contains the previous turns' extractions for the current request **and** every extraction from earlier requests in the suite's chain. Turn 0 of the chain's first request evaluates with those names unbound (JSONata undefined); turn 0 of a later request evaluates with the earlier requests' columns already bound. The request streams (not forced non-streaming); the assembled response body (including any DIAL `custom_content`, merged across SSE chunks) is what response columns are extracted from. There is no hardcoded `messages` array or `choices[0].message` reply path — history accumulation across turns is entirely the author's JSONata expression (typically `$append($history, [...])`), not a Java-level concatenation of message objects. The merged effective view is also the `data` namespace supplied to conditional-metric evaluation for that turn.
Status: **Implemented**

#### Scenario: Two-turn test case accumulates history via the frame
- **WHEN** a 2-turn case runs successfully on a single-request suite and its template's body expression references `$history`
- **THEN** turn 0 evaluates with `$history` unbound (undefined), turn 1 evaluates with `$history` bound to turn 0's reconciled extracted response columns, and two SUCCESS result rows are persisted with `turn_index` 0 and 1 and `total_turns=2`

#### Scenario: Shared field is visible on every turn
- **WHEN** a template placeholder is bound to a shared field and the case is multi-turn
- **THEN** every turn resolves that placeholder from the shared `data` value (the merged effective view), without the value being repeated in each turn map

#### Scenario: Turns run sequentially under one permit
- **WHEN** a multi-turn case executes
- **THEN** its turns run strictly in order under a single concurrency permit (concurrency applies across cases, not across turns of one case, and not across requests of one chain)

#### Scenario: Turns stream like single-turn requests
- **WHEN** a multi-turn case executes
- **THEN** each turn's HTTP call streams (SSE), and the response body is assembled by the same accumulation path a single-turn suite uses, before response-column extraction runs against it

#### Scenario: A later request's turn 0 already sees earlier requests' columns
- **WHEN** request #0 of a chain extracts `configId` and request #1 is multi-turn
- **THEN** request #1's turn 0 evaluates with `$configId` bound, and every subsequent turn of request #1 keeps it bound

### Requirement: Fail-fast on turn failure
If a turn fails (non-2xx after retries, timeout/network error, oversized response, or the resolved request body does not JSONata-evaluate to a JSON object), the run SHALL stop. Earlier turns MUST persist as SUCCESS rows; the failing turn MUST persist as one ERROR row; later turns MUST NOT be sent.
Status: **Implemented**

#### Scenario: Failure at turn k
- **WHEN** turn k of N fails
- **THEN** turns `0..k-1` persist as SUCCESS rows, turn k persists as one ERROR row, and turns `k+1..N-1` produce no rows

#### Scenario: Non-object evaluated body fails the run at runtime
- **WHEN** a turn's resolved request body JSONata-evaluates to a value that is not a JSON object (e.g. a scalar or array), or evaluation throws
- **THEN** that turn persists one ERROR row and other cases continue (this is not a suite-validation failure)

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
- Executor: `runner.job.TurnLoopExecutor` (replaces the fixed-`N` loop previously in `MultiTurnExecutor`), `PerTurnBindingDetector` (turn-count decision), `RequestBodyEvaluator` (JSONata evaluation + object-contract check), `DeploymentTurnInvoker` (now streaming), `CustomContentAccumulator` (DIAL `custom_content` chunk merge), dispatched from `EvaluationWorker.execute`.
- CSV grouping in `service.domain.CsvImportService` / `CsvExportService` — unchanged by this change.
- Guard via `existsMultiTurnByDatasetId` in `TestCaseRepository`, wired into `TestSuiteRunService` run-creation guards — unchanged; MCP + multi-turn rejection is independent of turn-loop mechanics.
- The assistant-reply-path requirement previously hardcoded to `choices[0].message` is retired: reply content only matters insofar as the suite's own response columns extract it, and history is whatever the author's request-template JSONata expression constructs from `$<responseColumnName>`.
