## Context

The Evaluation Framework currently evaluates HTTP-based DIAL deployments (models and applications) through a pipeline: request template resolution → body serialization → HTTP invocation via `DialCoreDeploymentInvoker` → streaming/non-streaming response handling → JSONata extraction → metric evaluation. All abstractions (EndpointContractDto, RequestTemplateDto, RequestBodySerializer, DialCoreUrlBuilder) are HTTP-centric.

DIAL Core now supports MCP (Model Context Protocol) toolsets — registered MCP servers exposing tools via JSON-RPC 2.0 at `POST /v1/toolset/{name}/mcp`. Applications can also expose MCP interfaces via `dial:applicationTypeMcp`. The MCP protocol differs fundamentally from REST: tool calls use `tools/call` with a tool name + JSON arguments, responses return typed content blocks (text/image/audio/resource) + optional `structuredContent` + `isError` flag.

## Goals / Non-Goals

**Goals:**
- Enable evaluation of MCP tools (toolsets and MCP-capable applications) through the existing test suite run pipeline
- Reuse the existing analytics pipeline (TestCaseRunResult, batch writes, metric evaluation, eval summaries) without modification
- Support MCP tool discovery (`tools/list`) for populating tool schemas in the UI
- Provide MCP-specific try-it-out for interactive tool testing
- Single test_suites table with type discriminator for unified listing/filtering

**Non-Goals:**
- MCP resource access (`resources/read`, `resources/list`) — tools only in this phase
- MCP prompts (`prompts/get`, `prompts/list`) — out of scope
- MCP notifications or sampling — out of scope
- Multi-tool orchestration (chaining tool calls) — each test case invokes exactly one tool
- MCP server hosting — EF is a client only, DIAL Core proxies to actual MCP servers
- Application-specific MCP configuration delivery (`dial:mcpConfigDelivery` mode handling) — EF invokes via the standard MCP proxy which handles config delivery transparently

## Decisions

### D1: Java MCP SDK over custom HTTP client

**Decision:** Use the official Java MCP SDK (`io.modelcontextprotocol.sdk:mcp`) with Streamable HTTP transport.

**Alternatives considered:**
- *Custom HTTP client with manual JSON-RPC envelope construction:* Simpler initially, but requires hand-crafted JSON-RPC request/response serialization, manual content block type discrimination, no protocol version negotiation, and manual updates on MCP spec evolution.
- *Spring AI MCP Client starter:* Full Spring Boot integration with auto-configuration, but pulls in Spring AI dependency tree which is heavy for our use case (we only need the MCP client, not the full AI framework).

**Rationale:** The SDK provides typed models (`CallToolRequest`, `CallToolResult`, `TextContent`, `ImageContent`, etc.), handles JSON-RPC 2.0 protocol details, supports Streamable HTTP transport (exactly what DIAL Core's MCP proxy expects), and will track MCP spec evolution. The dependency is lightweight (`mcp-core` + `mcp-json-jackson3`). Our project already uses Jackson.

**Transport configuration:** DIAL Core exposes MCP via `POST /v1/toolset/{name}/mcp`. The SDK's `HttpClientSseClientTransport` (Streamable HTTP mode) sends JSON-RPC payloads over HTTP POST — a natural fit. We create a new transport instance per toolset invocation (URL includes toolset name). Auth headers (user JWT) are injected via the transport's custom `HttpClient` builder.

**MCP initialization handshake:** The MCP specification requires clients to send an `initialize` request before making `tools/list` or `tools/call` calls. The Java MCP SDK handles this automatically during `McpClient` construction/initialization. This means each per-call client instantiation incurs 2 round trips (initialize + actual call) instead of 1. This is acceptable because: (1) tool call latency is typically 100ms–seconds, so an additional ~50ms initialization round trip is a small fraction; (2) DIAL Core's MCP proxy may optimize or cache initialization per session; (3) the alternative (client pooling keyed by deploymentId + token) adds complexity for marginal gain. If profiling shows initialization overhead is significant, introduce a client cache keyed by `(deploymentId, token)` with short TTL. During implementation, verify the SDK's actual initialization behavior — if the SDK supports skipping initialization or reusing sessions, prefer that approach.

### D2: Single Table Inheritance (STI) with suite_type discriminator

**Decision:** Extend `test_suites` table with a `suite_type VARCHAR(20) DEFAULT 'DEPLOYMENT'` column and nullable MCP-specific JSONB columns (`mcp_deployment_ref`, `tool_ref`, `argument_template`).

**Alternatives considered:**

- *Class Table Inheritance (CTI) — base `test_suites` table + per-type subtables (`http_suite_data`, `mcp_suite_data`):* Clean schema with no nullable type-specific columns and DB-level NOT NULL enforcement on subtable fields. However, every read requires a JOIN to subtables (or two queries), every write requires two INSERT/UPDATE operations within the same transaction, optimistic locking (`version`) coordination across tables adds complexity, and the `RowMapper` must handle joined results or multi-query assembly. All 5 existing child tables (`test_cases`, `test_suite_runs`, `revalidation_tasks`, `test_suite_metric_definitions`) FK to `test_suites.id` and would still work, but list/filter/paginate queries become JOIN-heavy.

- *Concrete Table Inheritance — separate `http_test_suites` and `mcp_test_suites` tables with duplicated common columns:* Strongest per-type constraints, but breaks all 5 FK references (child tables can't point to both tables), requires `UNION ALL` for cross-type listing (pagination across UNION is painful), and doubles the repository/mapper/migration surface area.

- *Polymorphic JSONB blob — single `type_config JSONB` column:* Flexible but loses SQL-level querying on type-specific fields and makes validation harder.

**Rationale:** STI is the pragmatic choice for this case because:
1. **Symmetric column count** — MCP adds 3 JSONB columns, HTTP already has 3 JSONB columns. The table widening is minimal.
2. **NULL JSONB is free in PostgreSQL** — NULL JSONB columns have zero storage cost (just a null flag in the tuple header, no TOAST overhead).
3. **5 child tables with cascading FKs** — all existing FK references, cascade deletes, and child table queries work unchanged.
4. **Single query path** — no JOINs for list, detail, create, or update operations. Existing pagination, filtering, and sorting logic requires zero changes.
5. **Optimistic locking stays simple** — one `version` column in one table.
6. **App-level validation already exists** — the `is_valid` / `validation_warnings` pattern already handles complex validation rules that go beyond DB constraints. Type-specific field validation fits naturally into this pattern.
7. **Default value backward compatibility** — `suite_type DEFAULT 'DEPLOYMENT'` means existing suites require zero data migration.

**When to reconsider (switch to CTI):** If future evolution introduces 4+ suite types with 10+ type-specific columns each, the single table becomes unwieldy — nullable column proliferation hurts readability and the semantic gap between "columns present in schema" and "columns relevant for this row" grows confusing. At that point, refactoring to CTI (base table + per-type subtables) would be justified. The signs to watch for: multiple suite types with non-overlapping column sets, DB-level constraint enforcement becoming critical, or type-specific columns dominating the table width. For the current 2-type / 6-column split, STI is clearly the better fit.

### D3: New client package `client.mcp` for MCP SDK integration

**Decision:** Create `client.mcp` package with `McpToolInvoker` (wraps MCP SDK client) and `McpClientConfiguration` (Spring config for transport/timeout/auth).

**Rationale:** Follows the existing pattern (`client.dialcore` for REST, `client.metricprovider` for metric providers). Keeps MCP SDK dependency isolated. The invoker handles: creating per-call MCP client with correct toolset URL, injecting auth headers, executing `tools/call`, handling timeouts, and returning typed `CallToolResult`.

**Package layout:** `McpClientProperties` lives in `configuration.properties.dial` (following the existing `DialCoreProperties` pattern), while `McpClientConfiguration` and `McpToolInvoker` live in `client.mcp`.

### D4: McpRequestResolver as a service-layer component

**Decision:** Create `McpRequestResolver` in `service.domain` as an injectable `@Component` that resolves argument templates using test case data and input bindings.

**Rationale:** Parallel to `ResolvedRequestService` for HTTP suites. The MCP argument template is simpler (just key-value argument bindings, no URL/headers/query params), but the variable substitution mechanism (`${{variable}}` syntax) is reused. The resolver produces a `Map<String, Object>` of resolved tool arguments.

### D5: McpResponseSerializer for JSONata extraction compatibility

**Decision:** Create `McpResponseSerializer` in `service.domain` that serializes MCP SDK's `CallToolResult` to a JSON string preserving the MCP envelope structure: `{ content: [...], structuredContent: {...}, isError: boolean }`.

**Rationale:** The existing `ResponseColumnExtractor` works on JSON strings via JSONata. By serializing the MCP response to a well-defined JSON structure, all existing extraction logic works unchanged. Users configure JSONata expressions targeting MCP paths: `$.isError`, `$.content[0].text`, `$.structuredContent.results`.

### D6: EvaluationWorker branching by suite type

**Decision:** The `EvaluationWorker` branches on `suiteType` at the beginning of test case execution. HTTP suites follow the existing flow (ResolvedRequestService → RequestBodySerializer → DialCoreDeploymentInvoker → StreamingResponseAccumulator). MCP suites follow the new flow (McpRequestResolver → McpToolInvoker → McpResponseSerializer). Both flows converge at ResponseColumnExtractor.

**Alternatives considered:**
- *Strategy pattern with EvaluationWorkerStrategy interface:* Cleaner OCP compliance but over-abstraction for two variants. We can refactor to strategy if a third invocation type emerges.

**Rationale:** Keeps changes minimal. The branching point is early in the worker's execute method. The rest of the pipeline (extraction, result building, retry, batching, metrics) is shared.

### D7: Unified deployment listing via DIAL Core `/v1/deployments`

**Decision:** Switch from 3 parallel DIAL Core calls (`/openai/models`, `/openai/applications`, `/openai/toolsets`) to a single `GET /v1/deployments` endpoint. Add `ToolsetInfoDto extends DeploymentInfoDto` with `$type: "dial-toolset"`. Support `type` and `interface` query parameters on EF's `GET /api/v1/deployments` endpoint.

**Alternatives considered:**
- *Keep 3 parallel calls, add client-side interface filtering:* Proven approach, but requires EF to replicate DIAL Core's interface-detection logic (checking schemas for `dial:applicationTypeMcp`, model types, etc.). Fragile and duplicative.
- *Use unified endpoint only for interface-filtered queries, keep 3 calls for unfiltered:* Adds complexity with two code paths for the same endpoint.

**Rationale:** DIAL Core's unified endpoint is the source of truth for which deployments support which interfaces. It handles schema-based interface detection (e.g., `dial:applicationTypeMcp` on apps) server-side. Using it for all deployment listing queries gives EF a single integration point. The response includes a `type` field (`model`, `application`, `toolset`) and `interfaces` array, enabling both type and interface filtering without EF needing to understand schema internals.

**Backend routing:**
- `GET /api/v1/deployments` (no params) → `GET /v1/deployments` on DIAL Core
- `GET /api/v1/deployments?interface=mcp` → `GET /v1/deployments?interface_type=mcp` on DIAL Core
- `GET /api/v1/deployments?type=dial-model` → `GET /v1/deployments` on DIAL Core, filter by type client-side
- `GET /api/v1/deployments?type=dial-application&interface=mcp` → `GET /v1/deployments?interface_type=mcp` on DIAL Core, filter by type client-side

**Type-only filter and over-fetch:** When only `type` is provided (no `interface`), EF fetches all deployments from DIAL Core and filters client-side. DIAL Core's `/v1/deployments` does not support a `type` query parameter — only `interface_type`. This is an accepted trade-off: deployment lists are typically small (tens to low hundreds of entries), the call is proxied through DIAL Core which already loads all deployments into memory, and adding a DIAL Core API dependency for type filtering would block this change. If deployment counts grow large enough to cause performance issues, request `type` parameter support from DIAL Core as a separate enhancement.

### D8: Generalized MCP tool discovery endpoint

**Decision:** Add `GET /api/v1/deployments/{type}/{id}/tools` endpoint that calls MCP `tools/list` via DIAL Core proxy and returns the tool schemas. The endpoint works for any MCP-capable deployment type (`dial-toolset` or `dial-application`). For deployment types that don't support MCP (e.g., `dial-model`), the endpoint returns HTTP 400.

**Rationale:** Both toolsets and MCP-capable applications use the same DIAL Core MCP proxy endpoint (`POST /v1/toolset/{name}/mcp`). The tool discovery endpoint is type-agnostic at the invocation level — DIAL Core resolves the deployment name regardless of whether it's a toolset or app. The `{type}` path parameter lets the UI build correct URLs and enables validation.

### D9: Deployment listing query parameters — `type` and `interface`

**Decision:** Add optional `type` and `interface` query parameters to `GET /api/v1/deployments`. Supported values: `type` accepts `DeploymentType` enum values (`dial-model`, `dial-application`, `dial-toolset`); `interface` accepts DIAL Core interface types (`chat`, `embedding`, `mcp`, `custom_ui`). Both are optional; omitting both returns all deployments.

**Rationale:** `type` and `interface` are orthogonal filtering dimensions. Models expose `chat` or `embedding` interfaces; applications can expose `chat`, `mcp`, and/or `custom_ui`; toolsets always expose `mcp`. The UI needs `interface=mcp` to populate the MCP deployment picker (returns toolsets + MCP apps), and `type` to filter by deployment kind. Using dedicated query params (not the generic `filter=` syntax) because these control which DIAL Core backend call to make, not SQL WHERE clauses.

## Risks / Trade-offs

**[Risk] MCP SDK version compatibility** → Pin SDK version in build.gradle, test against DIAL Core's MCP proxy in integration tests. MCP spec is still evolving (2025-06-18 was latest at time of design). Mitigation: SDK abstracts protocol details; version bumps are dependency-only changes.

**[Risk] Per-call MCP client instantiation overhead** → Creating a new `McpClient` per tool call (because URL differs per toolset). Mitigation: Overhead is negligible (object construction + HTTP connection from pool) compared to tool call latency (100ms–seconds). If profiling shows issues, introduce a client cache keyed by toolset ID.

**[Risk] MCP response size for image/audio content blocks** → MCP responses can contain base64-encoded images/audio in content blocks, which can be very large. Mitigation: The existing `max-response-size-bytes` limit applies to the serialized MCP response JSON. Truncation produces a JSON string (same as HTTP truncation behavior).

**[Risk] Auth token expiry during long-running MCP evaluations** → Same risk as HTTP evaluations. Mitigation: Existing `TokenPropagationHelper` pattern applies; MCP SDK transport receives the token at construction time per call.

**[Risk] HTTP-level errors from DIAL Core MCP proxy** → DIAL Core's `POST /v1/toolset/{name}/mcp` can return HTTP-level errors (401, 403, 404, 502) before the JSON-RPC layer is reached. The MCP SDK expects JSON-RPC responses — an HTTP error page would cause a deserialization failure. Mitigation: `McpToolInvoker` must catch HTTP transport exceptions separately from JSON-RPC errors and map them to `McpInvocationException` with appropriate status codes. This mirrors how `DialCoreDeploymentInvoker` handles HTTP errors vs application-level errors.

**[Trade-off] Single table nullable columns** → MCP suites have null `deployment_ref`/`endpoint_ref`/`request_template`; HTTP suites have null `mcp_deployment_ref`/`tool_ref`/`argument_template`. Accepted per D2 rationale: NULL JSONB is free in PostgreSQL, type-specific validation prevents invalid states, and the column count is symmetric (3+3). See D2 for CTI migration trigger criteria.

**[Trade-off] No streaming for MCP tool calls** → MCP `tools/call` responses are typically non-streaming (complete JSON-RPC response). The MCP SDK's Streamable HTTP transport handles this. If DIAL Core adds SSE support for MCP responses, the SDK transport will handle it transparently.

## Migration Plan

1. **Flyway migration**: Add columns to `test_suites` with defaults — zero-downtime, backward-compatible
2. **Dependency**: Add MCP SDK to `build.gradle` — no runtime impact until new code paths are exercised
3. **Feature rollout**: MCP suite creation is additive — existing HTTP suites are unaffected
4. **Rollback**: Remove new columns via reverse migration; MCP suites become inaccessible but HTTP suites are unaffected

## Resolved Questions

1. **~~MCP SDK exact Maven coordinates~~** — resolved at implementation time. Verify `io.modelcontextprotocol.sdk:mcp` on Maven Central and pin the latest stable version in `build.gradle`. Standard dependency management, not a design concern.
2. **~~DIAL Core MCP proxy auth header format~~** — resolved. DIAL Core uses `Authorization: Bearer <JWT>` for all user-authenticated endpoints (confirmed from `DialCoreClientConfiguration.authorizationTokenInterceptor()`). The MCP proxy at `POST /v1/toolset/{name}/mcp` follows the same pattern. `McpToolInvoker` injects the Bearer token via the MCP SDK transport's HTTP client builder.
3. **~~Tool discovery caching~~** — decided: no caching in phase 1. `tools/list` is called only on explicit user actions (opening tool picker in suite create/edit UI), not on every page load. Toolset tools can change at any time (MCP server redeployed), so stale cache risks outweigh latency gains. If latency becomes a problem, add a short-TTL cache keyed by toolset ID later.
4. **~~Application MCP support~~** — resolved: included in scope. Both toolsets and MCP-capable applications are supported via the unified `GET /v1/deployments?interface_type=mcp` listing and the same MCP proxy invocation path (`POST /v1/toolset/{name}/mcp`). The `mcpDeploymentRef` on MCP suites stores either a toolset or application reference.

## Open Questions

None remaining — all design-level questions resolved.
