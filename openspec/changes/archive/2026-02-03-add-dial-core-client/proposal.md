## Why

The Evaluation Framework Backend needs to integrate with DIAL Core to provide deployment information to frontend/clients. This makes our service the single point of truth, reducing complexity and dependencies on the client side. Clients will call our service instead of directly hitting DIAL Core.

**Why proxy (not cache)?** DIAL Core filters available models/applications based on the user's access rights. By proxying the user's token to DIAL Core, each user sees only the deployments they are authorized to access. A cache-based approach would require replicating DIAL Core's complex authorization logic.

## What Changes

- Add a DIAL Core HTTP client infrastructure using Spring's `RestClient` for making authenticated calls to DIAL Core API
- Define a unified `DeploymentInfoDto` hierarchy that transforms DIAL Core responses into our domain model
- Implement two endpoints:
  - `GET /api/v1/deployments` - List all deployments (merges models + applications from DIAL Core)
  - `GET /api/v1/deployments/{deploymentType}/{deploymentId}` - Get single deployment by type and ID
- Propagate the user's JWT token from incoming requests to DIAL Core (user context authentication)
- Configure retry logic for transient failures
- Add error handling and mapping for DIAL Core errors

### DTO Hierarchy

```
DeploymentInfoDto (abstract base)
├── $type: String (discriminator: "dial-model" | "dial-application")
├── deploymentId: String
├── displayName: String
├── version: String (nullable)
├── description: String (nullable)
├── owner: String (nullable)
├── createdAt: Long (epochMs)
├── updatedAt: Long (epochMs)
├── descriptionKeywords: List<String> (nullable)
└── inputAttachmentTypes: List<String> (nullable)

DialModelInfoDto extends DeploymentInfoDto ($type = "dial-model")
├── capabilities: ModelCapabilitiesDto (nullable)
├── limits: ModelLimitsDto (nullable)
└── pricing: ModelPricingDto (nullable)

DialApplicationInfoDto extends DeploymentInfoDto ($type = "dial-application")
├── applicationTypeSchemaId: String (nullable)
├── applicationProperties: Map<String, Object> (nullable)
└── routes: Map<String, ApplicationRouteDto> (nullable, typed)

ApplicationRouteDto
├── name: String
├── userRoles: List<String> (nullable)
├── response: RouteResponseDto (nullable)
├── rewritePath: Boolean
├── paths: List<String>
├── methods: List<String>
├── upstreams: List<RouteUpstreamDto>
├── maxRetryAttempts: Integer
├── order: Integer
├── permissions: List<String>
└── attachmentPaths: RouteAttachmentPathsDto (nullable)

RouteUpstreamDto
├── endpoint: String
├── extraData: Object (nullable)
├── weight: Integer
└── tier: Integer

RouteResponseDto
├── status: Integer
└── body: String

RouteAttachmentPathsDto
├── requestBody: List<String>
└── responseBody: List<String>
```

### Approach: RestClient (not Feign)

Using **Spring's RestClient** (built into Spring 6.1+) instead of Spring Cloud OpenFeign from the reference implementation:
- No additional dependencies needed
- Aligns with project's minimal-dependency approach
- Explicit HTTP operations, easier debugging
- Easy to test with `MockRestServiceServer`

## Capabilities

### New Capabilities

- `dial-core-client`: Infrastructure for making authenticated HTTP calls to DIAL Core API, including token propagation, retry logic, error handling, and response transformation. Exposes unified deployment endpoints.

### Modified Capabilities

_None - this is a new integration that doesn't modify existing capability requirements._

## Impact

### Code Changes

| Layer | Changes |
|-------|---------|
| **Configuration** | Add `DialCoreClientProperties` for client config (timeouts, retry settings) |
| **Infrastructure** | Add `DialCoreClient` (RestClient-based HTTP client), `AuthorizationTokenHolder` (ThreadLocal for token propagation), `AuthorizationHeaderInterceptor` (extracts token from requests) |
| **DTOs** | Add `DeploymentInfoDto` hierarchy with Jackson `@JsonSubTypes` for polymorphic serialization; typed route DTOs |
| **Mappers** | Add mappers to transform DIAL Core responses to our DTOs |
| **Service** | Add `DeploymentService` to orchestrate calls to DIAL Core and merge/transform responses |
| **Web** | Add `DeploymentController` exposing `/api/v1/deployments` endpoints |
| **Exception Handling** | Extend `DefaultExceptionHandler` to map DIAL Core errors |

### API Changes

New endpoints:

**1. List all deployments**
```
GET /api/v1/deployments
```
Response: `List<DeploymentInfoDto>` (merged models + applications)

**2. Get deployment by type and ID**
```
GET /api/v1/deployments/{deploymentType}/{deploymentId}
```
- `deploymentType`: `dial-model` | `dial-application` (kebab-case)
- Response: `DeploymentInfoDto` (specific subtype)

### Dependencies

- **No new dependencies** - RestClient is included in Spring Boot 3.5.3

### Configuration

New properties to add:
```yaml
dial:
  components:
    core:
      base-url: ${DIAL_CORE_URL:http://localhost:8080}
      connect-timeout-ms: ${DIAL_CORE_CONNECT_TIMEOUT_MS:5000}
      read-timeout-ms: ${DIAL_CORE_READ_TIMEOUT_MS:30000}
      retry:
        max-attempts: ${DIAL_CORE_RETRY_MAX_ATTEMPTS:3}
        delay-ms: ${DIAL_CORE_RETRY_DELAY_MS:1000}
        multiplier: ${DIAL_CORE_RETRY_MULTIPLIER:2.0}
```

### Future Extensibility

- The path-based `{deployment_type}` design allows adding new deployment types without breaking API
- The token propagation infrastructure supports additional DIAL Core endpoints
- Service-to-service authentication (OAuth2 client credentials) can be added later
