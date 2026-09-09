## ADDED Requirements

### Requirement: Author attribution for created entities
The service SHALL derive the `createdBy` value stored on newly created or cloned entities (test suites, datasets) from the caller's identity. By default the value is the caller's configured JWT claim (`security.jwt.user-claim`, default `sub`); when `security.jwt.resolve-user-name` is `true` the service SHALL instead attempt to resolve a human-readable display name from DIAL Core `GET /v1/user/info`, using the caller's own bearer token, and SHALL fall back to the claim value whenever a display name cannot be obtained. Attribution failures SHALL never fail the calling request.
Status: **Implemented**

#### Scenario: No JWT present
- **WHEN** an entity is created without an authenticated JWT (security mode `none`, or an API-key caller)
- **THEN** the service SHALL store `createdBy = "anonymous"` and SHALL NOT call DIAL Core, regardless of `security.jwt.resolve-user-name`

#### Scenario: Configured claim missing from JWT
- **WHEN** an authenticated JWT does not carry the claim named by `security.jwt.user-claim`
- **THEN** the service SHALL store `createdBy = "anonymous"` and SHALL NOT call DIAL Core

#### Scenario: Display-name resolution disabled
- **WHEN** `security.jwt.resolve-user-name=false` (default) and an authenticated JWT carries the configured claim
- **THEN** the service SHALL store the raw claim value as `createdBy` and SHALL NOT call DIAL Core

#### Scenario: Display name resolved from DIAL Core
- **WHEN** `security.jwt.resolve-user-name=true`, the JWT carries the configured claim, and DIAL Core `GET /v1/user/info` (called with the caller's bearer token) responds with a non-blank string `userDisplayName`
- **THEN** the service SHALL store that `userDisplayName` as `createdBy`

#### Scenario: Display name missing or blank
- **WHEN** `security.jwt.resolve-user-name=true` and DIAL Core responds successfully but `userDisplayName` is absent, `null`, not a string, or blank — or the response body is empty
- **THEN** the service SHALL store the raw claim value as `createdBy` and SHALL log the fallback at debug level

#### Scenario: DIAL Core error or unreachable
- **WHEN** `security.jwt.resolve-user-name=true` and DIAL Core responds with a non-2xx status, or the connection fails or times out
- **THEN** the service SHALL store the raw claim value as `createdBy`, SHALL log a warning including the cause, and SHALL complete the create/clone request normally

#### Scenario: Stored value is a snapshot
- **WHEN** a display name has been stored as `createdBy` and the user's display name later changes in the identity provider
- **THEN** the previously stored `createdBy` SHALL remain unchanged (no synchronisation or back-fill)

## Implementation notes
- `com.epam.aidial.evaluation.service.domain.AuthorResolver#getCreatedBy(Jwt)` — resolution and fallback logic; depends on `DialCoreClient#getUserInfo()`.
- `com.epam.aidial.evaluation.configuration.properties.security.JwtSecurityProperties#resolveUserName` — bound from `security.jwt.resolve-user-name`; default in `application.yml`; documented in `docs/configuration.md` §3.3.
- Unit coverage: `AuthorResolverTest`.
