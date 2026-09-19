## MODIFIED Requirements

### Requirement: Token propagation

The system SHALL propagate the caller's credential from the incoming request to DIAL Core, in the header that credential's kind requires: a credential taken from `Authorization: Bearer` SHALL be forwarded as `Authorization: Bearer <token>`, and a credential taken from the `Api-Key` header SHALL be forwarded as `Api-Key: <key>`. A request carrying both SHALL propagate the bearer credential only. This ensures DIAL Core filters deployments based on the caller's access rights - each caller sees only deployments they are authorized to access - for both credential kinds.

This propagation rule governs every client the system invokes on the caller's behalf through the shared request interceptor: the DIAL Core metadata client, the DIAL Core deployment invoker used by try-out and run execution, and the dial-adas structured-query DSL client (a separate host, not DIAL Core, that shares the same interceptor). It does not govern clients that authenticate with the service's own configured key rather than the caller's credential, nor the metric-provider client, which carries no caller credential at all.

Status: **Planned**

#### Scenario: Token is propagated to DIAL Core

- **WHEN** a caller sends a request with an `Authorization: Bearer <token>` header
- **THEN** the system forwards the same token to DIAL Core in an `Authorization: Bearer <token>` header
- **AND** does not send an `Api-Key` header
- **AND** DIAL Core returns only deployments the caller is authorized to access

#### Scenario: API-key credential is propagated to DIAL Core

- **WHEN** a caller sends a request with a non-blank `Api-Key` header and no `Authorization` header
- **THEN** the system forwards the same key to DIAL Core in an `Api-Key: <key>` header
- **AND** does not send an `Authorization` header
- **AND** DIAL Core returns only deployments that key is authorized to access

#### Scenario: Both headers present

- **WHEN** a caller sends both `Authorization: Bearer <token>` and `Api-Key: <key>`
- **THEN** the system forwards only `Authorization: Bearer <token>` to DIAL Core

#### Scenario: Missing authorization header

- **WHEN** a caller sends a request with neither an `Authorization` nor an `Api-Key` header
- **AND** security is enabled
- **THEN** the system rejects the request with HTTP 401 before calling DIAL Core

#### Scenario: User with limited access

- **WHEN** a caller with restricted permissions requests deployments
- **THEN** DIAL Core filters the response to include only authorized deployments
- **AND** the system returns this filtered list without modification

### Requirement: TokenPropagationHelper for async operations

The system SHALL provide a `TokenPropagationHelper` utility class for propagating the caller's credential - both its value and its kind - to new threads. When code executes asynchronously (e.g., via `CompletableFuture.supplyAsync()`), the new thread does not have access to the request thread's ThreadLocal credential. This helper MUST be used whenever spawning async tasks that need caller context, and the propagated credential MUST reach the async thread with the same kind it was captured with, so that outbound calls from that thread select the same header the request thread would have.

Status: **Planned**

#### Scenario: Async code needs user token

- **WHEN** a service spawns async tasks (e.g., `CompletableFuture.supplyAsync()`)
- **AND** the async code needs to make authenticated calls (e.g., to DIAL Core)
- **THEN** the service MUST capture the credential in the request thread before spawning
- **AND** wrap the async task with the helper so the credential and its kind are restored inside the task

#### Scenario: Credential kind survives the thread hop

- **WHEN** an API-key caller's credential is propagated to a worker thread
- **THEN** a DIAL Core call made from that worker thread SHALL send `Api-Key`, not `Authorization: Bearer`

#### Scenario: Token cleanup after async execution

- **WHEN** a wrapped async task completes (success or failure)
- **THEN** the helper clears the propagated credential from the async thread's state
- **AND** prevents leakage to subsequent tasks on pooled threads

#### Scenario: Usage pattern

- **WHEN** implementing parallel async operations with caller context
- **THEN** follow this pattern:
```java
// Capture the caller's credential (value + kind) in the request thread before spawning async tasks
CallerCredential credential = AuthorizationTokenHolder.getCredential();

CompletableFuture.supplyAsync(TokenPropagationHelper.withCredential(credential, () -> {
    // The credential is available here via AuthorizationTokenHolder.getCredential()
    return dialCoreClient.getModels();
}));
```

#### Scenario: Absent credential

- **WHEN** an async task is wrapped while no caller credential was captured (a `null` credential)
- **THEN** the helper SHALL run the task with no credential established, without throwing
- **AND** outbound calls from that task SHALL carry no caller credential header

#### Scenario: Helper variants

- **WHEN** different async patterns are needed
- **THEN** `TokenPropagationHelper` provides a variant for each of `Supplier<T>` (`CompletableFuture.supplyAsync()`), `Callable<T>` (`ExecutorService.submit(Callable)`), and `Runnable` (`ExecutorService.submit(Runnable)`)
- **AND** each variant accepts the captured credential including its kind

### Requirement: DialCoreDeploymentInvoker configuration
The invoker SHALL use a separate configuration from the metadata client, allowing independent timeout tuning.

Status: **Planned**

#### Scenario: Separate RestClient bean
- **WHEN** the application starts
- **THEN** a dedicated `RestClient` bean (e.g., `dialCoreTryOutRestClient`) SHALL be created with the try-it-out read timeout
- **AND** it SHALL share the same base URL and caller-credential interceptor as the metadata client

**Implementation note:** The caller-credential interceptor logic (reading the caller's credential from `AuthorizationTokenHolder` and setting either `Authorization: Bearer` or `Api-Key` according to its kind) is a **public static** method `callerCredentialInterceptor()` on `DialCoreClientConfiguration`. `DialCoreClientConfiguration` and `DialCoreDeploymentInvokerConfiguration` are both in `client.dialcore`; `DialAdasClientConfiguration` in `client.dialadas` reuses the same method for `dialAdasRestClient`.

#### Scenario: Default timeout
- **WHEN** `dial.components.core.try-out.read-timeout-ms` is not set
- **THEN** the invoker's RestClient SHALL use 120000ms as the read timeout

#### Scenario: Connect timeout shared
- **WHEN** the invoker makes a connection
- **THEN** it SHALL use the same `dial.components.core.connect-timeout-ms` as the metadata client

## Implementation notes

Planned touch points: `com.epam.aidial.evaluation.runner.util.AuthorizationTokenHolder` and `runner.util.TokenPropagationHelper` (`evaluation-runner-core`); the shared interceptor factory in `com.epam.aidial.evaluation.client.dialcore.DialCoreClientConfiguration`, reused by `client.dialcore.DialCoreDeploymentInvokerConfiguration` and `client.dialadas.DialAdasClientConfiguration`; capture in `configuration.security.AuthorizationHeaderInterceptor`. Header name and value per kind come from `CallerCredential#headerName()`/`headerValue()`, shared with the MCP invoker. `client.dialcore.DialFileClientConfiguration` keeps using the configured service-account key and is out of scope.
