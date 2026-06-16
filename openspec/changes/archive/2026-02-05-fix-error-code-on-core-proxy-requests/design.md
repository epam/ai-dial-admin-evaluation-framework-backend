## Context

The Evaluation Framework exposes `/api/v1/deployments` endpoints that proxy to DIAL Core (models and applications). When Core returns HTTP errors, the current implementation maps them via `DialCoreErrorMapper` and returns the same or a derived status to the client. In particular:

- **401** and **404** from Core are passed through as 401 and 404. This misleads clients: they may think they used wrong credentials or called a wrong endpoint, when the real cause is upstream (Core rejected the token or the resource doesn't exist in Core).
- Our service validates the client JWT before calling Core; a 401 from Core therefore indicates a service-to-service configuration issue, not invalid client credentials.
- A 404 from Core means "resource not found in Core," not "invalid path on our API."

**Current flow:** `DialCoreClient` → `RestClientResponseException` → `DialCoreClientException(statusCode)` → `DefaultExceptionHandler` → `DialCoreErrorMapper.toHttpStatus()` / `toDialCoreErrorCode()` → `ErrorView(status, code, message)`.

**Constraints:** Keep mapping logic in the client layer (`DialCoreErrorMapper`); web layer only maps `DialCoreErrorCode` to `ErrorCode` and builds the response. No new external dependencies.

## Goals / Non-Goals

**Goals:**

- Map DIAL Core 401 → HTTP 502 with `UPSTREAM_AUTH_ERROR` so clients do not misinterpret it as "invalid credentials."
- Map DIAL Core 404 → HTTP 502 with `UPSTREAM_NOT_FOUND` so clients do not misinterpret it as "invalid endpoint."
- Keep Core 403 as 403 (resource-level access denial remains meaningful).
- Preserve distinct error codes in the response body so clients can distinguish failure types (auth vs not-found vs generic upstream).
- Keep all mapping logic in `DialCoreErrorMapper`; web layer stays thin.

**Non-Goals:**

- Changing how Core is called (retries, timeouts, etc.).
- Adding configuration to toggle old vs new mapping.
- Changing behavior for other endpoints that do not proxy to Core.

## Decisions

### 1. Map 401 and 404 in DialCoreErrorMapper only

**Decision:** Implement the new mapping entirely in `DialCoreErrorMapper.toHttpStatus()` and `toDialCoreErrorCode()`. No changes to `DialCoreClient` or exception structure.

**Rationale:** The mapper is already the single place that defines "upstream status → client status/code." Centralizing here keeps the contract clear and avoids scattering status logic in the handler.

**Alternatives considered:** Mapping in `DefaultExceptionHandler` only — rejected because it would duplicate status/code rules and blur the boundary (client layer should own "what does upstream status mean").

### 2. New error codes: UPSTREAM_AUTH_ERROR and UPSTREAM_NOT_FOUND

**Decision:** Add `UPSTREAM_AUTH_ERROR` and `UPSTREAM_NOT_FOUND` to both `DialCoreErrorCode` (client) and `ErrorCode` (web). Handler maps `DialCoreErrorCode` → `ErrorCode` in the existing switch.

**Rationale:** Clients need a stable, machine-readable way to tell "upstream auth problem" vs "upstream resource missing" vs generic `UPSTREAM_ERROR`. Reusing `NOT_FOUND` or `AUTHENTICATION_REQUIRED` would preserve the old semantics and confuse clients who already rely on status 404/401.

**Alternatives considered:** Reuse existing codes with 502 only — rejected because it loses useful semantics. Single new code (e.g. `UPSTREAM_CLIENT_ERROR`) — rejected because it does not distinguish auth vs not-found for client handling.

### 3. Error message content

**Decision:** Leave the error message as-is (exception message from `DialCoreClientException`, which comes from RestClient). Do not add a prefix or override. Rely on HTTP status and the `errorCode` field in the response body to convey that the failure is upstream.

**Rationale:** Status (502) plus error code (`UPSTREAM_AUTH_ERROR`, `UPSTREAM_NOT_FOUND`, etc.) already make the source clear. Preserving the raw message from Core can help debugging without extra logic.

### 4. 403 remains pass-through

**Decision:** No change for Core 403: still return HTTP 403 with `ACCESS_DENIED`.

**Rationale:** Resource-level denial ("you cannot use this model in Core") is meaningful to the client and is not a gateway/config bug. Only 401 and 404 are remapped.

## Risks / Trade-offs


| Risk                                                                                  | Mitigation                                                                                                                                        |
| ------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Breaking change for clients** that rely on 401/404 status from deployment endpoints | Document in release notes and API docs; recommend checking 502 + error code (`UPSTREAM_AUTH_ERROR`, `UPSTREAM_NOT_FOUND`) instead of status only. |
| **OpenAPI / contract** still documents 401 and 404 for deployments                    | Update controller `@ApiResponse` (and any examples) to reflect 502 for upstream auth/not-found; keep 403 as documented.                           |
| **Tests** that expect 401/404 from Core stub                                          | Update functional tests and `DialCoreErrorMapperTest` to assert new status and error codes.                                                       |


## Migration Plan

- **Code change:** Deploy as a single release (mapper + enums + handler + tests).
- **Rollback:** Revert the change; no data or config migration.
- **Client impact:** Clients that only check HTTP status for 401/404 on deployment calls will see 502 instead; they should switch to using the `errorCode` field in the JSON body. No backward-compatible way to keep old status without keeping the misleading behavior.

## Open Questions

_None._

