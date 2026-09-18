## Purpose

Defines the cross-cutting behaviour of the Evaluation Framework's MCP server: how agents connect and authenticate, the shape and stability guarantees of the MCP tool contract, error and unsupported-feature semantics, suite-centric dataset handling, asynchronous run semantics, result size limits and agent guidance. Individual tool groups are specified in `mcp-tools-<group>` specs delivered by child changes.

## ADDED Requirements

### Requirement: MCP endpoint over Streamable HTTP
The service SHALL expose a single MCP endpoint at `/mcp` implementing the MCP Streamable HTTP transport (JSON-RPC over HTTP POST, optional SSE response streams, `Mcp-Session-Id` session management). The server SHALL advertise the `tools` capability. It SHALL NOT advertise `resources` or `prompts` in this version. The endpoint SHALL be disableable by configuration (`spring.ai.mcp.server.enabled=false`), in which case `/mcp` SHALL NOT be served.
Status: **Planned**

#### Scenario: Initialize and list tools
- **WHEN** an MCP client sends `initialize` followed by `tools/list` to `/mcp`
- **THEN** the server SHALL complete the handshake, return a session id, and list every registered tool with its name, description and JSON input schema

#### Scenario: Server disabled
- **WHEN** `spring.ai.mcp.server.enabled=false`
- **THEN** requests to `/mcp` SHALL NOT reach an MCP handler (HTTP 404 or 401 depending on security mode) and no MCP beans SHALL be active

### Requirement: MCP contract is independent of the REST contract
MCP tool inputs and outputs SHALL be defined by MCP-specific models that are versioned and evolved independently of REST request/response DTOs. A change to a REST DTO SHALL NOT change an MCP tool's input schema or output shape unless the MCP model is deliberately updated. Field naming SHALL be `camelCase` in models and `snake_case` for tool names. Timestamps SHALL be epoch milliseconds. Identifiers SHALL be UUID strings.
Status: **Planned**

#### Scenario: REST DTO field added
- **WHEN** a new field is added to a REST DTO that backs a domain the MCP server also exposes
- **THEN** `tools/list` for the corresponding MCP tool SHALL return an unchanged input schema until the MCP model is explicitly updated

### Requirement: Tool contract conventions
Every tool SHALL take exactly one structured input object whose properties carry descriptions, and SHALL return a structured JSON result. Every tool description SHALL state its preconditions and, where applicable, the tool an agent is expected to call next. Tools that mutate state SHALL return the resulting entity. Tools SHALL be keyed by `suiteId` wherever an operation concerns a suite's request template, response columns, test cases, schema, metrics or runs.
Status: **Planned**

#### Scenario: Tool metadata inspected
- **WHEN** an agent calls `tools/list`
- **THEN** each tool SHALL have a non-empty description and every input property SHALL have a non-empty description

### Requirement: Structured tool error contract
When a tool cannot complete, the server SHALL return an MCP tool result with `isError=true` whose content is a JSON object with `code` (stable machine-readable string), `message` (human/agent readable) and optional `details` (array of field-level messages). Codes SHALL be aligned with the REST `ErrorCode` vocabulary where an equivalent exists (`NOT_FOUND`, `VALIDATION_ERROR`, `INVALID_OPERATION`, `ACCESS_DENIED`, `VERSION_CONFLICT`, `UNIQUE_CONSTRAINT_VIOLATION`, `TOO_MANY_REQUESTS`) and SHALL add `NOT_SUPPORTED` and `UPSTREAM_ERROR`. Tool error content SHALL NOT include stack traces, SQL text or internal class names. The full exception SHALL be logged server-side.
Status: **Planned**

#### Scenario: Entity not found
- **WHEN** a tool is called with a `suiteId` that does not exist
- **THEN** the result SHALL have `isError=true` with `code="NOT_FOUND"` and a message naming the missing entity type and id

#### Scenario: Validation failure
- **WHEN** a tool input violates a domain validation rule (for example an invalid JSONata expression in a response column)
- **THEN** the result SHALL have `isError=true` with `code="VALIDATION_ERROR"` and `details` listing each violated field or rule

#### Scenario: Upstream failure
- **WHEN** a tool depends on DIAL Core and DIAL Core returns an error or is unreachable
- **THEN** the result SHALL have `isError=true` with `code="UPSTREAM_ERROR"` and a message that does not expose credentials or internal URLs beyond the deployment name

### Requirement: Forward-compatible models with explicit unsupported features
MCP models SHALL include the fields required to express multi-request suites (`additionalRequests`), multi-turn test cases (`multiTurnData`) and `MCP_TOOL` suites (`suiteType=MCP_TOOL`, `mcpDeploymentRef`, `toolRef`, `argumentTemplate`). In this version the server SHALL reject any tool call that sets one of these to a non-empty value with `code="NOT_SUPPORTED"` before invoking any domain service, and the descriptions of the affected tools and properties SHALL state that only single-request, single-turn `DEPLOYMENT` suites are supported.
Status: **Planned**

#### Scenario: Multi-request suite requested
- **WHEN** `create_test_suite` or `update_test_suite` is called with a non-empty `additionalRequests`
- **THEN** the result SHALL be `isError=true`, `code="NOT_SUPPORTED"` and no suite SHALL be created or modified

#### Scenario: Multi-turn test case requested
- **WHEN** `add_test_cases` is called with an item whose `multiTurnData` is non-empty
- **THEN** the result SHALL be `isError=true`, `code="NOT_SUPPORTED"` and no test case from that call SHALL be created

#### Scenario: Descriptions state the limitation
- **WHEN** an agent reads the `create_test_suite` tool description from `tools/list`
- **THEN** it SHALL contain an explicit statement that multi-request, multi-turn and MCP-tool suites are not supported in this version

### Requirement: Suite-centric dataset handling
Creating a suite through MCP SHALL also create a PRIVATE dataset bound to that suite, so that the suite is runnable as soon as it has valid test cases. Test-case and schema tools SHALL accept `suiteId` and operate on the suite's bound dataset. Deleting a suite through MCP SHALL remove its PRIVATE dataset and test cases according to the existing private-dataset cascade rules. Datasets SHALL NOT be exposed as a separate MCP concept in this version.
Status: **Planned**

#### Scenario: Suite created via MCP
- **WHEN** `create_test_suite` succeeds
- **THEN** the returned suite SHALL have a non-null `datasetId` referencing a PRIVATE dataset bound to it, and `get_test_case_schema` for that `suiteId` SHALL succeed with an empty schema

#### Scenario: Dataset creation fails after suite creation
- **WHEN** the suite row is created but the private dataset cannot be created
- **THEN** the server SHALL remove the newly created suite and return a tool error, leaving no unbound suite behind

#### Scenario: Suite without dataset addressed
- **WHEN** a test-case or schema tool is called for a suite whose `datasetId` is null (created outside MCP)
- **THEN** the result SHALL be `isError=true`, `code="INVALID_OPERATION"` with a message explaining the suite has no dataset

### Requirement: Asynchronous run semantics
`run_test_suite` SHALL return immediately with the run id and initial status after the run is accepted; it SHALL NOT block until completion. Run status and progress SHALL be observable through a read tool (`get_run`), and results SHALL be retrievable only once the run reached a terminal status. The same run-creation guards as the REST API SHALL apply (suite validity, at least one runnable test case, concurrency limits).
Status: **Planned**

#### Scenario: Run accepted
- **WHEN** `run_test_suite` is called for a valid suite with runnable test cases
- **THEN** the result SHALL contain the run id and a non-terminal status, and the tool SHALL return before evaluation completes

#### Scenario: Results requested for a running run
- **WHEN** `get_run_results` is called for a run whose status is not terminal
- **THEN** the result SHALL be `isError=true`, `code="INVALID_OPERATION"` and SHALL name the current status

### Requirement: Result size cap
Tools that return per-test-case result rows SHALL return at most `ef.mcp-server.results.max-rows` rows in one response. When more rows exist, the response SHALL include `truncated=true` and `totalRows` with the full count; otherwise `truncated=false`. Response bodies SHALL be excluded from result rows unless explicitly requested by the caller.
Status: **Planned**

#### Scenario: Run within the cap
- **WHEN** a run has fewer rows than `ef.mcp-server.results.max-rows`
- **THEN** `get_run_results` SHALL return all rows with `truncated=false`

#### Scenario: Run above the cap
- **WHEN** a run has more rows than `ef.mcp-server.results.max-rows`
- **THEN** `get_run_results` SHALL return exactly `max-rows` rows, `truncated=true` and the total row count

### Requirement: Agent guidance in server metadata
The server's `instructions` (returned in the initialize response) SHALL describe the intended workflow: inspect deployments, create a suite, iterate with try-out and suite updates, add schema and test cases, add metrics, run, poll, read results and summary. Server `name` and `version` SHALL be configurable.
Status: **Planned**

#### Scenario: Instructions returned
- **WHEN** a client completes `initialize`
- **THEN** the response SHALL contain non-empty `instructions` mentioning try-out iteration and run polling
