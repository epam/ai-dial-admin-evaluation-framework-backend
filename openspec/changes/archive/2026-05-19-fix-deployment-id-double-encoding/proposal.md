## Why

Try Out and evaluation runs return `404 "Application is not found"` for any DIAL Core deployment whose id contains URL-encoded characters — most visibly public Quick Apps whose display name has spaces (e.g. `applications/public/Quick App with RAG__0.0.1`). DIAL Core returns the `id` already URL-encoded (`Quick%20App%20with%20RAG__0.0.1`), but our `DialCoreUrlBuilder` concatenates that value verbatim into a path that is then passed through Spring's default `DefaultUriBuilderFactory`. In `TEMPLATE_AND_VALUES` mode the builder treats the path as a raw template and re-encodes the `%` characters, producing `%2520` on the wire. DIAL Core decodes once and looks up a resource that does not exist → 404. The bug surfaces on **every code path** that hands a pre-encoded DIAL Core deployment id to the deployment invoker: Try Out (`TryItOutService`) and live evaluation (`EvaluationWorker`). It is **not** a private-vs-public access issue (as guessed in epam/ai-dial-admin-backend#959) — it is a URL encoding contract mismatch between DIAL Core and our client.

## What Changes

- Fix `DialCoreDeploymentInvoker.invoke` / `invokeWithStreaming` so the path it sends to DIAL Core is encoded **exactly once**, regardless of whether the input deployment id arrives pre-encoded or raw.
- Adopt the existing in-repo pattern from `McpToolInvoker` (decode once, then `UriComponentsBuilder.pathSegment(...)` so each segment is encoded individually) — this is the only callable path that is already correct, so the fix unifies both clients on the same approach.
- Add a focused unit test for `DialCoreDeploymentInvoker` proving single-encoding for ids containing spaces, parentheses, and other reserved characters (e.g. `applications/public/Quick App (v2)__0.0.1`).
- Add a functional/WireMock-style assertion that the wire path captured against DIAL Core matches `applications/public/Quick%20App%20with%20RAG__0.0.1` (single `%20`, never `%2520`) for the Try Out and Run-Worker paths.

No DB schema, no public API contract, and no configuration changes. The deployment id format stored in `test_suites.deployment_ref` JSONB stays exactly as it is today.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `dial-core-client`: Add an explicit requirement that the client encodes DIAL Core deployment paths exactly once on the wire, treating ids from DIAL Core listings as already URL-encoded resource URLs. (No new endpoints — clarifies an existing contract that today is implicit and silently broken.)

## Impact

**Affected code (production):**
- `src/main/java/com/epam/aidial/evaluation/client/dialcore/DialCoreDeploymentInvoker.java` — change the `.uri(uriBuilder -> { uriBuilder.path(path); … })` call site so the path is single-encoded.
- `src/main/java/com/epam/aidial/evaluation/service/domain/DialCoreUrlBuilder.java` — may stay as-is (still returns the raw concatenated string), or change to return a structured representation if that simplifies the invoker. Decision deferred to design.md.

**Affected callers (no behavior change for them):**
- `service/domain/TryItOutService.java:310` (Try Out path — the originally reported symptom).
- `service/domain/job/EvaluationWorker.java:122` (live evaluation runs — same bug, same fix, just less visible because errors land in `TestCaseRunResult` rather than a modal).

**Affected tests:**
- New unit test class `DialCoreDeploymentInvokerEncodingTest` (or extend the existing `DialCoreDeploymentInvokerTest`) asserting single-encoding for representative id shapes.
- Existing functional tests of Try Out / Evaluation Run that mock DIAL Core SHOULD assert the captured wire path is single-encoded for an id with spaces.

**APIs/dependencies/systems:**
- No public API change (request and response bodies unchanged).
- No new dependencies — uses `org.springframework.web.util.UriComponentsBuilder` and `UriUtils` already on the classpath and already used by `McpToolInvoker`.
- No DIAL Core version requirement change. Behavior aligns with DIAL Core's existing `ApplicationRouteController` which expects single-encoded resource URLs on the wire and calls `UrlUtil.decodePath` exactly once.

**Risks:**
- If any other call site currently relied on the double-encoding bug as a workaround (e.g. by feeding a doubly-decoded id), the fix would expose that. Mitigation: the only two callers (`TryItOutService`, `EvaluationWorker`) both pass `deploymentRef.getId()` verbatim from the same JSONB blob populated by `DialCoreClient.getDeployments` — there is no known workaround in the codebase.
- DIAL Core's expected encoding contract is implicit (no spec, only code). The added scenario in `dial-core-client` spec captures it explicitly so future regressions are caught by the spec, not by user-reported 404s.

**Rollout:**
- Single PR, no migration, no flag. Verified by the new tests plus a manual repro against dev env using a public Quick App whose name contains spaces.
