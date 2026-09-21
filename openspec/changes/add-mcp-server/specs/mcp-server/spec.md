## Purpose

Defines the cross-cutting behaviour of the Evaluation Framework's MCP server: how agents connect and authenticate, the conventions of the MCP tool contract, error and unsupported-feature semantics, suite-centric dataset handling, asynchronous run semantics, result size limits and agent guidance. Individual tool groups are specified in `mcp-tools-<group>` specs delivered by child changes.

## ADDED Requirements

### Requirement: MCP endpoint over Streamable HTTP
The service SHALL expose a single MCP endpoint implementing the MCP Streamable HTTP protocol (JSON-RPC over HTTP POST). The protocol variant SHALL be configurable via `spring.ai.mcp.server.protocol`: `STATELESS` (default — one JSON response per POST, no `Mcp-Session-Id`, no SSE) or `STREAMABLE` (SSE response streams and `Mcp-Session-Id` session management); both SHALL serve the same configured endpoint path. The endpoint path SHALL be configurable and default to `/mcp`; the security rules of the `security` capability SHALL apply to whatever path is configured. The server SHALL advertise the `tools` capability and SHALL NOT advertise `resources` or `prompts` in this version. The server SHALL be disableable by configuration (`spring.ai.mcp.server.enabled=false`).
Status: **Implemented** — delivered by `mcp-foundation`; `spring.ai.mcp.server.*` in `application.yml` (see `docs/configuration.md` §2.6), `SecurityConfiguration`'s matcher authenticates exactly the configured `mcp-endpoint` path (the one path both the `STREAMABLE` and `STATELESS` transports serve — neither serves a sub-path). The deprecated `SSE` transport (`/sse`, `/mcp/message`) is out of scope and unsupported by the matcher. Verified by `McpServerStatelessFunctionalTests` (default `STATELESS`: raw `initialize` returns an `application/json` JSON-RPC object with no `Mcp-Session-Id`; `tools/list`, caller probe and `get_deployment`), `McpServerFoundationFunctionalTests` (pinned to `STREAMABLE`: `initialize`/`tools/list`, session behaviour) and `McpServerDisabledFunctionalTests`/`McpServerDisabledOidcFunctionalTests` (404 when disabled, in both `none` and `oidc` modes).

#### Scenario: Initialize and list tools
- **WHEN** an MCP client sends `initialize` followed by `tools/list` to the MCP endpoint
- **THEN** the server SHALL complete the handshake (returning an `Mcp-Session-Id` only in `STREAMABLE` mode) and list every registered tool with its name, description and JSON input schema

#### Scenario: Server disabled in oidc mode
- **WHEN** `spring.ai.mcp.server.enabled=false` and `config.rest.security.mode=oidc` and an authenticated client POSTs an `initialize` request to the configured MCP path
- **THEN** the service SHALL respond with HTTP 404 and SHALL NOT return an `InitializeResult`

#### Scenario: Server disabled in none mode
- **WHEN** `spring.ai.mcp.server.enabled=false` and `config.rest.security.mode=none` and a client POSTs an `initialize` request to the configured MCP path
- **THEN** the service SHALL respond with HTTP 404 and SHALL NOT return an `InitializeResult`

### Requirement: Tool contract conventions
Tool names SHALL be `snake_case`. Every tool SHALL take exactly one structured input object whose properties carry non-empty descriptions, and SHALL return its result as a single `text` content block containing a JSON document (object), additionally provided as `structuredContent` when the tool declares an output schema. Field names in inputs and outputs SHALL be `camelCase`; timestamps SHALL be epoch milliseconds; identifiers SHALL be UUID strings. Every tool description SHALL state its preconditions and, where applicable, the tool an agent is expected to call next. Tools that mutate state SHALL return the resulting entity. Tools SHALL be keyed by `suiteId` wherever an operation concerns a suite's request template, response columns, test cases, schema, metrics or runs. Every tool SHALL declare its MCP tool annotations explicitly — `title`, `readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint` — never relying on the protocol defaults (which advertise an unannotated tool as destructive and open-world): query tools SHALL declare `readOnlyHint=true`, `destructiveHint=false`, `idempotentHint=true`; mutating tools SHALL declare `readOnlyHint=false` and set `destructiveHint`/`idempotentHint` according to their effect; all tools SHALL declare `openWorldHint=false` (they operate on the closed domain of DIAL Core and this service). The declaration SHALL be enforced project-wide by `McpToolConventionTest`.
Status: **Partially implemented** — the annotation rule is implemented for `list_deployments`/`get_deployment`: `McpToolConventionTest` enforces a non-blank `title`, `readOnlyHint == !destructiveHint` and `openWorldHint == false` for every `@McpTool`; an explicit `idempotentHint` cannot be distinguished from the default statically, so the advertised value is asserted by the `tools/list` functional test. The remaining conventions are verified per tool group as the children land.

#### Scenario: Tool metadata inspected
- **WHEN** an agent calls `tools/list`
- **THEN** each tool SHALL have a `snake_case` name, a non-empty description, and every input property SHALL have a non-empty description

#### Scenario: Tool annotations declared explicitly
- **WHEN** an agent calls `tools/list`
- **THEN** every tool SHALL carry `annotations` with a non-blank `title`, `readOnlyHint` equal to the negation of `destructiveHint`, an explicit `idempotentHint`, and `openWorldHint=false`
- **AND** `list_deployments` and `get_deployment` SHALL report `readOnlyHint=true`, `destructiveHint=false`, `idempotentHint=true`

#### Scenario: Successful tool result shape
- **WHEN** any tool completes successfully
- **THEN** the result SHALL have `isError=false` and exactly one `text` content block whose text parses as a JSON object

### Requirement: Structured tool error contract
When a tool cannot complete, the server SHALL return a tool result with `isError=true` and exactly one `text` content block whose text is a JSON object with `code` (stable machine-readable string), `message` (agent-readable) and optional `details` (array of strings with field-level messages). Codes SHALL be: `NOT_FOUND`, `VALIDATION_ERROR`, `INVALID_OPERATION`, `ACCESS_DENIED`, `VERSION_CONFLICT`, `UNIQUE_CONSTRAINT_VIOLATION`, `TOO_MANY_REQUESTS`, `RUN_NOT_TERMINAL`, `SUITE_HAS_NO_DATASET`, `PAYLOAD_TOO_LARGE`, `UPSTREAM_ERROR`, `UPSTREAM_TIMEOUT`, `UPSTREAM_AUTH_ERROR`, `NOT_SUPPORTED`, `INTERNAL_ERROR`. Where a code also exists in the REST `ErrorCode` vocabulary it SHALL carry the same meaning. Tool error content SHALL NOT include stack traces, SQL text or internal class names. The full exception SHALL be logged server-side.

**Known limitation:** argument *type* mismatches (e.g. a JSON number where a string is declared) are rejected by Spring AI's own binding layer before a tool runs, and surface as Spring AI's plain-text `isError` result — outside this structured contract. Tool parameters are restricted to `String`/`Boolean`/`Integer`/`Long`/records so that only a type mismatch, never a value error, can take this path.
Status: **Implemented** — `mcp.support.McpToolResults` (encoding), `mcp.support.McpToolErrorTranslator` (exception → code, every `DialCoreErrorCode` value plus `ResourceAccessException`), `mcp.model.McpErrorCode`/`McpToolError`; `McpErrorCodeAlignmentTest` asserts every code except `NOT_SUPPORTED` exists in `web.handler.ErrorCode`; verified end-to-end by `McpServerFoundationFunctionalTests`. `PayloadTooLargeException` has no row (checked exception, cannot reach the translator's `RuntimeException` catch) — see `design.md` D7 deviation note.

#### Scenario: Entity not found
- **WHEN** a tool is called with a `suiteId` that does not exist
- **THEN** the result SHALL have `isError=true` with `code="NOT_FOUND"` and a message naming the missing entity type and id

#### Scenario: Validation failure
- **WHEN** a tool input violates a domain validation rule (for example an invalid JSONata expression in a response column)
- **THEN** the result SHALL have `isError=true` with `code="VALIDATION_ERROR"` and `details` listing each violated field or rule

#### Scenario: Duplicate name
- **WHEN** `create_test_suite` is called with a suite name that already exists
- **THEN** the result SHALL have `isError=true` with `code="UNIQUE_CONSTRAINT_VIOLATION"`

#### Scenario: Upstream failure
- **WHEN** a tool depends on DIAL Core and DIAL Core returns an error, times out, or is unreachable
- **THEN** the result SHALL have `isError=true` with `code` equal to `UPSTREAM_ERROR`, `UPSTREAM_TIMEOUT` or `UPSTREAM_AUTH_ERROR` respectively, and a message that does not expose credentials or internal URLs beyond the deployment name

#### Scenario: Unexpected failure
- **WHEN** a tool fails with an error that has no specific mapping
- **THEN** the result SHALL have `isError=true` with `code="INTERNAL_ERROR"` and a generic message without internal details

### Requirement: Forward-compatible models with explicit unsupported features
MCP models SHALL include the fields required to express multi-request suites (`additionalRequests`), multi-turn test cases (`multiTurnData`) and `MCP_TOOL` suites (`suiteType=MCP_TOOL`, `mcpDeploymentRef`, `toolRef`, `argumentTemplate`). In this version the server SHALL reject any tool call that sets one of these to a non-empty value with `code="NOT_SUPPORTED"` before performing any change, and the descriptions of the affected tools and properties SHALL state that only single-request, single-turn `DEPLOYMENT` suites are supported.
Status: **Partially implemented** — enforcement shipped by mcp-foundation (`mcp.support.UnsupportedFeatureGuard`/`UnsupportedFeatureException` → `NOT_SUPPORTED`, `McpToolDescriptions.UNSUPPORTED_FEATURES_SENTENCE`, unit-tested); no production tool uses it until `mcp-test-suites`.

#### Scenario: Multi-request suite requested
- **WHEN** `create_test_suite` or `update_test_suite` is called with a non-empty `additionalRequests`
- **THEN** the result SHALL be `isError=true`, `code="NOT_SUPPORTED"` and no suite SHALL be created or modified

#### Scenario: MCP_TOOL suite requested
- **WHEN** `create_test_suite` is called with `suiteType=MCP_TOOL` or any of `mcpDeploymentRef`, `toolRef`, `argumentTemplate` set
- **THEN** the result SHALL be `isError=true`, `code="NOT_SUPPORTED"` and no suite SHALL be created

#### Scenario: Multi-turn test case requested
- **WHEN** `add_test_cases` is called with an item whose `multiTurnData` is non-empty
- **THEN** the result SHALL be `isError=true`, `code="NOT_SUPPORTED"` and no test case from that call SHALL be created

#### Scenario: Descriptions state the limitation
- **WHEN** an agent reads the `create_test_suite` tool description from `tools/list`
- **THEN** it SHALL contain the sentence "Not supported in this version: only single-request, single-turn DEPLOYMENT suites can be created and run."

### Requirement: Suite-centric dataset handling
Creating a suite through MCP SHALL also create a PRIVATE dataset bound to that suite, so that the suite is runnable as soon as it has valid test cases. The dataset name SHALL equal the suite name; when that name is already taken (case-insensitive) the service SHALL append a numeric suffix `" (2)"`, `" (3)"`, … until unique. Test-case and schema tools SHALL accept `suiteId` and operate on the suite's bound dataset. Deleting a suite through MCP SHALL remove its PRIVATE dataset and test cases according to the existing private-dataset cascade rules. Datasets SHALL NOT be exposed as a separate MCP concept in this version.
Status: **Planned**

#### Scenario: Suite created via MCP
- **WHEN** `create_test_suite` succeeds
- **THEN** the returned suite SHALL have a non-null `datasetId` referencing a PRIVATE dataset bound to it whose name equals the suite name, and `get_test_case_schema` for that `suiteId` SHALL succeed with an empty schema

#### Scenario: Dataset name already taken
- **WHEN** `create_test_suite` is called and a dataset named exactly like the suite already exists
- **THEN** the bound PRIVATE dataset SHALL be created with the first free suffixed name and the tool SHALL succeed

#### Scenario: Dataset creation fails after suite creation
- **WHEN** the suite is created but the private dataset cannot be created
- **THEN** the service SHALL attempt to delete the newly created suite and SHALL return a tool error; if the compensating delete also fails, the error message SHALL include the orphaned `suiteId` so the agent can delete it

#### Scenario: Suite without dataset addressed
- **WHEN** a test-case or schema tool is called for a suite whose `datasetId` is null (created outside MCP)
- **THEN** the result SHALL be `isError=true`, `code="SUITE_HAS_NO_DATASET"`

### Requirement: Asynchronous run semantics
`run_test_suite` SHALL return immediately with the run id and initial status after the run is accepted; it SHALL NOT block until completion. Run status and progress SHALL be observable through `get_run`, and results SHALL be retrievable only once the run reached a terminal status. The same run-creation guards as the REST API SHALL apply (suite validity, at least one runnable test case, concurrency limits).
Status: **Planned**

#### Scenario: Run accepted
- **WHEN** `run_test_suite` is called for a valid suite with runnable test cases
- **THEN** the result SHALL contain the run id and a non-terminal status, and the tool SHALL return before evaluation completes

#### Scenario: Results requested for a running run
- **WHEN** `get_run_results` is called for a run whose status is not terminal
- **THEN** the result SHALL be `isError=true`, `code="RUN_NOT_TERMINAL"` and the message SHALL name the current status

### Requirement: Result size cap
Tools that return per-test-case result rows SHALL return at most `mcp-server.results.max-rows` rows in one response. When more rows exist, the response SHALL include `truncated=true` and `totalRows` with the full count; otherwise `truncated=false`. Response bodies SHALL be excluded from result rows unless the caller sets `includeResponseBody=true`.
Status: **Planned**

#### Scenario: Run within the cap
- **WHEN** a run has fewer rows than `mcp-server.results.max-rows`
- **THEN** `get_run_results` SHALL return all rows with `truncated=false`

#### Scenario: Run above the cap
- **WHEN** a run has more rows than `mcp-server.results.max-rows`
- **THEN** `get_run_results` SHALL return exactly `max-rows` rows, `truncated=true` and the total row count

#### Scenario: Response bodies requested
- **WHEN** `get_run_results` is called with `includeResponseBody=true`
- **THEN** every returned row SHALL carry the deployment response body captured for that row

### Requirement: Agent guidance in server metadata
The server's `instructions` (returned in the initialize response) SHALL describe the intended workflow: inspect deployments, create a suite, iterate with try-out and suite updates, add schema and test cases, add metrics, run, poll `get_run`, read results and summary. Server `name` and `version` SHALL be configurable.
Status: **Implemented** — `spring.ai.mcp.server.{name,version,instructions}` in `application.yml`; verified by `McpServerFoundationFunctionalTests` asserting `initialize`'s `instructions` mentions `try_out` and `get_run`.

#### Scenario: Instructions returned
- **WHEN** a client completes `initialize`
- **THEN** the response SHALL contain non-empty `instructions` mentioning try-out iteration and polling `get_run`

### Requirement: Authenticated principal visible inside tool execution
Tool executions SHALL observe the same authenticated principal as a REST controller handling the same HTTP request, so that author attribution and downstream token propagation behave identically.
Status: **Implemented** — verified by `probe_caller_identity` in `McpServerSecurityFunctionalTests` (`createdBy=alice`/`credentialKind=BEARER` under a JWT bearer caller, `createdBy=my-project`/`credentialKind=API_KEY` under an Api-Key caller) and in `McpServerFoundationFunctionalTests` (`createdBy=anonymous` for the unauthenticated caller in `none` mode); the `create_test_suite` scenario is deferred to the `mcp-test-suites` child, which owns that tool. `Api-Key` caller attribution (`createdBy=my-project`, `credentialKind=API_KEY` in the same probe) and Core-credential forwarding for `Api-Key` callers are delivered by the archived child change `api-key-core-propagation`.

#### Scenario: Suite created via MCP carries the caller identity
- **WHEN** `create_test_suite` is called with a JWT whose configured user claim is `alice`
- **THEN** the created suite's `createdBy` SHALL be `alice` (or its resolved display name when display-name resolution is enabled), never `anonymous`
