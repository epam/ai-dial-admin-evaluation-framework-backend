## 1. Configuration Infrastructure

- [x] 1.1 Extend `DialCoreProperties` with client configuration (timeouts, retry settings)
- [x] 1.2 Add new properties to `application.yml` with defaults and environment variable bindings
- [x] 1.3 Update `docs/configuration.md` with new configuration properties

## 2. Token Propagation Infrastructure

- [x] 2.1 Create `AuthorizationTokenHolder` class in `configuration.security` (ThreadLocal storage for JWT token)
- [x] 2.2 Create `AuthorizationHeaderInterceptor` in `configuration.security` (Spring MVC interceptor to extract and store token)
- [x] 2.3 Register interceptor in web configuration

## 3. DIAL Core Client

- [x] 3.1 Create `DialCoreClientConfiguration` class that builds `RestClient` bean with timeouts
- [x] 3.2 Create `DialCoreClient` component with methods: `getModels()`, `getApplications()`, `getModel(id)`, `getApplication(id)`
- [x] 3.3 Implement retry logic with exponential backoff for transient failures
- [x] 3.4 Create `DialCoreClientException` for wrapping upstream errors
- [x] 3.5 Add DEBUG-level logging for request/response bodies

## 4. Internal DTOs (DIAL Core response mapping)

- [x] 4.1 Create `DialCoreModelListResponseDto` for `/openai/models` response structure (`data` array)
- [x] 4.2 Create `DialCoreApplicationListResponseDto` for `/openai/applications` response structure (`data` array)
- [x] 4.3 Create `DialCoreModelDto` with all fields from model response
- [x] 4.4 Create `DialCoreApplicationDto` with all fields from application response
- [x] 4.5 Create supporting internal DTOs (`DialCoreCapabilitiesDto`, `DialCoreLimitsDto`, `DialCorePricingDto`, `DialCoreFeaturesDto`)
- [x] 4.6 Create internal route DTOs (`DialCoreRouteDto`, `DialCoreRouteUpstreamDto`, `DialCoreRouteResponseDto`, `DialCoreAttachmentPathsDto`)

## 5. API Response DTOs (DeploymentInfoDto hierarchy)

- [x] 5.1 Create abstract `DeploymentInfoDto` with common fields and `@JsonTypeInfo`/`@JsonSubTypes` for `$type` discriminator (values: `dial-model`, `dial-application` - kebab-case)
- [x] 5.2 Create `DialModelInfoDto` extending `DeploymentInfoDto` with nullable `capabilities`, `limits`, `pricing`
- [x] 5.3 Create `DialApplicationInfoDto` extending `DeploymentInfoDto` with nullable `applicationTypeSchemaId`, `applicationProperties`, `routes`
- [x] 5.4 Create `ModelCapabilitiesDto`, `ModelLimitsDto`, `ModelPricingDto` for model-specific nested objects
- [x] 5.5 Create typed route DTOs: `ApplicationRouteDto`, `RouteUpstreamDto`, `RouteResponseDto`, `RouteAttachmentPathsDto`
- [x] 5.6 Create `DeploymentType` enum (`dial-model`, `dial-application`) with kebab-case serialization

## 6. Mappers

- [x] 6.1 Create `DeploymentMapper` interface with MapStruct
- [x] 6.2 Implement `toDialModelInfoDto(DialCoreModelDto)` mapping with null handling
- [x] 6.3 Implement `toDialApplicationInfoDto(DialCoreApplicationDto)` mapping with null handling
- [x] 6.4 Implement route mapping (`DialCoreRouteDto` → `ApplicationRouteDto`)

## 7. Service Layer

- [x] 7.1 Create `DeploymentService` with `getAllDeployments()` method (calls both Core endpoints in parallel, merges results)
- [x] 7.2 Add `getDeployment(DeploymentType type, String id)` method

## 8. Controller Layer

- [x] 8.1 Create `DeploymentController` with `GET /api/v1/deployments` endpoint
- [x] 8.2 Add `GET /api/v1/deployments/{deploymentType}/{deploymentId}` endpoint with path variable validation
- [x] 8.3 Add OpenAPI annotations with descriptions and response examples

## 9. Error Handling

- [x] 9.1 Create `DialCoreErrorMapper` to map upstream errors to HTTP status and error code
- [x] 9.2 Extend `DefaultExceptionHandler` to handle `DialCoreClientException`
- [x] 9.3 Add validation error handling for invalid `deploymentType` path variable (IAE → 400)

## 10. OpenAPI Examples

- [x] 10.1 Create example JSON files for list deployments response (mixed models + applications)
- [x] 10.2 Create example JSON files for get deployment (model) response
- [x] 10.3 Create example JSON files for get deployment (application) response with routes

## 11. Testing

- [x] 11.1 Create unit tests for `AuthorizationTokenHolder`
- [x] 11.2 Create unit tests for `DialCoreClient` with mocked RestClient
- [x] 11.3 Create unit tests for `DeploymentMapper` including route mapping
- [x] 11.4 Create functional tests for `GET /api/v1/deployments` (mock DIAL Core)
- [x] 11.5 Create functional tests for `GET /api/v1/deployments/{type}/{id}` (mock DIAL Core)
- [x] 11.6 Test retry behavior with simulated transient failures
- [x] 11.7 Test error mapping scenarios
- [x] 11.8 Test JSON serialization with `$type` discriminator (kebab-case values)
- [x] 11.9 Test invalid deployment type returns 400
