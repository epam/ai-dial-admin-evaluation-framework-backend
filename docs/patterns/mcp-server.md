# MCP Server (inbound)

The Evaluation Framework exposes itself as an **MCP server** so an MCP-capable agent can drive it directly (discover deployments, build a suite, run it, read results) instead of hand-driving the REST API. This is the *inbound* MCP surface — distinct from [MCP Tool Invocation](mcp-tool-invocation.md), which is this application acting as an *outbound* MCP client against DIAL Core's MCP proxy for `MCP_TOOL` suites.

Built on `spring-ai-starter-mcp-server-webmvc` (Spring AI 2.0.1) over the MCP Streamable HTTP transport, sync server, tools capability only. Configuration: [`docs/configuration.md` §2.6](../configuration.md#26-mcp-server-inbound).

## The `mcp` layer, and why not `web.mcp`

`com.epam.aidial.evaluation.mcp` is a **peer of `web`**, not a sub-package of it, enforced by `LayeredArchitectureTest` (`mcp` layer, `mayOnlyBeAccessedByLayers("configuration")`). The MCP transport is a second, independent entry point into the application — it has its own request/response contract (`CallToolResult`, `McpToolError`), never a `web` DTO, and its own tool-method conventions (enforced by `McpToolConventionTest`). `service` is extended to allow `mcp` as a caller, symmetric with `web`: `mayOnlyBeAccessedByLayers("web", "mcp", "configuration")`. Outbound, `mcp` may depend on `service`, `configuration`, `constants`, `utils`, `client.*.dto` (e.g. `InterfaceType`, a `DeploymentService` parameter type) and `runner.*` (e.g. `AuthorizationTokenHolder`, `DialCoreClientException`); it must never depend on `web` or `data`.

Sub-packages: `mcp.constants` (`McpToolNames`, `McpToolDescriptions` — MCP-only, so they live under `mcp` rather than the app-wide `constants` package), `mcp.model` (MCP-owned DTOs, `McpErrorCode`, `McpToolError`), `mcp.mapper` (MapStruct mappers from service DTOs to `mcp.model`), `mcp.support` (cross-cutting tool plumbing), `mcp.tools.<group>` (one `@Component` per tool group, e.g. `mcp.tools.deployment.DeploymentTools`).

## Request-thread caller model

Tool bodies execute on the same HTTP request thread as the security filter chain — no propagation machinery is added. This rests on two Spring AI / MCP-SDK library behaviours:

1. Spring AI's servlet sync-server customizer (`McpServerAutoConfiguration.servletMcpSyncServerCustomizer`, active for `type=SYNC`) calls `SyncSpecification.immediateExecution(true)`, so `McpServerFeatures.AsyncToolSpecification.fromSync` does **not** add `subscribeOn(Schedulers.boundedElastic())`.
2. The WebMVC Streamable HTTP transport handles a POSTed request inside its `SseBuilder` consumer with `session.handle(msg).contextWrite(...).block()`, and Spring MVC runs that consumer synchronously on the container request thread.

Together, the tool body runs on the request thread while `SecurityContextHolder` and `AuthorizationTokenHolder` are still populated exactly as they are for a REST controller. `mcp.support.McpCallerContext` reads from that thread:

- `authentication()` — the current `Authentication`, or `null`. Treats `AnonymousAuthenticationToken` as "no authentication" (Spring Security wires an `AnonymousAuthenticationFilter` by default even in `config.rest.security.mode=none`, so without this filtering the context would never hold a real `null`).
- `jwt()` — the `Jwt` principal, or `null` when the principal is not a `Jwt` (e.g. an `Api-Key` caller, whose principal is a `String`).
- `callerCredential()` — `AuthorizationTokenHolder.getCredential()`, the nullable `CallerCredential` (value + `BEARER`/`API_KEY` kind); see [token-propagation](token-propagation.md).

**Api-Key caller contract (REST parity).** An `Api-Key` MCP caller has no `Jwt`, but `callerCredential()` returns `API_KEY`: `createdBy` resolves via `AuthorResolver`'s `Authentication`-name fallback to the introspected principal (the DIAL Core project name), exactly as the REST path resolves it, and Core-backed tools forward the key as an `Api-Key` header via `DialCoreClientConfiguration.callerCredentialInterceptor()` (the shared `RestClient` interceptor, which picks the one header the credential's kind requires), not `Authorization: Bearer`. Neither `jwt()` nor `callerCredential()` returning a bearer value is required for correct attribution or propagation. Note: attribution is probe-verified until a mutating MCP tool exists — the production MCP tools shipped by `mcp-foundation` are read-only deployment queries that never write `createdBy`; the only MCP consumer of `AuthorResolver` today is the test-only `probe_caller_identity` tool (see Harness usage below).

**Recorded fallback (not implemented; D-F2c).** If a future Spring AI/MCP-SDK upgrade drops either dependency above, the identity probe functional test (`probe_caller_identity`, see below) fails immediately. The fallback is an owned `WebMvcStreamableServerTransportProvider` bean with a `contextExtractor` that copies `Authentication` and the bearer header into the `McpTransportContext`, plus an `McpTransportContext` tool parameter and an executor overload that sets/clears `SecurityContextHolder`/`AuthorizationTokenHolder` around the call. See `openspec/changes/archive/2026-09-18-mcp-foundation/design.md` D-F2/D-F2c for the full design.

## Executor contract: tools never throw

Every `@McpTool` method is written as a one-line call into `mcp.support.McpToolExecutor`:

```java
@McpTool(name = McpToolNames.GET_DEPLOYMENT, description = McpToolDescriptions.GET_DEPLOYMENT)
public CallToolResult getDeployment(
        @McpToolParam(description = McpToolDescriptions.DEPLOYMENT_ID, required = true) String deploymentId) {
    return executor.execute(() -> mapper.toDetail(deploymentService.getDeployment(deploymentId)));
}
```

`McpToolExecutor.execute` (two overloads: `Supplier<T>`, and `Function<McpCallerContext, T>` for tools that need the caller's identity) runs the body, encodes success via `McpToolResults.success(Object)`, and translates any `RuntimeException` via `McpToolErrorTranslator` into an error result. Tool methods built on this class never throw and never touch `CallToolResult` directly. `McpToolConventionTest` (ArchUnit, `mcp.tools..`) enforces the tool-method shape project-wide: return type is `McpSchema.CallToolResult`; every parameter carries `@McpToolParam` with a non-blank description; parameter types are `String`/`Boolean`/`Integer`/`Long` or a record in `mcp.model`; tool names are `snake_case`.

## Result and error encoding

`McpToolResults` encodes: success → one `TextContent` block with the JSON payload, `isError=false`; a payload serialization failure becomes an `INTERNAL_ERROR` result rather than an exception. Error → one `TextContent` block with the JSON `McpToolError{code, message, details}`, `isError=true`.

`McpToolErrorTranslator` maps exceptions to `McpErrorCode` (15 codes; every code except `NOT_SUPPORTED` has a same-named, same-meaning counterpart in `web.handler.ErrorCode`, verified by `McpErrorCodeAlignmentTest`), ordered most specific first — validation, not-found, unique-constraint, dataset-visibility/invalid-operation, version-conflict, too-many-runs, run-not-terminal, access-denied, the full `DialCoreErrorCode` → `McpErrorCode` mapping for `DialCoreClientException`, `ResourceAccessException` (DIAL Core unreachable — `UPSTREAM_TIMEOUT` only when the cause chain contains a socket/HTTP timeout, else `UPSTREAM_ERROR`; the message is a fixed "DIAL Core is unreachable", **never** `getMessage()`, which contains the DIAL Core URL), `McpInvocationException`, `UnsupportedFeatureException` → `NOT_SUPPORTED`, and a generic `INTERNAL_ERROR` fallback. Every branch logs with the exception as the last SLF4J argument.

**Binding-layer limitation.** Argument *type* mismatches (e.g. a JSON number where a string is declared) are rejected by Spring AI's own binding before the tool body runs, and produce Spring AI's plain-text `isError` result — outside this structured contract entirely. All tool parameters are therefore declared as `String`, `Boolean`, `Integer`, `Long` or `mcp.model` records, with *value* validation happening inside the tool body (e.g. `list_deployments`'s `type`/`interfaceType` filters are `String`, parsed via `fromWireValue` inside `execute(...)`, not enums — the schema generator would otherwise advertise Java constant names while binding rejects them), so every value error still reaches the caller through the translator.

**`PayloadTooLargeException` note.** `service.domain.exception.PayloadTooLargeException` extends the checked `java.io.IOException`, not `RuntimeException`, so it can never reach `McpToolExecutor.execute`'s `catch (RuntimeException e)` and has no row in the translator. A future MCP tool that can produce this condition (e.g. a CSV-import-like tool) must catch it at its own call site and rethrow as an unchecked exception before the body returns.

## Unsupported-feature guard

`mcp.support.UnsupportedFeatureGuard` (`rejectIfPresent`, `rejectIfNotEmpty`) throws `UnsupportedFeatureException` — translated to `NOT_SUPPORTED` — for forward-compatible fields not supported in this version (multi-request suites, multi-turn test cases, `MCP_TOOL` suites). The exception message starts with `McpToolDescriptions.UNSUPPORTED_FEATURES_SENTENCE`, the literal sentence the umbrella spec requires tool descriptions to state.

## Constants registry (`mcp.constants`)

`McpToolNames` (one `snake_case` constant per tool) and `McpToolDescriptions` (tool/parameter descriptions, including `UNSUPPORTED_FEATURES_SENTENCE`) live under `mcp.constants`, not the app-wide `constants` package — they are MCP-only. Descriptions state preconditions, the accepted values of string-typed filters, and the tool an agent is expected to call next.

Every `@McpTool` method also declares `annotations = @McpTool.McpAnnotations(...)` explicitly, rather than relying on the library defaults (`readOnlyHint=false`, `destructiveHint=true`, `openWorldHint=true` — a client that only reads these hints would otherwise treat every tool as destructive). Read-only query tools: `readOnlyHint=true`, `destructiveHint=false`, `idempotentHint=true`, `openWorldHint=false`. Mutating tools: `readOnlyHint=false`, `destructiveHint` per actual effect, `idempotentHint` per semantics, `openWorldHint=false` (DIAL Core is a closed, first-party system, not the open web). Each tool also gets a short human `title` (e.g. `McpToolDescriptions.LIST_DEPLOYMENTS_TITLE`). `McpToolConventionTest` enforces `readOnlyHint == !destructiveHint`, a non-blank `title`, and `openWorldHint == false` on every `@McpTool` method.

## `JsonMapperConfiguration.canRead(String)` gotcha

The project's custom `JacksonJsonHttpMessageConverter` bean (`configuration.JsonMapperConfiguration`) sits ahead of the default converter list. A Jackson converter reports `canRead=true` for `String.class` against `application/json` (it only actually succeeds for a JSON string scalar, not the JSON object every JSON-RPC request is), so it pre-empted `StringHttpMessageConverter` for Spring AI's `WebMvcStreamableServerTransportProvider.handlePost`, which reads the raw JSON-RPC body via `ServerRequest.body(String.class)` and parses it itself. Every MCP request failed with a 500 (`MismatchedInputException`) before ever reaching Spring AI's JSON-RPC parsing. Fixed with a `canRead` override declining `String.class` on that same converter bean, symmetric with its existing `canWrite` override declining `byte[].class` (documented in the class's own Javadoc).

## Harness usage

- `functional.support.McpFunctionalTestSupport` — builds an `McpSyncClient` over `HttpClientStreamableHttpTransport` against the booted app (header injection via `httpRequestCustomizer`), plus `callTool`/`readJson` helpers and a `rawPost` helper (bypassing the MCP Java SDK) for asserting raw HTTP status codes (401 unauthenticated, 404 disabled server) that the SDK's blocking transport would otherwise surface only as an opaque client-side exception.
- `functional.support.StaticJwtTestConfiguration` — a `@TestConfiguration` providing `@Bean @Primary TokenDecoderFactory staticTokenDecoderFactory()` under a bean name **different** from `SecurityConfiguration`'s own `tokenDecoderFactory` (which stays in the context, unused). `@Primary` deterministically wins `securityFilterChain`'s by-type parameter resolution regardless of `@Import`/component-scan order. A same-named `@Bean` override was tried first and did **not** win — `ConfigurationClassPostProcessor` expanded `SecurityConfiguration`'s definition last in this project's bootstrap, overwriting the test one. Its decoder maps `"alice-token"` to a fixed `Jwt` (`sub=alice`, `roles=[admin]`) and throws `BadJwtException` for anything else.
- `functional.support.CallerIdentityProbeTools` — a test-scope `@TestConfiguration` (not `@Component`, so normal component scanning never picks it up) carrying `@McpTool(name = "probe_caller_identity")` directly on itself. Once a test `@Import`s it, the configuration class becomes a bean like any other, and Spring AI's annotation scanner (a plain `BeanPostProcessor`) discovers the `@McpTool` method on it. Returns `{threadName, authenticationPresent, createdBy, credentialKind}`, proving the request-thread caller model before any production tool existed.
- Nested registration pattern: `@Import` is declared on the concrete `@Nested` class in `PostgresFunctionalTests` (e.g. `@Nested @Import(CallerIdentityProbeTools.class) class McpServerFoundationTests extends McpServerFoundationFunctionalTests {}`), never on the abstract test base class — importing directly on the abstract base broke `@AutoConfigureTestRestTemplate` autowiring, matching the existing convention for other per-scenario `@TestPropertySource`/`@Import` usages (e.g. `ApiKeyAuthenticationTests`).

## Session limitation

Streamable HTTP sessions (`Mcp-Session-Id`) are held in memory per JVM. Single-replica deployments work as-is; a multi-replica deployment needs sticky sessions routed on `Mcp-Session-Id`, or a switch to `spring.ai.mcp.server.protocol=STATELESS`.

## Version lock-step rule

`gradle.properties`' `mcp_sdk_version` (currently `2.0.0`) must equal the MCP SDK version managed by the Spring AI BOM (`org.springframework.ai:spring-ai-bom`, currently `2.0.1`) in use, interpolated by both root `build.gradle` and `evaluation-runner-core/build.gradle`. Bumping either the Spring AI BOM or the MCP SDK pin without checking the other risks two copies of the protocol core drifting apart on the classpath; re-run `./gradlew dependencies --configuration runtimeClasspath` and confirm no upgrade arrow on `io.modelcontextprotocol.sdk:mcp-core` after any bump.
