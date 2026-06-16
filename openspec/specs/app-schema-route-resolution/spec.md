# App Schema Route Resolution Spec

## Purpose

Resolves application routes inherited from an application type schema via DIAL Core's schema API, including client integration, schema route DTOs, extraction/mapping logic, and merge behavior when both app-level and schema-level routes exist.

## Requirements

### Requirement: Fetch application type schema from DIAL Core

The system SHALL provide a method on `DialCoreClient` to fetch an application type schema from DIAL Core via `GET /v1/application_type_schemas/schema?id={schemaId}`. The schema ID is a URL (e.g., `https://dial-entity-extractor.example.com/`) and SHALL be URL-encoded when passed as a query parameter. The method SHALL return the schema as a `JsonNode`. The method SHALL use the same `RestClient`, retry logic, and token propagation as existing DIAL Core client methods.

#### Scenario: Successful schema fetch

- **WHEN** `DialCoreClient.getApplicationTypeSchema(schemaId)` is called with a valid schema ID
- **THEN** the client SHALL send `GET /v1/application_type_schemas/schema?id={schemaId}` to DIAL Core
- **AND** return the response body as a `JsonNode`

#### Scenario: Schema not found

- **WHEN** DIAL Core returns HTTP 404 for the schema request
- **THEN** the client SHALL throw `DialCoreClientException` with the appropriate status (same error handling as existing methods)

#### Scenario: Token propagated for schema request

- **WHEN** the schema fetch is triggered from a user request
- **THEN** the user's JWT token SHALL be propagated to DIAL Core via the `Authorization: Bearer` header

---

### Requirement: Schema route DTOs for dial-prefixed JSON

The system SHALL provide DTOs in `client.dialcore.dto` for deserializing route definitions from application type schema JSON. Schema routes use `dial:` prefixed property names (e.g., `dial:paths`, `dial:methods`, `dial:upstreams`). Each DTO SHALL use `@JsonProperty("dial:...")` annotations for deserialization and `@JsonIgnoreProperties(ignoreUnknown = true)` for forward compatibility.

The DTOs SHALL be:
- `DialCoreSchemaRouteDto` — fields: `paths`, `methods`, `upstreams`, `userRoles`, `rewritePath`, `order`, `maxRetryAttempts`, `permissions`, `attachmentPaths`, `response`
- `DialCoreSchemaRouteUpstreamDto` — fields: `endpoint`, `key`, `extraData`, `weight`, `tier`
- `DialCoreSchemaAttachmentPathsDto` — fields: `requestBody`, `responseBody`
- `DialCoreSchemaRouteResponseDto` — fields: `status`, `body`

#### Scenario: Deserialize schema route with dial prefix

- **WHEN** a JSON object with `dial:paths`, `dial:methods`, `dial:upstreams` is deserialized into `DialCoreSchemaRouteDto`
- **THEN** the values SHALL be mapped to `paths`, `methods`, `upstreams` fields respectively

#### Scenario: Unknown properties ignored

- **WHEN** the schema route JSON contains properties not mapped in the DTO
- **THEN** those properties SHALL be silently ignored (no deserialization error)

#### Scenario: Upstream with dial prefix

- **WHEN** an upstream object with `dial:endpoint`, `dial:weight`, `dial:tier` is deserialized into `DialCoreSchemaRouteUpstreamDto`
- **THEN** the values SHALL be mapped to `endpoint`, `weight`, `tier` fields respectively

---

### Requirement: SchemaRouteExtractor resolves routes from application type schema

The system SHALL provide a `SchemaRouteExtractor` component in `service.domain` that resolves effective routes for an application by fetching its application type schema and extracting `dial:applicationTypeRoutes`.

Resolution logic:
1. If the application has no `applicationTypeSchemaId` → return `null` (no schema to resolve)
2. Fetch the schema via `DialCoreClient.getApplicationTypeSchema(schemaId)`
3. Extract `dial:applicationTypeRoutes` from the schema `JsonNode`
4. If absent → return `null`
5. Deserialize into `Map<String, DialCoreSchemaRouteDto>` using `ObjectMapper`
6. Map each `DialCoreSchemaRouteDto` to `ApplicationRouteDto` via `DeploymentMapper` — this is the **base** map
7. If the application also has non-null `routes`, merge app-level routes into the base map:
   - For each app-level route key that also exists in the schema routes, **app-level route wins** and the extractor SHALL log a warning identifying the conflicting key and schema ID
   - App-level route keys not in schema are added to the merged map
8. Return the merged `Map<String, ApplicationRouteDto>`

#### Scenario: Application with schema routes and no app-level routes

- **WHEN** `SchemaRouteExtractor.resolveRoutes(app)` is called
- **AND** the application has `applicationTypeSchemaId = "https://my-schema"`
- **AND** the application has `routes = null`
- **THEN** the extractor SHALL fetch the schema from DIAL Core
- **AND** extract `dial:applicationTypeRoutes` from the schema
- **AND** return the routes mapped to `Map<String, ApplicationRouteDto>`

#### Scenario: Application with no schema ID

- **WHEN** `SchemaRouteExtractor.resolveRoutes(app)` is called
- **AND** the application has `applicationTypeSchemaId = null`
- **THEN** the extractor SHALL return `null` without making any DIAL Core calls

#### Scenario: Application has both app-level and schema routes (merge)

- **WHEN** `SchemaRouteExtractor.resolveRoutes(app)` is called
- **AND** the application has `applicationTypeSchemaId` set
- **AND** the application has non-null `routes` with keys `["custom"]`
- **AND** the schema has `dial:applicationTypeRoutes` with keys `["v1", "custom"]`
- **THEN** the extractor SHALL return a merged map with keys `["v1", "custom"]`
- **AND** the `"v1"` route SHALL come from the schema
- **AND** the `"custom"` route SHALL come from the app (app-level wins)

#### Scenario: Conflicting route keys produce a warning

- **WHEN** `SchemaRouteExtractor.resolveRoutes(app)` is called
- **AND** both app-level routes and schema routes contain the same key
- **THEN** the extractor SHALL log a warning for each conflicting key, including the key name and schema ID

#### Scenario: Schema has no routes defined

- **WHEN** the fetched schema does not contain `dial:applicationTypeRoutes`
- **THEN** the extractor SHALL return `null`

#### Scenario: Schema fetch fails gracefully

- **WHEN** the schema fetch fails (DIAL Core error, network issue, etc.)
- **THEN** the extractor SHALL log a warning with the schema ID and error
- **AND** return `null` (graceful degradation — the application is still returned without routes)

---

### Requirement: DeploymentMapper maps schema route DTOs to ApplicationRouteDto

The `DeploymentMapper` SHALL provide mapping methods to convert schema route DTOs (`DialCoreSchemaRouteDto`) to the existing `ApplicationRouteDto`. The mapper SHALL also handle nested DTOs: `DialCoreSchemaRouteUpstreamDto` → `RouteUpstreamDto`, `DialCoreSchemaAttachmentPathsDto` → `RouteAttachmentPathsDto`, `DialCoreSchemaRouteResponseDto` → `RouteResponseDto`.

Note: Schema route DTOs do not have a `name` field (the route name is the map key). The mapper SHALL accept the route name as a separate parameter and set it on the resulting `ApplicationRouteDto`.

#### Scenario: Schema route mapped to ApplicationRouteDto

- **WHEN** a `DialCoreSchemaRouteDto` with paths `["/v1/.*"]`, methods `["GET"]`, and upstreams is mapped
- **THEN** the resulting `ApplicationRouteDto` SHALL have the same `paths`, `methods`, and `upstreams` values

#### Scenario: Route name set from map key

- **WHEN** a schema route with map key `"v1"` is mapped
- **THEN** the resulting `ApplicationRouteDto` SHALL have `name = "v1"`

#### Scenario: Null nested objects preserved

- **WHEN** a schema route has `attachmentPaths = null` and `response = null`
- **THEN** the resulting `ApplicationRouteDto` SHALL have `attachmentPaths = null` and `response = null`
