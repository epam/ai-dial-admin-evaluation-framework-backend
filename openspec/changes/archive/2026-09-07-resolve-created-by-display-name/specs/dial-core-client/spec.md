## ADDED Requirements

### Requirement: Fetch caller user info from DIAL Core
The DIAL Core client SHALL expose an operation that fetches `GET /v1/user/info` for the current caller, propagating the caller's bearer token, and returns the response body as a parsed JSON tree without imposing a fixed schema on it. The operation SHALL tolerate DIAL Core labelling the JSON body as `application/octet-stream`. Failures SHALL surface through the same upstream error type used by the client's other operations so callers can apply a uniform fallback.
Status: **Implemented**

#### Scenario: Successful fetch with octet-stream content type
- **WHEN** DIAL Core responds 200 to `GET /v1/user/info` with a JSON body labelled `Content-Type: application/octet-stream`
- **THEN** the client SHALL return the parsed JSON tree (e.g. `userDisplayName`, `userClaims`, `roles` are readable as fields)

#### Scenario: Empty body
- **WHEN** DIAL Core responds 2xx with an empty body
- **THEN** the client SHALL return `null` rather than throwing

#### Scenario: Non-2xx response
- **WHEN** DIAL Core responds with a non-retryable error status (e.g. 401, 403, 404)
- **THEN** the client SHALL throw `DialCoreClientException` carrying that status code

#### Scenario: Transient upstream failure
- **WHEN** DIAL Core responds with a retryable status (408, 429, 5xx)
- **THEN** the client SHALL apply the configured retry policy (`config.components.core.retry.*`) before throwing `DialCoreClientException`

#### Scenario: Unparseable body
- **WHEN** DIAL Core responds 2xx with a body that is not valid JSON
- **THEN** the client SHALL throw `DialCoreClientException` with status 502

## Implementation notes
- `com.epam.aidial.evaluation.client.dialcore.DialCoreClient#getUserInfo()` — reads the body as `String` via the shared `dialCoreRestClient` (bearer propagation via `AuthorizationTokenHolder`) and parses it with the shared `ObjectMapper`; wrapped in the client's existing `withRetry`.
- Unit coverage: `DialCoreClientTest` (`getUserInfo*` cases).
