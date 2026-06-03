## ADDED Requirements

### Requirement: Deployment invocation via DialCoreDeploymentInvoker
The system SHALL provide a `DialCoreDeploymentInvoker` component in the `client.dialcore` package for invoking DIAL Core deployment endpoints. This component is separate from the existing `DialCoreClient` (which handles metadata retrieval only). The invoker SHALL have its own `RestClient` bean with a configurable read timeout (default 120s) and no retry logic.

The invoker SHALL return a `DeploymentInvocationResponse` record (defined in `client.dialcore`) containing `int statusCode` and `Object body` (nullable). This keeps the client-layer return type within the client package, preserving the layering rule that `client.*` does not depend on `service.*`. The service layer maps this to `TryItOutCoreResponseDto` when building the final response.

The invoker's `invoke()` method SHALL accept Spring/JDK types only: `HttpHeaders` for headers and `MultiValueMap<String, String>` for query params. This avoids importing `KeyValueTemplateDto` (a service-layer DTO in `service.domain.dto`) into the client package — consistent with the existing `DialCoreClient` which has zero imports from `service.*`. The service layer (`TryItOutService`) is responsible for converting `List<KeyValueTemplateDto>` from `ResolvedRequestDto` into `HttpHeaders`/`MultiValueMap<String, String>` before calling the invoker.

#### Scenario: Invoke deployment with POST
- **WHEN** service calls `DialCoreDeploymentInvoker.invoke()` with HTTP method POST, a relative path, headers (`HttpHeaders`), query params (`MultiValueMap<String, String>`), and a JSON body
- **THEN** the invoker SHALL send the request to `{coreBaseUrl}{path}` using its dedicated `RestClient`
- **AND** set `Content-Type: application/json` on the outgoing request when a body is present
- **AND** return `DeploymentInvocationResponse` with the response status code and body

**Note:** Headers use Spring `HttpHeaders` and query params use `MultiValueMap<String, String>` — both support duplicate keys natively (important for HTTP headers like `Set-Cookie`). The service layer converts `List<KeyValueTemplateDto>` from `ResolvedRequestDto` into these types before calling the invoker.

#### Scenario: Invoke deployment with GET
- **WHEN** service calls `DialCoreDeploymentInvoker.invoke()` with HTTP method GET and a relative path
- **THEN** the invoker SHALL send a GET request without a body to `{coreBaseUrl}{path}`
- **AND** return `DeploymentInvocationResponse` with the response status code and body

#### Scenario: Non-POST methods with request body
- **WHEN** service calls `DialCoreDeploymentInvoker.invoke()` with HTTP method GET (or DELETE, HEAD, OPTIONS) and a non-null `body` parameter
- **THEN** the invoker SHALL ignore the body and send the request without a body
- **AND** only include a request body for methods POST, PUT, and PATCH

#### Scenario: Authorization token propagated
- **WHEN** invoker sends a request to DIAL Core
- **THEN** the user's JWT token from `AuthorizationTokenHolder` SHALL be included as `Authorization: Bearer` header (via RestClient interceptor)

#### Scenario: Content-Type for request body
- **WHEN** invoker sends a request with a body (POST, PUT, PATCH)
- **THEN** the invoker SHALL set `Content-Type: application/json` on the outgoing request

#### Scenario: Custom headers from template
- **WHEN** invoker receives custom headers via `HttpHeaders` (e.g., resolved from template by the service layer)
- **THEN** those headers SHALL be added to the outgoing request alongside the authorization header

#### Scenario: Query parameters appended
- **WHEN** invoker receives query parameters via `MultiValueMap<String, String>`
- **THEN** those parameters SHALL be appended to the request URL

#### Scenario: DIAL Core returns error
- **WHEN** DIAL Core returns HTTP 4xx or 5xx
- **THEN** the invoker SHALL NOT throw an exception
- **AND** SHALL return the response status code and body as-is (for proxy behavior)

#### Scenario: Connection failure
- **WHEN** the connection to DIAL Core fails (refused, DNS error)
- **THEN** the invoker SHALL catch `ResourceAccessException`, inspect the cause chain, and if the root cause is NOT a `SocketTimeoutException` (e.g., `ConnectException`, `UnknownHostException`, or other I/O errors), throw `DialCoreClientException` with `HttpStatus.BAD_GATEWAY` (502)

#### Scenario: Read timeout
- **WHEN** DIAL Core does not respond within the configured read timeout
- **THEN** the invoker SHALL catch `ResourceAccessException`, inspect the cause chain, and if the root cause IS a `SocketTimeoutException`, throw `DialCoreClientException` with `HttpStatus.GATEWAY_TIMEOUT` (504)

#### Scenario: Response body parsing
- **WHEN** DIAL Core returns a response body
- **THEN** the invoker SHALL read the response body as a raw `String`
- **AND** attempt to parse it as JSON using `ObjectMapper.readValue(body, Object.class)`
- **AND** if JSON parsing succeeds, return the parsed object (Map, List, String, Number, Boolean, or null) as the `body` field
- **AND** if JSON parsing fails (e.g., HTML error page, plain text), return the raw string as the `body` field

---

### Requirement: DialCoreDeploymentInvoker configuration
The invoker SHALL use a separate configuration from the metadata client, allowing independent timeout tuning.

#### Scenario: Separate RestClient bean
- **WHEN** the application starts
- **THEN** a dedicated `RestClient` bean (e.g., `dialCoreTryOutRestClient`) SHALL be created with the try-it-out read timeout
- **AND** it SHALL share the same base URL and authorization interceptor as the metadata client

**Implementation note:** The authorization token interceptor logic (reading from `AuthorizationTokenHolder` and setting `Authorization: Bearer` header) is currently a private method in `DialCoreClientConfiguration`. To share it between both RestClient beans, extract the interceptor to a package-private static method in `DialCoreClientConfiguration` (both configuration classes are in `client.dialcore`) or to a shared utility within the same package.

#### Scenario: Default timeout
- **WHEN** `dial.components.core.try-out.read-timeout-ms` is not set
- **THEN** the invoker's RestClient SHALL use 120000ms as the read timeout

#### Scenario: Connect timeout shared
- **WHEN** the invoker makes a connection
- **THEN** it SHALL use the same `dial.components.core.connect-timeout-ms` as the metadata client
