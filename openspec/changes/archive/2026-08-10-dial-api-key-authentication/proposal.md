## Why

Today this service only accepts OIDC/JWT bearer tokens on `/api/v1/**`
(`SecurityConfiguration`, multi-issuer `MultiIssuerJwtDecoder`). Callers that
only hold a DIAL API key — a DIAL Core project key, or a JWT-rooted
per-request key minted by DIAL Core — have no way to call this API today.
The sibling `ai-dial-admin-deployment-manager-backend` service already
supports this via a filter that delegates key validation to DIAL Core's
`GET /v1/user/info` (no local key storage, no local JWT decoding). This
change ports that mechanism into this service so DIAL-key-holding clients
(e.g. automation, the `eval-cli` tool, other DIAL apps) can authenticate
without needing a JWT.

## What Changes

- Add a new `ApiKeyAuthenticationFilter` (`OncePerRequestFilter`) that:
  - Reads the `Api-Key` request header; skips (passes through unauthenticated)
    when `Authorization` is present or `Api-Key` is blank.
  - Calls DIAL Core's `GET {core-url}/v1/user/info` with `Api-Key: <key>` to
    introspect the key. Core returns one of two shapes: a project-key shape
    (`{roles, project}`) or a JWT-rooted per-request-key shape
    (`{roles, userClaims}`).
  - Maps a non-200/unreachable Core response to `401 Unauthorized` (invalid
    key) or `503 Service Unavailable` (Core unreachable), using this
    service's existing `ErrorView`/`ErrorCode` error contract; failure
    results are never cached.
  - Caches successful introspection results (Caffeine, keyed by SHA-256 hash
    of the raw API key, JDK-only hashing — no new dependency) with
    configurable TTL and max size.
  - Maps Core's returned roles to this service's `GrantedAuthority` strings
    via a configurable mapping, kept separate for the project-key shape vs.
    the JWT-rooted shape, and populates `SecurityContextHolder` on success.
- Add `config.rest.security.api-key.*` configuration (`enabled`, `core-url`,
  `cache-ttl-seconds`, `cache-max-size`, `request-timeout-ms`,
  `roles-mapping`, `default-roles-mapping`, `user-claims-role-claim`,
  `startup-probe`), documented in `docs/configuration.md`. The feature
  fails fast at startup if enabled without a `core-url`, or without at
  least one non-empty roles mapping.
- Wire the new filter into the existing OIDC `SecurityFilterChain`
  (`config.rest.security.mode=oidc`) only, positioned
  `addFilterBefore(BearerTokenAuthenticationFilter.class)`. The `none`
  security mode is unaffected.
- New packages: `com.epam.aidial.evaluation.web.security.apikey` (filter,
  cache, authority resolver, introspector, authentication token) and
  `com.epam.aidial.evaluation.client.apikey` (dedicated `RestClient` bean
  for calling DIAL Core's `/v1/user/info`). `ApiKeyProperties` itself lives
  in `com.epam.aidial.evaluation.configuration.properties.security`, not
  `web.security.apikey` — `client.apikey.ApiKeyIntrospectionClientConfiguration`
  needs to depend on it, and `LayeredArchitectureTest` forbids non-`configuration`
  code depending on `web..` classes.
- No database schema changes.

This is additive and opt-in (`enabled: false` by default) — no breaking
changes to existing OIDC/JWT authentication behavior.

## Capabilities

### New Capabilities
(none — this extends the existing security capability rather than
introducing a standalone one)

### Modified Capabilities
- `security`: adds a new inbound authentication mode — DIAL API-Key
  authentication via DIAL Core introspection — as an alternative to OIDC/JWT
  bearer tokens on `/api/v1/**`, active only when
  `config.rest.security.mode=oidc` and `config.rest.security.api-key.enabled=true`.

## Impact

- **Code**: new `web.security.apikey` package (filter, `@ConfigurationProperties`,
  Caffeine cache, authority resolver, DIAL Core introspector, authentication
  token); new `client.apikey` package (RestClient configuration); modifies
  `SecurityConfiguration` to conditionally register the new filter and a
  `FilterRegistrationBean` that disables Spring Boot's default raw-filter
  auto-registration.
- **API**: no endpoint contract changes; adds an alternative way to
  authenticate to all existing `/api/v1/**` endpoints via the `Api-Key`
  header.
- **Config**: new `config.rest.security.api-key.*` properties (see above);
  `docs/configuration.md` must be updated in the same change.
- **Dependencies**: none added — reuses existing `spring-boot-starter-security`,
  `spring-boot-starter-security-oauth2-resource-server`, and Caffeine
  (already present in root `build.gradle`); cache-key hashing uses JDK-only
  `MessageDigest`/`HexFormat` rather than adding `commons-codec`.
- **External systems**: adds an outbound dependency on DIAL Core's
  `GET /v1/user/info` endpoint when the feature is enabled.
