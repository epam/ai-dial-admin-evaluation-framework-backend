## Context

See `proposal.md` (Why) and the umbrella `add-mcp-server/design.md` for the architecture this child implements. Facts established against the resolved jars (`spring-ai-*-2.0.1`, `mcp-core-2.0.0`, `javap` and a compiled schema probe) that shape this design:

- **Tool thread model (umbrella D3 verified: it holds, conditionally).** `McpServerFeatures.AsyncToolSpecification.fromSync(spec, immediateExecution)` only adds `subscribeOn(Schedulers.boundedElastic())` when `immediateExecution` is `false`. Spring AI's `McpServerAutoConfiguration.servletMcpSyncServerCustomizer` (`@ConditionalOnWebApplication(SERVLET)`, `type=SYNC` with `matchIfMissing`) calls `SyncSpecification.immediateExecution(true)`. The WebMVC streamable transport handles a POSTed request inside the `SseBuilder` consumer with `session.handle(msg).contextWrite(...).block()`, and Spring MVC runs that consumer synchronously in `SseServerResponse.writeTo` on the container request thread. Therefore the tool body executes on the request thread while the security filter chain is still active: `SecurityContextHolder` holds the caller's `Authentication` and `AuthorizationHeaderInterceptor` (registered without path patterns, so it also covers `/mcp`) has populated `AuthorizationTokenHolder`. This depends on two library behaviours (`immediateExecution(true)` and the synchronous `block()`), so it is **verified empirically first** (D-F2) and a contingency is kept (D-F2c).
- **Transport hooks (contingency only).** Spring AI's `WebMvcStreamableServerTransportProvider` bean and its `RouterFunction` are `@ConditionalOnMissingBean`; the builder exposes `contextExtractor(McpTransportContextExtractor<ServerRequest>)`, `securityValidator` (default `NOOP`), `maxSessions`, `sessionIdleTimeout`. The autoconfig bean injects `@Qualifier("mcpServerJsonMapper") JsonMapper`, a `defaultCandidate=false` bean invisible to by-type injection. An `McpTransportContext` tool-method parameter is supported and excluded from the generated input schema.
- **Activation conditions.** `spring.ai.mcp.server.enabled` is `matchIfMissing=true`; `spring.ai.mcp.server.protocol=STREAMABLE` is **`matchIfMissing=false`** in every Spring AI condition — with `protocol` unset, no server autoconfig activates. `McpServerStreamableHttpProperties` is registered only by the streamable WebMVC autoconfiguration.
- **Annotation tooling.** `@McpToolParam` has only `required()` and `description()`; input property names come from `Parameter.getName()` (`-parameters` is on via the Boot Gradle plugin), so a property cannot be named with a Java keyword. `McpJsonSchemaGenerator` registers `JacksonSchemaModule` with only `RESPECT_JSONPROPERTY_REQUIRED`: enum parameters are emitted with `Enum.name()` values while argument binding honours `@JsonCreator`, so wire-value enums would advertise values they reject. `SyncMcpToolMethodCallback.apply` catches `RuntimeException` around argument conversion **and** the method call and returns a plain-text `isError` result; conversion failures never reach tool code. `@McpTool` attributes: `name`, `description`, `title`, `annotations`, `generateOutputSchema` (default `false`), `metaProvider`. Methods returning `McpSchema.CallToolResult` are passed through unchanged.
- **Spring AI properties** (prefixes `spring.ai.mcp.server`, `.streamable-http`, `.annotation-scanner`): `enabled`, `stdio`, `name`, `version`, `instructions`, `type` (`SYNC|ASYNC`), `protocol` (`SSE|STREAMABLE|STATELESS`), `request-timeout`, `capabilities.{tool,resource,prompt,completion}`, `streamable-http.{mcp-endpoint,keep-alive-interval,disallow-delete}`, `annotation-scanner.enabled`.
- **Existing security shapes.** JWT callers have a `Jwt` principal; `Api-Key` callers have `ApiKeyAuthenticationToken` with a `String` principal, so `@AuthenticationPrincipal Jwt` is `null` for them and `AuthorResolver.getCreatedBy(null)` yields `anonymous`. Functional tests run with `config.rest.security.mode=none`; `oidc` nested classes exist but only assert 401 paths. `SecurityConfiguration` declares `@Bean TokenDecoderFactory tokenDecoderFactory(...)` injected by type; `spring.main.allow-bean-definition-overriding=true`.
- **Upstream errors.** `DialCoreClient.withRetry` wraps only `RestClientResponseException` into `DialCoreClientException`; connect failures and socket timeouts escape as Spring `ResourceAccessException`, whose message contains the target URL. `DialCoreErrorMapper.toDialCoreErrorCode` yields `UPSTREAM_AUTH_ERROR` (401), `ACCESS_DENIED` (403), `UPSTREAM_NOT_FOUND` (404), `VALIDATION_ERROR` (other 4xx), `UPSTREAM_TIMEOUT` (504) or `UPSTREAM_ERROR`.
- **Deployments read path.** `DeploymentService.getAllDeployments` maps through `DeploymentMapper.toDeploymentInfoShortDto`, which fills only `deploymentId`, `displayName`, `description`, `interfaces` (and `transport` for toolsets) by design; `getDeployment(String id)` returns the full DTO and translates upstream 404 to `EntityNotFoundException("Deployment not found: <id>")`. The deployment kind is the `@JsonTypeInfo` subclass, not a field.
- Existing `McpDeploymentFunctionalTests` etc. test the MCP **client**; `LayeredArchitectureTest` uses only `mayOnlyBeAccessedByLayers`, and `client`/`runner` are not layers. No `archunit.properties` exists, so ArchUnit rules fail on an empty class set.
- **Annotation package (verified via `javap` against the resolved `spring-ai-mcp-annotations-2.0.1.jar`).** `@McpTool` and `@McpToolParam` live under `org.springframework.ai.mcp.annotation`, not `org.springaicommunity.mcp.annotation` — no `org.springaicommunity` artifact is anywhere in the resolved dependency tree for `spring-ai-bom:2.0.1`.
- **`JacksonJsonHttpMessageConverter` intercepted the MCP transport's own body read (found empirically, fixed here).** The project's `JsonMapperConfiguration` already registers a custom `JacksonJsonHttpMessageConverter` bean ahead of the default converter list (an existing comment on that bean documents this for a `byte[]`/OpenAPI-spec case). Because a Jackson converter reports `canRead=true` for `String.class` against `application/json` (it only actually succeeds for a JSON string scalar, not the JSON object every JSON-RPC request is), it pre-empted `StringHttpMessageConverter` for Spring AI's `WebMvcStreamableServerTransportProvider.handlePost`, which reads the raw JSON-RPC body via `ServerRequest.body(String.class)` and parses it itself. Every MCP request — in every security mode, regardless of body content — failed with a 500 (`MismatchedInputException`: "Cannot deserialize value of type `String` from Object value") before ever reaching Spring AI's own JSON-RPC parsing. Fixed with a `canRead` override on the same converter bean declining `String.class`, symmetric with its existing `canWrite` override declining `byte[].class`.

## Goals / Non-Goals

**Goals:**
- Every later child adds a `*Tools` class plus MCP models/mappers and nothing else: security, caller access, error translation, encoding, descriptions and the test harness are complete here.
- A functional test proves a tool sees the JWT caller identity, without a live identity provider, and it runs **before** any propagation machinery is considered.
- Deviations from the umbrella are explicit (proposal) and pushed back into the umbrella artifacts (task 5.5).

**Non-Goals:**
- Anything under Non-goals in `proposal.md`.
- Propagating MDC / OpenTelemetry context beyond what the request thread already carries.

## Decisions

### D-F1. Dependency wiring
`build.gradle` (root):
```
implementation platform("org.springframework.ai:spring-ai-bom:2.0.1")
implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc") {
    exclude group: "org.springframework.boot", module: "spring-boot-starter-web"
}
implementation platform("io.modelcontextprotocol.sdk:mcp-bom:${mcp_sdk_version}")
```
`gradle.properties` gains `mcp_sdk_version=2.0.0` (file currently lacks a trailing newline; add one) with a comment that it must equal the MCP SDK version managed by the Spring AI BOM in use; `evaluation-runner-core/build.gradle` interpolates the same property. Verified by the dependency report in the task.
*Alternative*: let the Spring AI BOM own the MCP version and drop `mcp-bom`. Rejected: `evaluation-runner-core` must stay Spring-AI-free and needs its own pin.

### D-F2. Caller identity: request-thread model, verified first
No owned transport bean, no context extractor. Tools read the caller from the thread they run on:
```
mcp.support.McpCallerContext   @Component: Authentication authentication(); Jwt jwt() (principal instanceof Jwt ? jwt : null); String bearerToken() (AuthorizationTokenHolder)
mcp.support.McpToolExecutor    @Component: <T> CallToolResult execute(Supplier<T> body); <T> CallToolResult execute(Function<McpCallerContext, T> body)
```
`McpToolExecutor.execute` = run the body → `McpToolResults.success(result)`; any `RuntimeException` → `McpToolErrorTranslator.translate(e)` → `McpToolResults.error(...)`. Tool methods never throw and never touch `CallToolResult` directly. Services that take a `Jwt` (later children) receive `callerContext.jwt()`; `Api-Key` callers get `null` → `createdBy = anonymous`, identical to REST.

`McpCallerContext.authentication()` treats an `AnonymousAuthenticationToken` the same as "no authentication" (returns `null`), not the raw `SecurityContextHolder` value. Spring Security's `HttpSecurity` wires an `AnonymousAuthenticationFilter` by default even under `NoSecurityConfiguration` (`config.rest.security.mode=none`), so without this filtering `SecurityContextHolder` always holds a non-null `AnonymousAuthenticationToken` in none mode and `authenticationPresent` in the probe tool would never read `false`. `jwt()` is unaffected either way (an anonymous token's principal is the `"anonymousUser"` string, not a `Jwt`), but `authentication()` itself needed the filter to give tool code — and the probe's `authenticationPresent` field — the intended "is there a real caller" signal.

**Known limitation (REST parity).** An `Api-Key` caller has `ApiKeyAuthenticationToken` with a `String` principal, so `jwt()` is `null` → `createdBy = anonymous`, and `bearerToken()` is also `null` because `AuthorizationHeaderInterceptor` only captures an `Authorization: Bearer` header and `DialCoreClientConfiguration` only forwards a bearer token onward — Core-backed tools therefore call DIAL Core without caller credentials for an `Api-Key` caller, exactly as the REST controllers do today. Out of scope here; a future change would need to fix REST and MCP together (e.g. forwarding the `Api-Key` header itself, or minting a delegated Core credential).

**Verification comes first (task 1.5).** A test-scope `@McpTool probe_caller_identity` returns `{threadName, authenticationPresent, createdBy, bearerTokenPresent}` with no propagation machinery installed. The `oidc` functional test asserts `createdBy = alice` and `bearerTokenPresent = true`; the `none`-mode test asserts `createdBy = anonymous`. Only if that fails does D-F2c apply.

Tool method shape (enforced by `McpToolConventionTest`, D-F10):
```java
@McpTool(name = McpToolNames.GET_DEPLOYMENT, description = McpToolDescriptions.GET_DEPLOYMENT)
public CallToolResult getDeployment(
        @McpToolParam(description = McpToolDescriptions.DEPLOYMENT_ID, required = true) String deploymentId) {
    return executor.execute(() -> mapper.toDetailDto(deploymentService.getDeployment(deploymentId)));
}
```
*Alternatives*: transport-context capture (D-F2c) — correct but adds an owned transport bean, a qualifier-sensitive `JsonMapper` injection and condition mirroring for a problem the library already solves; `MODE_INHERITABLETHREADLOCAL` — pointless on the same thread and unsafe on pools.

### D-F2c. Contingency: transport-context capture (apply only if the D-F2 probe fails)
`configuration.mcp.McpServerTransportConfiguration` (`@Configuration`, `@LogExecution`) defines `WebMvcStreamableServerTransportProvider` with `@Qualifier("mcpServerJsonMapper") JsonMapper`, `McpServerStreamableHttpProperties`, and `.contextExtractor(McpCallerContextExtractor)`; conditions mirror Spring AI **exactly** (`enabled` matchIfMissing=true, `protocol=STREAMABLE` matchIfMissing=**false**). `McpCallerContextExtractor` copies `Authentication` and the bearer header into the `McpTransportContext`; tools gain an `McpTransportContext` parameter and `McpToolExecutor.execute(ctx, ...)` sets and clears `SecurityContextHolder` / `AuthorizationTokenHolder` in a `finally`. Not implemented unless needed; recorded so the fallback is a known quantity.

### D-F3. Security matcher (single endpoint)
`SecurityConfiguration` gets one field-injected `@Value` endpoint:
```
@Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint}") String mcpEndpoint;          // /mcp
```
Normalised (trailing slash stripped) and the matcher authenticates exactly that one path: `.requestMatchers(mcpEndpoint).authenticated()`. `WebMvcStreamableServerTransportProvider` and `WebMvcStatelessServerTransport` both route GET/POST/DELETE on exactly this configured path and serve no sub-path, so the matcher needs neither a `/**` suffix nor extra patterns; an operator can switch `spring.ai.mcp.server.protocol` between `STREAMABLE` and `STATELESS` purely via configuration, with no code change. The deprecated SSE transport (`/sse`, `/mcp/message`) is out of scope and deliberately unsupported — the matcher does not cover it. `NoSecurityConfiguration` already permits everything. With `enabled=false` the matcher still applies: 401 without credentials, then MVC 404 — both umbrella disabled scenarios.

*Rejected*: a startup guard failing unless `protocol=STREAMABLE` (an earlier revision of this design). Rejected per explicit user decision: the operator must be able to switch `spring.ai.mcp.server.protocol` (`STREAMABLE`/`STATELESS`) purely via configuration, and a guard defeats that. The single-endpoint matcher above is the replacement — it authenticates the one path both supported transports serve, so there is nothing left for a guard to protect against.

### D-F4. `mcp` layer in `LayeredArchitectureTest`
Add `MCP_PACKAGE = "com.epam.aidial.evaluation.mcp.."`, `.layer("mcp").definedBy(MCP_PACKAGE)`, `.whereLayer("mcp").mayOnlyBeAccessedByLayers("configuration")`, and extend `service` to `mayOnlyBeAccessedByLayers("web", "mcp", "configuration")`. `web` and `data` already exclude `mcp` implicitly. Outbound, `mcp` may use `service`, `configuration`, `constants`, `utils`, `client.*.dto` (e.g. `InterfaceType`, a parameter of `DeploymentService`) and `runner.*` (e.g. `McpTransport`, `AuthorizationTokenHolder`); it must not use `web` or `data`. The `config.yaml` Layering Principle line says exactly that. A comment in the test explains why `mcp` is a peer of `web`, not `web.mcp`.

### D-F5. Result and error encoding
- `mcp.model.McpErrorCode` (15 codes), `mcp.model.McpToolError(code, message, List<String> details)`.
- `mcp.support.McpToolResults` (`@Component`, shared `ObjectMapper`): `success(Object)` → `isError=false` + one `TextContent` with the JSON; `error(McpToolError)` → `isError=true` + one text block. A serialization failure of a success payload becomes an `INTERNAL_ERROR` result, never an exception. Consequence of the `NON_NULL` mapper: explicit JSON nulls inside `Map` payloads such as `features` are dropped; acceptable for read-only metadata and stated in the spec.
- `mcp.support.McpToolErrorTranslator` (`@Component`), ordered most specific first:

| Exception | Code | Message / details |
|---|---|---|
| `TryItOutService.TryItOutValidationException` | `VALIDATION_ERROR` | message; `resolvedRequest` in details when present |
| `ValidationException`, jakarta `ConstraintViolationException` | `VALIDATION_ERROR` | violations in details |
| `FilterValidationException` | `VALIDATION_ERROR` | deliberate: REST uses `INVALID_FILTER`, which MCP does not carry |
| `EntityNotFoundException` | `NOT_FOUND` | message (names type and id) |
| `UniqueConstraintViolationException` | `UNIQUE_CONSTRAINT_VIOLATION` | message |
| `DatasetVisibilityRuleException` | `INVALID_OPERATION` | rule code in details (`SUITE_HAS_NO_DATASET` is a tool precondition, not this exception) |
| `InvalidOperationException` | `INVALID_OPERATION` | message |
| `VersionConflictException`, Spring `OptimisticLockingFailureException` | `VERSION_CONFLICT` | message |
| `TooManyRunsException` | `TOO_MANY_REQUESTS` | message |
| `RunNotTerminalException` | `RUN_NOT_TERMINAL` | message (names status) |
| `PayloadTooLargeException` | `PAYLOAD_TOO_LARGE` | message — **not implemented, see deviation below** |
| Spring `AccessDeniedException` | `ACCESS_DENIED` | fixed message |
| `DialCoreClientException` by `DialCoreErrorMapper.toDialCoreErrorCode(status)`: `UPSTREAM_TIMEOUT`→`UPSTREAM_TIMEOUT`; `UPSTREAM_AUTH_ERROR`/`AUTHENTICATION_REQUIRED`→`UPSTREAM_AUTH_ERROR`; `ACCESS_DENIED`→`ACCESS_DENIED`; `VALIDATION_ERROR`→`VALIDATION_ERROR`; `UPSTREAM_NOT_FOUND`, `NOT_FOUND`, `UPSTREAM_ERROR`→`UPSTREAM_ERROR` | as listed | message from the exception (already user-facing in REST) |
| Spring `ResourceAccessException` (Core unreachable / socket timeout) | `UPSTREAM_TIMEOUT` when the cause chain contains a `SocketTimeoutException`/`HttpTimeoutException`, else `UPSTREAM_ERROR` | **fixed** message "DIAL Core is unreachable" — never `getMessage()`, which contains the URL |
| `McpInvocationException` (runner-core) | `UPSTREAM_ERROR` | message |
| `UnsupportedFeatureException` | `NOT_SUPPORTED` | message |
| anything else | `INTERNAL_ERROR` | fixed "Unexpected server error"; logged at `error` |

Every branch logs with the exception as the last SLF4J argument (`warn` for mapped, `error` for unmapped). `McpErrorCodeAlignmentTest` (test scope) asserts every `McpErrorCode` except `NOT_SUPPORTED` exists in `web.handler.ErrorCode`.

**Deviation (task 2.2):** `service.domain.exception.PayloadTooLargeException` extends the checked
`java.io.IOException`, not `RuntimeException`, so it can never reach `translate(RuntimeException e)` —
neither `catch (RuntimeException e)` in `McpToolExecutor.execute` nor a `case PayloadTooLargeException`
pattern against a `RuntimeException` switch selector can match it (the two types are unrelated, so the
pattern does not compile). The row is therefore omitted from the implemented translator, with no
corresponding test. A future MCP tool that can produce this condition (e.g. a CSV-import-like tool) must
catch it at its own call site and rethrow as an unchecked exception before the body returns to
`McpToolExecutor`.

**Known limitation (recorded for the umbrella spec):** argument *type* mismatches (e.g. a JSON number where a string is declared) are rejected by Spring AI's binding before the tool runs and produce Spring AI's plain-text `isError` result, outside the structured contract. All tool parameters are therefore declared as `String`, `Boolean`, `Integer` or record types whose *value* validation happens inside the tool body, so every value error goes through the translator.
*Alternative*: reuse `DefaultExceptionHandler`. Rejected: it lives in `web` and produces `ResponseEntity<ErrorView>`.

### D-F6. Unsupported-feature guard, model-agnostic
`mcp.support.UnsupportedFeatureGuard` (`@Component`): `rejectIfPresent(String feature, Object value)`, `rejectIfNotEmpty(String feature, Collection<?> value)` → `UnsupportedFeatureException(feature)` whose message starts with `McpToolDescriptions.UNSUPPORTED_FEATURES_SENTENCE`. Suite/test-case children call it with their fields.

### D-F7. Names, descriptions, instructions
Constants follow the "one constants class per bounded context" rule and live in `com.epam.aidial.evaluation.mcp.constants` (not the top-level `constants` package): `McpToolNames` (tool names, `snake_case`) and `McpToolDescriptions` (tool and parameter descriptions, `UNSUPPORTED_FEATURES_SENTENCE`). They are MCP-only, not cross-cutting, so they belong under the `mcp` root rather than the app-wide `constants` package. Descriptions state preconditions, accepted values for string-typed filters, and the next step. Server `instructions` are a multi-line scalar under `spring.ai.mcp.server.instructions` in `application.yml` describing the full workflow (deployments → create suite → try_out/update loop → schema + test cases → metrics → run → poll `get_run` → results/summary), naming later tools by their planned names.

### D-F8. Deployments tool group
```
mcp.model.DeploymentKind             enum DIAL_MODEL("dial-model"), DIAL_APPLICATION("dial-application"), DIAL_TOOLSET("dial-toolset"); @JsonValue; static fromWireValue(String) throws ValidationException listing accepted values
mcp.model.DeploymentInterface        enum mirroring InterfaceType wire values; same fromWireValue contract
mcp.model.DeploymentSummaryMcpDto    record(deploymentId, type, displayName, description, interfaces, toolsetTransport)   — list shape
mcp.model.DeploymentMcpDto           record(deploymentId, type, displayName, version, description, owner, interfaces, features, createdAt, updatedAt, model, toolset, application)   — detail shape
mcp.model.ModelDetailsMcpDto / ToolsetDetailsMcpDto / ApplicationDetailsMcpDto
mcp.model.DeploymentListMcpDto       record(List<DeploymentSummaryMcpDto> deployments, int total)
mcp.mapper.DeploymentMcpMapper       MapStruct; explicit `default DeploymentKind kindOf(DeploymentInfoDto)` switch on subclass (MapStruct cannot derive the @JsonTypeInfo discriminator); toSummary(...), toDetail(...); enum <-> service/client enums by wire value
mcp.tools.deployment.DeploymentTools @Component @LogExecution
    list_deployments(@McpToolParam type: String?, @McpToolParam interfaceType: String?)  -> parse via fromWireValue inside execute(...) -> DeploymentService.getAllDeployments(kind, iface) -> DeploymentListMcpDto
    get_deployment(@McpToolParam deploymentId: String, required)                        -> blank -> ValidationException; DeploymentService.getDeployment(id) -> DeploymentMcpDto
```
Filters are `String` parameters (not enums) because the schema generator would advertise constant names while binding rejects them, and because binding errors bypass the translator (Context). The parameter is `interfaceType`, not `interface` (Java keyword; names come from `Parameter.getName()`). Descriptions enumerate the accepted values, and the contract test asserts that text. The list shape is lean by construction of `DeploymentService.getAllDeployments`; detail fields are only available from `get_deployment` — no per-row fan-out (project rule).
*Alternative*: one input record per tool (`ListDeploymentsInput`) as umbrella D2 sketched. Rejected: Spring AI binds a non-special parameter by name, producing a nested `{"input": {...}}` property; flat `@McpToolParam` parameters give the flat object the umbrella spec describes. Recorded as a deviation in the proposal.

### D-F9. Test harness and identity proof
```
test: functional.support.McpFunctionalTestSupport     McpSyncClient over HttpClientStreamableHttpTransport.builder(baseUrl).endpoint(mcpEndpoint).httpRequestCustomizer(headers); callTool(name, args), readJson(result), rawPost(headers, body) for 401/404 assertions against the configured MCP endpoint
test: functional.support.StaticJwtTestConfiguration   @TestConfiguration with @Bean @Primary TokenDecoderFactory staticTokenDecoderFactory() — a DIFFERENT bean name from SecurityConfiguration's `tokenDecoderFactory` (which stays in the context, unused). SecurityConfiguration#securityFilterChain injects TokenDecoderFactory as a method parameter resolved by type, so with two candidates @Primary deterministically wins that by-type resolution. Its JwtDecoder maps "alice-token" -> Jwt{iss=https://issuer.example.com, sub=alice, aud=[test-audience], roles=[admin]} and throws BadJwtException otherwise.
test: functional.support.CallerIdentityProbeTools     @TestConfiguration (not @Component, so ordinary component scanning never picks it up) carrying @McpTool(name="probe_caller_identity") directly on itself -> {threadName, authenticationPresent, createdBy (AuthorResolver.getCreatedBy(callerContext.jwt())), bearerTokenPresent}. A @Configuration-class bean is a bean like any other, so Spring AI's ServerAnnotatedMethodBeanPostProcessor (a plain BeanPostProcessor, runs for every bean regardless of stereotype) discovers the @McpTool method on it once imported.
test: functional.tests.McpServerFoundationFunctionalTests   none mode: initialize + instructions; probe -> createdBy=anonymous, authenticationPresent=false; tools/list contract; list/get deployments; NOT_FOUND; UPSTREAM_TIMEOUT (DialCoreClientException 504 and ResourceAccessException); INTERNAL_ERROR sanitisation; invalid filter value -> VALIDATION_ERROR envelope
test: functional.tests.McpServerSecurityFunctionalTests     ONE nested class carrying oidc + api-key properties (pattern of ApiKeyAuthenticationTests), @MockitoBean CoreApiKeyIntrospector: raw POST without creds -> 401; malformed bearer -> 401; alice-token -> probe createdBy=alice, bearerTokenPresent=true; Api-Key valid-key -> createdBy=anonymous, bearerTokenPresent=false
test: functional.tests.McpServerDisabledFunctionalTests     enabled=false: none mode -> 404; oidc + static JWT -> 404 with alice-token, 401 without
test: (review fixes) McpServerFoundationFunctionalTests gained InitializeResult capabilities()/serverInfo() assertions and an application-deployment `interfaces` assertion in the unfiltered list_deployments listing; StaticJwtTestConfiguration/McpServerSecurityFunctionalTests gained a bob-token (role `guest`, outside allowedRoles) raw POST initialize -> 403 Forbidden assertion; McpToolDescriptionsTest and DeploymentMcpDtoJsonTest gained a LIST_DEPLOYMENTS wording assertion and a features-null-omission assertion respectively.
```
Nested classes in `PostgresFunctionalTests`: `McpServerFoundationTests`, `McpServerSecurityTests`, `McpServerDisabledTests`, `McpServerDisabledOidcTests`. The probe tool is test-scope only and prefixed `probe_`; the contract test compares the production tool set after filtering `probe_*`.

**`@Import` placement (verified empirically).** `@Import(CallerIdentityProbeTools.class)` / `@Import(StaticJwtTestConfiguration.class)` are declared on the *concrete nested class registrations* in `PostgresFunctionalTests` (e.g. `@Nested @Import(CallerIdentityProbeTools.class) class McpServerFoundationTests extends McpServerFoundationFunctionalTests {}`), not on the abstract test base classes. Putting `@Import` directly on the abstract base broke `@AutoConfigureTestRestTemplate` — the nested class's `TestRestTemplate` field failed to autowire (`NoSuchBeanDefinitionException`) — matching the codebase's existing convention of putting per-scenario `@TestPropertySource`/`@Import` on the nested registration (e.g. `ApiKeyAuthenticationTests`) rather than the abstract base.

**Static JWT override mechanism (verified empirically; two attempts).** A plain same-named `@Bean tokenDecoderFactory()` on `StaticJwtTestConfiguration` did **not** override `SecurityConfiguration`'s bean of the same name, despite `spring.main.allow-bean-definition-overriding=true`: `@Import` on a test class adds the class as an additional component source, and its `@Bean` methods are expanded by `ConfigurationClassPostProcessor` in the same pass that expands `SecurityConfiguration`'s — empirically `SecurityConfiguration`'s definition is expanded last and wins the same-name overwrite (verified via a debug probe: the live bean stayed `TokenDecoderFactoryImpl`, the real Nimbus-JWKS-backed one, causing "alice-token" to be rejected as a malformed JWT with a clean 401). The fix that shipped: `@Bean @Primary TokenDecoderFactory staticTokenDecoderFactory()` under a *different* bean name — `@Primary` wins the by-type parameter resolution in `securityFilterChain(..., TokenDecoderFactory tokenDecoderFactory, ...)` deterministically, regardless of scan/import order, while the production bean stays in the context (harmless, unused). Verified via the same debug probe that the live bean is now the static one, and the `alice` functional assertion passes.
*Alternative*: `@MockitoBean TokenDecoderFactory`. Rejected: `securityFilterChain` calls `createJwtDecoder()` while the context is refreshing, before any test method can stub the mock.

**Group 4 deviations (verified empirically).** The disabled-server oidc scenario shipped as a second top-level file, `functional.tests.McpServerDisabledOidcFunctionalTests`, alongside `McpServerDisabledFunctionalTests` for none mode, mirroring the existing `NoSecurityStartupSmokeTest`/`OidcSecurityStartupSmokeTest` split, rather than as a second nested class inside one file; `WebMvcStreamableServerTransportProvider.handlePost` (confirmed by reading its decompiled source) returns `HttpStatus.NOT_FOUND` for both an unknown `Mcp-Session-Id` on a `tools/call` and any POST once `spring.ai.mcp.server.enabled=false` removes the router function, so both the group-4 "unknown session" and the group-1/4 "disabled server" assertions use `404`; and since neither `McpSyncClient` nor `HttpClientStreamableHttpTransport` (SDK 2.0.0) expose a session-id accessor, the "different `Mcp-Session-Id` per client" assertion (task 4.4) uses two raw `initialize` POSTs and compares the `Mcp-Session-Id` response header instead of a client-side getter.

### D-F10. Conventions test
`McpToolConventionTest` (ArchUnit, test scope, created in group 3 once `DeploymentTools` exists, so the rule is never checked against an empty set): every `@McpTool` method in `com.epam.aidial.evaluation.mcp.tools..` returns `McpSchema.CallToolResult`; every parameter carries `@McpToolParam` with a non-blank description (an `McpTransportContext` parameter is allowed only if D-F2c is adopted); parameter types are `String`, `Boolean`, `Integer`, `Long` or records in `mcp.model` (no enums, per D-F8); tool names are `snake_case`.

### D-F11. Documentation placement
- `docs/configuration.md`: subsection `2.6 MCP Server (inbound)` under "Spring Framework Configuration" (root `spring.*`; distinct from `5.4 DIAL MCP Client`), one six-column row per property set in `application.yml` (`enabled`, `stdio`, `name`, `version`, `instructions`, `type`, `protocol`, `request-timeout`, `capabilities.*`, `streamable-http.mcp-endpoint`, `keep-alive-interval`, `disallow-delete`, `annotation-scanner.enabled`); `Applied when` = `spring.ai.mcp.server.enabled=true` except for `enabled`.
- `docs/patterns/mcp-server.md`: the `mcp` layer and why not `web.mcp`; request-thread caller model and the two library behaviours it rests on, with D-F2c as the recorded fallback; executor contract (tools never throw); error table and the binding-layer limitation; unsupported-feature guard; descriptions registry; harness usage; session limitation; version lock-step rule. Linked from `docs/patterns/README.md` and AGENTS.md.
- `openspec/config.yaml`: Architecture list appends item `9. mcp (com.epam.aidial.evaluation.mcp) — MCP server tools, MCP-owned models/mappers, tool support (executor, translator, encoding)`; Layering Principle adds the D-F4 line; Tech Stack → Spring Boot 4.1.1; Feature Surface gains the MCP server; testing infrastructure mentions `McpFunctionalTestSupport` and the static-JWT configuration.
- `openspec/specs/README.md`: `mcp-tools-deployments` under Integration with a sentence separating MCP **server** specs from MCP **client** specs (`mcp-tool-invocation`, `toolset-listing`).

## Risks / Trade-offs

- [Spring AI stops setting `immediateExecution(true)` or the transport stops blocking on the request thread] → the identity probe test fails immediately on upgrade; D-F2c is the prepared fix. The pattern doc names both dependencies.
- [Binding-layer errors leak plain text] → all parameters are strings/booleans/numbers/records, so only JSON type mismatches hit that path; documented in the umbrella spec by task 5.5.
- [Two BOMs managing the MCP SDK drift apart] → `mcp_sdk_version` comment + dependency-report check in tasks.
- [Static JWT test config leaks into other `oidc` tests] → `@Import`ed only by the MCP security nested classes.
- [Operator sets `protocol=STATELESS`] → no guard needed: the D-F3 matcher already authenticates the single Streamable HTTP endpoint (reused by `STATELESS`) unconditionally. The deprecated `SSE` protocol is out of scope and unsupported by the matcher.
- [Origin validation stays `NOOP`] → endpoint requires bearer/API-key authentication that browsers cannot attach cross-site; revisit if a browser-based MCP client is ever supported.

## Migration Plan

1. Merge; MCP enabled by default, read-only tools only. Operators who do not want the endpoint set `SPRING_AI_MCP_SERVER_ENABLED=false`.
2. Rollback = revert or disable the property; no data or schema involved.
3. Umbrella artifacts updated in the same PR (D3 confirmation with its two dependencies, D2 note on flat parameters, spec note on binding-layer errors, task 2.1 wording, statuses of the requirements this child satisfies).

## Open Questions

- Whether to enable `DefaultServerTransportSecurityValidator` with `allowedHosts` for local-development setups; deferrable, and only relevant if D-F2c makes the transport bean ours.
