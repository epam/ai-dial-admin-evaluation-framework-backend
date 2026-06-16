## Why

When DIAL Core returns certain HTTP error codes (401, 404), the Evaluation Framework currently passes them through to the client. This is misleading because:

1. **401 from Core**: Our service already validated the client's JWT. If Core rejects it, this indicates a service-to-service configuration issue (different trusted issuers, validation rules), not invalid client credentials. The client will waste time re-authenticating when the real problem is infrastructure.

2. **404 from Core**: Client thinks they called an invalid endpoint on our service, when actually the upstream resource doesn't exist or there's a service misconfiguration.

We need to map these upstream errors to gateway error codes (5xx) to clearly indicate the failure is on the upstream side, while preserving meaningful error codes that describe what went wrong.

## What Changes

- **Map upstream 401 to 502**: DIAL Core 401 responses become HTTP 502 BAD_GATEWAY with `UPSTREAM_AUTH_ERROR` code. Signals that our service accepted the token but Core rejected it (config issue).
- **Keep upstream 403 as 403**: Pass through as-is. This is legitimate resource-level access denial ("you don't have permission to use this model in Core") which is meaningful for the client.
- **Map upstream 404 to 502**: DIAL Core 404 responses become HTTP 502 BAD_GATEWAY with `UPSTREAM_NOT_FOUND` code. Distinguishes "upstream resource not found" from "invalid endpoint".
- **Keep upstream 5xx as 502**: Already correct - clearly indicates upstream failure.
- **Add new error codes**: `UPSTREAM_AUTH_ERROR` and `UPSTREAM_NOT_FOUND` in both `DialCoreErrorCode` and `ErrorCode` enums.
- **Improve error messages**: Include context that failures originated from DIAL Core, not from invalid API usage.

### Summary of mapping changes

| Core response | Current | New | Error code |
|---------------|---------|-----|------------|
| 401 | 401 UNAUTHORIZED | **502 BAD_GATEWAY** | `UPSTREAM_AUTH_ERROR` |
| 403 | 403 FORBIDDEN | 403 FORBIDDEN (no change) | `ACCESS_DENIED` |
| 404 | 404 NOT_FOUND | **502 BAD_GATEWAY** | `UPSTREAM_NOT_FOUND` |
| other 4xx | 400 BAD_REQUEST | 400 BAD_REQUEST (no change) | `VALIDATION_ERROR` |
| 504 | 504 GATEWAY_TIMEOUT | 504 GATEWAY_TIMEOUT (no change) | `UPSTREAM_TIMEOUT` |
| other 5xx | 502 BAD_GATEWAY | 502 BAD_GATEWAY (no change) | `UPSTREAM_ERROR` |

## Capabilities

### New Capabilities

_None_ — this change modifies existing error handling behavior, no new feature capabilities are introduced.

### Modified Capabilities

- `dial-core-client`: Error mapping requirement — 401/404 from Core SHALL be mapped to 502 with `UPSTREAM_AUTH_ERROR` / `UPSTREAM_NOT_FOUND`; 403 remains pass-through.

## Impact

- **Affected code**:
  - `DialCoreErrorMapper` — change 401 → 502, 404 → 502 mappings
  - `DialCoreErrorCode` — add `UPSTREAM_AUTH_ERROR`, `UPSTREAM_NOT_FOUND` enum values
  - `ErrorCode` — add `UPSTREAM_AUTH_ERROR`, `UPSTREAM_NOT_FOUND` enum values
  - `DefaultExceptionHandler.toErrorCode()` — map the new error codes
  - `DialCoreErrorMapperTest` — update tests for new behavior
- **API behavior**:
  - `/api/v1/deployments/*` endpoints will return 502 instead of 401/404 for corresponding Core errors
  - Error response body will contain specific error codes to distinguish failure types
- **Breaking change**: **BREAKING** for clients that rely on HTTP 401 or 404 status from deployment endpoints. They should now check for 502 + specific error code (`UPSTREAM_AUTH_ERROR`, `UPSTREAM_NOT_FOUND`).
- **Dependencies**: None
