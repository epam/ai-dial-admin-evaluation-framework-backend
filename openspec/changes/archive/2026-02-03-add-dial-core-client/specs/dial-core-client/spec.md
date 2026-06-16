## ADDED Requirements

### Requirement: List all deployments

The system SHALL provide an endpoint to list all available deployments (models and applications) from DIAL Core. The system SHALL call both `/openai/models` and `/openai/applications` DIAL Core endpoints, transform responses to `DeploymentInfoDto` hierarchy, merge into a single list, and return.

#### Scenario: Successful deployment listing

- **WHEN** authenticated user sends GET request to `/api/v1/deployments`
- **THEN** system calls DIAL Core `/openai/models` and `/openai/applications` with user's JWT token
- **AND** transforms responses to `DialModelInfoDto` and `DialApplicationInfoDto` respectively
- **AND** returns merged list with HTTP 200

#### Scenario: Deployment listing without authentication

- **WHEN** unauthenticated user sends GET request to `/api/v1/deployments`
- **THEN** system returns HTTP 401 Unauthorized

#### Scenario: One DIAL Core endpoint fails

- **WHEN** authenticated user sends GET request to `/api/v1/deployments`
- **AND** one of the DIAL Core endpoints returns an error
- **THEN** system returns appropriate error status (does not return partial results)

---

### Requirement: Get deployment by type and ID

The system SHALL provide an endpoint to get a single deployment by type and ID. The `deploymentType` path parameter determines which DIAL Core endpoint to call (`/openai/models/{id}` for `dial-model`, `/openai/applications/{id}` for `dial-application`). Path values use kebab-case (hyphens preferred over underscores in URLs).

#### Scenario: Successful model retrieval

- **WHEN** authenticated user sends GET request to `/api/v1/deployments/dial-model/{id}`
- **THEN** system calls DIAL Core `/openai/models/{id}` with user's JWT token
- **AND** transforms response to `DialModelInfoDto`
- **AND** returns with HTTP 200

#### Scenario: Successful application retrieval

- **WHEN** authenticated user sends GET request to `/api/v1/deployments/dial-application/{id}`
- **THEN** system calls DIAL Core `/openai/applications/{id}` with user's JWT token
- **AND** transforms response to `DialApplicationInfoDto`
- **AND** returns with HTTP 200

#### Scenario: Invalid deployment type

- **WHEN** authenticated user sends GET request to `/api/v1/deployments/invalid-type/{id}`
- **THEN** system returns HTTP 400 Bad Request with error message listing valid types

#### Scenario: Deployment not found

- **WHEN** authenticated user sends GET request to `/api/v1/deployments/dial-model/{id}`
- **AND** DIAL Core returns HTTP 404
- **THEN** system returns HTTP 404 Not Found

---

### Requirement: DeploymentInfoDto hierarchy

The system SHALL define a `DeploymentInfoDto` abstract base class with polymorphic serialization using `$type` discriminator. Two concrete implementations SHALL be provided: `DialModelInfoDto` and `DialApplicationInfoDto`. Type discriminator values SHALL use kebab-case naming (consistent with URL paths).

#### Scenario: Model serialized with correct discriminator

- **WHEN** system returns a `DialModelInfoDto`
- **THEN** JSON contains `"$type": "dial-model"`
- **AND** contains model-specific fields: `capabilities`, `limits`, `pricing` (all nullable)

#### Scenario: Application serialized with correct discriminator

- **WHEN** system returns a `DialApplicationInfoDto`
- **THEN** JSON contains `"$type": "dial-application"`
- **AND** contains application-specific fields: `applicationTypeSchemaId`, `applicationProperties`, `routes` (all nullable)

#### Scenario: Common fields present

- **WHEN** system returns any `DeploymentInfoDto`
- **THEN** JSON contains required fields: `deploymentId`, `displayName`, `createdAt`, `updatedAt`
- **AND** nullable fields present if available: `version`, `description`, `owner`, `descriptionKeywords`, `inputAttachmentTypes`

---

### Requirement: Typed routes in DialApplicationInfoDto

The system SHALL represent `routes` in `DialApplicationInfoDto` as `Map<String, ApplicationRouteDto>` with fully typed nested DTOs.

#### Scenario: Routes serialized with full structure

- **WHEN** system returns a `DialApplicationInfoDto` with routes
- **THEN** each route contains typed fields: `name`, `paths`, `methods`, `upstreams`, `maxRetryAttempts`, `order`, `permissions`
- **AND** `upstreams` is a list of `RouteUpstreamDto` with: `endpoint`, `extraData`, `weight`, `tier`
- **AND** `attachmentPaths` contains `requestBody` and `responseBody` lists
- **AND** `response` (if present) contains `status` and `body`

#### Scenario: Route example structure

- **WHEN** system returns application with route "v1"
- **THEN** route structure matches:
```json
{
  "name": "v1",
  "userRoles": null,
  "response": null,
  "rewritePath": true,
  "paths": ["/v1/.*"],
  "methods": ["DELETE", "GET"],
  "upstreams": [{
    "endpoint": "http://my-endpoint.svc.cluster.local",
    "extraData": null,
    "weight": 1,
    "tier": 0
  }],
  "maxRetryAttempts": 1,
  "order": 2147483647,
  "permissions": [],
  "attachmentPaths": {
    "requestBody": [],
    "responseBody": []
  }
}
```

---

### Requirement: Token propagation

The system SHALL propagate the user's JWT token from incoming requests to DIAL Core. The token MUST be extracted from the `Authorization: Bearer` header and forwarded to DIAL Core in the same format. This ensures DIAL Core filters deployments based on the user's access rights - each user sees only deployments they are authorized to access.

#### Scenario: Token is propagated to DIAL Core

- **WHEN** user sends request with `Authorization: Bearer <token>` header
- **THEN** system forwards the same token to DIAL Core in `Authorization: Bearer <token>` header
- **AND** DIAL Core returns only deployments the user is authorized to access

#### Scenario: Missing authorization header

- **WHEN** user sends request without `Authorization` header
- **AND** security is enabled
- **THEN** system rejects request with HTTP 401 before calling DIAL Core

#### Scenario: User with limited access

- **WHEN** user with restricted permissions requests deployments
- **THEN** DIAL Core filters response to include only authorized deployments
- **AND** system returns this filtered list without modification

---

### Requirement: TokenPropagationHelper for async operations

The system SHALL provide a `TokenPropagationHelper` utility class for propagating the user's authorization token to new threads. When code executes asynchronously (e.g., via `CompletableFuture.supplyAsync()`), the new thread does not have access to the request thread's ThreadLocal token. This helper MUST be used whenever spawning async tasks that need user context.

#### Scenario: Async code needs user token

- **WHEN** service spawns async tasks (e.g., `CompletableFuture.supplyAsync()`)
- **AND** async code needs to make authenticated calls (e.g., to DIAL Core)
- **THEN** service MUST capture token before spawning: `String token = AuthorizationTokenHolder.getToken()`
- **AND** wrap async supplier with: `TokenPropagationHelper.withToken(token, () -> { ... })`

#### Scenario: Token cleanup after async execution

- **WHEN** wrapped async task completes (success or failure)
- **THEN** `TokenPropagationHelper` clears the token from the async thread's ThreadLocal
- **AND** prevents token leakage to subsequent tasks on pooled threads

#### Scenario: Usage pattern

- **WHEN** implementing parallel async operations with user context
- **THEN** follow this pattern:
```java
// Capture token in request thread before spawning async tasks
String token = AuthorizationTokenHolder.getToken();

CompletableFuture.supplyAsync(TokenPropagationHelper.withToken(token, () -> {
    // Token is available here via AuthorizationTokenHolder.getToken()
    return dialCoreClient.getModels();
}));
```

#### Scenario: Helper variants

- **WHEN** different async patterns are needed
- **THEN** `TokenPropagationHelper` provides:
  - `withToken(token, Supplier<T>)` - for `CompletableFuture.supplyAsync()`
  - `withTokenCallable(token, Callable<T>)` - for `ExecutorService.submit(Callable)`
  - `withTokenRunnable(token, Runnable)` - for `ExecutorService.submit(Runnable)`

---

### Requirement: Retry on transient failures

The system SHALL retry requests to DIAL Core when transient failures occur. Retryable failures include HTTP status codes 408 (Request Timeout), 429 (Too Many Requests), 500, 502, 503, and 504. The system SHALL use exponential backoff between retries.

#### Scenario: Successful retry after transient failure

- **WHEN** request to DIAL Core fails with HTTP 503
- **AND** retry attempt succeeds
- **THEN** system returns successful response to client

#### Scenario: All retries exhausted

- **WHEN** request to DIAL Core fails with HTTP 503
- **AND** all retry attempts (default: 3) fail
- **THEN** system returns HTTP 502 Bad Gateway with error details

#### Scenario: Non-retryable error

- **WHEN** request to DIAL Core fails with HTTP 401 or 403
- **THEN** system does NOT retry and returns the error immediately

---

### Requirement: Error mapping

The system SHALL map DIAL Core errors to appropriate HTTP responses for clients.

#### Scenario: DIAL Core authentication error

- **WHEN** DIAL Core returns HTTP 401 or 403
- **THEN** system returns the same status code to client

#### Scenario: DIAL Core not found error

- **WHEN** DIAL Core returns HTTP 404
- **THEN** system returns HTTP 404 to client

#### Scenario: DIAL Core client error

- **WHEN** DIAL Core returns HTTP 4xx (other than 401, 403, 404)
- **THEN** system returns HTTP 400 Bad Request with error details

#### Scenario: DIAL Core server error

- **WHEN** DIAL Core returns HTTP 5xx (other than 504) after all retries
- **THEN** system returns HTTP 502 Bad Gateway with error code `UPSTREAM_ERROR` and error details

#### Scenario: DIAL Core timeout or 504

- **WHEN** DIAL Core returns HTTP 504 or connection/read to DIAL Core times out
- **THEN** system returns HTTP 504 Gateway Timeout with error code `UPSTREAM_TIMEOUT`

#### Scenario: Upstream error codes clarify failure source

- **WHEN** system returns an error due to DIAL Core (upstream) failure
- **THEN** response uses `UPSTREAM_ERROR` (502) or `UPSTREAM_TIMEOUT` (504) so clients understand the failure is on the upstream side, not this service

---

### Requirement: Configuration

The system SHALL support configuration of DIAL Core client settings via application properties.

#### Scenario: Configure base URL

- **WHEN** `dial.components.core.base-url` is set
- **THEN** system uses configured URL for DIAL Core requests

#### Scenario: Configure timeouts

- **WHEN** `dial.components.core.connect-timeout-ms` and `dial.components.core.read-timeout-ms` are set
- **THEN** system uses configured timeout values

#### Scenario: Configure retry settings

- **WHEN** retry properties are configured (`max-attempts`, `delay-ms`, `multiplier`)
- **THEN** system uses configured retry behavior

#### Scenario: Default configuration

- **WHEN** no explicit configuration is provided
- **THEN** system uses sensible defaults (base-url: localhost:8080, connect-timeout: 5s, read-timeout: 30s, max-attempts: 3)

---

### Requirement: OpenAPI documentation

The system SHALL expose OpenAPI documentation for the deployment endpoints, including polymorphic response schemas.

#### Scenario: Swagger UI shows endpoints

- **WHEN** user navigates to Swagger UI
- **THEN** deployment endpoints are visible with descriptions and response examples

#### Scenario: Response schema shows polymorphic types

- **WHEN** user views endpoint documentation
- **THEN** response schema shows `DeploymentInfoDto` with `$type` discriminator and both subtypes documented

---

### Requirement: Response field mapping

The system SHALL map DIAL Core response fields to `DeploymentInfoDto` fields as follows:

| DIAL Core Field | DeploymentInfoDto Field | Nullable |
|-----------------|-------------------------|----------|
| `id` | `deploymentId` | No |
| `display_name` | `displayName` | No |
| `display_version` | `version` | Yes |
| `description` | `description` | Yes |
| `owner` | `owner` | Yes |
| `created_at` | `createdAt` | No |
| `updated_at` | `updatedAt` | No |
| `description_keywords` | `descriptionKeywords` | Yes |
| `input_attachment_types` | `inputAttachmentTypes` | Yes |

#### Scenario: Fields correctly mapped

- **WHEN** DIAL Core returns model/application with fields
- **THEN** system maps to corresponding `DeploymentInfoDto` fields using camelCase naming

#### Scenario: Nullable field absent in response

- **WHEN** DIAL Core response does not include optional field (e.g., `description`)
- **THEN** corresponding field is null in response (not empty string or empty array)

---

### Requirement: DeploymentType enum

The system SHALL define a `DeploymentType` enum for valid deployment types used in path parameters and JSON discriminators.

#### Scenario: Valid deployment types

- **WHEN** system validates deployment type
- **THEN** accepted values are: `dial-model`, `dial-application` (kebab-case)

#### Scenario: Enum serialization

- **WHEN** `DeploymentType` is serialized to JSON or used in URL
- **THEN** value is kebab-case (e.g., `"dial-model"`)

#### Scenario: Consistency between URL and JSON

- **WHEN** client uses deployment type in URL path
- **AND** receives response with `$type` discriminator
- **THEN** both values use the same format (kebab-case)
