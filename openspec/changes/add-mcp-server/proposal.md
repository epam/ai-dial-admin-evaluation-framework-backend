## Why

Agents (and the people steering them) want to build, run and analyse evaluations without hand-driving the REST API. Exposing the Evaluation Framework as an MCP server lets any MCP-capable agent create a test suite for a chosen deployment, iterate on request/extraction templates via try-out, add test cases and metrics, run the suite and read the results, all through a small, stable, LLM-oriented tool surface. This is an **umbrella change**: it fixes the intent, architecture and cross-cutting rules once, and tracks a sequence of child changes that each deliver one tool group.

## What Changes

- **New: MCP server** at `/mcp` using `spring-ai-starter-mcp-server-webmvc` (Spring AI 2.0.1) over the MCP **Streamable HTTP** transport, sync server, tools capability only (no resources/prompts in v1).
- **New top-level package `com.epam.aidial.evaluation.mcp`** holding every MCP concern: tool classes (`mcp.tools.<group>`), MCP-owned request/response models (`mcp.model`), MapStruct mappers (`mcp.mapper`), cross-cutting support (`mcp.support`: error translation, unsupported-feature guard, result cap, caller context), and configuration (`mcp.config`).
- **New architectural layer `mcp`**, a peer of `web`: may depend on `service`, `configuration`, `constants`, `utils`; MUST NOT depend on `web` or `data`; nothing depends on `mcp`. Enforced by `LayeredArchitectureTest`. Requires `openspec/config.yaml` and AGENTS.md updates.
- **Security**: `/mcp/**` is protected exactly like `/api/v1/**` (OIDC/JWT multi-issuer, optional DIAL `Api-Key`, `none` mode permits all). v1 clients authenticate with a statically configured bearer JWT or API key. No MCP OAuth discovery.
- **Decoupled MCP contract**: MCP models are separate from REST DTOs and service models so the MCP surface can stay stable while REST/domain evolve. Contract is agent-oriented: `snake_case` tool names, one input record per tool, descriptions that state preconditions and next steps.
- **Suite-centric surface**: `create_test_suite` also creates a PRIVATE dataset bound to the suite; test-case and schema tools are keyed by `suiteId` and resolve the dataset internally. Datasets are not a first-class MCP concept in v1.
- **Tool groups** (each a child change): deployments, test suites, try-out, test cases + schema, metrics (declarations + suite metric definitions), runs, results (full result rows + summary).
- **Forward-compatible models with explicit v1 limits**: `additionalRequests` (multi-request), `multiTurnData` (multi-turn) and the `MCP_TOOL` suite-type fields (`mcpDeploymentRef`, `toolRef`, `argumentTemplate`) are present in MCP models but documented as "not supported in this version" in tool descriptions and rejected with a `NOT_SUPPORTED` tool error when set.
- **Result delivery**: whole-run results returned in a single tool response (target users run suites of one or two test cases). A configurable cap `ef.mcp-server.results.max-rows` truncates with an explicit `truncated` flag. Pagination and a Query DSL tool are deferred.
- **New configuration** (documented in `docs/configuration.md`): `spring.ai.mcp.server.enabled|name|version|instructions|type|protocol`, `spring.ai.mcp.server.streamable-http.mcp-endpoint`, `ef.mcp-server.results.max-rows`.
- **No DB schema changes**, no Flyway migrations, no REST API changes.

Non-goals for v1 (tracked as deferred items in `design.md`): pagination/cursor for results, Query DSL tool, inline try-out overrides, PUBLIC dataset sharing, multi-request/multi-turn execution, `MCP_TOOL` suites, CSV export as resource link, MCP progress notifications, OAuth discovery, multi-replica session affinity.

## Capabilities

### New Capabilities

- `mcp-server`: cross-cutting MCP server behaviour — endpoint and transport, authentication parity with REST, contract isolation and conventions, error contract, unsupported-feature rejection, suite-centric dataset handling, asynchronous run semantics, result size cap, agent guidance (server instructions and tool descriptions). Per-tool behaviour is specified by child changes in `mcp-tools-<group>` specs.

### Modified Capabilities

- `security`: new requirement — the MCP endpoint (`/mcp/**`) SHALL be protected identically to `/api/v1/**` in every security mode.

## Impact

- **Build**: root `build.gradle` adds `spring-ai-bom:2.0.1` + `spring-ai-starter-mcp-server-webmvc`; declares `platform('io.modelcontextprotocol.sdk:mcp-bom:2.0.0')` so root and `evaluation-runner-core` pin the MCP SDK from one version (verified: the starter transitively pulls `io.modelcontextprotocol.sdk:mcp:2.0.0`, same as runner-core; no conflict; Jackson 3 throughout).
- **New package / layer**: `com.epam.aidial.evaluation.mcp.*` (see What Changes). Major classes: `McpServerConfiguration`, `McpServerProperties`, `McpToolErrorTranslator`, `UnsupportedFeatureGuard`, `McpCallerContext`, `SuiteProvisioningOrchestrator`, one `*Tools` class per group, one `*McpMapper` per group.
- **Modified**: `configuration.security.SecurityConfiguration` (add `/mcp/**` matcher), `architectural.LayeredArchitectureTest` (new layer), `application.yml`, `docs/configuration.md`, `openspec/config.yaml`, `AGENTS.md`, new `docs/patterns/mcp-server.md`.
- **Services consumed (unchanged)**: `DeploymentService`, `TestSuiteService`, `DatasetService`, `TestCaseService`, `TryItOutService`, `MetricDeclarationService`, `TestSuiteMetricDefinitionService`, `TestSuiteRunService`, analytics result / eval-summary services.
- **Security surface**: one new authenticated endpoint family. Tool calls execute on the HTTP request thread, so `SecurityContextHolder`, `AuthorResolver` and `TokenPropagationHelper` behave as for controllers.
- **Operational**: Streamable HTTP sessions are held in memory per instance; multi-replica deployments need sticky sessions (known limitation, documented). MCP can be disabled with `spring.ai.mcp.server.enabled=false`.
- **Risks**: agent misuse of large results (mitigated by row cap and summary tool); tool description drift from behaviour (mitigated by functional tests asserting `tools/list` metadata); Spring AI 2.0.x API churn (pinned via BOM).
- **Rollout**: umbrella stays open while child changes land in order (`mcp-foundation` → `mcp-test-suites` → `mcp-try-out` → `mcp-test-cases` → `mcp-metrics` → `mcp-runs` → `mcp-results`); each child ships behind the same endpoint and is independently releasable.
- **Test plan**: ArchUnit layer rules; unit tests for mappers/translator/guard; per-group functional tests driving `/mcp` with the MCP Java SDK client (already on the classpath) under `@PostgresFunctionalTests`; security functional test (401 without credentials in `oidc` mode).
