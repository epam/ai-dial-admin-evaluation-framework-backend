## Context

This service authenticates every `/api/v1/**` request via OIDC/JWT today
(`SecurityConfiguration`, `MultiIssuerJwtDecoder`,
`JwtAuthenticationConverterFactory`; role authorities are raw
`GrantedAuthority` strings pulled straight from configurable JWT claim
paths and filtered against per-issuer `allowedRoles` — there is no fixed
internal role enum). The sibling service
`ai-dial-admin-deployment-manager-backend` already solved the "authenticate
with a DIAL API key" problem under
`com.epam.aidial.deployment.manager.web.security.apikey`: a filter that
delegates validation to DIAL Core's `GET /v1/user/info`, with no local key
storage or JWT decoding. This design ports that mechanism into this
service, adapted to its own conventions — most importantly this repo has no
`commons-codec` dependency and no fixed role enum, and it already has a
reusable `ErrorView`/`ErrorCode` error contract and `RestClient`-based DIAL
Core client pattern (`DialCoreClientConfiguration`) to mirror instead of
`RestTemplate`.

## Goals / Non-Goals

**Goals:**
- Let a caller holding a DIAL API key (a DIAL Core project key, or a
  JWT-rooted per-request key) authenticate to `/api/v1/**` without a JWT.
- Delegate all key validation to DIAL Core (`GET /v1/user/info`) — no local
  key storage, no local key format assumptions.
- Cache successful introspections (never failures) to bound the added
  latency/load on DIAL Core.
- Map DIAL Core's returned roles to this service's authority strings via
  configuration, independently for the two response shapes Core can return.
- Keep the feature fully opt-in and isolated to `config.rest.security.mode=oidc`;
  `mode=none` is unaffected.

**Non-Goals:**
- No changes to OIDC/JWT authentication behavior for existing callers.
- No local persistence of API keys or introspection results beyond the
  in-memory cache.
- No new dependency: cache-key hashing uses JDK `MessageDigest`/`HexFormat`,
  not `commons-codec` (absent from this repo, and Apache Commons Codec adds
  nothing this in-process cache-key hash actually needs).
- No email/identity-detail extraction beyond a single principal claim —
  this repo's `AuthorResolver` model doesn't carry an email concept today,
  so one isn't invented for this feature.

## Decisions

**HTTP client: `RestClient`, not `RestTemplate`.**
The reference implementation uses `RestTemplateBuilder`. This repo's own
DIAL Core client (`client.dialcore.DialCoreClientConfiguration`) already
establishes the `RestClient` + `SimpleClientHttpRequestFactory` +
`RestClient.builder().baseUrl(...).requestFactory(...).requestInterceptor(...)`
pattern for calling DIAL Core. A new `client.apikey.ApiKeyIntrospectionClientConfiguration`
mirrors that exact skeleton (own bean, `"apiKeyIntrospectionRestClient"`)
rather than introducing a second HTTP client abstraction into the codebase.
Kept in its own `client.apikey` package rather than folded into
`client.dialcore`, since it's an inbound-auth concern (validating a caller's
credentials) rather than an outbound business call, even though it happens
to hit the same DIAL Core host.

**Role model: raw authority strings via two independent configurable
mappings, not a fixed enum.**
The reference maps Core roles to a closed `UserRole` enum
(`FULL_ADMIN`/`READ_ONLY_ADMIN`). This service has no such enum — OIDC
authorities are already just JWT-claim strings filtered against
`allowedRoles`. To stay consistent, `ApiKeyAuthorityResolver` maps Core's
raw role names to `SimpleGrantedAuthority` strings via two separately
configured JSON maps (`config.rest.security.api-key.roles-mapping` for the
project-key shape, `...default-roles-mapping` for the JWT-rooted
`userClaims` shape) — unmapped role names are dropped, matching the
existing OIDC "unmapped role → dropped, not rejected" behavior in
`SecurityConfiguration` rather than failing the request outright.

**Cache-key hashing: JDK-only (`MessageDigest` + `HexFormat`).**
No hashing utility (`commons-codec`, `MessageDigest`, `HexFormat`) is used
anywhere in this codebase today, and `commons-codec` is not a build
dependency. Since a SHA-256 hex digest is a two-line JDK operation, adding a
new dependency for it is unjustified; this keeps the cache-key derivation
dependency-free while preserving the same guarantee (plaintext API key
never used as a cache key or logged).

**Principal extraction for the JWT-rooted shape reuses `JwtSecurityProperties.getUserClaim()`.**
The reference introduces its own `default.principal-claim` property. This
service already has `security.jwt.user-claim` (default `sub`), consumed by
`AuthorResolver` for the exact same purpose (extracting an identity claim
from token-shaped data). Reusing it avoids a duplicate, drifting property
for what is conceptually the same claim lookup.

**Error responses reuse the existing `ErrorView`/`ErrorCode` contract.**
Rather than introducing a project-local error DTO (as the reference does),
`ApiKeyAuthenticationFilter` constructs the existing
`com.epam.aidial.evaluation.web.handler.ErrorView` directly (via its
`(HttpServletRequest, HttpStatus, ErrorCode, String)` constructor) and
serializes it with the injected `ObjectMapper`, since the filter runs
outside `@RestControllerAdvice` dispatch and must write the response body
itself. Uses `ErrorCode.AUTHENTICATION_REQUIRED` for `401` (invalid key) and
`ErrorCode.UPSTREAM_AUTH_ERROR` for `503` (Core unreachable) — both already
exist in the enum, so every error response an existing client sees stays in
one shape.

**Filter registration: `addFilterBefore(BearerTokenAuthenticationFilter.class)`,
gated by `@ConditionalOnBean` + a disabled `FilterRegistrationBean`.**
Positioned before Spring's Bearer-token filter so an `Authorization` header
still wins if present (the filter's own header-precedence check is a second
line of defense). The filter bean only exists when
`config.rest.security.api-key.enabled=true`
(`@ConditionalOnProperty` on the filter and its collaborators); `SecurityConfiguration`
takes an `ObjectProvider<ApiKeyAuthenticationFilter>` so the chain still
builds when the feature is disabled. A `FilterRegistrationBean` with
`setEnabled(false)` is added alongside it to stop Spring Boot from
additionally auto-registering the `@Component`-annotated filter as a raw
servlet filter outside the Spring Security chain (the same double-registration
guard used by the reference implementation) — otherwise the filter would run
twice per request.

**Validation: hand-rolled `@PostConstruct`, not Bean Validation.**
`ApiKeyProperties.rolesMapping`/`defaultRolesMapping` are raw JSON strings
(needed for single-environment-variable configurability of a
`Map<String, List<String>>`); `@NotNull`/`@Size` can't express "valid JSON
that parses to a non-empty map, and at least one of two properties must be
non-empty after parsing." A `@PostConstruct validate()` method does this in
one place, mirroring the reference's own approach, and fails application
startup (`IllegalStateException`) when: enabled with a blank `core-url`;
either mapping string is invalid JSON; or both parsed mappings are empty.

## Risks / Trade-offs

- **[Risk]** Enabling the feature with a misconfigured `roles-mapping`
  silently authenticates callers who then get 403'd by empty authorities,
  which can look like a Core outage.
  → **Mitigation**: `@PostConstruct` fails the whole application at boot if
  both mappings are empty, and the filter logs a `WARN` (naming the
  principal and `fromProjectKey`) whenever a successfully authenticated
  caller resolves to zero authorities, so operators see it in logs rather
  than only observing 403s.
- **[Risk]** A slow or flapping DIAL Core could add latency/`503`s to every
  uncached request.
  → **Mitigation**: `request-timeout-ms` bounds the introspection call; the
  Caffeine cache (TTL + max size, both configurable) keeps steady-state load
  on Core low; an optional startup probe (`startup-probe`, default `true`)
  fails the app at boot if Core is misconfigured/unreachable, catching
  config errors before traffic arrives rather than during it.
- **[Risk]** Caching `Authentication` objects keyed by a key hash means a
  revoked-at-Core key stays valid here for up to `cache-ttl-seconds`.
  → **Mitigation**: default TTL is short (60s, matching the reference);
  this is an accepted trade-off identical to the sibling service's, not a
  new one introduced here.
- **[Trade-off]** Two independent role-mapping properties (project-key vs.
  JWT-rooted) is more configuration surface than a single mapping, but
  matches the task's explicit requirement and avoids conflating two
  different role vocabularies (DIAL Core's own project roles vs. an
  upstream OIDC provider's JWT role claims) that happen to arrive through
  the same endpoint.

## Migration Plan

Purely additive — no schema changes, no changes to existing request paths
when `config.rest.security.api-key.enabled=false` (the default). Rollout is
a configuration change only: set `enabled=true`, `core-url`, and at least
one roles-mapping in the target environment. Rollback is flipping
`enabled=false` (or unsetting it) and redeploying/restarting; no data
migration or cleanup is required in either direction.

## Open Questions

- ~~Exact `RestClient`-based test harness for `CoreApiKeyIntrospectorTest`
  (`MockRestServiceServer` bound via a package-private `RestClient`
  accessor, vs. a lightweight local HTTP stub) — to be resolved during
  `tasks.md`/implementation by checking what test infrastructure this repo
  already has for `RestClient`-based clients (if any).~~ **Resolved:**
  `CoreApiKeyIntrospectorTest` builds its own `RestClient.Builder`, binds a
  `MockRestServiceServer` to it via `MockRestServiceServer.bindTo(builder)`,
  then calls `builder.build()` to get the `RestClient` instance passed into
  `CoreApiKeyIntrospector`'s constructor — no production-code test seam
  needed.
