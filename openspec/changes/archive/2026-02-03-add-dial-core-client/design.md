## Context

The Evaluation Framework Backend currently operates independently but needs to integrate with DIAL Core to provide a unified API surface for clients. Currently, clients would need to know about and authenticate with both services separately.

**Current state:**
- `DialCoreProperties` exists with just `baseUrl` configuration
- No HTTP client infrastructure for external service calls
- Security uses multi-issuer JWT validation (OIDC)

**Constraints:**
- Must propagate user's JWT token to DIAL Core (user context authentication)
- Transform DIAL Core responses to unified DTO model
- No new dependencies (use Spring's built-in RestClient)
- Follow existing project patterns (minimal dependencies, explicit code)

**Why proxy (not cache)?**
DIAL Core filters available deployments based on the user's access rights. Each user may see a different set of models/applications depending on their permissions. By proxying the user's token, DIAL Core handles authorization - we don't need to replicate this complex logic.

## Goals / Non-Goals

**Goals:**
- Create reusable DIAL Core client infrastructure that can be extended for future endpoints
- Define unified `DeploymentInfoDto` hierarchy that abstracts DIAL Core's model/application distinction
- Provide merged deployment listing (models + applications in one call)
- Provide single deployment lookup by type and ID
- Propagate user authentication to DIAL Core
- Handle errors gracefully with appropriate HTTP status codes
- Support retry for transient failures

**Non-Goals:**
- Service-to-service authentication (OAuth2 client credentials) - deferred to future change
- Caching of DIAL Core responses
- Rate limiting
- Pagination (DIAL Core endpoints don't support it for deployments)

## Decisions

### D1: Use RestClient over Spring Cloud OpenFeign

**Decision:** Use Spring's `RestClient` (available since Spring 6.1 / Boot 3.2)

**Alternatives considered:**
| Option | Pros | Cons |
|--------|------|------|
| RestClient | No new deps, explicit, easy testing | More boilerplate than Feign |
| WebClient | Reactive, non-blocking | Adds reactive complexity, not needed |
| Spring Cloud OpenFeign | Declarative, less code | Heavy deps (Spring Cloud), more magic |

**Rationale:** RestClient fits the project's philosophy of minimal dependencies and explicit code. It's part of Spring core (no extra deps), synchronous (matches existing patterns), and easy to test with `MockRestServiceServer`.

### D2: Token propagation via ThreadLocal

**Decision:** Store the incoming JWT token in a `ThreadLocal` via an MVC interceptor, then inject it into outgoing requests.

**Flow:**
```
Request → AuthorizationHeaderInterceptor → ThreadLocal → RestClient interceptor → DIAL Core
                    (extracts token)          (stores)        (reads & attaches)
```

**Alternatives considered:**
- Pass token explicitly through method parameters - verbose, breaks encapsulation
- Use Spring Security context - more complex, less explicit

**Rationale:** ThreadLocal pattern is simple, thread-safe for servlet model, and proven in the reference implementation.

### D3: Unified DTO model with Jackson polymorphism

**Decision:** Use `@JsonTypeInfo` and `@JsonSubTypes` for polymorphic serialization with `$type` discriminator. Use kebab-case naming for type values (consistent with URL paths).

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "$type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = DialModelInfoDto.class, name = "dial-model"),
    @JsonSubTypes.Type(value = DialApplicationInfoDto.class, name = "dial-application")
})
public abstract class DeploymentInfoDto { ... }
```

**Alternatives considered:**
- Separate endpoints returning different types - duplicates API surface, harder for clients
- Generic `Map<String, Object>` response - loses type safety, poor OpenAPI docs
- Snake_case type names (`dial_model`) - works but underscores not recommended in URLs

**Rationale:** Jackson polymorphism provides type-safe serialization with clear discriminator. Kebab-case (`dial-model`) is consistent between URL paths and JSON, follows REST API conventions (hyphens preferred over underscores in URLs).

### D4: Path-based deployment type

**Decision:** Use path parameter for single deployment lookup: `GET /deployments/{deploymentType}/{deploymentId}`

**Alternatives considered:**
| Option | Example | Pros | Cons |
|--------|---------|------|------|
| Path-based | `/deployments/dial-model/{id}` | RESTful, clear URLs, better caching | More endpoints |
| Query param | `/deployments/{id}?type=dial-model` | Single endpoint | Less RESTful, query param validation |
| Auto-detect | `/deployments/{id}` | Simple API | Requires searching both Core endpoints |

**Rationale:** Path-based is more RESTful and provides cleaner URLs. The `deploymentType` in path is clearer for routing and documentation. Using hyphens (kebab-case) follows URL best practices (underscores not recommended in URLs). Supports future extensibility by adding new path segments.

### D5: Error mapping strategy

**Decision:** Map DIAL Core HTTP errors to our service's error responses:

| DIAL Core Status | Our Response | Error Code | Reason |
|------------------|--------------|------------|--------|
| 401, 403 | 401/403 (pass through) | AUTHENTICATION_REQUIRED / ACCESS_DENIED | Authentication/authorization errors |
| 404 | 404 | NOT_FOUND | Resource not found |
| 4xx | 400 Bad Request | VALIDATION_ERROR | Client errors |
| 5xx (except 504) | 502 Bad Gateway | UPSTREAM_ERROR | Upstream service error or unreachable |
| 504 or connection/read timeout | 504 Gateway Timeout | UPSTREAM_TIMEOUT | Upstream did not respond in time |

**Rationale:** Clients should understand errors came from upstream while getting meaningful HTTP semantics. Error codes `UPSTREAM_ERROR` and `UPSTREAM_TIMEOUT` make it explicit that the failure is on the upstream side, not this service.

### D6: Retry configuration

**Decision:** Implement exponential backoff retry for transient failures (5xx, 408, 429).

**Configuration:**
- Max attempts: 3
- Initial delay: 1000ms
- Multiplier: 2.0 (delays: 1s, 2s, 4s)
- Retryable status codes: 408, 429, 500, 502, 503, 504

**Rationale:** Improves resilience against transient DIAL Core issues without overwhelming the upstream service.

### D7: Component structure

```
┌─────────────────────────────────────────────────────────────────────┐
│ Web Layer                                                           │
│  ┌──────────────────────────────┐  ┌─────────────────────────────┐ │
│  │ DeploymentController         │  │ AuthorizationHeaderInterceptor│ │
│  │ GET /deployments             │  │ (extracts token → ThreadLocal)│ │
│  │ GET /deployments/{type}/{id} │  │                               │ │
│  └───────────┬──────────────────┘  └─────────────────────────────┘ │
├──────────────┼──────────────────────────────────────────────────────┤
│ Service Layer│                                                      │
│  ┌───────────▼────────────┐                                        │
│  │ DeploymentService      │                                        │
│  │ - getAllDeployments()  │ ──► Calls both Core endpoints,        │
│  │ - getDeployment(type,id)    merges & transforms responses      │
│  └───────────┬────────────┘                                        │
│              │                                                      │
│  ┌───────────▼────────────┐                                        │
│  │ DeploymentMapper       │                                        │
│  │ (Core DTO → Our DTO)   │                                        │
│  └────────────────────────┘                                        │
├─────────────────────────────────────────────────────────────────────┤
│ Client Layer (new package: .client.dialcore)                        │
│  ┌────────────────────────┐  ┌──────────────────────────────────┐  │
│  │ DialCoreClient         │  │ AuthorizationTokenHolder         │  │
│  │ - getModels()          │──│ (configuration.security)         │  │
│  │ - getApplications()    │  │                                  │  │
│  │ - getModel(id)         │  └──────────────────────────────────┘  │
│  │ - getApplication(id)   │                                        │
│  └───────────┬────────────┘                                        │
│              │                                                      │
│  ┌───────────▼────────────┐  ┌──────────────────────────────────┐  │
│  │ DialCoreClientConfig   │  │ DialCoreClientProperties         │  │
│  │ (RestClient bean)      │  │ (timeouts, retry settings)       │  │
│  └────────────────────────┘  └──────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
                               │
                               ▼ HTTP with Authorization header
                    ┌─────────────────────────────────────────────────┐
                    │              DIAL Core API                      │
                    │  GET /openai/models      GET /openai/models/{id}│
                    │  GET /openai/applications GET /openai/apps/{id} │
                    └─────────────────────────────────────────────────┘
```

**Package organization:**
- `com.epam.aidial.evaluation.configuration.security` - AuthorizationTokenHolder, AuthorizationHeaderInterceptor (generic token propagation)
- `com.epam.aidial.evaluation.client.dialcore` - DIAL Core client components
- `com.epam.aidial.evaluation.client.dialcore.dto` - Internal DTOs for DIAL Core responses
- `com.epam.aidial.evaluation.service.domain` - DeploymentService
- `com.epam.aidial.evaluation.service.domain.dto` - DeploymentInfoDto hierarchy (API DTOs)
- `com.epam.aidial.evaluation.service.domain.mapper` - DeploymentMapper
- `com.epam.aidial.evaluation.web.controller` - DeploymentController

## Risks / Trade-offs

| Risk | Impact | Mitigation |
|------|--------|------------|
| DIAL Core unavailability | Service returns 502/504 | Retry with backoff, clear error messages |
| Token expiration during retry | 401 from DIAL Core | Don't retry on 401, propagate immediately |
| ThreadLocal token leak | Security issue | Clear token in `afterCompletion` interceptor hook |
| DIAL Core schema changes | Mapping breaks | Internal DTOs isolate changes; update mapper |
| Large responses | Memory pressure | Current scope is list endpoints; streaming can be added if needed |
| Parallel calls for getAllDeployments | Latency | Call models and applications in parallel |

## Resolved Questions

1. **Logging verbosity** - Log request/response bodies to DIAL Core in DEBUG level. This helps with troubleshooting while keeping production logs clean.

2. **Circuit breaker** - Deferred to future. Retry with exponential backoff is sufficient for the initial implementation.

3. **Field mapping** - `owner` (not `author`), `display_version` → `version`, `id` → `deploymentId`

4. **Endpoint design** - Path-based: `GET /deployments/{deploymentType}/{deploymentId}` where `deploymentType` is `dial-model` or `dial-application` (kebab-case)

5. **Discriminator** - Use `$type` with Jackson `@JsonSubTypes`, values in kebab-case (`dial-model`, `dial-application`) - consistent with URL paths

6. **Nullable fields**:
   - DeploymentInfoDto: `version`, `description`, `owner`, `descriptionKeywords`, `inputAttachmentTypes`
   - DialModelInfoDto: all specific fields (`capabilities`, `limits`, `pricing`)
   - DialApplicationInfoDto: all specific fields (`applicationTypeSchemaId`, `applicationProperties`, `routes`)

7. **Routes typing** - `routes` in DialApplicationInfoDto is `Map<String, ApplicationRouteDto>` with fully typed nested DTOs for upstreams, response, attachmentPaths

## Future Considerations

1. **TestSuite filtering by deployment access** - TestSuites reference deployments (models/applications). In the future, we may need to filter TestSuites visible to a user based on whether they have access to the deployments used in that TestSuite. This would require checking the user's deployment access when listing/retrieving TestSuites.
