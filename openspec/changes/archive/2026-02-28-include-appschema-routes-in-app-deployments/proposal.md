## Why

DIAL applications built on an `applicationTypeSchema` inherit route definitions from their schema's `dial:applicationTypeRoutes`. DIAL Core resolves these schema-inherited routes at request-routing time (in `ApplicationRouteController`) but does NOT include them in the `GET /openai/applications` listing response. Our deployment detail endpoint therefore shows `routes: null` for schema-based apps, even though they have effective routes defined in their schema. Admins need to see these effective routes to understand what endpoints an application exposes.

## What Changes

- Add a new DIAL Core client method to fetch an application type schema via `GET /v1/application_type_schemas/schema?id={schemaId}`.
- Add a `SchemaRouteExtractor` service component that, for applications with `applicationTypeSchemaId` and `routes == null`, fetches the schema, extracts `dial:applicationTypeRoutes`, and maps them to `ApplicationRouteDto`.
- Add DTOs for deserializing schema route JSON (which uses `dial:` prefixed property names like `dial:paths`, `dial:methods`, `dial:upstreams`).
- Modify the deployment detail endpoint (`GET /api/v1/deployments/{type}/{id}`) to resolve schema-inherited routes for applications.
- Modify the deployment list endpoint (`GET /api/v1/deployments`) to always return `routes: null` for applications (no route resolution on the list, regardless of source).
- Update OpenAPI examples to reflect the new behavior.

## Capabilities

### New Capabilities
- `app-schema-route-resolution`: Resolving application routes inherited from an application type schema via DIAL Core's schema API, including client integration, schema route DTOs, and extraction/mapping logic.

### Modified Capabilities
- `dial-core-client`: Adding a new client method to fetch application type schemas from DIAL Core (`GET /v1/application_type_schemas/schema`). Updating the deployment detail flow to resolve schema-inherited routes. Updating the list flow to always null out routes.

## Impact

- **Client layer** (`client.dialcore`): New method on `DialCoreClient`, new schema route DTOs.
- **Service layer** (`service.domain`): New `SchemaRouteExtractor` component; changes to `DeploymentService` for route resolution and list-level route suppression.
- **Mapper layer** (`service.domain.mapper`): New mapping methods on `DeploymentMapper` for schema route DTOs to `ApplicationRouteDto`.
- **OpenAPI examples**: List full example updated (routes → null), detail example unchanged.
- **Tests**: New unit tests for `SchemaRouteExtractor` and mapper methods; updated functional tests for both endpoints.
- **No database changes, no migration needed.**
