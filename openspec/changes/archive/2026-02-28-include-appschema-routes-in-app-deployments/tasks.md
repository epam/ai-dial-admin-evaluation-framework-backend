## 1. Client Layer — Schema Route DTOs and Client Method

- [x] 1.1 Create `DialCoreSchemaRouteUpstreamDto` in `client.dialcore.dto` with `@JsonProperty("dial:endpoint")`, `@JsonProperty("dial:key")`, `@JsonProperty("dial:extraData")`, `@JsonProperty("dial:weight")`, `@JsonProperty("dial:tier")` fields. Use `@JsonIgnoreProperties(ignoreUnknown = true)`, Lombok `@Data/@Builder/@NoArgsConstructor/@AllArgsConstructor`.
- [x] 1.2 Create `DialCoreSchemaAttachmentPathsDto` in `client.dialcore.dto` with `@JsonProperty("dial:requestBody")` and `@JsonProperty("dial:responseBody")` fields (`List<String>`). Same Lombok/Jackson annotations.
- [x] 1.3 Create `DialCoreSchemaRouteResponseDto` in `client.dialcore.dto` with `@JsonProperty("dial:status")` (`Integer`) and `@JsonProperty("dial:body")` (`String`) fields. Same Lombok/Jackson annotations.
- [x] 1.4 Create `DialCoreSchemaRouteDto` in `client.dialcore.dto` with `@JsonProperty("dial:paths")`, `@JsonProperty("dial:methods")`, `@JsonProperty("dial:upstreams")` (List of `DialCoreSchemaRouteUpstreamDto`), `@JsonProperty("dial:userRoles")`, `@JsonProperty("dial:rewritePath")`, `@JsonProperty("dial:order")`, `@JsonProperty("dial:maxRetryAttempts")`, `@JsonProperty("dial:permissions")`, `@JsonProperty("dial:attachmentPaths")` (`DialCoreSchemaAttachmentPathsDto`), `@JsonProperty("dial:response")` (`DialCoreSchemaRouteResponseDto`). Same Lombok/Jackson annotations.
- [x] 1.5 Add `getApplicationTypeSchema(String schemaId)` method to `DialCoreClient`. It SHALL call `GET /v1/application_type_schemas/schema?id={schemaId}` and return `JsonNode`. **The schema ID is a URL** (e.g., `https://dial-entity-extractor.example.com/`) **and MUST be URL-encoded** as a query parameter. Use RestClient's `.uri()` with a URI template and variable map (e.g., `UriComponentsBuilder`) or pass a pre-built `URI` to ensure proper encoding — do NOT concatenate the ID into the path string. Use the existing `dialCoreRestClient` and `withRetry` pattern. Add a `SCHEMAS_PATH` constant (`"/v1/application_type_schemas/schema"`).

## 2. Mapper Layer — Schema Route to ApplicationRouteDto Mapping

- [x] 2.1 Add `toApplicationRouteDto(DialCoreSchemaRouteDto source)` mapping method to `DeploymentMapper`. Map fields: `paths`→`paths`, `methods`→`methods`, `upstreams`→`upstreams`, `userRoles`→`userRoles`, `rewritePath`→`rewritePath`, `order`→`order`, `maxRetryAttempts`→`maxRetryAttempts`, `permissions`→`permissions`, `attachmentPaths`→`attachmentPaths`, `response`→`response`. Ignore `name` target (set separately).
- [x] 2.2 Add `toRouteUpstreamDto(DialCoreSchemaRouteUpstreamDto source)` mapping method to `DeploymentMapper`. Map `endpoint`, `extraData`, `weight`, `tier`.
- [x] 2.3 Add `toRouteAttachmentPathsDto(DialCoreSchemaAttachmentPathsDto source)` mapping method to `DeploymentMapper`. Map `requestBody`, `responseBody`.
- [x] 2.4 Add `toRouteResponseDto(DialCoreSchemaRouteResponseDto source)` mapping method to `DeploymentMapper`. Map `status`, `body`.

## 3. Service Layer — SchemaRouteExtractor

- [x] 3.1 Create `SchemaRouteExtractor` as `@Component` in `service.domain` with `@LogExecution` and `@Slf4j`. Inject `DialCoreClient`, `ObjectMapper`, and `DeploymentMapper`. Define `APPLICATION_TYPE_ROUTES_KEY = "dial:applicationTypeRoutes"` constant.
- [x] 3.2 Update `resolveRoutes(DialCoreApplicationDto app)` to merge app-level and schema routes. Logic: (1) if `applicationTypeSchemaId == null` → return null; (2) fetch schema via `dialCoreClient.getApplicationTypeSchema(schemaId)`; (3) extract `dial:applicationTypeRoutes` node; (4) if absent → return null; (5) deserialize to `Map<String, DialCoreSchemaRouteDto>` using ObjectMapper; (6) map each entry to `ApplicationRouteDto` via mapper, setting `name` from map key — this is the **base** map; (7) if app also has non-null `routes`, map each app route via `deploymentMapper.toApplicationRouteDto(DialCoreRouteDto)` and overlay onto the base map — app wins on conflict, `log.warn` for each conflicting key with key name and schemaId; (8) return merged result. Wrap in try-catch: on any exception, log warning with schema ID and return null (graceful degradation).

## 4. Service Layer — DeploymentService Changes

- [x] 4.1 Inject `SchemaRouteExtractor` into `DeploymentService`.
- [x] 4.2 Modify `getAllDeployments()`: after mapping applications to `DialApplicationInfoDto`, set `routes = null` on each application DTO before adding to result list.
- [x] 4.3 Modify `getDeployment()` for `DIAL_APPLICATION` case: after mapping to `DialApplicationInfoDto`, call `schemaRouteExtractor.resolveRoutes(dialCoreApp)`. If result is non-null, set it on the DTO's `routes` field.

## 5. OpenAPI Examples

- [x] 5.1 Update `api-v1-deployments-GET-response-200-full.json`: set `"routes": null` on the application entry (currently shows routes).

## 6. Tests

- [x] 6.1 Add unit tests for `DeploymentMapper` schema route mapping methods: test `DialCoreSchemaRouteDto` → `ApplicationRouteDto` with all fields, null nested objects, empty lists.
- [x] 6.2 Update unit tests for `SchemaRouteExtractor`: update "app has routes" test to verify merge behavior, add test for merge with no conflicts (disjoint keys), add test for merge with conflicts (app wins, verify log.warn), keep existing tests for no schema ID, schema has no routes, and fetch fails.
- [x] 6.3 Update `DeploymentFunctionalTests`: verify list endpoint always returns `routes: null` for applications. Verify detail endpoint resolves schema routes when app has `applicationTypeSchemaId` and `routes == null`. Add functional test for merge scenario (app + schema both have routes, app wins on conflict).
- [x] 6.4 Run `./gradlew clean build` to verify checkstyle, compilation, and all tests pass.
