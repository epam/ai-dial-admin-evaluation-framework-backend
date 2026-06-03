## Context

DIAL Core treats every custom application as a resource and exposes it via its `ResourceDescriptor.getUrl()` API, which URL-encodes each path segment with `UrlUtil.encodePathSegment`. So a public Quick App named `Quick App with RAG` version `0.0.1` is surfaced by DIAL Core's `GET /v1/deployments` with `id = "applications/public/Quick%20App%20with%20RAG__0.0.1"`. The space is already encoded in the value.

Our evaluation framework persists this `id` verbatim inside `test_suites.deployment_ref` JSONB (see `JsonbMapper`, `TestSuiteMapper`). When Try Out or an evaluation run executes:

1. `TryItOutService.invokeAndBuildResponse` (line 310) and `EvaluationWorker.invokeSingle` (line 122) call `DialCoreUrlBuilder.buildUrl(deploymentRef.getId(), resolvedUrl)`, which returns `/v1/deployments/applications/public/Quick%20App%20with%20RAG__0.0.1/route/chat/completions` — i.e., a path that **already contains one layer of URL encoding**.
2. `DialCoreDeploymentInvoker.invoke` / `invokeWithStreaming` pass that string into Spring's `RestClient` via `uriBuilder.path(path)` (`DialCoreDeploymentInvoker.java:48-49`). The `RestClient` here uses Spring's default `DefaultUriBuilderFactory` in `TEMPLATE_AND_VALUES` mode (no custom factory is set in `DialCoreDeploymentInvokerConfiguration`).
3. When Spring builds the final URI it treats the path as a raw template — the `%` character is itself encoded to `%25`. Result on the wire: `/v1/deployments/applications/public/Quick%2520App%2520with%2520RAG__0.0.1/route/...`.
4. DIAL Core's `ApplicationRouteController` calls `UrlUtil.decodePath` exactly once, producing `applications/public/Quick%20App%20with%20RAG__0.0.1`. Lookup against the in-memory app map keyed by the canonical URL fails depending on key shape — final symptom is `404 "Application is not found: applications/public/Quick%2520App%2520with%2520RAG__0.0.1"` (the error message uses the post-one-decode value).

The same bug fires from the live evaluation worker — it is just less visible because errors land in a stored `TestCaseRunResult` row rather than a UI modal.

The MCP path is already correct: `McpToolInvoker.buildMcpEndpoint`/`buildSseEndpoint` (`client/mcp/McpToolInvoker.java:120-144`) defensively decodes the id once and then uses `UriComponentsBuilder.pathSegment(...)` so each segment is encoded exactly once. That is the canonical pattern to mirror.

## Goals / Non-Goals

**Goals:**
- DIAL Core deployment HTTP calls send paths that are **encoded exactly once on the wire**, regardless of whether the input `deploymentRef.getId()` arrives pre-encoded (DIAL Core listing output) or contains literal special chars.
- Fix applies uniformly to **both** call sites: Try Out (`TryItOutService`) and evaluation worker (`EvaluationWorker`) — i.e., the fix lives in the shared component (`DialCoreDeploymentInvoker`), not in each caller.
- Behaviour is verified by a focused unit test that asserts the **wire path** captured against a stub `RestClient`/`HttpClient`, not just the value of intermediate strings.
- No regression in the `OPENAI_STANDARD_PATHS` branch of `DialCoreUrlBuilder` (model deployments like `gpt-4` whose ids contain no special chars must keep working unchanged).

**Non-Goals:**
- Changing the JSONB storage format of `deployment_ref` or the public REST API of EF.
- Re-encoding ids on the way **into** EF (we keep accepting DIAL Core's URL-encoded form verbatim — same as today).
- Restructuring `DialCoreUrlBuilder` to know about encoding. The natural single-responsibility split keeps the URL builder returning a logical, structured path (or the raw string it returns today), and the invoker is the one place that knows about wire-level encoding contract with DIAL Core.
- Touching `DialFileClient` (its `encodePath` already does per-segment encoding from raw input — different contract, file paths supplied by EF are not pre-encoded).
- Adding feature flags, migration scripts, or staged rollouts.

## Decisions

### D1. Single fix point: `DialCoreDeploymentInvoker`, not `DialCoreUrlBuilder`

The encoding contract is owned by the HTTP client boundary — DIAL Core API — not by the URL composition step. Pushing encoding awareness into `DialCoreUrlBuilder` would couple a path-shape utility to a Spring-encoder concern, and we would still have to trust every future caller of the invoker to feed a pre-encoded path. Putting the fix in the invoker, on the exact `.uri(...)` call, means **every** future call site is automatically safe.

**Alternatives considered:**
- *Have `DialCoreUrlBuilder` return a `URI` instead of a `String`.* Rejected: pushes Spring-specific encoding into a domain-named utility, and `URI.create` requires the path to already be valid URI text — i.e., we would still need to decide encoding somewhere upstream. Simply moves the problem.
- *Switch the `dialCoreTryOutRestClient` to a `DefaultUriBuilderFactory` configured with `EncodingMode.NONE`.* Rejected: it would silently require every caller to pre-encode correctly; very easy to misuse. Also reduces robustness against future callers that pass raw segments.

### D2. Adopt the `McpToolInvoker` pattern structurally — decode once, then `pathSegment(...)`

The MCP comparison is **structural** (decode → per-segment `pathSegment(...)` → single `encode()`), not literal API. `McpToolInvoker` builds against an explicit `mcpProxyBaseUrl` string passed to the Java MCP SDK transport; it has no Spring `RestClient` or `DefaultUriBuilderFactory` in the call chain, so it can simply call `.toUriString()` on an absolute path. Our case is different: `dialCoreTryOutRestClient` is built with `RestClient.builder().baseUrl(properties.getBaseUrl())…` (see `DialCoreDeploymentInvokerConfiguration`), so the base URL is owned by the `RestClient`'s `UriBuilderFactory`. We MUST preserve that baseUrl — passing an absolute `URI` to `RestClient.uri(URI)` would bypass the configured base and any future interceptors that depend on it.

Concretely the invoker will, for each call, use the `uri(Function<UriBuilder, URI>)` overload and inside the lambda:

1. Split the incoming `path` (e.g. `/v1/deployments/applications/public/Quick%20App%20with%20RAG__0.0.1/route/chat/completions`) into its segments.
2. `UriUtils.decode(segment, UTF_8)` each non-empty segment — turns `Quick%20App` back into `Quick App`, leaves `chat`/`completions` untouched, and is idempotent against already-raw segments (no `%` → no-op).
3. Build the encoded path string with a side `UriComponentsBuilder.newInstance().pathSegment(decodedSegments.toArray(new String[0])).build().encode().toUriString()` — Spring's `pathSegment(String...)` takes varargs, so the `List<String>` must be converted to a `String[]`. Spring encodes **once**, per-segment (so `/` characters inside a segment become `%2F`, which is what DIAL Core's regex `^/+v1/deployments/(?<id>.+)/route(?<routePath>/.+?)$` plus `UrlUtil.decodePath` expects), and sub-delims like `(` and `)` are preserved literally per RFC 3986 `pchar`.
4. Inside the `uri(uriBuilder -> …)` lambda, call `uriBuilder.replacePath(encodedPath)` and `uriBuilder.replaceQueryParams(queryParams)` (or `queryParams(queryParams)` if the builder starts empty), then `return uriBuilder.build(true)` to signal "already encoded" so the `DefaultUriBuilderFactory` does not re-encode the path. The `RestClient`'s configured `baseUrl` is preserved by the builder.

This mirrors the **structural** flow `McpToolInvoker.buildMcpEndpoint` uses today (`UriUtils.decode(...)` → `UriComponentsBuilder.pathSegment(...)` → `.build().encode()`), but uses Spring's `RestClient.uri(Function)` API rather than producing an absolute URI string, so the configured base URL is preserved.

**Alternatives considered:**
- *Just call `.encoded(true)` / `.build(true)`.* Rejected: there is no `encoded(boolean)` setter on the `UriBuilder` exposed to the `RestClient` lambda, and `UriComponents.build(true)` only declares "already-encoded" — it does not stop `DefaultUriBuilderFactory` from re-encoding when it materializes the URI in TEMPLATE_AND_VALUES mode.
- *Apply `org.springframework.web.util.UriUtils.encodePath` to a raw path.* Rejected: `encodePath` does not split on `/` per-segment, so encoded `/` inside a single segment (rare but possible if DIAL Core surfaces a name containing a literal `/`) would be normalized incorrectly. The `pathSegment(String...)` route is segment-aware and reflects DIAL Core's own `UrlUtil.encodePath` semantics.

### D3. Keep `DialCoreUrlBuilder` as-is

`DialCoreUrlBuilder.buildUrl(deploymentId, resolvedUrl)` continues to return a single concatenated path string. The new invoker logic does not need a new contract from it. This minimises blast radius:

- The OPENAI standard-paths branch (`/openai/deployments/{id}/chat/completions` etc.) keeps working — model ids do not contain special chars in practice, but even if they did, the new invoker behaviour single-encodes them correctly.
- No callers of `DialCoreUrlBuilder` outside the invoker need to change.

### D4. Query parameters and headers are unaffected

`MultiValueMap<String, String> queryParams` is consumed via `uriBuilder.queryParams(queryParams)` — Spring already single-encodes query-param values correctly in `TEMPLATE_AND_VALUES` mode (the bug specifically affects `.path()` because we're feeding it an already-encoded literal). No change needed on the query-params side.

Per D2 we stay inside the `uri(Function<UriBuilder, URI>)` lambda (to preserve the `RestClient`'s configured `baseUrl`). The path is pre-encoded via a side `UriComponentsBuilder.newInstance().pathSegment(...).build().encode().toUriString()` and applied via `uriBuilder.replacePath(encodedPath)`; query params are applied via `uriBuilder.queryParams(queryParams)` on the same lambda builder; we then return `uriBuilder.build(true)` so the `DefaultUriBuilderFactory` does not re-encode the path. This keeps encoding consistent across path and query components while leaving the configured base URL intact.

### D5. Test strategy — capture the wire path, not intermediate strings

A unit test that asserts the result of an intermediate string-building call (e.g. `assertEquals("...Quick%20App...", builder.someInternalMethod(...))`) is easy to satisfy and still leave the bug in place — the bug is in how Spring serializes the URI on the wire. The unit test will:

1. Build a `DialCoreDeploymentInvoker` against a mock `HttpClient` (the same `JdkClientHttpRequestFactory` strategy used in `DialCoreDeploymentInvokerConfiguration`) or a WireMock server.
2. Call `invoke(POST, "/v1/deployments/applications/public/Quick App with RAG__0.0.1/route/chat/completions", …)` AND `invoke(POST, "/v1/deployments/applications/public/Quick%20App%20with%20RAG__0.0.1/route/chat/completions", …)`.
3. Assert the captured request URI's raw path is exactly `/v1/deployments/applications/public/Quick%20App%20with%20RAG__0.0.1/route/chat/completions` in both cases — i.e. the function is idempotent across raw-vs-pre-encoded inputs.
4. Add a row for parentheses (`Quick App (v2)__0.0.1` → `Quick%20App%20(v2)__0.0.1`) and one model-style id (`gpt-4` — unchanged passthrough).

A second test through the Try Out functional path (existing `TryItOutFunctionalTests` or equivalent) using a WireMock stub for DIAL Core will catch any regression from the full controller path.

## Risks / Trade-offs

- **[Risk]** A caller (today or future) might pass an already-decoded path (literal spaces) by accident. → **Mitigation**: Idempotent decode-then-encode handles both shapes correctly; documented in javadoc on the invoker.
- **[Risk]** DIAL Core changes its encoding contract (e.g. expects `%2F` for the slashes inside the id). → **Mitigation**: Per-segment `pathSegment(...)` already encodes inner `/` to `%2F`. If DIAL Core ever moves to requiring slashes literal, the regex on its side already accepts `.+` greedily through slashes and `UrlUtil.decodePath` is a no-op for unencoded slashes — both shapes work today.
- **[Trade-off]** Pre-encoding the path with a side `UriComponentsBuilder` and then applying it via `uriBuilder.replacePath(...)` inside the `RestClient.uri(Function)` lambda adds a few lines per call site versus the prior one-liner. → **Mitigation**: ~5 extra lines per `invoke`/`invokeWithStreaming`; the gain is preserving the configured `baseUrl` while still getting total control of encoding (`build(true)` so `DefaultUriBuilderFactory` does not re-encode).
- **[Trade-off]** Two HTTP clients now have very similar segment-encoding code (`McpToolInvoker.buildMcpEndpoint` and the new `DialCoreDeploymentInvoker` path). → **Mitigation**: If a third caller appears, lift into a shared utility (`com.epam.aidial.evaluation.client.dialcore.PathEncoder` or in `utils`). Premature for two call sites — keep duplication for now.

**Verification note (resolved during implementation).** The initial attempt to keep encoding inside the `RestClient.uri(Function<UriBuilder, URI>)` lambda — by calling `uriBuilder.replacePath(encodedPath)` followed by `uriBuilder.build(true)` — was empirically falsified by the real-`RestClient` unit test in task 1.1: `DefaultUriBuilderFactory` in `TEMPLATE_AND_VALUES` mode re-encoded the pre-encoded `%20` bytes to `%2520`. The root cause is that `UriBuilder` does not expose a `build(boolean encoded)` overload — the `boolean` is autoboxed and silently treated as a URI template variable, while the underlying `UriComponentsBuilder` had already been put into encoded state at factory-builder construction (via `initUriComponentsBuilder`'s `result.encode()` for `TEMPLATE_AND_VALUES`), so `replacePath` could not signal "already encoded" to the final `toString()` reconstruction. The documented fallback was therefore activated: the invoker now (a) injects `DialCoreProperties` to read the configured base URL, (b) composes the absolute URI explicitly via `UriComponentsBuilder.fromUriString(baseUrl).pathSegment(decodedSegments).queryParams(queryParams).build().encode().toUri()` (single encode pass over both path and query), and (c) hands the resulting `URI` to `RestClient.uri(URI)` so the `UriBuilderFactory` is bypassed entirely and the wire bytes are preserved verbatim. The cost — reading `dial.core.base-url` inside the invoker — is small and keeps the encoding contract within the single component already responsible for the DIAL Core HTTP boundary.

## Migration Plan

Single PR, no migration steps, no data backfill, no config flag. Order:

1. Add the unit test that **fails** today (asserting single-encoded wire path for the Quick-App-with-spaces id).
2. Implement the fix in `DialCoreDeploymentInvoker`. Test goes green.
3. Add the parentheses and model-id variants to the test for coverage breadth.
4. Manual repro against the dev env: run Try Out against the existing public Quick App `5e7792d3-5947-4db0-a203-1d79d2c45003` and confirm 200.
5. Roll forward — no rollback required (the change is purely defensive on encoding; previous behaviour was strictly broken for the affected id shapes).

## Open Questions

- None blocking. The fix shape is straightforward; the design decision was choosing the call site and pattern, both of which are settled above.
