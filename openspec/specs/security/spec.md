# Security

## Purpose
This spec describes authentication/authorization behavior for the REST API.

Status: **Implemented** (OIDC/JWT modes, DIAL API-Key authentication), **Planned** (fine-grained permissions per resource).

## Key Terms
- **Security mode**: operational mode controlling whether auth is enforced.
- **Provider**: a JWT issuer configuration (multi-issuer).
- **DIAL API-Key authentication**: an alternative to OIDC/JWT bearer tokens where a caller
  authenticates via an `Api-Key` header, validated by delegating to DIAL Core's
  `GET /v1/user/info`, active only in `oidc` security mode.

## Requirements

### Requirement: Support OIDC/JWT authentication
The service SHALL support OIDC/JWT authentication in `oidc` security mode.
Status: **Implemented**

#### Scenario: OIDC mode enabled
- **WHEN** `config.rest.security.mode=oidc`
- **THEN** protected endpoints SHALL require a valid JWT

#### Scenario: Multiple issuers
- **WHEN** multiple providers are configured
- **THEN** system SHALL accept JWTs from any configured issuer and validate them using the matching JWK set

### Requirement: Allow disabling security for local/test
The service SHALL support disabling authentication/authorization via configuration for local/test usage.
Status: **Implemented**

#### Scenario: No-security mode
- **WHEN** `config.rest.security.mode=none`
- **THEN** the service SHALL allow requests without authentication (intended for local/test)

### Requirement: Role-based access control
The service SHALL support role-based access control for protected endpoints.
Status: **Implemented** (baseline), **Planned** (resource-level policy)

#### Scenario: Default allowed roles
- **WHEN** a request is authenticated
- **THEN** access decisions SHALL consider `config.rest.security.default.allowedRoles` (or provider-specific roles)

### Requirement: Support DIAL API-Key authentication
The service SHALL support authenticating requests via a `Api-Key` header,
as an alternative to OIDC/JWT bearer tokens, when
`config.rest.security.mode=oidc` and `config.rest.security.api-key.enabled=true`.
The service SHALL delegate key validation to DIAL Core's `GET /v1/user/info`
endpoint and SHALL NOT store or validate API keys locally.
Status: **Implemented**

#### Scenario: Api-Key header present and Authorization absent
- **WHEN** a request carries a non-blank `Api-Key` header and no `Authorization` header
- **THEN** the service SHALL introspect the key against DIAL Core's `GET {core-url}/v1/user/info` (header `Api-Key: <key>`) and authenticate the request based on the result

#### Scenario: Authorization header takes precedence
- **WHEN** a request carries both an `Authorization` header and an `Api-Key` header
- **THEN** the service SHALL ignore the `Api-Key` header and proceed with OIDC/JWT authentication for that request

#### Scenario: Blank Api-Key header
- **WHEN** a request has no `Authorization` header and a blank or absent `Api-Key` header
- **THEN** the service SHALL treat the request as unauthenticated and let existing authorization rules apply

#### Scenario: Feature disabled
- **WHEN** `config.rest.security.api-key.enabled=false` (the default) or `config.rest.security.mode=none`
- **THEN** the service SHALL NOT attempt Api-Key introspection, regardless of any `Api-Key` header present

### Requirement: DIAL Core introspection response handling
The service SHALL parse two DIAL Core `/v1/user/info` response shapes — a
project-key shape (`{roles, project}`) and a JWT-rooted per-request-key
shape (`{roles, userClaims}`) — and SHALL map both a non-2xx response and an
unreachable Core to a distinct, non-cached failure outcome.
Status: **Implemented**

#### Scenario: Project-key response shape
- **WHEN** DIAL Core responds `200 OK` with a body containing a non-blank `project` field
- **THEN** the service SHALL authenticate the caller using `project` as principal and `roles` as the caller's raw Core roles

#### Scenario: JWT-rooted per-request-key response shape
- **WHEN** DIAL Core responds `200 OK` with a body containing a non-empty `userClaims` object and no `project` field
- **THEN** the service SHALL authenticate the caller using the configured user-identity claim from `userClaims` as principal and `roles` as the caller's raw Core roles

#### Scenario: Malformed introspection response
- **WHEN** DIAL Core responds `200 OK` with a body containing neither a non-blank `project` field nor a non-empty `userClaims` object
- **THEN** the service SHALL reject the request with `401 Unauthorized`

#### Scenario: Core rejects the key
- **WHEN** DIAL Core responds with a non-2xx HTTP status to the introspection call
- **THEN** the service SHALL reject the request with `401 Unauthorized` and SHALL NOT cache the failure

#### Scenario: Core is unreachable
- **WHEN** the introspection call to DIAL Core fails due to a connection error or timeout
- **THEN** the service SHALL reject the request with `503 Service Unavailable` and SHALL NOT cache the failure

### Requirement: Cache successful Api-Key introspections
The service SHALL cache successful DIAL Core introspection results, keyed by
a one-way hash of the raw API key (never the plaintext key), with
configurable time-to-live and maximum size, and SHALL NOT cache failed
introspections.
Status: **Implemented**

#### Scenario: Cache hit avoids re-introspection
- **WHEN** two requests within the configured cache TTL carry the same `Api-Key` value
- **THEN** the service SHALL call DIAL Core's introspection endpoint at most once and reuse the cached result for subsequent requests

#### Scenario: Failed introspection is never cached
- **WHEN** an introspection attempt for a given `Api-Key` value fails (invalid key or Core unreachable)
- **THEN** the service SHALL re-attempt introspection against DIAL Core on the next request carrying that same key, rather than reusing a cached failure

### Requirement: Map DIAL Core roles to service authorities
The service SHALL map DIAL Core's raw role names returned by introspection
to this service's `GrantedAuthority` strings via configurable mappings, and
SHALL use an independent mapping for the project-key response shape versus
the JWT-rooted per-request-key response shape.
Status: **Implemented**

#### Scenario: Project-key roles mapped independently
- **WHEN** an introspection result is a project-key shape with raw Core roles
- **THEN** the service SHALL resolve authorities using `config.rest.security.api-key.roles-mapping` only

#### Scenario: JWT-rooted roles mapped independently
- **WHEN** an introspection result is a JWT-rooted per-request-key shape with raw Core roles
- **THEN** the service SHALL resolve authorities using `config.rest.security.api-key.default-roles-mapping` only

#### Scenario: Unmapped role name
- **WHEN** a raw Core role name has no entry in the applicable mapping
- **THEN** the service SHALL drop that role name without rejecting the request

#### Scenario: Authenticated caller with no resolved authorities
- **WHEN** an introspected caller's raw roles resolve to zero mapped authorities
- **THEN** the service SHALL log a warning identifying the principal and SHALL mark the resulting authentication as unauthenticated, so downstream authorization denies the request

### Requirement: Fail fast on invalid Api-Key configuration
The service SHALL fail application startup when Api-Key authentication is
enabled with configuration that would make every caller unauthenticatable
or unauthorizable.
Status: **Implemented**

#### Scenario: Enabled without a Core URL
- **WHEN** `config.rest.security.api-key.enabled=true` and `config.rest.security.api-key.core-url` is blank
- **THEN** the service SHALL fail to start

#### Scenario: Enabled with both role mappings empty
- **WHEN** `config.rest.security.api-key.enabled=true` and both `config.rest.security.api-key.roles-mapping` and `config.rest.security.api-key.default-roles-mapping` parse to empty maps
- **THEN** the service SHALL fail to start, since every Api-Key caller would otherwise resolve to zero authorities

#### Scenario: Enabled with invalid mapping JSON
- **WHEN** `config.rest.security.api-key.enabled=true` and either roles-mapping property is not valid JSON
- **THEN** the service SHALL fail to start, naming the offending property

## Implementation Notes
- Configuration entrypoints:
  - `com.epam.aidial.evaluation.configuration.security.SecurityConfiguration`
  - `com.epam.aidial.evaluation.configuration.security.NoSecurityConfiguration`
- Multi-issuer decoding:
  - `com.epam.aidial.evaluation.web.security.MultiIssuerJwtDecoder`
  - `com.epam.aidial.evaluation.configuration.properties.security.JwtProvidersProperties`
- DIAL API-Key authentication (wired into `SecurityConfiguration`'s `securityFilterChain`,
  `addFilterBefore(..., BearerTokenAuthenticationFilter.class)`, gated by
  `config.rest.security.api-key.enabled`):
  - `com.epam.aidial.evaluation.web.security.apikey.ApiKeyAuthenticationFilter`
  - `com.epam.aidial.evaluation.web.security.apikey.ApiKeyAuthenticationToken`
  - `com.epam.aidial.evaluation.web.security.apikey.CoreApiKeyIntrospector` (DIAL Core `GET /v1/user/info` introspection)
  - `com.epam.aidial.evaluation.web.security.apikey.ApiKeyAuthorityResolver`
  - `com.epam.aidial.evaluation.web.security.apikey.ApiKeyCache` (Caffeine, SHA-256-hashed cache key)
  - `com.epam.aidial.evaluation.configuration.properties.security.ApiKeyProperties`
  - `com.epam.aidial.evaluation.client.apikey.ApiKeyIntrospectionClientConfiguration` (dedicated `RestClient` bean)
- See `docs/configuration.md` (Security Configuration section) for property surface.

## Open Questions / TODO
- Define which endpoints are public vs protected beyond health and swagger (document exact path patterns).
- Clarify how roles are mapped (claim names, authorities conversion) and default behavior for missing roles.

