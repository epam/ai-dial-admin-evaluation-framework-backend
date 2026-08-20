## REMOVED Requirements

### Requirement: Try it out with test case data
**Reason**: The requirement's scenario "MCP suite rejects multi-turn test case" mandates an HTTP 409 for a combination this change makes runnable; preview must not reject what a real run accepts. Every other scenario is carried forward verbatim by the ADDED requirement "Try it out with test case data (any suite type)", which additionally states that multi-turn preview is available for both suite types.
**Migration**: A try-it-out call for an `MCP_TOOL` suite whose test case carries `multiTurnData` now returns HTTP 200 with a per-turn `history` instead of HTTP 409 `INVALID_OPERATION`. Clients that treated that 409 as "preview unsupported for this case" SHALL treat the 200 response the same way they already treat a multi-turn DEPLOYMENT preview. No behaviour changes for single-turn cases or for DEPLOYMENT suites.

## MODIFIED Requirements

### Requirement: Try it out with MCP tool call (test case)

The system SHALL support try-it-out for MCP_TOOL suites via `POST /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out`. For MCP suites, the system SHALL resolve tool arguments from the argument template using effective bindings and test case data, execute the MCP tool call, and return the MCP response. When the test case is multi-turn under the same turn-count decision used for HTTP suites, the system SHALL execute every turn sequentially and return the last executed turn's result plus a per-turn `history`, threading each successful turn's extracted response columns into the next turn's frame — so a `jsonataArguments` template behaves in preview exactly as it does in a real run.

Status: **Planned**

#### Scenario: Successful MCP try-it-out with test case
- **WHEN** authenticated user sends POST to `/api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out`
- **AND** the test suite has `suiteType = MCP_TOOL` with valid `mcpDeploymentRef`, `toolRef`, and `argumentTemplate`
- **THEN** the system SHALL determine effective bindings: test case `inputBindingsOverride` (if non-null) takes priority over suite-level `inputBindings` (same override semantics as HTTP suites)
- **AND** resolve tool arguments from the argument template, the test case data, and an empty frame
- **AND** determine the MCP transport from `mcpDeploymentRef.transport` (defaulting to `STREAMABLE_HTTP` when null)
- **AND** execute the tool call against `mcpDeploymentRef.id` and `toolRef.name`
- **AND** return HTTP 200 with `TryItOutResponseDto` containing the resolved arguments, the serialized MCP response, and execution duration

#### Scenario: Multi-turn MCP test case executes every turn
- **WHEN** an `MCP_TOOL` suite's effective bindings reference a `perTurn=true` schema field and the test case carries N entries in `multiTurnData`
- **THEN** the system SHALL invoke the tool once per turn, in order, each turn resolving arguments from the merge of shared `data` with that turn's entry
- **AND** each turn after the first SHALL be resolved with `frameBindings` derived from the previous turn's extracted response columns
- **AND** the response SHALL carry the last executed turn's result at the top level plus a `history` array with one entry per executed turn

#### Scenario: Multi-turn MCP case with no per-turn binding collapses to a single call
- **WHEN** an `MCP_TOOL` suite binds no `perTurn=true` field and the test case carries more than one `multiTurnData` entry
- **THEN** the system SHALL invoke the tool once using only the shared `data`, and the response SHALL have single-turn shape with no `history`

#### Scenario: MCP turn failure stops the sequence
- **WHEN** a turn's tool call returns `isError = true`, times out, fails at transport level, or its arguments fail JSONata evaluation
- **THEN** the system SHALL stop executing further turns and return that turn's result as both the top-level result and the last `history` entry

#### Scenario: MCP suite missing mcpDeploymentRef
- **WHEN** the MCP_TOOL suite has `mcpDeploymentRef` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: MCP suite missing toolRef
- **WHEN** the MCP_TOOL suite has `toolRef` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: MCP tool call returns isError
- **WHEN** the MCP tool call returns `isError = true`
- **THEN** the try-it-out endpoint SHALL still return HTTP 200
- **AND** `response.body` SHALL contain the serialized MCP response with `isError: true`

#### Scenario: MCP tool call timeout
- **WHEN** the MCP tool call times out
- **THEN** the system SHALL return HTTP 504 with error code `UPSTREAM_TIMEOUT`

#### Scenario: Preview covers request #0 only
- **WHEN** an `MCP_TOOL` suite carries a non-empty `additionalRequests` chain
- **THEN** try-it-out SHALL preview request #0 only, consistently with how a `DEPLOYMENT` suite's chain is previewed

## ADDED Requirements

### Requirement: Try it out with test case data (any suite type)
The system SHALL provide `POST /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out` to resolve the effective request template using the test case's data and effective bindings, send the resolved request to the DIAL Core deployment referenced by the test suite, and return the deployment's response along with the resolved request details. When the test case is multi-turn, the system SHALL execute every turn of the sequence and return the result of the last executed turn, plus a per-turn `history` when more than one turn actually ran. Multi-turn preview SHALL be available for both suite types and SHALL NOT be refused on the basis of `suiteType`; the MCP variant of the invocation itself is specified in "Try it out with MCP tool call (test case)".

Status: **Planned**

#### Scenario: Successful try-it-out with test case
- **WHEN** authenticated user sends POST to `/api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out`
- **AND** the test suite has a valid `deploymentRef`, `requestTemplate`, and `endpointRef`
- **AND** the test case exists and belongs to the test suite
- **THEN** the system SHALL load the suite (for `deploymentRef`/`endpointRef` precondition validation via `JsonbMapper` deserialization), then delegate turn planning and resolution to `ResolvedRequestService`, which handles test-case loading, effective template/bindings determination, turn planning, and resolution within its own `@Transactional(readOnly=true)` scope. Note: on the single-turn collapse path (no `multiTurnData`, or `multiTurnData` present but no per-turn binding) this results in the suite being loaded 3 times and the test case being loaded 2 times — once via `loadSuite`, once via `planTurns` (suite + test case), and once more via the separate `resolveRequest` call (suite + test case) that `tryWithTestCase` makes when the plan collapses to a single turn. On the true multi-turn path (N>1 turns), the suite is loaded only 2 times and the test case only 1 time — via `loadSuite` and `planTurns` alone, since `resolveRequest` is not called in that case. This is an accepted trade-off for clear pre-validation errors without modifying `ResolvedRequestService`.
- **AND** send the resolved request(s) to the DIAL Core deployment (after the transaction completes and the DB connection is released)
- **AND** return HTTP 200 with `TryItOutResponseDto` containing the resolved request, the deployment's response (status code + body), and execution duration in milliseconds

#### Scenario: Test case with template/bindings overrides
- **WHEN** the test case has `requestTemplateOverride` and/or `inputBindingsOverride`
- **THEN** the system SHALL use the overrides instead of suite-level template/bindings for resolution

#### Scenario: Test suite not found
- **WHEN** user sends try-it-out request with non-existent `testSuiteId`
- **THEN** the system SHALL return HTTP 404 with error code `NOT_FOUND`

#### Scenario: Test case not found
- **WHEN** user sends try-it-out request with non-existent `testCaseId` or a test case that does not belong to the test suite
- **THEN** the system SHALL return HTTP 404 with error code `NOT_FOUND`

#### Scenario: Multi-turn test case executes every turn
- **WHEN** the test case has non-null `multiTurnData` and at least one effective input binding references a dataset schema field flagged `perTurn=true` (same turn-count decision as `PerTurnBindingDetector` uses for real runs)
- **THEN** the system SHALL resolve and invoke each turn sequentially, in order, from turn 0 to turn N-1, where N is the number of entries in `multiTurnData`
- **AND** each turn's effective data SHALL be the merge of the test case's shared `data` with that turn's entry (per-turn wins on key collision)
- **AND** each turn after the first SHALL be resolved using `frameBindings` derived from the response columns extracted from the previous turn's response (via the suite's `responseColumns` definitions), enabling `$history`-style JSONata expressions in the request template to accumulate across turns exactly as they do in a real suite run
- **AND** the first turn SHALL be resolved with empty `frameBindings`
- **AND** the response SHALL be a `TryItOutResponseDto` containing the last executed turn's `resolvedRequest`/`response`/`durationMs`/`traceId` at the top level, plus a `history` array with one entry per executed turn (see "Multi-turn response includes per-turn history")

#### Scenario: Multi-turn data present but no per-turn binding collapses to a single turn
- **WHEN** the test case has non-null `multiTurnData` with more than one entry, but no effective input binding references any `perTurn=true` schema field
- **THEN** the system SHALL treat the case as a single turn using only the shared `data` (identical to the `PerTurnBindingDetector` collapse behavior used by real runs)
- **AND** the response SHALL be identical in shape to a single-turn test case's response (no `history`)

#### Scenario: Turn failure stops the sequence
- **WHEN** executing a multi-turn test case and a turn's invocation resolves to a non-2xx DIAL Core status, or fails request-body JSONata evaluation (`RequestBodyEvaluationException`)
- **THEN** the system SHALL stop executing further turns (fail-fast)
- **AND** the failed turn's resolved request and error response SHALL be returned as the `resolvedRequest`/`response`, and as the last entry of `history`
- **NOTE**: a transport-level failure during a turn's invocation (timeout, connection refused, unreachable deployment) is NOT caught by this mechanism — it propagates uncaught exactly as in the single-turn path, producing the pre-existing plain 502/504 error response.
- **NOTE**: `ValidationException` (null resolved URL) or `TryItOutValidationException` (unresolved REQUIRED template variables) thrown by `validateResolutionResult` for turns after the first are ALSO not caught by this mechanism — `runTurnSequence`'s catch clause only catches `RequestBodyEvaluationException`. These propagate uncaught exactly like transport-level failures.

#### Scenario: Multi-turn preview is not refused by suite type
- **WHEN** a try-it-out request targets an `MCP_TOOL` suite whose test case carries non-null `multiTurnData`
- **THEN** the system SHALL NOT return HTTP 409 `INVALID_OPERATION`, and SHALL instead preview the case under the same turn-count decision applied to DEPLOYMENT suites

## Implementation Notes

- `TryItOutService`'s MCP branch reuses the existing `runTurnSequence` shape rather than a parallel loop; turn planning comes from the same per-turn binding decision `ResolvedRequestService.planTurns` already applies to HTTP suites.
- Chain preview remains out of scope for both suite types — a pre-existing gap, made explicit here so MCP and DEPLOYMENT preview behave alike.
- The core requirement is retired and re-added under a widened name rather than modified in place, because it carried a scenario mandating an HTTP 409 that no longer describes any reachable state.
