## Why

`created_by` on test suites and datasets stores the raw JWT claim value (`sub` by default), which in most IdP setups is an opaque user id that means nothing to a human reading the UI. DIAL Core's `GET /v1/user/info` now returns a `userDisplayName` field for the calling user, so the service can attribute created entities to a human-readable name at creation time without introducing its own user directory.

## What Changes

- New configuration property `security.jwt.resolve-user-name` (env `SECURITY_JWT_RESOLVE_USER_NAME`, default `false`). Documented in `docs/configuration.md` §3.3 "JWT Claim Resolution".
- `AuthorResolver.getCreatedBy(jwt)` gains an opt-in resolution step: when the flag is on and the configured claim is present, it calls DIAL Core `GET /v1/user/info` with the caller's bearer token and returns `userDisplayName`. The existing behaviour (raw claim value; `anonymous` for null JWT or missing claim) remains the fallback and is unchanged when the flag is off.
- Fallback rules when the flag is on: `userDisplayName` missing / null / non-string / blank, empty Core body, Core HTTP error (`DialCoreClientException`), or Core unreachable (`RestClientException`) → raw claim value. Failures are logged (warn with stacktrace for errors, debug for missing name) and never fail the calling request — attribution is informational, not a data-integrity concern.
- `DialCoreClient.getUserInfo()` added: fetches `/v1/user/info` via the shared `dialCoreRestClient` (which already propagates the caller's bearer token), reading the body as a `String` and parsing to `JsonNode` because Core labels this JSON body `application/octet-stream`. Reuses the client's existing retry / `DialCoreClientException` path.
- No DB schema change; `created_by` stays `VARCHAR`. No new packages. No new endpoints (a throwaway diagnostic endpoint used during exploration was removed before this change was written).

Everything above is **Implemented** in the working tree; this change documents it.

**Goals**: human-readable `createdBy` out of the box for deployments that opt in; zero behaviour change for deployments that don't.
**Non-goals**: back-filling existing rows; keeping names in sync with the IdP after creation; a per-user cache (creates/clones are infrequent; add a Caffeine cache if latency shows up); resolving `createdBy` for API-key callers (they carry no JWT and already resolve to `anonymous`).

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `security`: adds a requirement for author attribution — raw-claim resolution as the default, opt-in display-name resolution via DIAL Core with defined fallback rules.
- `dial-core-client`: adds a requirement for fetching the caller's user info from DIAL Core (`getUserInfo()`), including the octet-stream content-type quirk and error mapping.

The `test-suites`, `datasets` and `test-suite-clone` specs say `createdBy` is set "from JWT subject (or `anonymous`)". That remains the default behaviour; the display-name extension is specified once in `security` rather than repeated in every entity spec.

## Impact

- **Code**: `service.domain.AuthorResolver` (new `DialCoreClient` dependency), `client.dialcore.DialCoreClient` (`getUserInfo()`), `configuration.properties.security.JwtSecurityProperties` (`resolveUserName`), `application.yml`, `docs/configuration.md`.
- **API**: none. Response DTOs unchanged; `createdBy` may now contain a display name instead of an id.
- **Data**: with the flag on, `created_by` holds a creation-time snapshot and will mix ids (fallback) and names across rows. Filtering by `createdBy` (`filter=createdBy:eq:...`) then matches whatever was stored.
- **Security**: one extra outbound call to DIAL Core per entity create/clone, authenticated with the caller's own bearer token — no new credentials.
- **Risks**: Core latency/downtime adds up to the client's configured timeout+retry budget to create/clone requests; mitigated by graceful fallback and by the flag defaulting to `false`.
- **Rollout**: ship with flag off; enable per environment once Core exposes `userDisplayName` there.
- **Tests**: `AuthorResolverTest` (9 unit cases covering every branch), `DialCoreClientTest` (octet-stream parse, empty body, 401), existing service unit tests and `PostgresFunctionalTests$TestSuiteTests` / `$DatasetCrudTests` for context boot and default-off behaviour.
