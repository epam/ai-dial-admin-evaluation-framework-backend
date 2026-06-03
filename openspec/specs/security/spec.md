# Security

## Purpose
This spec describes authentication/authorization behavior for the REST API.

Status: **Implemented** (OIDC/JWT modes), **Planned** (fine-grained permissions per resource).

## Key Terms
- **Security mode**: operational mode controlling whether auth is enforced.
- **Provider**: a JWT issuer configuration (multi-issuer).

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

## Implementation Notes
- Configuration entrypoints:
  - `com.epam.aidial.evaluation.configuration.security.SecurityConfiguration`
  - `com.epam.aidial.evaluation.configuration.security.NoSecurityConfiguration`
- Multi-issuer decoding:
  - `com.epam.aidial.evaluation.web.security.MultiIssuerJwtDecoder`
  - `com.epam.aidial.evaluation.configuration.properties.security.JwtProvidersProperties`
- See `docs/configuration.md` (Security Configuration section) for property surface.

## Open Questions / TODO
- Define which endpoints are public vs protected beyond health and swagger (document exact path patterns).
- Clarify how roles are mapped (claim names, authorities conversion) and default behavior for missing roles.

