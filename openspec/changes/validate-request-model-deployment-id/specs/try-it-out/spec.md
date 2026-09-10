## ADDED Requirements

### Requirement: Try-It-Out validates the resolved deployment model

All deployment Try-It-Out modes SHALL validate the resolved body for canonical OpenAI Responses and Anthropic Messages requests against the suite's deployment ID immediately before invocation. A failure SHALL expose `REQUEST_BODY_VALIDATION_ERROR`, include the resolved request for diagnosis, and SHALL not call DIAL Core.

Status: **Planned**

#### Scenario: Single-invocation Try-It-Out rejects an invalid model

- **WHEN** a single-invocation test-case or variables Try-It-Out resolves an invalid `model`
- **THEN** the endpoint SHALL return HTTP 400 with outer error code `VALIDATION_ERROR`
- **AND** the error details' resolved request SHALL carry a `REQUEST_BODY_VALIDATION_ERROR` warning
- **AND** DIAL Core SHALL not be invoked

#### Scenario: Chained Try-It-Out reports an invalid model as the failed invocation

- **WHEN** model validation fails during a multi-request or multi-turn Try-It-Out
- **THEN** the returned failed invocation SHALL contain a status-code-zero error response with code `REQUEST_BODY_VALIDATION_ERROR`
- **AND** its resolved request SHALL be retained in the top-level response and history
- **AND** no later invocation in the chain SHALL run

#### Scenario: Matching model invokes normally

- **WHEN** the resolved `model` matches the suite's deployment ID
- **THEN** Try-It-Out SHALL invoke DIAL Core using its existing response contract

## MODIFIED Requirements

### Requirement: Try it out with test case data
The system SHALL provide `POST /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out` to resolve the effective request template using the test case's data and effective bindings, send the resolved request to the DIAL Core deployment referenced by the test suite, and return the deployment's response along with the resolved request details. When the test case is multi-turn, the system SHALL execute every turn of the sequence and return the result of the last executed turn, plus a per-turn `history` when more than one turn actually ran.

Status: **Implemented**

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
- **WHEN** executing a multi-turn test case and a turn's invocation resolves to a non-2xx DIAL Core status, fails request-body JSONata evaluation (`RequestBodyEvaluationException`), or fails resolved request-model validation (`RequestBodyValidationException`)
- **THEN** the system SHALL stop executing further turns (fail-fast)
- **AND** the failed turn's resolved request and error response SHALL be returned as the `resolvedRequest`/`response`, and as the last entry of `history`
- **NOTE**: a transport-level failure during a turn's invocation (timeout, connection refused, unreachable deployment) is NOT caught by this mechanism — it propagates uncaught exactly as in the single-turn path, producing the pre-existing plain 502/504 error response.
- **NOTE**: `ValidationException` (null resolved URL) or `TryItOutValidationException` (unresolved REQUIRED template variables) thrown by `validateResolutionResult` for turns after the first remain uncaught. `runChain` catches only `RequestBodyEvaluationException` and the new `RequestBodyValidationException`; other validation failures propagate exactly like transport-level failures.

#### Scenario: MCP suite rejects multi-turn test case
- **WHEN** the test suite has `suiteType = MCP_TOOL` and the test case has non-null `multiTurnData`
- **THEN** the system SHALL return HTTP 409 with error code `INVALID_OPERATION`, without invoking the MCP tool
- **AND** the error message SHALL indicate that MCP suites do not support multi-turn test cases (consistent with the existing rejection of MCP + multi-turn at run creation)
- **NOTE**: the check is a coarse presence check (`multiTurnData != null`), not a `PerTurnBindingDetector`-based collapse check — it rejects even when the data would collapse to a single turn, matching the existing run-creation guard's coarseness.

## Implementation notes

Planned. `TryItOutService` gains a `RequestBodyValidationException` catch clause in `runChain` alongside the existing `RequestBodyEvaluationException` clause, reusing `buildEvaluationFailureResult`'s status-code-zero envelope; the single-invocation paths convert the same exception into the established HTTP 400 `TryItOutValidationException` with a `REQUEST_BODY_VALIDATION_ERROR` warning. The check itself is the shared `RequestModelValidator` (`evaluation-runner-core`, `com.epam.aidial.evaluation.runner.service`) invoked at the pre-invocation boundary — deliberately NOT inside `validateResolutionResult`, which is shared with the single-invocation path.
