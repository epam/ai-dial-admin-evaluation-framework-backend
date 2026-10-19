## MODIFIED Requirements

### Requirement: Turn count is driven by per-turn bindings, not a fixed array length
Turn count `N` for an HTTP suite (DEPLOYMENT or ROUTE) SHALL be decided **per request in the suite's chain**, from that request's own effective input bindings. For a given request, `N` SHALL be `multiTurnData.length` if and only if **that request's** input bindings reference at least one dataset field declared `perTurn: true`; otherwise `N = 1` for that request. Requests in one chain MAY therefore have different turn counts — none, some, or all of them multi-turn. A single-turn test case (`multiTurnData` absent/null) is always the `N = 1` case for every request, unaffected by this rule. A multi-turn test case (`multiTurnData` non-empty) executed by a request with no per-turn binding SHALL execute that request as one call built from the case's shared `data`, not `multiTurnData.length` repeated calls. A single-turn test case executed by a request that references a `perTurn: true` field SHALL run that request with `N = 1` and resolve that placeholder using the same unresolved-variable behavior as any other unbound template variable (there is no turn array to source a per-turn value from). For a suite with no `additionalRequests` the chain is one request and this rule is exactly the pre-existing suite-level rule.
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

### Requirement: MCP suites reject multi-turn datasets
Multi-turn is supported only for HTTP suites (DEPLOYMENT and ROUTE). A run creation for an MCP suite bound to a dataset containing any multi-turn case SHALL be rejected.
Status: **Implemented**

#### Scenario: MCP + multi-turn rejected at run creation
- **WHEN** a run is created for an `MCP_TOOL` suite whose dataset contains at least one case with `multi_turn_data`
- **THEN** it is rejected with HTTP 409 `INVALID_OPERATION`

#### Scenario: ROUTE suite with a multi-turn dataset is not rejected at run creation
- **WHEN** a run is created for a ROUTE suite bound to a dataset containing multi-turn cases
- **THEN** run creation SHALL NOT be rejected on that ground; the MCP-only guard does not apply

## Implementation notes

- The run-creation guard lives in `TestSuiteRunService` and applies to `MCP_TOOL` suites only; the turn loop (`TurnLoopExecutor`) is shared by DEPLOYMENT and ROUTE suites.
- Tests: no dedicated ROUTE multi-turn test.
