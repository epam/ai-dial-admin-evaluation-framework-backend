## REMOVED Requirements

### Requirement: MCP suites reject additional requests
**Reason**: The rejection existed only because an MCP argument template had no JSONata-capable form and therefore could not consume an earlier request's extracted columns. That gap is closed by the `jsonataArguments` authoring form (see `request-template` and `mcp-tool-invocation`), so an MCP suite can now express a chain with the same accumulating-frame semantics as a DEPLOYMENT suite. Replaced by "Chain entries are homogeneous with the suite type".
**Migration**: No client action required. A non-empty `additionalRequests` on an `MCP_TOOL` suite is now accepted at create, update, and clone instead of returning HTTP 400. Each entry SHALL carry `toolRef` and `argumentTemplate` rather than `endpointRef` and `requestTemplate`; submitting HTTP-shaped entries on an MCP suite is rejected with HTTP 400 (`VALIDATION_ERROR`).

### Requirement: Request dimension on analytics rows
**Reason**: The requirement's closing clause ("The MCP execution path SHALL never stamp them") and its scenario "MCP rows never stamp request columns" assert a universal that stops being true once an MCP suite can carry a chain — a chained MCP suite stamps the dimension exactly as a chained DEPLOYMENT suite does. Replaced verbatim in every other respect by the ADDED requirement "Request dimension on analytics rows for both suite types".
**Migration**: No client action and no data migration. The columns, defaults, contiguity rule, and orthogonality to the turn dimension are unchanged; only the suite-type carve-out is dropped. Rows written by single-request suites of either type remain byte-identical to rows written before this change.

### Requirement: Chain execution is owned by a dedicated runner component
**Reason**: The requirement mandates that "the `MCP_TOOL` branch SHALL be unchanged" and its scenario "MCP path is untouched" requires the chain executor not to be involved for MCP suites — the exact arrangement this change replaces, since MCP execution now flows through the same chain executor and turn loop. Replaced by the ADDED requirement "Chain execution is owned by a dedicated runner component for both suite types", which keeps the module-ownership and boundary-contract rules intact.
**Migration**: No client-visible change. Rows produced for a single-request MCP suite are unchanged; what changes is which internal component produces them.

## MODIFIED Requirements

### Requirement: Request definition shape

Each element of `additionalRequests` SHALL be a `RequestDefinitionDto` carrying the **full** per-request definition: `name` (`String`, optional, max 255 — a user-facing label), `responseColumns` (`List<ResponseColumnDefinitionDto>`, defaults to empty), `inputBindings` (`List<InputBindingDto>`, defaults to empty), plus the transport-specific pair for the suite's type — `endpointRef` (`EndpointContractDto`) and `requestTemplate` (`RequestTemplateDto`) for a `DEPLOYMENT` suite, or `toolRef` (`ToolReferenceDto`) and `argumentTemplate` (`ArgumentTemplateDto`) for an `MCP_TOOL` suite. All four transport-specific fields SHALL be nullable on the DTO; which pair is required is decided by the suite's `suiteType` (see "Chain entries are homogeneous with the suite type"). A `RequestDefinitionDto` SHALL NOT carry a `deploymentRef`, an `mcpDeploymentRef`, `testCaseFilter`, `overallScore`, or any dataset reference — the execution target and those settings remain suite-level. `additionalRequests` SHALL be persisted as a JSONB array column `additional_requests` on `test_suites`, `NOT NULL DEFAULT '[]'`.

Every element of `additionalRequests` SHALL be a non-null object. A JSON `null` element SHALL be rejected at write time with HTTP 400 (`VALIDATION_ERROR`), and the error message SHALL name the offending 0-based index within `additionalRequests`. A null element SHALL NOT be silently dropped, coerced to an empty request definition, or allowed to reach persistence — a null in the middle of a chain would otherwise shift every later request's `request_index` away from the author's intent.

Status: **Planned**

#### Scenario: Additional request round-trips all five fields
- **WHEN** a suite is created with an additional request carrying `name`, `endpointRef`, `requestTemplate`, `responseColumns` and `inputBindings`
- **THEN** `GET /api/v1/test-suites/{id}` SHALL return all five values unchanged

#### Scenario: Additional MCP request round-trips its fields
- **WHEN** an `MCP_TOOL` suite is created with an additional request carrying `name`, `toolRef`, `argumentTemplate`, `responseColumns` and `inputBindings`
- **THEN** `GET /api/v1/test-suites/{id}` SHALL return all five values unchanged, with `endpointRef` and `requestTemplate` absent

#### Scenario: Omitted per-request lists default to empty
- **WHEN** an additional request omits `responseColumns` and `inputBindings`
- **THEN** the persisted and returned definition SHALL carry `[]` for both, and the request SHALL execute with no bindings and extract no columns

#### Scenario: Name is optional
- **WHEN** an additional request omits `name`
- **THEN** the suite SHALL be accepted and the request's label SHALL be `null`

#### Scenario: Name over 255 characters is rejected
- **WHEN** an additional request has a `name` longer than 255 characters
- **THEN** the request SHALL be rejected with HTTP 400 (`VALIDATION_ERROR`)

#### Scenario: Chain entry carrying a deployment target is rejected
- **WHEN** an additional request carries a `deploymentRef` or an `mcpDeploymentRef`
- **THEN** the request SHALL be rejected with HTTP 400 (`VALIDATION_ERROR`) — the execution target is suite-level

#### Scenario: Null chain element is rejected
- **WHEN** a suite is created or updated with `additionalRequests: [ {...}, null ]`
- **THEN** the system SHALL respond HTTP 400 (`VALIDATION_ERROR`) with a message naming index `1`, and SHALL NOT persist the suite

#### Scenario: Null element is not silently dropped
- **WHEN** a suite is submitted with a null element between two valid additional requests
- **THEN** the system SHALL reject the write rather than persist a 2-element chain, so no request's `request_index` silently shifts

## ADDED Requirements

### Requirement: Request dimension on analytics rows for both suite types

`test_case_run_results` and `test_case_eval_summaries` SHALL each carry `request_index INTEGER NOT NULL DEFAULT 0` and `total_requests INTEGER NOT NULL DEFAULT 1`, identifying the row's position in the chain and the chain's length. Indices SHALL be contiguous `0..totalRequests-1`. The request dimension SHALL be orthogonal to the turn dimension: a row's identity within a test-case repetition is `(request_index, turn_index)`. The values SHALL be stamped **only when the chain length is greater than 1**; a single-request chain SHALL leave both at their builder/DB defaults, so its rows are byte-identical to rows written before this capability existed. The rule SHALL be identical for both suite types — no suite type is exempt from stamping, and none stamps when its chain length is one.

Status: **Planned**

#### Scenario: Chain rows carry contiguous request indices
- **WHEN** a 3-request chain runs
- **THEN** its rows SHALL carry `request_index` 0, 1, 2 with `total_requests = 3` on every row

#### Scenario: Single-request suite leaves the defaults
- **WHEN** a suite with empty `additionalRequests` runs
- **THEN** every row SHALL have `request_index = 0` and `total_requests = 1` from the defaults, with no explicit stamping

#### Scenario: Request and turn dimensions are independent
- **WHEN** a chain's request #1 is multi-turn with 2 turns
- **THEN** its rows SHALL be `(request_index=1, turn_index=0)` and `(request_index=1, turn_index=1)`, with `total_requests` and `total_turns` both populated

#### Scenario: MCP chain rows carry the request dimension
- **WHEN** an `MCP_TOOL` suite with two `additionalRequests` entries runs
- **THEN** its rows SHALL carry `request_index` 0, 1, 2 with `total_requests = 3`

#### Scenario: Single-request MCP suite leaves request columns at defaults
- **WHEN** an `MCP_TOOL` suite with empty `additionalRequests` runs
- **THEN** its row SHALL carry `request_index = 0` and `total_requests = 1` from the defaults

### Requirement: Chain execution is owned by a dedicated runner component for both suite types

Chain orchestration SHALL live in the `evaluation-runner-core` module as `com.epam.aidial.evaluation.runner.job.RequestChainExecutor`, so a standalone runner shares exact chain semantics with the EF backend. It SHALL build the ordered chain from the run snapshot, thread the accumulated frame through it, delegate each request to the turn-loop executor, stamp the request dimension, and stop on the first aborting request. Both the `DEPLOYMENT` and the `MCP_TOOL` execution paths SHALL delegate to it; there SHALL be no suite-type branch in the worker ahead of the chain executor. The turn-loop executor SHALL take a per-request specification and return both its rows and the frame it ended with; its turn planning, extraction, error mapping, and row building SHALL be shared by both suite types, with only the per-turn resolve-and-invoke step selected by suite type. That selection SHALL be made once per chain rather than per request, since a chain is homogeneous. New classes SHALL satisfy the module's boundary contract (no JDBC/jOOQ/Flyway, no dependency on the EF backend, `@LogExecution` on every Spring component).

Status: **Planned**

#### Scenario: Chain executor performs no database access
- **WHEN** the module's boundary test runs
- **THEN** `RequestChainExecutor` and its collaborators SHALL contain no JDBC/jOOQ/Flyway dependency and SHALL carry `@LogExecution`

#### Scenario: MCP execution flows through the chain executor
- **WHEN** an `MCP_TOOL` suite executes
- **THEN** its rows SHALL be produced by the same chain executor and turn loop as a DEPLOYMENT suite's, with the MCP invocation step selected once for the whole chain

#### Scenario: Turn and chain semantics are identical across suite types
- **WHEN** a DEPLOYMENT chain and an `MCP_TOOL` chain of the same length and turn counts run
- **THEN** both SHALL produce the same number of rows, in the same `(request_index, turn_index)` order, with the same fail-fast behaviour

#### Scenario: Invocation step is selected once per chain
- **WHEN** a chain of any length executes
- **THEN** the per-turn invocation implementation SHALL be resolved once from the suite's `suiteType`, and no per-entry transport decision SHALL be made

### Requirement: Chain entries are homogeneous with the suite type
A suite SHALL have exactly one execution target — `deploymentRef` for a `DEPLOYMENT` suite or `mcpDeploymentRef` for an `MCP_TOOL` suite — so every entry of its `additionalRequests` SHALL be shaped for that suite's type. For a `DEPLOYMENT` suite each entry SHALL carry `endpointRef` and/or `requestTemplate` and SHALL NOT carry `toolRef` or `argumentTemplate`; for an `MCP_TOOL` suite each entry SHALL carry `toolRef` and/or `argumentTemplate` and SHALL NOT carry `endpointRef` or `requestTemplate`. An entry populating neither pair, or populating both, SHALL be rejected with HTTP 400 (`VALIDATION_ERROR`) naming the offending 0-based index. Mixed chains SHALL NOT be expressible. `suiteType` SHALL remain the sole discriminator: an entry's populated fields SHALL be validated against it and SHALL NOT be used to derive a per-entry type.

Status: **Planned**

#### Scenario: MCP entry on an MCP suite is accepted
- **WHEN** an `MCP_TOOL` suite is created with an `additionalRequests` entry carrying `toolRef` and `argumentTemplate`
- **THEN** the suite SHALL be persisted and the entry SHALL execute as a tool call

#### Scenario: HTTP entry on an MCP suite is rejected
- **WHEN** an `MCP_TOOL` suite is submitted with an `additionalRequests` entry carrying `endpointRef` or `requestTemplate`
- **THEN** the system SHALL respond HTTP 400 (`VALIDATION_ERROR`) naming the entry's index, and SHALL NOT persist the suite

#### Scenario: MCP entry on a DEPLOYMENT suite is rejected
- **WHEN** a `DEPLOYMENT` suite is submitted with an `additionalRequests` entry carrying `toolRef` or `argumentTemplate`
- **THEN** the system SHALL respond HTTP 400 (`VALIDATION_ERROR`) naming the entry's index, and SHALL NOT persist the suite

#### Scenario: Entry populating both pairs is rejected
- **WHEN** an entry carries both `requestTemplate` and `argumentTemplate`
- **THEN** the system SHALL respond HTTP 400 (`VALIDATION_ERROR`) naming the entry's index

#### Scenario: Entry populating neither pair is rejected
- **WHEN** an entry carries only `name`, `responseColumns` and `inputBindings`, with no transport-specific pair
- **THEN** the system SHALL respond HTTP 400 (`VALIDATION_ERROR`) naming the entry's index

#### Scenario: Homogeneity is enforced on update and clone
- **WHEN** a `PUT` or a clone would produce an effective suite whose chain contains an entry mismatched with its `suiteType`
- **THEN** the write SHALL be rejected with HTTP 400 and the stored suite SHALL be unchanged

## Implementation Notes

- The chain-length cap (`RunnerValidationConstants.MAX_ADDITIONAL_REQUESTS`) and the suite-wide response-column union cap (`RunnerValidationConstants.MAX_RESPONSE_COLUMNS`) apply to both suite types unchanged; `ResponseColumnUnionResolver` already walks `additionalRequests` without regard to transport and needs no change.
- Per-request turn counts continue to come from each entry's own `inputBindings` via `PerTurnBindingDetector`, so an MCP chain may mix multi-turn and single-turn entries exactly as an HTTP chain does.
- No migration: `additional_requests` already exists and the analytics request/turn columns (V1.13, V1.14, V1.17, V1.18) are transport-neutral.
- Two requirements are retired and re-added under widened names rather than modified in place, because each carried a scenario asserting a suite-type carve-out that no longer describes any reachable state.
