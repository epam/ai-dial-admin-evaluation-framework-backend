# Multi-turn Test Case Execution

## Purpose

Binding-driven per-request turn counts, sequential JSONata frame/history execution, streaming, failure isolation and MCP run guards. The data model/structural write contract belongs to [test-cases](../test-cases/spec.md); scope warnings/caps to [test-case-validation](../test-case-validation/spec.md); flat CSV assembly, scope inference, preview and fixup to [test-case-csv-transfer](../test-case-csv-transfer/spec.md); guarded schema-change processing to [test-case-revalidation](../test-case-revalidation/spec.md). Snapshot and result storage remain owned by their respective capabilities.

Status: **Implemented**

## Requirements

### Requirement: Turn count is driven by per-turn bindings, not a fixed array length

Status: **Implemented**

Turn count `N` for an HTTP suite (DEPLOYMENT or ROUTE) SHALL be decided **per request in the suite's chain**, from that request's own effective input bindings. For a given request, `N` SHALL be `multiTurnData.length` if and only if **that request's** input bindings reference at least one dataset field declared `perTurn: true`; otherwise `N = 1` for that request. Requests in one chain MAY therefore have different turn counts — none, some, or all of them multi-turn. A single-turn test case (`multiTurnData` absent/null) is always the `N = 1` case for every request, unaffected by this rule. A multi-turn test case (`multiTurnData` non-empty) executed by a request with no per-turn binding SHALL execute that request as one call built from the case's shared `data`, not `multiTurnData.length` repeated calls. A single-turn test case executed by a request that references a `perTurn: true` field SHALL run that request with `N = 1` and resolve that placeholder using the same unresolved-variable behavior as any other unbound template variable (there is no turn array to source a per-turn value from). For a suite with no `additionalRequests` the chain is one request and this rule is exactly the pre-existing suite-level rule.

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| Multi-turn dataset with a per-turn binding runs N turns | a multi-turn test case has `multiTurnData` with N elements and the request's `requestTemplate` binds at least one placeholder to a dataset field with `perTurn: true` | that request executes N turns, one per `multiTurnData` element, exactly as before this change |
| Multi-turn dataset with no per-turn binding collapses to one request | a multi-turn test case has `multiTurnData` with N > 1 elements, but none of the request's effective input bindings reference a `perTurn: true` field | that request executes exactly one call built from the case's shared `data`, producing one result row with `turnIndex`/`totalTurns` left at the builder/DB defaults `0`/`1` (byte-identical to a single-turn case; `turn_index`/`total_turns` are non-nullable `int` columns, never `null` — see analytics migration `V1.13__AddTurnColumnsToTestCaseRunResults.sql`), not N result rows |
| Single-turn case with a per-turn binding still runs once | a single-turn test case (`multiTurnData` absent) is executed by a request whose template references a `perTurn: true` field | that request runs with `N = 1`; the referenced placeholder resolves as an unbound variable (per the existing unresolved-variable warning behavior), since there is no turn array to source a value from |
| Turn count differs between two requests of one chain | a chain's request #0 binds no per-turn field, request #1 binds one, and the case carries 3 turns | request #0 SHALL run once and request #1 SHALL run 3 turns, producing 4 result rows for that repetition |

### Requirement: JSONata-driven turn-loop execution with frame-based history

Status: **Implemented**

A multi-turn case SHALL execute as one sequential unit **per request**. For each turn in order, the engine resolves **that request's** `requestTemplate`/`inputBindings` against that turn's effective view — the merge of the case's shared `data` map with that turn's own per-turn map (per-turn keys take precedence on any overlap) — by JSONata-evaluating the resolved request body with a `Frame` carrying the accumulated reconciled extracted response columns bound by name (e.g. a response column named `history` is reachable as `$history` inside the JSONata expression). The accumulated frame contains the previous turns' extractions for the current request **and** every extraction from earlier requests in the suite's chain. Turn 0 of the chain's first request evaluates with those names unbound (JSONata undefined); turn 0 of a later request evaluates with the earlier requests' columns already bound. The request streams (not forced non-streaming); the assembled response body (including any DIAL `custom_content`, merged across SSE chunks) is what response columns are extracted from. There is no hardcoded `messages` array or `choices[0].message` reply path — history accumulation across turns is entirely the author's JSONata expression (typically `$append($history, [...])`), not a Java-level concatenation of message objects. The merged effective view is also the `data` namespace supplied to conditional-metric evaluation for that turn.

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| Two-turn test case accumulates history via the frame | a 2-turn case runs successfully on a single-request suite and its template's body expression references `$history` | turn 0 evaluates with `$history` unbound (undefined), turn 1 evaluates with `$history` bound to turn 0's reconciled extracted response columns, and two SUCCESS result rows are persisted with `turn_index` 0 and 1 and `total_turns=2` |
| Shared field is visible on every turn | a template placeholder is bound to a shared field and the case is multi-turn | every turn resolves that placeholder from the shared `data` value (the merged effective view), without the value being repeated in each turn map |
| Turns run sequentially under one permit | a multi-turn case executes | its turns run strictly in order under a single concurrency permit (concurrency applies across cases, not across turns of one case, and not across requests of one chain) |
| Turns stream like single-turn requests | a multi-turn case executes | each turn's HTTP call streams (SSE), and the response body is assembled by the same accumulation path a single-turn suite uses, before response-column extraction runs against it |
| A later request's turn 0 already sees earlier requests' columns | request #0 of a chain extracts `configId` and request #1 is multi-turn | request #1's turn 0 evaluates with `$configId` bound, and every subsequent turn of request #1 keeps it bound |

### Requirement: Fail-fast on turn failure

Status: **Implemented**

If a turn fails (non-2xx after retries, timeout/network error, oversized response, or the resolved request body does not JSONata-evaluate to a JSON object), the run SHALL stop. Earlier turns MUST persist as SUCCESS rows; the failing turn MUST persist as one ERROR row; later turns MUST NOT be sent.

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| Failure at turn k | turn k of N fails | turns `0..k-1` persist as SUCCESS rows, turn k persists as one ERROR row, and turns `k+1..N-1` produce no rows |
| Non-object evaluated body fails the run at runtime | a turn's resolved request body JSONata-evaluates to a value that is not a JSON object (e.g. a scalar or array), or evaluation throws | that turn persists one ERROR row and other cases continue (this is not a suite-validation failure) |

### Requirement: MCP suites reject multi-turn datasets

Status: **Implemented**

Multi-turn is supported only for HTTP suites (DEPLOYMENT and ROUTE). A run creation for an MCP suite bound to a dataset containing any multi-turn case SHALL be rejected.

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| MCP + multi-turn rejected at run creation | a run is created for an `MCP_TOOL` suite whose dataset contains at least one case with `multi_turn_data` | it is rejected with HTTP 409 `INVALID_OPERATION` |
| ROUTE suite with a multi-turn dataset is not rejected at run creation | a run is created for a ROUTE suite bound to a dataset containing multi-turn cases | run creation SHALL NOT be rejected on that ground; the MCP-only guard does not apply |

## Implementation notes

- [TurnLoopExecutor.java](../../../evaluation-runner-core/src/main/java/com/epam/aidial/evaluation/runner/job/TurnLoopExecutor.java): sequential per-request execution and frame propagation.
- [TestSuiteRunService.java](../../../src/main/java/com/epam/aidial/evaluation/service/domain/TestSuiteRunService.java): run-start guards.
