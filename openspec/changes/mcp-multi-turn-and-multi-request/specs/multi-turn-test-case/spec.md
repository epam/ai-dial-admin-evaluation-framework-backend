## REMOVED Requirements

### Requirement: MCP suites reject multi-turn datasets
**Reason**: Multi-turn is a property of the test-case data and the suite's per-turn bindings, not of how a request is issued. The turn loop, per-turn binding detection, and per-turn row emission are transport-independent, so an MCP tool suite can execute a multi-turn case with the same semantics as an HTTP suite. Replaced by "Multi-turn execution applies to MCP tool suites".
**Migration**: No client action required. A run creation or try-it-out call for an `MCP_TOOL` suite bound to a dataset containing multi-turn cases now succeeds instead of returning HTTP 409 `INVALID_OPERATION`. A suite that must keep single-turn behaviour SHALL either bind no `perTurn: true` field (the turn count collapses to 1) or bind a dataset with no multi-turn cases.

## ADDED Requirements

### Requirement: Multi-turn execution applies to MCP tool suites
Multi-turn execution SHALL be independent of suite type. For an `MCP_TOOL` suite, the turn count SHALL be decided by the same rule as for a DEPLOYMENT suite: a case carrying `multiTurnData` whose effective input bindings reference at least one dataset schema field declared `perTurn: true` SHALL run `multiTurnData.length` tool invocations; any other case SHALL run exactly once from the case's shared `data`. Each turn SHALL resolve its own tool arguments from the merged effective view `merge(shared, multiTurnData[i])`, with per-turn keys winning. Turns SHALL execute sequentially within one test-case repetition and SHALL emit one result row per executed turn, stamped with `turn_index`/`total_turns` under the same rules as HTTP rows.

Status: **Planned**

#### Scenario: MCP suite with a per-turn binding runs one tool call per turn
- **WHEN** an `MCP_TOOL` suite's `inputBindings` reference a `perTurn: true` field and a case carries 3 entries in `multiTurnData`
- **THEN** the run SHALL issue 3 tool calls and persist 3 result rows with `turn_index` 0, 1, 2 and `total_turns = 3`

#### Scenario: Each turn resolves arguments from its own merged view
- **WHEN** a case has shared `data = {"sessionName": "s1"}` and `multiTurnData = [{"message": "a"}, {"message": "b"}]`, and the argument template references both fields
- **THEN** turn 0's arguments SHALL resolve `message` to `"a"` and turn 1's to `"b"`, with `sessionName` resolving to `"s1"` on both

#### Scenario: MCP multi-turn case with no per-turn binding collapses to one call
- **WHEN** an `MCP_TOOL` suite binds no `perTurn: true` field and a case carries 3 entries in `multiTurnData`
- **THEN** the run SHALL issue exactly one tool call built from the case's shared `data`, and its row SHALL leave `turn_index`/`total_turns` at their defaults `0`/`1`

#### Scenario: MCP turn failure aborts the remaining turns
- **WHEN** turn 1 of a 3-turn MCP case fails (transport error, timeout, oversized response, or a tool-level `isError`)
- **THEN** turn 0's row SHALL persist as `SUCCESS`, turn 1's row SHALL persist with its terminal status, and turn 2 SHALL NOT be invoked

#### Scenario: Single-shot MCP suite rows are unchanged
- **WHEN** an `MCP_TOOL` suite runs a case with no `multiTurnData`
- **THEN** its single row SHALL be byte-identical to the row written before this capability existed, with turn columns at their defaults

## Implementation Notes

- Turn planning, per-turn binding detection, and row identity stamping stay in `runner.job.TurnLoopExecutor` and `runner.job.PerTurnBindingDetector` — both already transport-independent; only the per-turn resolve-and-invoke step becomes suite-type-specific.
- The removed guard's supporting query (`TestCaseRepository.existsMultiTurnByDatasetId`) and its service wrapper (`TestCaseService.datasetHasMultiTurnCases`) become unused and are deleted with the guard.
- Each turn is a separate MCP session: the MCP client is created, initialized, and closed per tool call, so state that must survive across turns SHALL be threaded explicitly through arguments (see `mcp-tool-invocation`), never assumed to persist server-side.
