## Context

See `proposal.md` for motivation. Constraints that shape the approach:

- Strict layering (`web → service → data`) enforced by `LayeredArchitectureTest`; today only `web` and `configuration` may touch `service`. A new top-level package that calls services needs its own layer definition.
- `evaluation-runner-core` already pins `io.modelcontextprotocol.sdk:mcp-bom:2.0.0` (MCP *client* for `MCP_TOOL` suites). Spike result (2026-09-18): Spring AI 2.0.1 targets Boot 4.1.1 and depends on `io.modelcontextprotocol.sdk:mcp:2.0.0` (= `mcp-core` + `mcp-json-jackson3`). Spring AI owns the WebMVC transport (`org.springframework.ai:mcp-spring-webmvc`) and the `@McpTool` annotations; the protocol core stays the SDK. Gradle resolution of both sets lands on 2.0.0 with zero upgrades. Fully Jackson 3.
- MCP has no chunked/partial tool results: a `CallToolResult` is one JSON-RPC message. Hosts cap tool output (Claude Code: warning at 10k tokens, default cap 25k via `MAX_MCP_OUTPUT_TOKENS`, optional per-tool raise via `_meta["anthropic/maxResultSizeChars"]`). Target users run suites of 1–2 test cases, so whole-result delivery is acceptable for v1.
- Test cases belong to datasets; a suite binds one dataset; an unbound suite cannot run. `DatasetService` already depends on `TestSuiteService` (bind), so the reverse dependency is not allowed.
- Several services take the caller's `Jwt` explicitly (e.g. `DatasetService.create(dto, jwt)`).
- Spring AI streamable server properties (from jar metadata): `spring.ai.mcp.server.protocol` (`SSE|STREAMABLE|STATELESS`, default `streamable`), `spring.ai.mcp.server.streamable-http.mcp-endpoint` (default `/mcp`), `keep-alive-interval`, `disallow-delete`; `spring.ai.mcp.server.{enabled,name,version,instructions,type,request-timeout,annotation-scanner.enabled,capabilities.*}`.

## Goals / Non-Goals

**Goals:**
- One place (this umbrella) for architecture, conventions and cross-cutting rules; child changes add tool groups without re-deciding them.
- MCP contract stable under REST/domain change (own models, own mappers).
- Zero new persistence, zero new transaction managers; reuse services as-is.
- Every tool observable through the real `/mcp` endpoint in functional tests.

**Non-Goals (v1, tracked for later):**
- Pagination / cursors for result tools; Query DSL tool.
- Inline try-out overrides (agents persist via `update_test_suite` then try again).
- PUBLIC / shared datasets via MCP.
- Executing multi-request, multi-turn or `MCP_TOOL` suites (fields present, rejected).
- CSV export as MCP resource link; DIAL bucket export.
- MCP progress notifications; long-polling inside `run_test_suite`.
- MCP OAuth discovery (`WWW-Authenticate` resource metadata).
- Multi-replica session affinity or `STATELESS` protocol.

## Decisions

### D1. Spring AI starter + `@McpTool` annotations
Use `spring-ai-starter-mcp-server-webmvc` with the annotation scanner (`@McpTool`, `@McpToolParam` from `spring-ai-mcp-annotations`). One `@Component` per tool group; each public annotated method is a tool.
*Alternatives*: `@Tool` + `ToolCallbackProvider` (Spring AI generic tool model, an extra indirection); hand-built `McpServerFeatures.SyncToolSpecification` (most control, most glue). Annotations give MCP-native schema generation and descriptions with the least code.

### D2. Package layout and a fourth layer
```
com.epam.aidial.evaluation.mcp
  config/    McpServerConfiguration, McpServerProperties (ef.mcp-server.*)
  model/     records: <Group>*Input / <Entity>McpDto (MCP-owned)
  mapper/    MapStruct <Group>McpMapper: service DTO/model <-> mcp.model
  support/   McpToolErrorTranslator, UnsupportedFeatureGuard, McpCallerContext,
             ResultRowLimiter, SuiteProvisioningOrchestrator, McpToolDescriptions
  tools/     deployment/, suite/, tryout/, testcase/, metric/, run/, result/
```
`LayeredArchitectureTest`: layer `mcp` = `..evaluation.mcp..`; `service` may be accessed by `web`, `mcp`, `configuration`; `mcp` may be accessed by nobody; `mcp` may not access `web` or `data`. Because a new top-level package and layer are introduced, `openspec/config.yaml` (Architecture list + Layering Principle) and AGENTS.md (Architecture Overview, Unique Patterns row → `docs/patterns/mcp-server.md`, key-packages) are updated by the foundation child.
*Alternative*: put tools under `web.mcp`. Rejected: MCP is not HTTP-controller code and must not reuse web DTOs; a separate layer makes the isolation enforceable.

### D3. Security parity by matcher, not by filter
Add `.requestMatchers("/mcp/**").authenticated()` beside `/api/v1/**` in `SecurityConfiguration`. `NoSecurityConfiguration` already permits everything. The sync server executes tools on the HTTP request thread, so `SecurityContextHolder`, `AuthorResolver` and `TokenPropagationHelper` work unchanged; `McpCallerContext` (`mcp.support`) is the single place that reads the `Jwt` from the security context for services that take it as a parameter, returning `null` in `none` mode (matches controller behaviour). MCP Origin-header validation is delegated to the Spring AI transport; the foundation child verifies behaviour with a test.

### D4. MCP-owned models, MapStruct mappers, no `web` reuse
`mcp.model` records mirror the domain but omit internals (`createdBy`, snapshot ids, column tiers) and keep JSON schema fields (`configSchema`, `inputSchema`, `outputSchema` on metric declarations) as `Map<String, Object>`. Mappers map from **service** DTOs/models (`service.domain.dto.*`, domain models), never from `web` DTOs. Field names stay `camelCase`; timestamps epoch millis.

### D5. Suite provisioning = suite + PRIVATE dataset, compensated
`SuiteProvisioningOrchestrator` (`mcp.support`) calls `TestSuiteService.create(...)` then `DatasetService.create({visibility: PRIVATE, bindToSuiteId})`. Each call keeps its own service-layer transaction. If dataset creation fails, the orchestrator deletes the suite and rethrows; the tool reports a single error. Transactions stay in the service layer (AGENTS.md rule); a cross-domain service method would create a `TestSuiteService → DatasetService` cycle.
*Alternative*: a new `TestSuiteService.createWithPrivateDataset`. Rejected for the cycle above.

### D6. `update_test_suite` merge semantics
Tool input is a partial suite; the tool loads the current suite, overlays non-null input fields, and calls the existing PUT path with the suite's current version for optimistic locking. Agents never resend whole objects. `null` in input means "leave unchanged"; explicit clearing of a field is out of scope for v1.

### D7. Error translation
`McpToolErrorTranslator` maps service exceptions to `{code, message, details?}` (`isError=true`): `EntityNotFoundException → NOT_FOUND`, `ValidationException`/`TryItOutValidationException`/`ConstraintViolationException → VALIDATION_ERROR` (+details), `InvalidOperationException → INVALID_OPERATION`, `VersionConflictException → VERSION_CONFLICT`, `TooManyRunsException → TOO_MANY_REQUESTS`, `AccessDeniedException → ACCESS_DENIED`, `DialCoreClientException`/`McpInvocationException → UPSTREAM_ERROR`, `UnsupportedFeatureException → NOT_SUPPORTED`. Anything else → `INTERNAL_ERROR` with a generic message. Every branch logs with the exception as last SLF4J argument. Codes reuse `ErrorCode` names but the translator lives in `mcp.support` and must not import `web.handler.ErrorCode` (layer rule); a small `McpErrorCode` enum mirrors the names, with a unit test asserting alignment for shared codes.

### D8. Unsupported-feature guard
`UnsupportedFeatureGuard` runs before any service call in suite and test-case tools and throws `UnsupportedFeatureException` if `additionalRequests` is non-empty, `suiteType == MCP_TOOL`, `mcpDeploymentRef`/`toolRef`/`argumentTemplate` is set, or any test case has non-empty `multiTurnData`. Descriptions for the affected tools and properties are constants in `McpToolDescriptions` and carry the sentence "Not supported in this version: only single-request, single-turn DEPLOYMENT suites can be created and run."

### D9. Result delivery
`get_run_results` fetches rows through the existing **paged** repository/service calls in a loop until exhausted or the cap is hit (no new unbounded query). `ResultRowLimiter` applies `ef.mcp-server.results.max-rows` and produces `{rows, totalRows, truncated}`. Response bodies are omitted unless `includeResponseBody=true`. `get_run_summary` reuses `EvalSummaryService.aggregate` for per-metric aggregates and the run's overall score. Result-heavy tools may set `_meta["anthropic/maxResultSizeChars"]` (harmless for other hosts).

### D10. Async runs
`run_test_suite` delegates to `TestSuiteRunService` and returns `{runId, status}`; `get_run` returns status, progress counters, error category and costs; `cancel_run` maps to the cancel path. Polling cadence is left to the agent; the `instructions` text says so.

### D11. Session model
`protocol=streamable`, `type=sync`. Sessions are in memory per JVM. Single-replica for v1; multi-replica requires sticky sessions on `Mcp-Session-Id` or a switch to `STATELESS`. Documented in `docs/patterns/mcp-server.md`.

### D12. Testing harness
`McpFunctionalTestSupport` (test scope) builds an `McpSyncClient` with the SDK's streamable HTTP transport against the booted app, injecting `Authorization`/`Api-Key` headers. Per-group functional test classes use `@PostgresFunctionalTests` and the existing `MetaTestDataHelper`/deployment stubs. A `tools/list` contract test asserts tool names, non-empty descriptions and required-property presence for each group.

### D13. OpenSpec structure (umbrella + children)
This change owns `proposal.md`, `design.md`, the `mcp-server` spec, the `security` delta and a `tasks.md` whose checkboxes are child changes. Children (`mcp-foundation`, `mcp-test-suites`, `mcp-try-out`, `mcp-test-cases`, `mcp-metrics`, `mcp-runs`, `mcp-results`) each carry their own `mcp-tools-<group>` delta spec (per-tool inputs, outputs, errors), design notes only where they deviate from this document, and implementation tasks sized to the 5-task / 1-group iteration rule. The umbrella is archived only after every child is archived; its archive syncs `mcp-server` and `security` and performs the AGENTS.md / config.yaml / specs README reviews.

## Risks / Trade-offs

- [Agent dumps a large run into context] → row cap + `truncated` flag + `get_run_summary`; pagination is an additive follow-up.
- [Tool descriptions drift from behaviour] → descriptions are constants asserted by `tools/list` contract tests; unsupported-feature sentence tested literally.
- [Two-step suite provisioning leaves an unbound suite on failure] → compensating delete in `SuiteProvisioningOrchestrator`; functional test forces dataset failure and asserts no suite remains.
- [Spring AI 2.0.x API churn] → versions pinned via `spring-ai-bom`; MCP SDK pinned via `mcp-bom` in both modules from one property.
- [Duplicate transitive `spring-boot-starter-web` alias from the starter] → exclude it; root uses `spring-boot-starter-webmvc`.
- [In-memory sessions break behind a load balancer] → documented; `STATELESS` is a one-property switch if needed.
- [Merge-update hides "clear field" intent] → accepted for v1; documented in the tool description.
- [Layer rule too strict for a future need to reuse a `web` helper] → move the helper to `service.domain` or `utils` instead of relaxing the rule.

## Migration Plan

1. Foundation child adds dependencies, `/mcp`, security matcher, layer rule, properties, harness and the deployments tools. Release with MCP enabled; rollback = `spring.ai.mcp.server.enabled=false` (or revert).
2. Remaining children ship in order; each is independently releasable because tools are additive.
3. No data migration. No REST change. Deployment manifests add MCP properties only if defaults are overridden.

## Open Questions

- Whether to expose `_meta["anthropic/maxResultSizeChars"]` on result tools; harmless either way, decide in `mcp-results`.
- Exact default for `ef.mcp-server.results.max-rows` (proposed 500); decide in `mcp-results` after measuring a typical row size.
