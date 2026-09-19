## Why

`Api-Key` authentication (`ApiKeyAuthenticationFilter`) validates the caller's key against DIAL Core and then throws it away: nothing stores it for downstream use, so every outbound call this service makes on the caller's behalf reaches DIAL Core with no credential at all and is rejected with 401. Any REST or MCP feature that needs Core — deployment listing/detail, toolset listing, try-out, run execution, MCP tool listing/invocation, user-info — therefore works only for JWT callers, which makes `Api-Key` authentication unusable for the MCP server, whose clients authenticate with a project key rather than an interactive OIDC login. `createdBy` for those callers is additionally recorded as `anonymous`, losing attribution.

## What Changes

- The caller's credential is captured **with its kind** (bearer JWT vs. DIAL API key) for the duration of the request, instead of only a bearer token. An `Api-Key` is captured only when the `Authorization` header is blank, mirroring `ApiKeyAuthenticationFilter`'s precedence check exactly.
- Every outbound call made on behalf of the caller sends the caller's credential in its native header: `Authorization: Bearer <jwt>` for JWT callers, `Api-Key: <key>` for API-key callers. This covers the DIAL Core REST clients (deployments, toolsets, user-info), the try-out / run-execution deployment invoker, the DSL query client, and the MCP tool invoker (`tools/list` and `tools/call`).
- `createdBy` for an API-key caller becomes the introspected API-key principal (the DIAL Core project name for a project key, or the user-identity claim for a JWT-rooted per-request key) instead of `anonymous`, identically for REST and MCP. Display-name resolution (`security.jwt.resolve-user-name`) stays JWT-only.
- No change to introspection, caching, or role mapping. No new configuration properties.
- MCP tool code stops being handed "the bearer token" and is handed the caller's credential with its kind, so no path can emit `Authorization: Bearer <api-key>`.
- Not breaking: JWT callers keep byte-identical outbound requests, and `eval-cli`'s API-key interceptor logic is unchanged — two CLI files are still edited mechanically (a renamed/re-typed context field and the shared header-name constant); see design.md — D1.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities

- `security`: caller-credential capture now records the credential kind, not just a bearer token; `createdBy` attribution resolves an API-key caller to its introspected principal rather than `anonymous`.
- `dial-core-client`: the outbound propagation requirement covers both credential kinds — the caller's credential is forwarded in its native header rather than always as `Authorization: Bearer`; the async propagation helper carries the kind across thread boundaries.
- `mcp-tool-invocation`: `tools/call` and `tools/list` send the caller's credential in its native header instead of always `Authorization: Bearer {token}`.

## Impact

**Goals** — make `Api-Key` a first-class caller credential end to end, for REST and MCP alike, without touching JWT behavior.

**Non-goals** — introspection/caching/role-mapping changes; the service-account key used by `DialFileClientConfiguration` (file storage is service-owned, not caller-scoped); the `eval-cli` static-key clients; new configuration properties.

**Affected code**

- `evaluation-runner-core`: `runner/util/AuthorizationTokenHolder`, `runner/util/TokenPropagationHelper`, `runner/client/mcp/McpToolInvoker` (hardcodes `Authorization: Bearer ` + token at both transport builders), `runner/client/dialcore/DialCoreDeploymentInvoker` (via its injected `dialCoreTryOutRestClient`), `runner/job/TestCaseRunner` + `runner/job/EvaluationWorker` (worker-thread propagation and the `token` argument threaded to the MCP invoker).
- main app: `configuration/security/AuthorizationHeaderInterceptor` (+ its registration in `configuration/logging/WebMvcConfig`), `client/dialcore/DialCoreClientConfiguration#authorizationTokenInterceptor` → `callerCredentialInterceptor` (public static factory, reused by `DialCoreDeploymentInvokerConfiguration` and `client/dialadas/DialAdasClientConfiguration`), `mcp/support/McpCallerContext` (`bearerToken()` → `callerCredential()`), `service/domain/AuthorResolver` (signature unchanged, so its five call sites are untouched), `service/domain/DeploymentService`, `service/domain/TryItOutService`, `service/domain/TestSuiteRunService`, `service/domain/job/TestSuiteEvaluationJob` (dispatch chain re-typed to `CallerCredential`), plus the `"Api-Key"` literal in `client/dialcore/DialFileClientConfiguration` and `web/security/apikey/CoreApiKeyIntrospector`.
- `eval-cli`: `cli/client/target/TargetDialCoreClientConfiguration#apiKeyInterceptor` keeps its behaviour but reads the typed credential and emits `Api-Key` only for an `API_KEY` kind; `cli/service/EvaluationContextFactory` follows the renamed context field, and both CLI client configs adopt the shared header-name constant.

**Security impact** — the raw API key becomes request-scoped in-process state for as long as the bearer token already is; it MUST never be logged and MUST be cleared on request completion and after every propagated async task, exactly as the bearer token is today. The credential is forwarded only to DIAL Core / dial-adas hosts that are already the targets of caller-scoped calls.

**Risks** — (1) a credential kind that fails to propagate to a run's worker threads would silently revert to unauthenticated Core calls; covered by unit tests on the holder/helper plus the existing run functional tests. (2) `dial-adas` shares the interceptor factory and so receives `Api-Key` too; that is strictly better than today's no-credential call for those callers, and the fallback is a one-bean interceptor swap (design.md — D3).

**API / data / config impact** — none: no endpoint, DTO, DB schema (no Flyway migration), or configuration property changes. `docs/configuration.md` therefore needs no update.

**Docs** — `docs/patterns/token-propagation.md` (credential kind + capture/propagate rules), the "Known limitation (REST parity)" paragraph in `docs/patterns/mcp-server.md`, and the `AuthorResolver` inline-convention line in `AGENTS.md`.

**Test plan** — unit: holder/helper kind semantics and clearing; request interceptor capture precedence; `MockRestServiceServer`-based assertions that the Core `RestClient` emits the right header per kind and that all three client beans register the interceptor; `McpToolInvoker` header selection; `AuthorResolver` API-key principal resolution. Functional: REST (`ApiKeyAuthenticationTests`) asserts a created entity's `createdBy`; MCP (`McpServerSecurityTests`) asserts the same identity through the test-only `probe_caller_identity` tool — MCP attribution is test-verified only until a mutating MCP tool exists.

**Sequencing** — lands after `mcp-foundation` is archived; it edits `McpCallerContext`, `CallerIdentityProbeTools`, `McpServerSecurityFunctionalTests` and `docs/patterns/mcp-server.md`, which that change owns.

All requirements in this change are **Planned**.
