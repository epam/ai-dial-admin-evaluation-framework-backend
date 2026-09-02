## Context

See `proposal.md` — Why. Requirements live in `specs/dial-core-client/spec.md`.

Constraints that shape the approach:

- `DeploymentController` already carries three mappings under `/api/v1/deployments`: the exact `/tools`, the capturing `/{deploymentType}/**`, and the collection root. IDs may contain slashes, so the by-ID path must stay a trailing-wildcard mapping resolved by `web.path.WildcardPathResolver` (decoded exactly once, in the web layer).
- `DialCoreClient` exposes exactly the three per-type fetches this change needs (`getModel`, `getApplication`, `getToolset`), each already wrapped in `withRetry` and each translating a non-2xx into `DialCoreClientException` carrying the upstream `HttpStatusCode`. `RETRYABLE_STATUS_CODES` excludes 404, so a miss is a single call.
- The client reads the caller's JWT from `AuthorizationTokenHolder`, a ThreadLocal. It does **not** cross a thread boundary by itself — `runner.util.TokenPropagationHelper` exists for exactly this and is the established pattern (`docs/patterns/token-propagation.md`).
- `spring.threads.virtual.enabled` defaults to true, and both existing fan-out sites (`runner.job.TestCaseRunner`, `service.domain.job.InProcessMetricEvaluationExecutor`) use `Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor())` so OpenTelemetry context survives the hop.
- Mapping upstream status → HTTP status + `ErrorCode` is owned by `runner.client.dialcore.DialCoreErrorMapper` and `web.handler.DefaultExceptionHandler`. A `DialCoreClientException` thrown from the service already produces the right response.
- No database involvement anywhere on this path.

## Goals / Non-Goals

**Goals:**

- One round-trip of latency for a type-less lookup, not three.
- A response byte-identical to the typed endpoint's for the same deployment, so callers can migrate to the type-less form without re-reading payloads.
- Exactly two exit paths (hit / unified error) so no outcome combination is left implicitly defined.
- Collapse rules unit-testable without Spring, mocks, or HTTP.

**Non-Goals:**

- Caching (positive or negative). Every lookup hits Core.
- Cancelling in-flight probes once one hits.
- Any change to the typed endpoint, the listing, `/tools`, or `DialCoreClient`'s method surface.
- Bulk lookup (`?ids=a,b,c`). Single ID only.

## Decisions

### 1. Literal `/all/**` mapping rather than a query parameter or a reserved type value

`GET /api/v1/deployments/all/**` keeps the by-ID shape symmetric with the typed endpoint: same wildcard tail, same resolver, same decode-once semantics, no new encoding rules for slash-containing IDs.

Alternatives considered:

- **`GET /api/v1/deployments?id=…`** on the collection endpoint — collides conceptually with the listing's filter params, and returning a single object from a list endpoint is a worse contract than a sibling path.
- **`/{deploymentType}/**` with a `deploymentType` value of `all`** — would require `DeploymentType` to carry a non-type member, polluting an enum that is also the `$type` discriminator and the switch subject in `getDeployment(type, id)`.
- **`/by-id/**` or `/lookup/**`** — equally viable; `all` was chosen to read as "search across all types".

`/all/**` and `/{deploymentType}/**` both match `/deployments/all/x`. Spring's `PathPattern` specificity ranks a literal segment above a captured one, so the literal wins — the same implicit guarantee the existing `/tools` mapping relies on. Because it is a framework property rather than anything in our code, it gets its own regression test, mirroring `toolsPathIsNotSwallowedByWildcardMapping`.

### 2. Probe raw DTOs in parallel, map only the winner

`getDeployment(String)` fans out the three raw client calls, then feeds the winning raw DTO into the same per-type mapping the typed `getDeployment(DeploymentType, String)` already performs — including `SchemaRouteExtractor.resolveRoutes` for an application.

The rejected shape is fanning out three calls to `getDeployment(type, id)` itself: the application branch would then fetch an application type schema on a leg that is about to be discarded, adding a fourth upstream call to most lookups. Probing raw and mapping late keeps the extra work on the winner only, and guarantees representation parity by construction rather than by duplication.

### 3. Fan-out on a per-call virtual-thread executor, token captured up-front

```
getDeployment(id):
    token = AuthorizationTokenHolder.getToken()          // request thread
    try (executor = Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor())):
        f_model   = supplyAsync(withToken(token, () -> probe(DIAL_MODEL,       id)), executor)
        f_app     = supplyAsync(withToken(token, () -> probe(DIAL_APPLICATION, id)), executor)
        f_toolset = supplyAsync(withToken(token, () -> probe(DIAL_TOOLSET,     id)), executor)
        outcomes  = join all three                        // executor.close() awaits termination
    winner = collapser.collapse(id, outcomes)             // throws unified error if no hit
    return map(winner)
```

- The token is read on the request thread **before** the fan-out; `TokenPropagationHelper.withToken` sets and clears it inside each virtual thread. Reading it inside the task would see `null`.
- `Context.taskWrapping` keeps the OTel span/baggage attached, matching the two existing fan-out sites, so DIAL Core calls stay correlated in traces.
- Three virtual threads per request, created and reaped per call, is cheaper than sizing a shared pool and cannot starve unrelated work. The upstream, not the thread count, is the bottleneck.
- `ForkJoinPool.commonPool()` (the `supplyAsync` default) is rejected: three blocking HTTP calls on the shared compute pool, sized to CPU count, is exactly what the pool is not for.
- `StructuredTaskScope` would express this better but is still a preview API in Java 25; not worth `--enable-preview` for three tasks.
- Each probe catches only `DialCoreClientException` (the client's declared failure type) and records it as an outcome. `CompletableFuture.join` wraps anything escaping a task in `CompletionException`, so the join site unwraps one level before inspecting — skipping that unwrap would degrade every upstream failure into a generic 500. An `InterruptedException` surfacing during the join restores the interrupt flag before rethrowing.

### 4. Collapse rules in an injectable component, not private service methods

New `@Component` in `service.domain` (working name `DeploymentProbeCollapser`) whose single method takes the deployment ID plus the three probe outcomes and returns the winning raw payload, or throws the unified `DialCoreClientException`. Per the layering principle, specialized decision logic belongs in a top-level injectable class rather than private methods, and this is the part with real branching — hit precedence, severity ordering, message assembly.

Its input carrier is a small internal record — one per probe, holding the `DeploymentType`, the nullable body, and the nullable `DialCoreClientException` — from which hit / miss / error is derived. Kept next to the collapser rather than in `service.domain.dto.deployment`: it is an internal orchestration carrier, never serialized.

The whole component is a pure function, so every branch in §5 and §6 is a plain unit test with no Spring context, no mocked HTTP, and no timing.

### 5. Hit precedence `dial-model > dial-application > dial-toolset`

DIAL Core IDs are expected to be globally unique across the three kinds, so a multi-hit is a Core anomaly rather than a case with a "correct" answer. Rather than fail (a 500 on data we could serve) or pick nondeterministically (a flapping API), the winner follows a fixed order — `DeploymentType`'s own declaration order, so there is one source of truth for it — and the anomaly is logged at WARN with the ID and the colliding types so it is diagnosable in ops.

### 6. No-hit ⇒ one exception, status by severity `401 > 403 > other > 404`

Ordering exists so a real failure is never disguised as absence. If the models probe 401s and the other two 404, the honest answer is "we could not determine whether this exists", which the caller reads as `UPSTREAM_AUTH_ERROR`, not `UPSTREAM_NOT_FOUND`. Same reasoning for a 5xx leg. 404 is the weakest signal precisely because it is the expected outcome on two of three legs in every successful lookup.

Consequences worth stating plainly:

- **An all-404 lookup returns 502 / `UPSTREAM_NOT_FOUND`, not 404.** This is inherited, not invented: `DialCoreErrorMapper` already maps upstream 404 → 502 / `UPSTREAM_NOT_FOUND`, and the typed endpoint has shipped that way (pinned by `DeploymentFunctionalTests.getDeploymentWhenNotFoundYields502AndUpstreamNotFoundCode`). Reusing it means zero new mapping code and identical behavior across both endpoints for a missing deployment. The alternative — a literal 404 / `NOT_FOUND`, arguably better REST for a lookup — was rejected because two sibling endpoints answering the same question differently is worse than one debatable status. Note the main spec's prose claimed 404 while the code returned 502; the delta corrects that line rather than the code.
- **A 403 on any leg surfaces as 403 / `ACCESS_DENIED`** via the mapper's existing pass-through, which discloses that *something* with that ID exists. That trade-off was already made by the typed endpoint; this change inherits rather than widens it.
- The message names every leg's outcome (`Deployment '<id>' not resolvable: models=404, applications=401, toolsets=404`) so one log line or error body explains the whole fan-out. The message is diagnostic, not a contract — the spec pins the status and error code, not the exact wording.
- A probe returning 2xx with an empty body is a **miss**, not a hit: mapping a null DTO would emit an all-null deployment object. With no errors to unify, an all-miss lookup synthesizes a 404 into the same unified path rather than adding a third exit.

### 7. Error handling, transactions, logging

- No `@Transactional` anywhere on this path — no datasource is touched. Worth stating because every sibling service in `service.domain` is transactional.
- The service throws; `DefaultExceptionHandler` renders. No `ErrorView` construction and no new `ErrorCode` members.
- Retry behavior is inherited unchanged: 404 legs are single calls, a 5xx leg burns its `dial-core.retry` budget with backoff while the others idle, so a partial outage makes the lookup as slow as the retry budget of the slowest leg (bounded by config). Suppressing retries for probes was considered and rejected — it would make the by-ID path less resilient than the typed one for no gain.
- Every `catch` logs with the exception as the last SLF4J argument (`LoggingConventionTest`-enforced). Discarded losing-leg errors are logged at DEBUG (expected 404s) or WARN (unexpected statuses on a lookup that still succeeded).

### 8. Rejected alternative: list-then-fetch

One `GET /v1/deployments` call, find the ID client-side, then one typed fetch. Two upstream calls instead of three — but it transfers the caller's entire visible catalog on every lookup, its latency grows with catalog size, and the listing is a short projection so the typed follow-up is mandatory anyway. Three small parallel probes are more predictable and bounded.

## Risks / Trade-offs

- **The "globally unique ID" premise is Core's, not ours** → If `/openai/models/{id}` also resolves application IDs, precedence stops being defensive and becomes the semantics of the endpoint. Mitigation: verify against a dev Core before implementing (task 1.1); the precedence rule plus the WARN log keeps behavior deterministic and diagnosable either way.
- **3× request amplification on DIAL Core per lookup** → Mitigation: misses are not retried, so the common case is three cheap authenticated calls; no caching is introduced, so nothing to invalidate. If amplification ever bites, negative caching is an additive follow-up that changes no contract.
- **Routing precedence rests on a framework property** → A Spring upgrade changing `PathPattern` specificity would silently route `/all/x` into the typed handler and start returning `VALIDATION_ERROR`. Mitigation: dedicated functional test asserting the type-less handler wins.
- **Concurrency correctness is invisible in a passing test** → A missing `withToken` wrap fails only when security is enabled, which functional tests disable. Mitigation: a unit test asserting all three legs observe the caller's token, extending the existing `DeploymentServiceTokenPropagationTest`.
- **Partial-outage latency** → A single 5xx leg drags the lookup out to its full retry budget even when another leg already hit. Accepted: the retry budget is configured, small, and shared with every other DIAL Core call. Returning early on first hit would fix it and is captured as an open question.
- **`all` becomes a reserved first segment** → A future deployment type literally named `all` could not be addressed by the typed endpoint. Acceptable; the same is already true of `tools`.

## Migration Plan

Additive: one new endpoint, no contract moves, no schema, no config, no flag. Rollback is a revert.

The working tree already carries an unreleased stub of this endpoint at `/any/**` with an unimplemented service method; it is renamed to `/all/**` as part of this change and has never shipped, so no deprecation or alias is needed.

## Open Questions

- Should the fan-out return as soon as the first hit lands, abandoning the other two legs (they are already dispatched; `RestClient` gives no real cancellation, so the win is latency under partial outage, not saved upstream work)? Deferrable — pure latency optimization behind an unchanged contract, and no spec scenario distinguishes it.
- Is negative caching of all-404 lookups worth adding if this endpoint turns out to be called in a hot loop? Deferrable until there is traffic data.
