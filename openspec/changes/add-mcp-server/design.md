## Context

See `proposal.md` for motivation. Constraints that shape the approach:

- Strict layering (`web → service → data`) enforced by `LayeredArchitectureTest`; today only `web` and `configuration` may touch `service`. A new top-level package that calls services needs its own layer definition.
- Root `build.gradle` and `evaluation-runner-core/build.gradle` each hardcode `io.modelcontextprotocol.sdk:mcp-bom:2.0.0` (MCP *client* for `MCP_TOOL` suites). Spike result (2026-09-18): Spring AI 2.0.1 targets Boot 4.1.1 and depends on `io.modelcontextprotocol.sdk:mcp:2.0.0` (= `mcp-core` + `mcp-json-jackson3`). Spring AI owns the WebMVC transport (`org.springframework.ai:mcp-spring-webmvc`) and the `@McpTool` annotations; the protocol core stays the SDK. Gradle resolution of both sets lands on 2.0.0 with zero upgrades. Fully Jackson 3.
- MCP has no chunked/partial tool results: a `CallToolResult` is one JSON-RPC message whose `content` is a list of content blocks (`text`, `image`, `resource`, …) plus optional `structuredContent`. Hosts cap tool output (Claude Code: warning at 10k tokens, default cap 25k via `MAX_MCP_OUTPUT_TOKENS`, optional per-tool raise via `_meta["anthropic/maxResultSizeChars"]`). Target users run suites of 1–2 test cases, so whole-result delivery is acceptable for v1.
- Test cases belong to datasets; a suite binds one dataset; an unbound suite cannot run. `DatasetService` already depends on `TestSuiteService` (bind), so the reverse dependency is not allowed. Dataset names are globally unique case-insensitively.
- Several services take the caller's `Jwt` explicitly (e.g. `DatasetService.create(dto, jwt)`, `TestSuiteService.create(dto, jwt)`).
- Analytics result reads are cursor-based and filter-string driven (`EvalSummaryService.listByFilter(List<String> filter, computation, cursor, size)`), with a mandatory `runId` filter. Bodies are available in bulk through `EvalSummaryRepository.findAllForExportWithBodies` (used by the CSV export path), not through the list DTO.
- Spring AI streamable server properties (from jar metadata): `spring.ai.mcp.server.protocol` (`SSE|STREAMABLE|STATELESS`, default `streamable`), `spring.ai.mcp.server.streamable-http.mcp-endpoint` (default `/mcp`), `keep-alive-interval`, `disallow-delete`; `spring.ai.mcp.server.{enabled,name,version,instructions,type,request-timeout,annotation-scanner.enabled,capabilities.*}`.
- Existing `application.yml` top-level namespaces are feature-named (`test-case`, `analytics`, `test-suite-run`, …) and every `@ConfigurationProperties` class lives under `configuration.properties.**`.

## Goals / Non-Goals

**Goals:**
- One place (this umbrella) for architecture, conventions and cross-cutting rules; child changes add tool groups without re-deciding them.
- MCP contract stable under REST/domain change (own models, own mappers, no `web` dependency).
- Zero new persistence, zero new transaction managers; reuse services, adding typed service methods only where the REST-shaped signature is unusable from a tool.
- Every tool observable through the real MCP endpoint in functional tests.

**Non-Goals (v1, tracked for later):**
- Pagination / cursors for result tools; Query DSL tool.
- Inline try-out overrides (agents persist via `update_test_suite` then try again).
- PUBLIC / shared datasets via MCP.
- Executing multi-request, multi-turn or `MCP_TOOL` suites (fields present, rejected).
- CSV export as MCP resource link; DIAL bucket export.
- MCP progress notifications; long-polling inside `run_test_suite`.
- MCP OAuth discovery (`WWW-Authenticate` resource metadata).
- Multi-replica session affinity or `STATELESS` protocol.
- Clearing a suite field to null through `update_test_suite`.

## Decisions

### D1. Spring AI starter + `@McpTool` annotations
Use `spring-ai-starter-mcp-server-webmvc` with the annotation scanner (`@McpTool`, `@McpToolParam` from `spring-ai-mcp-annotations`). One `@Component` per tool group; each public annotated method is a tool. Exclude the starter's transitive `spring-boot-starter-web` alias (root uses `spring-boot-starter-webmvc`). Extract the MCP SDK version into `gradle.properties` (`mcp_sdk_version=2.0.0`) and reference it from both `build.gradle` files so root and runner-core pin from one place.
*Alternatives*: `@Tool` + `ToolCallbackProvider` (Spring AI generic tool model, an extra indirection); hand-built `McpServerFeatures.SyncToolSpecification` (most control, most glue). Annotations give MCP-native schema generation and descriptions with the least code.

### D2. Package layout and a fourth layer
```
com.epam.aidial.evaluation.mcp
  model/     records: <Group>*Input / <Entity>McpDto (MCP-owned), McpToolError, McpErrorCode
  mapper/    MapStruct <Group>McpMapper: service DTO/model <-> mcp.model
  support/   McpToolResults (JSON text-block encoding), McpToolErrorTranslator,
             UnsupportedFeatureGuard, UnsupportedFeatureException, McpCallerContext,
             ResultRowLimiter, McpToolDescriptions
  tools/     deployment/, suite/, tryout/, testcase/, metric/, run/, result/
com.epam.aidial.evaluation.configuration.mcp
             McpServerSecurityCustomizer (see D3), McpToolResultProperties (`mcp-server.results.*`)
com.epam.aidial.evaluation.service.domain
             SuiteProvisioningService (see D5)
```
Configuration and properties stay in `configuration.**` like every other feature (properties class named `McpToolResultProperties` to avoid clashing with Spring AI's `McpServerProperties`). `LayeredArchitectureTest`: layer `mcp` = `..evaluation.mcp..`; `service` may be accessed by `web`, `mcp`, `configuration`; `mcp` may be accessed only by `configuration`; `mcp` may not access `web` or `data`. Because a new top-level package and layer are introduced, the **foundation child** updates `openspec/config.yaml` (Architecture list, Layering Principle, stale Boot version), AGENTS.md (Architecture Overview, Unique Patterns row), `docs/key-packages.md`, and writes the first version of `docs/patterns/mcp-server.md`; the umbrella close-out only re-reviews them.
*Alternative*: put tools under `web.mcp`. Rejected: MCP is not HTTP-controller code and must not reuse web DTOs; a separate layer makes the isolation enforceable.

### D3. Security parity by matcher derived from the endpoint property
`SecurityConfiguration` adds `.requestMatchers(mcpEndpoint + "/**").authenticated()` beside `/api/v1/**`, where `mcpEndpoint` is read from `spring.ai.mcp.server.streamable-http.mcp-endpoint` (default `/mcp`), so overriding the path can never leave the endpoint outside the authenticated matcher. `NoSecurityConfiguration` already permits everything. **Assumption to verify first in the foundation child**: the Spring AI sync streamable server executes tools on the HTTP request thread, so `SecurityContextHolder`, `AuthorResolver` and `TokenPropagationHelper` work unchanged. The foundation functional test asserts the spec scenario "created entity carries the caller identity". If the assumption fails, `McpCallerContext` captures the `Authentication` in a servlet filter on the MCP path and re-establishes it around tool execution. `McpCallerContext` (`mcp.support`) is the single place that reads the `Jwt` for services that take it as a parameter, returning `null` in `none` mode. MCP Origin-header validation is delegated to the Spring AI transport; verified by test.

### D4. MCP-owned models, MapStruct mappers, no `web` reuse
`mcp.model` records mirror the domain but omit internals (`createdBy` on inputs, snapshot ids, column tiers) and keep JSON schema fields (`configSchema`, `inputSchema`, `outputSchema` on metric declarations) as `Map<String, Object>`. Mappers map from **service** DTOs/models (`service.domain.dto.*`, domain models), never from `web` DTOs. This isolation is a design/AGENTS rule enforced by the layer test, not a spec requirement.

### D5. Suite provisioning = suite + PRIVATE dataset, compensated, in the service layer
`SuiteProvisioningService` (`service.domain`, `@Service`, depends on `TestSuiteService` and `DatasetService`; no cycle because the only existing edge is `DatasetService → TestSuiteService`) calls `TestSuiteService.create(dto, jwt)` then `DatasetService.create({name, visibility: PRIVATE, bindToSuiteId}, jwt)`. Each call keeps its own service-layer transaction. Dataset `name` = suite name; on case-insensitive collision (`DatasetRepository.existsByNameIgnoreCase`, reached via `DatasetService`) append `" (2)"`, `" (3)"`, … (same approach as `DatasetCloneService.deriveCloneName`; extract a shared `DatasetNameDeriver` component). If dataset creation fails, the service attempts `TestSuiteService.delete(suiteId)` and rethrows the original exception; if the delete also fails it logs both and rethrows a `ProvisioningIncompleteException` carrying the `suiteId`, which the translator renders as `INTERNAL_ERROR` with the id in the message. Reusable by REST later.
*Alternatives*: `TestSuiteService.createWithPrivateDataset` (rejected: `TestSuiteService → DatasetService` cycle); orchestrator in `mcp.support` (rejected: cross-domain orchestration and compensation are business logic and belong to `service`).

### D6. `update_test_suite` merge semantics
Tool input is a partial suite; the tool loads the current suite, overlays non-null input fields, and calls the existing PUT path with the suite's current version for optimistic locking. Agents never resend whole objects. `null` in input means "leave unchanged"; explicit clearing is out of scope for v1 and the tool description says so.

### D7. Tool result and error encoding
`McpToolResults` (`mcp.support`) is the single encoder: success → `CallToolResult` with `isError=false` and one `TextContent` holding the result record serialized with the shared `ObjectMapper`; when `@McpTool` output-schema generation is enabled Spring AI additionally fills `structuredContent`, which is a bonus, not the contract. Error → `isError=true`, one `TextContent` holding `McpToolError{code, message, details?}`. Tool methods never throw; a shared `execute(Supplier<T>)` wrapper catches, translates and encodes.

`McpToolErrorTranslator` mapping (all exceptions from `service.domain.exception` unless noted; every branch logs with the exception as last SLF4J argument):

| Exception | Code | Notes |
|---|---|---|
| `EntityNotFoundException` | `NOT_FOUND` | |
| `TryItOutService.TryItOutValidationException` | `VALIDATION_ERROR` | subclass of `ValidationException`; branch exists to add `resolvedRequest` to `details` |
| `ValidationException`, jakarta `ConstraintViolationException` | `VALIDATION_ERROR` | `details` from violations |
| `FilterValidationException` | `VALIDATION_ERROR` | |
| `UniqueConstraintViolationException` | `UNIQUE_CONSTRAINT_VIOLATION` | e.g. duplicate suite/dataset name |
| `InvalidOperationException`, `DatasetVisibilityRuleException` | `INVALID_OPERATION` | visibility rule code appended to `details` |
| `VersionConflictException`, Spring `OptimisticLockingFailureException` | `VERSION_CONFLICT` | |
| `TooManyRunsException` | `TOO_MANY_REQUESTS` | |
| `RunNotTerminalException` | `RUN_NOT_TERMINAL` | |
| suite `datasetId == null` guard in tools | `SUITE_HAS_NO_DATASET` | raised by `UnsupportedFeatureGuard`-adjacent precondition check |
| `PayloadTooLargeException` | `PAYLOAD_TOO_LARGE` | |
| Spring `AccessDeniedException` | `ACCESS_DENIED` | |
| `DialCoreClientException` (runner-core) | `UPSTREAM_TIMEOUT` / `UPSTREAM_AUTH_ERROR` / `UPSTREAM_ERROR` | same status discrimination as `DefaultExceptionHandler` |
| `McpInvocationException` (runner-core) | `UPSTREAM_ERROR` | |
| `UnsupportedFeatureException` (`mcp.support`) | `NOT_SUPPORTED` | |
| anything else | `INTERNAL_ERROR` | generic message |

`McpErrorCode` (`mcp.model`) mirrors the names of the REST `ErrorCode` it shares; a unit test asserts every shared name exists in `web.handler.ErrorCode` (test scope may import both).

### D8. Unsupported-feature guard
`UnsupportedFeatureGuard` runs before any service call in suite and test-case tools and throws `UnsupportedFeatureException` if `additionalRequests` is non-empty, `suiteType == MCP_TOOL`, `mcpDeploymentRef`/`toolRef`/`argumentTemplate` is set, or any test case has non-empty `multiTurnData`. Descriptions for the affected tools and properties are constants in `McpToolDescriptions` and carry the exact sentence "Not supported in this version: only single-request, single-turn DEPLOYMENT suites can be created and run." (asserted literally by the `tools/list` contract test).

### D9. Result delivery
`mcp-results` adds one typed service method, `EvalSummaryService.listByRun(UUID runId, String computation, boolean includeBodies, Cursor cursor, int size)`, wrapping `EvalSummaryRepository.findAllForExport` / `findAllForExportWithBodies` (both cursor-paged, body variant already used by CSV export). `get_run_results` loops over that method until exhausted or the cap is hit (no new unbounded query, no per-row fan-out, no filter-string composition in `mcp`). `ResultRowLimiter` applies `mcp-server.results.max-rows` and produces `{rows, totalRows, truncated}` (`totalRows` from `countByFilter`). `get_run_summary` reuses `EvalSummaryService.aggregate` for per-metric aggregates and the run's overall score. Result-heavy tools may set `_meta["anthropic/maxResultSizeChars"]` (harmless for other hosts).

### D10. Async runs and timeouts
`run_test_suite` delegates to `TestSuiteRunService.createRun` and returns `{runId, status}`; `get_run` returns status, progress counters, error category and costs; `cancel_run` maps to `cancelRun`. Polling cadence is left to the agent; the `instructions` text says so. `spring.ai.mcp.server.request-timeout` (server→client requests) is left at its default; the only long tool is `try_out`, which is bounded by the existing try-out HTTP client timeouts, so no MCP-level timeout is added.

### D11. Session model
`protocol=streamable`, `type=sync`. Sessions are in memory per JVM. Single-replica for v1; multi-replica requires sticky sessions on `Mcp-Session-Id` or a switch to `STATELESS`. Documented in `docs/patterns/mcp-server.md`.

### D12. Testing harness
`McpFunctionalTestSupport` (test scope) builds an `McpSyncClient` with the SDK's streamable HTTP transport against the booted app, injecting `Authorization`/`Api-Key` headers. Per-group functional test classes use `@PostgresFunctionalTests` and the existing `MetaTestDataHelper`/deployment stubs. A `tools/list` contract test asserts, per group: tool names, non-empty descriptions on tools and properties, required-property presence, and the literal unsupported-feature sentence where applicable. The foundation child also asserts the caller-identity scenario (D3 assumption).

### D13. OpenSpec structure (umbrella + children)
This change owns `proposal.md`, `design.md`, the `mcp-server` spec, the `security` delta and a `tasks.md` whose checkboxes are child changes. Children (`mcp-foundation`, `mcp-test-suites`, `mcp-try-out`, `mcp-test-cases`, `mcp-metrics`, `mcp-runs`, `mcp-results`) each carry their own `mcp-tools-<group>` delta spec (per-tool inputs, outputs, errors), design notes only where they deviate from this document, and implementation tasks sized to the 5-task / 1-group iteration rule. Cross-cutting docs (config.yaml, AGENTS.md, key-packages, pattern doc) are **written by `mcp-foundation`** and **re-reviewed at umbrella close-out**. The umbrella is archived only after every child is archived; its archive syncs `mcp-server` and `security`.

## Risks / Trade-offs

- [Agent dumps a large run into context] → row cap + `truncated` flag + `get_run_summary`; pagination is an additive follow-up.
- [Tool descriptions drift from behaviour] → descriptions are constants asserted by `tools/list` contract tests; unsupported-feature sentence tested literally.
- [Two-step suite provisioning leaves an unbound suite on failure] → compensating delete; on double failure the error carries the `suiteId`; functional test forces dataset failure and asserts no suite remains.
- [Tools not executed on the request thread] → verified first in foundation; fallback is an explicit security-context capture around tool execution.
- [Spring AI 2.0.x API churn] → versions pinned via `spring-ai-bom`; MCP SDK version in one `gradle.properties` entry.
- [In-memory sessions break behind a load balancer] → documented; `STATELESS` is a one-property switch if needed.
- [Merge-update hides "clear field" intent] → accepted for v1; documented in the tool description.
- [Layer rule too strict for a future need to reuse a `web` helper] → move the helper to `service.domain` or `utils` instead of relaxing the rule.

## Migration Plan

1. Foundation child adds dependencies, the MCP endpoint, security matcher, layer rule, properties, harness, cross-cutting docs and the deployments tools. Release with MCP enabled; rollback = `spring.ai.mcp.server.enabled=false` (or revert).
2. Remaining children ship in order; each is independently releasable because tools are additive.
3. No data migration. No REST change. Deployment manifests add MCP properties only if defaults are overridden.

## Open Questions

- Whether to expose `_meta["anthropic/maxResultSizeChars"]` on result tools; harmless either way, decide in `mcp-results`.
- Exact default for `mcp-server.results.max-rows` (proposed 500); decide in `mcp-results` after measuring a typical row size.
