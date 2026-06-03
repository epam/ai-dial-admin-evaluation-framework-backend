## 1. Dependencies and Configuration

- [x] 1.1 Add MCP SDK dependency (`io.modelcontextprotocol.sdk:mcp`) to `build.gradle` (done: dependency resolves, `./gradlew dependencies` shows it)
- [x] 1.2 Create `McpClientProperties` in `configuration.properties.dial` with `dial.mcp.connect-timeout-ms` and `dial.mcp.read-timeout-ms` (done: `@ConfigurationProperties` class with `@Validated`, defaults in `application.yml`)
- [x] 1.3 Update `docs/configuration.md` with new `dial.mcp.*` properties

## 2. Database Migration

- [x] 2.1 Create Flyway migration `V{next}__AddMcpFieldsToTestSuites.sql` adding `suite_type VARCHAR(20) NOT NULL DEFAULT 'DEPLOYMENT'`, `mcp_deployment_ref JSONB`, `tool_ref JSONB`, `argument_template JSONB` columns to `test_suites` (done: migration runs cleanly, existing suites get `suite_type = 'DEPLOYMENT'`)
- [x] 2.2 Update `docs/database-schema.md` with new columns

## 3. Data Layer — TestSuite Model Extension

- [x] 3.1 Add `suiteType`, `mcpDeploymentRef`, `toolRef`, `argumentTemplate` fields to `TestSuite` model in `data.db.model` (done: pure carrier, String fields for JSONB)
- [x] 3.2 Update `TestSuiteRowMapper` to map new columns (done: handles null JSONB columns)
- [x] 3.3 Update `PostgresTestSuiteRepository` — include new columns in SELECT, INSERT, UPDATE SQL (done: all CRUD operations include new columns)
- [x] 3.4 Add `suiteType` to `FilterWhitelists` for test suite filtering (done: `suiteType:eq` filter works)

## 4. Service Layer — DTOs and Mapper

- [x] 4.1 Create `SuiteType` enum (`DEPLOYMENT`, `MCP_TOOL`) in `service.domain.dto` (done: Jackson serialization works)
- [x] 4.2 Create `McpDeploymentReferenceDto` in `service.domain.dto` — `id` (required), `type` (required — `dial-toolset` or `dial-application`), `name` (optional), `transport` (optional) (done: validated DTO)
- [x] 4.3 Create `ToolReferenceDto` in `service.domain.dto` — `name` (required), `description`, `inputSchema` (Map, required), `outputSchema` (Map, nullable) (done: validated DTO)
- [x] 4.4 Create `ArgumentTemplateDto` in `service.domain.dto` — `arguments` (Map, required) (done: validated DTO)
- [x] 4.5 Add `suiteType`, `mcpDeploymentRef`, `toolRef`, `argumentTemplate` to `TestSuiteRequestDto` and `TestSuiteResponseDto` (done: fields with type-specific validation annotations)
- [x] 4.6 Update `TestSuiteMapper` — map new fields between DTOs and model using `JsonbMapper` for JSONB serialization (done: bidirectional mapping)

## 5. Service Layer — Type-Specific Validation

- [x] 5.1 Add suite-type-specific field validation in `TestSuiteService` — DEPLOYMENT requires `deploymentRef`/`endpointRef`/`requestTemplate`; MCP_TOOL requires `mcpDeploymentRef`/`toolRef`; ignore cross-type fields (done: HTTP 400 for invalid combinations)
- [x] 5.2 Add suite type immutability check on update — reject `PUT` with different `suiteType` (done: HTTP 400 with `VALIDATION_ERROR`)
- [x] 5.3 Add MCP suite-level soft validation — `argumentTemplate: null` produces warning (done: `isValid`/`validationWarnings` correct for MCP suites)
- [x] 5.4 Add MCP suite re-validation triggers — when `toolRef` or `argumentTemplate` changes on MCP_TOOL suite update, trigger re-validation of existing TestCases against the new tool schema/bindings (same pattern as `requestTemplate`/`inputBindings` change re-validation for HTTP suites) (done: test cases re-validated on `toolRef`/`argumentTemplate` change)

## 6. Client Layer — DIAL Core Unified Deployment Listing

- [x] 6.1 Create `DialCoreDeploymentDto` in `client.dialcore.dto` — unified response entry from `/v1/deployments` with `type`, `id`, `display_name`, `description`, `interfaces`, and type-specific fields (done: maps from DIAL Core unified response)
- [x] 6.2 Create `DialCoreToolsetDto` in `client.dialcore.dto` — for single toolset detail from `/openai/toolsets/{id}` (done: fields mapped from DIAL Core response)
- [x] 6.3 Add `getDeployments(interfaceType)` method to `DialCoreClient` — calls `GET /v1/deployments` with optional `?interface_type=` param (done: uses existing retry/error mapping)
- [x] 6.4 Keep `getToolset(id)` method on `DialCoreClient` — calls `GET /openai/toolsets/{id}` for single-item detail (done: existing pattern)
- [x] 6.5 Create `ToolsetInfoDto extends DeploymentInfoDto` in `service.domain.dto.deployment` with `transport` and `allowedTools` fields, discriminator `"dial-toolset"` (done: polymorphic serialization works)
- [x] 6.6 Create `InterfaceType` enum in `service.domain.dto.deployment` — `CHAT`, `EMBEDDING`, `MCP`, `CUSTOM_UI` (done: Jackson serialization with snake_case)
- [x] 6.7 Add `DIAL_TOOLSET("dial-toolset")` to `DeploymentType` enum (done: URL path and JSON discriminator consistent)
- [x] 6.8 Update `DeploymentMapper` — add unified response mapping (type-discriminated dispatch to model/app/toolset mappers), add toolset mapping methods (done: `DialCoreDeploymentDto` → `DeploymentInfoDto` hierarchy)
- [x] 6.9 Update `DeploymentService` — replace 3 parallel calls with single `getDeployments(interfaceType)` call; add client-side type filtering when `type` query param is provided (done: unified listing works with both filters)

## 7. Client Layer — MCP Tool Invoker

- [x] 7.1 Create `McpClientConfiguration` in `client.mcp` — Spring `@Configuration` for MCP client beans/properties (done: reads `McpClientProperties`, configures base URL from `DialCoreProperties`)
- [x] 7.2 Create `McpInvocationException` in `client.mcp` — extends RuntimeException with status code and error details (done: mirrors `DialCoreClientException` pattern)
- [x] 7.3 Create `McpToolInvoker` in `client.mcp` — `callTool(deploymentId, toolName, arguments, token)` and `listTools(deploymentId, token)` methods using MCP SDK Streamable HTTP transport (done: creates per-call client, injects auth header, handles timeout/connection errors, maps to `McpInvocationException`)
- [x] 7.4 Map `McpInvocationException` to HTTP responses in `DefaultExceptionHandler` — 502 for connection errors, 504 for timeouts, 502 with `UPSTREAM_ERROR` for JSON-RPC errors (done: exception handler maps MCP errors correctly)

## 8. Service Layer — MCP Request Resolution

- [x] 8.1 Create `McpRequestResolver` in `service.domain` — stateless transformer that resolves argument template by merging input bindings with test case data, produces `Map<String, Object>` of resolved arguments (done: handles `${{variable}}` substitution, type preservation, default values, resolution warnings)
- [x] 8.2 Unit tests for `McpRequestResolver` — cover: variable substitution, constant passthrough, type coercion, missing required variables, default values (done: all scenarios pass)

## 9. Service Layer — MCP Response Serialization

- [x] 9.1 Create `McpResponseSerializer` in `service.domain` — serializes MCP SDK `CallToolResult` to JSON string with `content`, `structuredContent` (if present), `isError` (done: preserves content block types, omits null structuredContent)
- [x] 9.2 Unit tests for `McpResponseSerializer` — cover: text content, mixed content types, structuredContent present/absent, isError true/false (done: all scenarios pass)

## 10. Web Layer — Deployment Controller Extension

- [x] 10.1 Update `DeploymentController` — add `type` (optional `DeploymentType`) and `interface` (optional `InterfaceType`) query parameters to `GET /api/v1/deployments` (done: params validated, passed to service)
- [x] 10.2 Update `DeploymentController` — support `dial-toolset` in `getDeploymentByTypeAndId()` (done: routes to `DialCoreClient.getToolset()`)
- [x] 10.3 Generalize tool discovery endpoint to `GET /api/v1/deployments/{type}/{id}/tools` — works for `dial-toolset` and `dial-application`; returns HTTP 400 for `dial-model` (done: endpoint validates MCP capability by type)
- [x] 10.4 Create `ToolDefinitionDto` in `service.domain.dto.deployment` — `name`, `description`, `inputSchema` (Map), `outputSchema` (Map, nullable) (done: DTO for tool discovery response)
- [x] 10.5 Add OpenAPI annotations for new query params, toolset endpoints, `InterfaceType` enum, `ToolsetInfoDto` subtype, and tools endpoint (done: Swagger UI shows all new params and types)

## 11. Service Layer — TryItOut MCP Support

- [x] 11.1 Update `TryItOutService` — branch by suite type: existing HTTP flow for DEPLOYMENT, new MCP flow (McpRequestResolver → McpToolInvoker → McpResponseSerializer) for MCP_TOOL (done: both modes work)
- [x] 11.2 Add MCP-specific validation in `TryItOutService` — reject if `mcpDeploymentRef` or `toolRef` is null for MCP suites (done: HTTP 400 for missing references)
- [x] 11.3 Handle MCP try-it-out response — map `CallToolResult` to `TryItOutResponseDto` (resolved arguments as body, serialized MCP response as response.body, statusCode = 200 for successful transport) (done: response structure correct)

## 12. Service Layer — EvaluationWorker MCP Support

- [x] 12.1 Update `EvaluationContext` — add `suiteType` and deserialized MCP-specific references (`mcpDeploymentRef`, `toolRef`, `argumentTemplate`) loaded from the suite at run initialization time; these are immutable for the run duration (done: context carries MCP fields, populated alongside existing HTTP fields at run start)
- [x] 12.2 Update `EvaluationWorker` — add suite type branching at `execute()` entry: MCP flow uses `McpRequestResolver` → `McpToolInvoker` → `McpResponseSerializer`, then converges at `ResponseColumnExtractor` (done: MCP test cases execute correctly)
- [x] 12.3 Implement MCP execution status mapping — SUCCESS (isError=false), FAILED (isError=true), TIMEOUT (transport timeout), ERROR (network/JSON-RPC error) (done: status values correct)
- [x] 12.4 Implement MCP retry logic — retryable on transport errors and JSON-RPC server errors; non-retryable on isError and InvalidParams (done: retry behavior consistent with spec)
- [x] 12.5 Store resolved MCP arguments in `requestBody` field of `TestCaseRunResult` (done: arguments serialized as JSON in requestBody)
- [x] 12.6 Implement MCP response size limiting in `EvaluationWorker` MCP flow — enforce `max-response-size-bytes` on serialized MCP response: truncate to JSON string, set executionStatus to ERROR, add truncation warning (done: response exceeding limit is truncated, status set to ERROR, warning logged)

## 13. Functional Tests

- [x] 13.1 Functional tests for deployment listing — `GET /api/v1/deployments` returns models, apps, toolsets; `?interface=mcp` returns only MCP-capable; `?type=dial-toolset` returns only toolsets; combined filters work (done: tests with WireMock for DIAL Core `/v1/deployments` endpoint)
- [x] 13.2 Functional tests for single deployment detail — `GET /api/v1/deployments/dial-toolset/{id}` returns toolset detail (done: WireMock stubs `/openai/toolsets/{id}`)
- [x] 13.3 Functional tests for tool discovery — `GET /api/v1/deployments/dial-toolset/{id}/tools` and `GET /api/v1/deployments/dial-application/{id}/tools` return tool list; `GET /api/v1/deployments/dial-model/{id}/tools` returns 400 (done: tests with WireMock for MCP proxy)
- [x] 13.4 Functional tests for MCP test suite CRUD — create MCP_TOOL suite with mcpDeploymentRef (toolset and app types), update, validate type-specific fields, type immutability, filtering by suiteType (done: all CRUD scenarios covered)
- [x] 13.5 Functional tests for MCP try-it-out — test case mode and variables mode for MCP suites, error handling (done: try-it-out with MCP mocked responses)
- [x] 13.6 Unit tests for `McpToolInvoker` — tool call success, isError, timeout, connection failure, JSON-RPC error (done: all invoker scenarios covered)
- [x] 13.7 Functional test for MCP evaluation end-to-end — start an MCP_TOOL suite run via the run API, verify test case results are written to analytics DB with correct `executionStatus`, `requestBody` (resolved arguments), `responseBody` (serialized MCP response), and `extractedColumns` (done: WireMock stubs MCP proxy, assertions on analytics results)

## 14. Cross-Cutting

- [x] 14.1 Run `./gradlew checkstyleMain checkstyleTest` and fix any violations (done: clean)
- [x] 14.2 Run `./gradlew test` — all existing + new tests pass (done: green)
- [x] 14.3 Update `openspec/specs/README.md` per Spec Index Maintenance Policy — add `mcp-tool-invocation` and `toolset-listing` specs. Note: `toolset-listing` contains only ADDED requirements (new toolset-specific functionality, interface/type query params, tool discovery, InterfaceType enum); MODIFIED requirements for deployment listing/detail/hierarchy/DeploymentType are in `dial-core-client` delta spec (done: index reflects new specs)
- [x] 14.4 Add OpenAPI examples for new endpoints and DTOs — `@Schema(example=...)` on new DTO fields (`McpDeploymentReferenceDto`, `ToolReferenceDto`, `ArgumentTemplateDto`, `ToolsetInfoDto`, `ToolDefinitionDto`, `SuiteType`, `InterfaceType`); JSON example files under `src/main/resources/openapi/examples/` for tool discovery endpoint, MCP suite creation request/response, and MCP try-it-out (done: Swagger UI shows examples for all new types and endpoints)
- [x] 14.5 Update `AGENTS.md` per AGENTS.md Maintenance guidelines — add `client.mcp` package to Key Packages Reference, add MCP Tool Invocation pattern to Unique Patterns (done: relevant sections reflect the change)
