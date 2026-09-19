## Context

See `proposal.md` — Why. Current state, verified in code:

- `web/security/apikey/ApiKeyAuthenticationFilter` authenticates the `Api-Key` header via `CoreApiKeyIntrospector` and publishes an `ApiKeyAuthenticationToken(principal, authorities)` whose `getCredentials()` is `null` — the raw key is never stored. Its precedence check is `StringUtils.isNotBlank(request.getHeader(AUTHORIZATION))` (`ApiKeyAuthenticationFilter.java:47`): **any** non-blank `Authorization` header suppresses api-key authentication, not just a `Bearer` one.
- `configuration/security/AuthorizationHeaderInterceptor` (a `HandlerInterceptor` registered in `configuration/logging/WebMvcConfig` at `HIGHEST_PRECEDENCE + 1`, so it also covers the MCP streamable-HTTP router function) captures **only** `Authorization: Bearer <jwt>` into `runner/util/AuthorizationTokenHolder` (a static `ThreadLocal<String>` in `evaluation-runner-core`) and clears it in `afterCompletion`.
- `client/dialcore/DialCoreClientConfiguration#authorizationTokenInterceptor()` is a **public static** factory (the `dial-core-client` spec's note calling it package-private is stale) reused by three clients: `dialCoreRestClient`, `dialCoreTryOutRestClient` (declared in `DialCoreDeploymentInvokerConfiguration`, consumed by runner-core's `DialCoreDeploymentInvoker`) and `dialAdasRestClient` (declared in `client/dialadas/DialAdasClientConfiguration`). It sets a bearer header when the holder is non-null and nothing otherwise.
- `runner/client/mcp/McpToolInvoker` hardcodes `.header("Authorization", "Bearer " + token)` in both transport builders (`McpToolInvoker.java:89`, `:109`); its callers are `DeploymentService#listTools` (holder token), `TryItOutService` (holder token) and runner-core `EvaluationWorker` (the run's `EvaluationContext#token`).
- Run dispatch: `TestSuiteRunService.java:133` captures `AuthorizationTokenHolder.getToken()` and passes it into `dispatchEvaluation(runId, token, …)`; `EvaluationContext#token` (a `String`) is re-established per worker thread by `TestCaseRunner.java:104` via `TokenPropagationHelper.withTokenRunnable`.
- `eval-cli` already runs the same machinery with an API key: `EvaluationContextFactory.java:85` puts `TargetProperties#apiKey` into `EvaluationContext#token`, and `cli/client/target/TargetDialCoreClientConfiguration#apiKeyInterceptor()` (`:85`, registered by the two client beans at `:56` and `:73`) reads `AuthorizationTokenHolder.getToken()` and writes it as `Api-Key`.
- `client/dialcore/DialFileClientConfiguration:48` is the one client that is *not* caller-scoped: it sends the configured service-account `dial.api-key`.
- The literal `"Api-Key"` is spelled out in four places: `CoreApiKeyIntrospector.java:31` (`API_KEY_HEADER` constant, in `.web`), `DialFileClientConfiguration.java:48`, `TargetDialCoreClientConfiguration.java:87`, `SourceClientConfiguration.java:46`.

Constraints: `evaluation-runner-core` must not depend on the main app or on Spring Security; `.service` must not import `.web` (so `AuthorResolver` cannot reference `ApiKeyAuthenticationToken`, and the header-name constant cannot come from `CoreApiKeyIntrospector`); the `eval-cli` module compiles against runner-core.

## Goals / Non-Goals

**Goals**

- One request-scoped representation of "the caller's credential" that carries its kind, usable from `evaluation-runner-core` (no Spring Security on the classpath) and propagated across thread hops by the existing helper.
- Exactly one outbound header per call, chosen from the kind; no double-sending, and no path by which an API key can be emitted as `Authorization: Bearer`.
- `createdBy` parity between REST and MCP without changing controller or service signatures.

**Non-Goals**

- Persisting a credential with a run (a run keeps using the dispatching caller's in-memory credential, exactly as it does with a bearer token today).
- Reworking `DialFileClientConfiguration` / the CLI's static-key client *logic* (they keep sending their configured key; only the header-name literal is centralised).
- Any new configuration property.

## Decisions

### D1. One `ThreadLocal<CallerCredential>`; `getToken()` returns the value regardless of kind

Add to `evaluation-runner-core` `runner.util`:

- `enum CredentialKind { BEARER, API_KEY }`
- `record CallerCredential(String value, CredentialKind kind)` with factories `bearer(String)` / `apiKey(String)`
- `AuthorizationTokenHolder` keeps a **single** `ThreadLocal<CallerCredential>` with `setCredential(CallerCredential)` / `getCredential()` (nullable) / `clearToken()`. `setToken(String s)` stores `CallerCredential.bearer(s)`. There is no `getCredentialKind()` — callers that need the kind read `getCredential()`.
- `getToken()` is specified to return the **credential value regardless of kind** (`getCredential() == null ? null : getCredential().value()`). It is deliberately kind-blind so the CLI's static-key interceptor keeps working verbatim; every in-app consumer that turns the value into a header is migrated to `getCredential()` (audit below).

`CallerCredential.bearer(null/blank)` and `apiKey(null/blank)` return `null` — an absent credential is represented by `null`, never by a record with a blank value, and no exception is thrown (capture points must not fail a request over a missing header). `setCredential(null)` clears. `TokenPropagationHelper` gains `withCredential(CallerCredential, …)` for the three functional shapes; a `null` credential is skipped exactly as `withToken(null, …)` is today, and every variant clears in `finally`. A `null` `EvaluationContext#credential` therefore means "no caller credential" and every outbound call for that run omits the auth header — the same behaviour a `null` token produces today.

*Alternatives rejected.* (a) A sibling `ApiKeyHolder`: every capture point, propagation call site and interceptor would have to reconcile two ThreadLocals that can disagree ("both set" is representable but meaningless). (b) A second `ThreadLocal<CredentialKind>` beside the string: the two can drift out of sync; one record cannot. (c) Renaming the holder class: churn across three modules and their tests for no behavioral gain; the javadoc is updated instead.

**`getToken()` consumer audit** — every reader, and its fate:

| Consumer | Change |
|---|---|
| `client/dialcore/DialCoreClientConfiguration.java:37` | → `getCredential()`, kind-aware header (D3) |
| `mcp/support/McpCallerContext.java:52` (`bearerToken()`) | **replaced** by `callerCredential()` returning the nullable `CallerCredential` — see below |
| `service/domain/DeploymentService.java:153` | → `getCredential()`, passed to `McpToolInvoker#listTools` |
| `service/domain/TryItOutService.java:589` | → `getCredential()`, passed to `McpToolInvoker#callTool` |
| `service/domain/TestSuiteRunService.java:133` | → `getCredential()`, passed to `dispatchEvaluation` |
| `runner/util/TokenPropagationHelper.java:44,66,87` (`setToken`) | kept; the `withToken` variants delegate to `withCredential` with `bearer(...)` |
| `runner/job/TestCaseRunner.java:69` and `runner/job/EvaluationWorker.java:283` (`EvaluationContext#getToken()`) | → `EvaluationContext#getCredential()` (D4) |
| `eval-cli` `TargetDialCoreClientConfiguration.java:85` | **unchanged** — kind-blind `getToken()` plus an unconditional `Api-Key` header is correct for its static target key |

`McpCallerContext#bearerToken()` is the one reader that must not survive: a tool that asks for "the bearer token" and receives an API key would emit `Authorization: Bearer <api-key>`. It becomes `callerCredential()`, and the test-only `CallerIdentityProbeTools` payload reports `credentialKind` instead of `bearerTokenPresent` (with `McpCallerContextTest` and `McpServerSecurityFunctionalTests` following).

**CLI impact, precisely:** the CLI's interceptor logic and behaviour are unchanged, and no CLI change is needed to keep it working. Two CLI files are still touched by this change: `EvaluationContextFactory.java:85` because the `EvaluationContext` field is renamed and re-typed (`.token(apiKey)` → `.credential(CallerCredential.apiKey(apiKey))`), and the two CLI client configs when the `"Api-Key"` literal moves to the shared constant (S4/D7). The re-typing has a bonus effect: CLI runs that invoke MCP tools will send `Api-Key` instead of today's `Authorization: Bearer <api-key>`.

### D2. Capture in `AuthorizationHeaderInterceptor`, mirroring the filter's precedence

The existing interceptor gains the `Api-Key` branch and now implements `AsyncHandlerInterceptor`:

1. `Authorization` starts with `Bearer ` → capture the token as `BEARER`.
2. else `Authorization` is **blank** and `Api-Key` is non-blank → capture the key as `API_KEY`.
3. else → clear.

Step 2 uses `StringUtils.isBlank(authorization)`, not "has no `Bearer` prefix", so it mirrors `ApiKeyAuthenticationFilter.java:47` exactly: a request with `Authorization: Basic …` plus an `Api-Key` header captures **nothing** and forwards nothing, just as that request is not api-key-authenticated. Anything looser would let one header authenticate the caller while a different one is forwarded to Core.

Clearing happens in `afterCompletion` **and** in `afterConcurrentHandlingStarted` (`AsyncHandlerInterceptor`): an async dispatch hands the request thread back to the pool before `afterCompletion` runs, so without the second hook a credential can outlive the request on that thread. Capturing inside `ApiKeyAuthenticationFilter` instead would split the precedence rule across two classes, run only when `config.rest.security.api-key.enabled=true`, and leave clearing to a different lifecycle.

Capture is deliberately **not** gated on the api-key feature flag: like the bearer branch today, it captures whatever the caller presented and lets the upstream be the authority on validity. In `config.rest.security.mode=none` an unauthenticated caller can therefore have its `Api-Key` forwarded — the desired behaviour for a local/dev setup, and what the bearer branch already does.

### D3. One kind-aware interceptor factory for the REST clients

`DialCoreClientConfiguration#authorizationTokenInterceptor()` becomes `callerCredentialInterceptor()`: it reads `getCredential()` and sets `Authorization: Bearer`, `Api-Key`, or nothing when absent. All three consumers (`dialCoreRestClient`, `dialCoreTryOutRestClient`, `dialAdasRestClient`) inherit it with no per-client logic, which is why the `dial-core-client` delta states the rule once as a cross-cutting requirement.

`dial-adas` receives the caller's credential uniformly. Today an API-key caller reaches dial-adas with **no** credential and is rejected outright, so forwarding `Api-Key` cannot regress any working case; and keeping dial-adas bearer-only would mean inventing a second interceptor for the one client that is guaranteed to fail today. If dial-adas later turns out to reject `Api-Key`, the fix is local (a bearer-only interceptor for that one bean) and changes neither the requirement nor the task breakdown.

### D4. Thread the credential through the MCP invoker and the run context as a value object

`McpToolInvoker#callTool` / `#listTools` take `CallerCredential` in place of `String token`, and both transport builders apply the header through one **package-private** helper (`applyCredentialHeader`), so a unit test can assert header selection per kind without standing up an MCP server. Explicit parameters keep the invoker free of ThreadLocal reads, matching its current style. `EvaluationContext#token` becomes `CallerCredential credential`, so a run's worker threads re-establish both value and kind; `TestSuiteRunService` captures `getCredential()` and `dispatchEvaluation(runId, credential, …)` carries it.

### D5. Attribution fallback keyed on the `Authentication` shape

`AuthorResolver#getCreatedBy(Jwt jwt)` keeps its signature and its entire JWT path. When `jwt == null`, it inspects the current `Authentication`: if one is present, is authenticated, is not an `AnonymousAuthenticationToken`, and its principal is **not** a `Jwt`, then `createdBy` is `authentication.getName()` — the introspected api-key principal (project name, or the user-identity claim for a JWT-rooted key) — and display-name resolution is skipped. Otherwise `anonymous`, as today.

Keying on the `Authentication` shape rather than on the captured credential kind keeps attribution independent of the propagation machinery (a context populated without a header still attributes correctly) and needs no import from `.web.security.apikey`, which the layering test forbids.

*Alternative rejected:* an overload taking `Authentication`. It would force edits to two controllers (`TestSuiteController`, `DatasetController` inject `@AuthenticationPrincipal Jwt`), the five `getCreatedBy` call sites and the MCP tool surface to pass a value the resolver can read itself, with zero behavioral difference. Display-name resolution stays JWT-only because a project principal is not a user and `GET /v1/user/info` would return the project's own record.

### D6. Assert outbound headers in unit tests, identity in functional tests

`DialCoreClient` is a `@MockitoBean` in `PostgresFunctionalTests` (and in the MCP security tests), so outbound headers are invisible to functional tests. The header contract is therefore asserted with `MockRestServiceServer` bound to a `RestClient.Builder` carrying the new interceptor (precedent: `CoreApiKeyIntrospectorTest`, `DialAdasClientTest`, `ApiKeyIntrospectionClientConfigurationTest`), plus one assertion that all three production `RestClient` beans actually register it; MCP header selection is asserted on `applyCredentialHeader`. Functional tests assert only what they can see cheaply: `probe_caller_identity` reporting `createdBy = my-project` for an `Api-Key` MCP caller, and the REST equivalent.

Note (scope of verification): the MCP attribution path is **test-verified only** until a mutating MCP tool exists. The production MCP tools shipped by `mcp-foundation` are read-only deployment queries that never write `createdBy`; the only MCP consumer of `AuthorResolver` is the test-only `CallerIdentityProbeTools` probe. The REST path is verified against a real created entity.

### D7. One `Api-Key` header-name constant

The header name lives next to `CredentialKind` in runner-core `runner.util` (e.g. `CallerCredential.API_KEY_HEADER`), because runner-core is the lowest module all three consumers can see; `CoreApiKeyIntrospector.API_KEY_HEADER` in `.web` cannot be referenced from `.client`, `.service` or `eval-cli` without breaking layering. The main app (`DialFileClientConfiguration`, `CoreApiKeyIntrospector`, the new interceptor) and both CLI configs reference it instead of the literal.

## Risks / Trade-offs

- **The raw API key becomes in-process request state** → never logged (no new log statements carry it; `ApiKeyCache` keeps hashing its key), cleared in `afterCompletion`, in `afterConcurrentHandlingStarted`, and in every `TokenPropagationHelper` variant's `finally`, exactly as the bearer token is today.
- **`getToken()` stays kind-blind** → a future consumer could format an API key as a bearer header. Mitigated by the audit in D1 (every in-app header-forming consumer moves to `getCredential()`, and `bearerToken()` is removed so no API exists that *promises* a bearer), plus javadoc on `getToken()` stating it returns the value of whatever kind was captured.
- **Kind silently lost on a thread hop, reverting to unauthenticated calls** → unit tests assert kind survival through all three helper variants and through `EvaluationContext`; the existing run functional tests exercise the dispatch path.
- **`dial-adas` receives `Api-Key` without confirmed support** → strictly better than today's no-credential call for those callers; JWT callers are unaffected, and the fallback is a one-bean interceptor swap (D3).
- **A run outliving its credential** → a revoked/rotated key fails mid-run exactly as an expired bearer token does today; no new behaviour.

## Migration Plan

Pure code change: no migration, no new property, no schema change. Deploy is a normal rollout; rollback is a redeploy of the previous image, since nothing is persisted in the new shape.

**Sequencing:** this change lands **after `mcp-foundation` is archived**. It edits files that change owns — `mcp/support/McpCallerContext`, `functional/support/CallerIdentityProbeTools`, `functional/tests/McpServerSecurityFunctionalTests` and `docs/patterns/mcp-server.md` — and rebases onto them rather than racing them.
