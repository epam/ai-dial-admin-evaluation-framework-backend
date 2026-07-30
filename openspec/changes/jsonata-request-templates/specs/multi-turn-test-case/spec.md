## MODIFIED Requirements

### Requirement: Turn count is driven by per-turn bindings, not a fixed array length
Turn count `N` for a DEPLOYMENT HTTP suite SHALL be `multiTurnData.length` if and only if the suite's effective input bindings reference at least one dataset field declared `perTurn: true`; otherwise `N = 1`. A single-turn test case (`multiTurnData` absent/null) is always the `N = 1` case, unaffected by this rule. A multi-turn test case (`multiTurnData` non-empty) bound to a suite with no per-turn binding SHALL execute as one request built from the case's shared `data`, not `multiTurnData.length` repeated requests. A single-turn test case bound to a suite that references a `perTurn: true` field SHALL run with `N = 1` and resolve that placeholder using the same unresolved-variable behavior as any other unbound template variable (there is no turn array to source a per-turn value from).
Status: **Implemented**

#### Scenario: Multi-turn dataset with a per-turn binding runs N turns
- **WHEN** a multi-turn test case has `multiTurnData` with N elements and the suite's `requestTemplate` binds at least one placeholder to a dataset field with `perTurn: true`
- **THEN** the case executes N turns, one per `multiTurnData` element, exactly as before this change

#### Scenario: Multi-turn dataset with no per-turn binding collapses to one request
- **WHEN** a multi-turn test case has `multiTurnData` with N > 1 elements, but none of the suite's effective input bindings reference a `perTurn: true` field
- **THEN** the case executes exactly one request built from the case's shared `data`, producing one result row with `turnIndex`/`totalTurns` set to `null` (same as a single-turn case), not N result rows

#### Scenario: Single-turn case with a per-turn binding still runs once
- **WHEN** a single-turn test case (`multiTurnData` absent) is bound to a suite whose template references a `perTurn: true` field
- **THEN** the case runs with `N = 1`; the referenced placeholder resolves as an unbound variable (per the existing unresolved-variable warning behavior), since there is no turn array to source a value from

### Requirement: JSONata-driven turn-loop execution with frame-based history
A multi-turn case SHALL execute as one sequential unit. For each turn in order, the engine resolves the suite's single `requestTemplate`/`inputBindings` against that turn's effective view — the merge of the case's shared `data` map with that turn's own per-turn map (per-turn keys take precedence on any overlap) — by JSONata-evaluating the resolved request body with a `Frame` carrying the previous turn's reconciled extracted response columns bound by name (e.g. a response column named `history` is reachable as `$history` inside the JSONata expression). Turn 0 evaluates with those names unbound (JSONata undefined). The request streams (not forced non-streaming); the assembled response body (including any DIAL `custom_content`, merged across SSE chunks) is what response columns are extracted from. There is no hardcoded `messages` array or `choices[0].message` reply path — history accumulation across turns is entirely the author's JSONata expression (typically `$append($history, [...])`), not a Java-level concatenation of message objects. The merged effective view is also the `data` namespace supplied to conditional-metric evaluation for that turn.
Status: **Implemented**

#### Scenario: Two-turn test case accumulates history via the frame
- **WHEN** a 2-turn case runs successfully and its template's body expression references `$history`
- **THEN** turn 0 evaluates with `$history` unbound (undefined), turn 1 evaluates with `$history` bound to turn 0's reconciled extracted response columns, and two SUCCESS result rows are persisted with `turn_index` 0 and 1 and `total_turns=2`

#### Scenario: Shared field is visible on every turn
- **WHEN** a template placeholder is bound to a shared field and the case is multi-turn
- **THEN** every turn resolves that placeholder from the shared `data` value (the merged effective view), without the value being repeated in each turn map

#### Scenario: Turns run sequentially under one permit
- **WHEN** a multi-turn case executes
- **THEN** its turns run strictly in order under a single concurrency permit (concurrency applies across cases, not across turns of one case)

#### Scenario: Turns stream like single-turn requests
- **WHEN** a multi-turn case executes
- **THEN** each turn's HTTP call streams (SSE), and the response body is assembled by the same accumulation path a single-turn suite uses, before response-column extraction runs against it

### Requirement: Fail-fast on turn failure
If a turn fails (non-2xx after retries, timeout/network error, oversized response, or the resolved request body does not JSONata-evaluate to a JSON object), the run SHALL stop. Earlier turns MUST persist as SUCCESS rows; the failing turn MUST persist as one ERROR row; later turns MUST NOT be sent.
Status: **Implemented**

#### Scenario: Failure at turn k
- **WHEN** turn k of N fails
- **THEN** turns `0..k-1` persist as SUCCESS rows, turn k persists as one ERROR row, and turns `k+1..N-1` produce no rows

#### Scenario: Non-object evaluated body fails the run at runtime
- **WHEN** a turn's resolved request body JSONata-evaluates to a value that is not a JSON object (e.g. a scalar or array), or evaluation throws
- **THEN** that turn persists one ERROR row and other cases continue (this is not a suite-validation failure)

## Implementation Notes

- Executor: `service.domain.job.TurnLoopExecutor` (replaces the fixed-`N` loop previously in `MultiTurnExecutor`), `PerTurnBindingDetector` (turn-count decision), `RequestBodyEvaluator` (JSONata evaluation + object-contract check), `DeploymentTurnInvoker` (now streaming), `CustomContentAccumulator` (DIAL `custom_content` chunk merge), dispatched from `EvaluationWorker.execute`.
- CSV grouping in `service.domain.CsvImportService` / `CsvExportService` — unchanged by this change.
- Guard via `existsMultiTurnByDatasetId` in `TestCaseRepository`, wired into `TestSuiteRunService` run-creation guards — unchanged; MCP + multi-turn rejection is independent of turn-loop mechanics.
- The assistant-reply-path requirement previously hardcoded to `choices[0].message` is retired: reply content only matters insofar as the suite's own response columns extract it, and history is whatever the author's request-template JSONata expression constructs from `$<responseColumnName>`.
