## Context

DIAL Core applications built on an `applicationTypeSchema` define routes in the schema via `dial:applicationTypeRoutes`. DIAL Core resolves these at request-routing time (`ApplicationRouteController` calls `ApplicationSchemaService.getRoutes(app)` first, then falls back to `app.getRoutes()`), but the `GET /openai/applications` listing endpoint does NOT include schema-inherited routes — it only returns app-level routes. For schema-based apps without explicit app-level routes, the `routes` field comes back as `null`.

Our deployment endpoints currently pass through whatever DIAL Core returns. This means the detail endpoint shows `routes: null` for schema-based apps that have routes defined only in their schema.

DIAL Core exposes application type schemas via `GET /v1/application_type_schemas/schema?id={schemaId}`, which returns the full schema JSON (with sensitive endpoint URLs stripped but `dial:applicationTypeRoutes` intact).

## Goals / Non-Goals

**Goals:**
- Resolve effective routes for applications in the detail endpoint by fetching schema-defined routes when the app has `applicationTypeSchemaId` and `routes == null`
- Always return `routes: null` in the list endpoint for all applications (regardless of source)
- Resolve schema routes for any application with `applicationTypeSchemaId`. When both schema and app-level routes exist, merge them: schema routes as the base map, app-level routes overlaid on top (app wins per key). Log a warning for each conflicting route key.

**Non-Goals:**
- Caching application type schemas (can be added later if latency is a concern)
- Resolving other schema-inherited properties (completion endpoint, MCP config, interceptors, etc.)
- Modifying the list endpoint to resolve any routes at all
- Supporting complex merge strategies beyond "app wins on conflict" (e.g., field-level merging within a single route)

## Decisions

### Decision 1: Fetch schema on-demand per application, not batch

**Choice:** When the detail endpoint fetches a single application, if it has `applicationTypeSchemaId`, make a separate call to `GET /v1/application_type_schemas/schema?id={schemaId}` to resolve and merge routes.

**Why not batch fetch all schemas upfront:** The detail endpoint serves a single application. Fetching all schemas would be wasteful. The list endpoint never needs routes at all.

**Why not cache:** YAGNI — no performance issue yet. Schemas are config-level data that could be cached in the future if needed.

### Decision 2: New DTOs for schema route JSON with `dial:` prefix

**Choice:** Create separate DTO classes (`DialCoreSchemaRouteDto`, `DialCoreSchemaRouteUpstreamDto`, `DialCoreSchemaAttachmentPathsDto`, `DialCoreSchemaRouteResponseDto`) in `client.dialcore.dto` with `@JsonProperty("dial:paths")` etc. for deserialization.

**Alternative considered:** Parse with Jackson `JsonNode` manually.

**Why DTOs:** Consistent with project patterns (typed DTOs in client layer, MapStruct mapping). More type-safe, testable, and readable.

### Decision 3: SchemaRouteExtractor as injectable service component

**Choice:** Create `SchemaRouteExtractor` as a `@Component` in `service.domain` that encapsulates the "fetch schema → extract routes → map to DTOs" logic.

**Why not in DeploymentService directly:** Follows the project pattern of specialized injectable components for conversion logic. Keeps `DeploymentService` focused on orchestration.

### Decision 4: DialCoreClient returns JsonNode for schema response

**Choice:** `DialCoreClient.getApplicationTypeSchema(String schemaId)` returns `JsonNode` rather than a typed DTO for the full schema.

**Why:** The schema is a JSON Schema document with hundreds of possible properties. We only need `dial:applicationTypeRoutes` from it. A full DTO would be massive and unnecessary. `SchemaRouteExtractor` extracts the `dial:applicationTypeRoutes` node and deserializes just that part into typed DTOs.

### Decision 5: List endpoint always nulls out routes

**Choice:** `DeploymentService.getAllDeployments()` explicitly sets `routes = null` on every `DialApplicationInfoDto` after mapping.

**Why:** Consistent behavior — routes are never shown on the list, regardless of whether they come from the app config or schema. The list is for overview; routes are detail-level data.

## Risks / Trade-offs

**[Risk] Schema fetch adds latency to detail endpoint** → Only fetched when `applicationTypeSchemaId != null`. Single HTTP call. Acceptable for a detail endpoint that already calls DIAL Core. Can add caching later if needed.

**[Risk] DIAL Core schema endpoint unavailable or returns error** → `SchemaRouteExtractor` SHALL log a warning and return `null` routes (graceful degradation). The app is still returned with all other fields; routes are just missing.

**[Risk] Schema JSON format changes (new `dial:` properties)** → DTOs use `@JsonIgnoreProperties(ignoreUnknown = true)` for forward compatibility. New fields are silently ignored until we add support.

**[Risk] Schema endpoint strips fields that we need** → Confirmed: `handleGetSchema` strips endpoint URLs (completion, configuration, rate, tokenize, truncate) and `appendApplicationProperties`, but does NOT strip `dial:applicationTypeRoutes`. Routes are available.
