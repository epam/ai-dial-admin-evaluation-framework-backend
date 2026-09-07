## Context

See proposal.md — Why. Relevant current state:

- `service.domain.AuthorResolver` is the single component that turns a `Jwt` into the `createdBy` string; five call sites (suite create/clone, dataset create/clone paths) all go through it on the request thread.
- `client.dialcore.DialCoreClientConfiguration` already provides an unconditional `dialCoreRestClient` whose interceptor attaches the caller's bearer token from `AuthorizationTokenHolder` (populated per request by `AuthorizationHeaderInterceptor`).
- A second, property-guarded `apiKeyIntrospectionRestClient` (`config.rest.security.api-key.enabled=true`) already calls `/v1/user/info`, but with an `Api-Key` header and only when API-key auth is on. It widens its JSON converter to accept `application/octet-stream` because Core mislabels that endpoint's body.
- Layering: `service.domain` may depend on `client.*` (precedent: `DeploymentService → DialCoreClient`), so no new layer edges are needed.

## Goals / Non-Goals

**Goals:**
- Add display-name resolution with zero behaviour change when the flag is off.
- Keep all fallback logic in one place (`AuthorResolver`) so entity services stay untouched.
- Reuse the existing Core client, timeouts, retry and error type rather than adding a parallel HTTP path.

**Non-Goals:**
- Caching, back-fill, or later synchronisation of stored names (see proposal Non-goals).
- Changing what API-key callers resolve to (`anonymous`, unchanged).
- Making `/v1/user/info` available to controllers as an endpoint of this service.

## Decisions

1. **Reuse `dialCoreRestClient`, not the api-key introspection client.**
   The api-key client is `@ConditionalOnProperty`-guarded and authenticates with an `Api-Key` header, which is the wrong credential here. The shared client is always present and already forwards the caller's bearer token, so `getUserInfo()` needs no new bean, property, or base URL. *Alternative rejected:* extracting the api-key client out of its guard — more churn, and it would still send the wrong header.

2. **Read the body as `String` and parse in `DialCoreClient`.**
   Core labels the JSON body `application/octet-stream`. Widening the shared client's JSON converter (as the api-key config does) would change behaviour for every other Core call; reading as `String` (the string converter accepts `*/*`) and parsing with the shared `ObjectMapper` keeps the quirk local to one method. A parse failure maps to `DialCoreClientException(502)`, consistent with the client's other deserialisation failures.

3. **Return `JsonNode`, not a typed DTO.**
   Only one field (`userDisplayName`) is consumed, the response shape is Core-owned and still evolving, and `AuthorResolver` already tolerates its absence. A DTO would add a class with a single used field. Revisit if a second consumer appears.

4. **Resolution lives in `AuthorResolver`; call sites unchanged.**
   `getCreatedBy(Jwt)` keeps its signature. Decision order: null JWT → `anonymous`; missing claim → `anonymous`; flag off → claim; flag on → Core lookup with fallback to claim. The `anonymous` short-circuits happen *before* any Core call so no-security mode and API-key callers never incur a round-trip.

5. **Graceful degradation, not fail-fast.**
   Per the project's exception-handling convention, fail-fast is for data integrity; attribution is informational and the fallback value (the id) is exactly what was stored before this change. Caught: `DialCoreClientException` (status errors after retry, parse errors) and `RestClientException` (connection/timeout failures, which the client's retry loop does not wrap). Both log the exception as the trailing SLF4J argument (`log.warn`); missing/blank names log at debug because they are expected in some deployments.

6. **Flag on `JwtSecurityProperties` (`security.jwt.resolve-user-name`).**
   It configures what the JWT-derived author looks like, so it sits beside `security.jwt.user-claim`. Default `false` lives in `application.yml` (no Java initializer) with env var `SECURITY_JWT_RESOLVE_USER_NAME`. *Alternative rejected:* a new `config.components.core.*` property — this is an attribution policy, not a client setting.

7. **No cache.**
   Resolution runs only on create/clone, which are rare relative to reads. A Caffeine cache keyed by claim id (pattern: `ApiKeyCache`) is a straightforward follow-up if p99 create latency regresses; adding it now would add two properties and a TTL policy nobody has asked for.

**Component flow (flag on):**
`Controller (@AuthenticationPrincipal Jwt) → Service.create/clone → AuthorResolver.getCreatedBy(jwt) → DialCoreClient.getUserInfo() → dialCoreRestClient GET /v1/user/info [Bearer from AuthorizationTokenHolder] → JsonNode → "userDisplayName" | fallback claim → Service persists created_by`.

**Data model / API contract / transactions:** unchanged. `created_by` remains `VARCHAR`; the Core call happens before the service's write transaction begins in the create paths and inside the clone flows' existing service method — either way it is a read-only outbound call and does not participate in the DB transaction.

## Risks / Trade-offs

- [Core latency or outage slows every create/clone by up to connect+read timeout × retry attempts] → flag defaults to `false`; fallback keeps the request succeeding; timeouts are the existing `config.components.core.*` values and can be tuned per environment.
- [`created_by` mixes ids and names once enabled] → documented in `docs/configuration.md`; `filter=createdBy:eq:` continues to match stored values verbatim. Accepted for now; a separate "author id + author name" column pair would be the clean fix if the UI needs both.
- [Display name changes are not reflected in existing rows] → explicitly a snapshot (spec scenario); acceptable for attribution.
- [Core removes or renames `userDisplayName`] → resolver treats absence as "no name" and falls back; no crash.

## Migration Plan

- Deploy with the flag unset (defaults to `false`): no behavioural change.
- Enable `SECURITY_JWT_RESOLVE_USER_NAME=true` per environment once that environment's DIAL Core returns `userDisplayName`.
- Rollback: set the flag back to `false`; rows already written with names stay as they are.
