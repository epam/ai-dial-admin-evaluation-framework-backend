## MODIFIED Requirements

### Requirement: List all deployments

The system SHALL provide an endpoint to list all available deployments (models and applications) from DIAL Core. The system SHALL call both `/openai/models` and `/openai/applications` DIAL Core endpoints, transform responses to `DeploymentInfoDto` hierarchy, merge into a single list, and return. The `routes` field on every `DialApplicationInfoDto` in the list response SHALL always be `null`, regardless of whether the application has app-level routes or schema-inherited routes.

#### Scenario: Successful deployment listing

- **WHEN** authenticated user sends GET request to `/api/v1/deployments`
- **THEN** system calls DIAL Core `/openai/models` and `/openai/applications` with user's JWT token
- **AND** transforms responses to `DialModelInfoDto` and `DialApplicationInfoDto` respectively
- **AND** sets `routes = null` on every `DialApplicationInfoDto`
- **AND** returns merged list with HTTP 200

#### Scenario: Deployment listing without authentication

- **WHEN** unauthenticated user sends GET request to `/api/v1/deployments`
- **THEN** system returns HTTP 401 Unauthorized

#### Scenario: One DIAL Core endpoint fails

- **WHEN** authenticated user sends GET request to `/api/v1/deployments`
- **AND** one of the DIAL Core endpoints returns an error
- **THEN** system returns appropriate error status (does not return partial results)

#### Scenario: Application with app-level routes in list

- **WHEN** DIAL Core returns an application with non-null `routes`
- **THEN** the list endpoint SHALL still return `routes: null` for that application

---

### Requirement: Get deployment by type and ID

The system SHALL provide an endpoint to get a single deployment by type and ID. The `deploymentType` path parameter determines which DIAL Core endpoint to call (`/openai/models/{id}` for `dial-model`, `/openai/applications/{id}` for `dial-application`). Path values use kebab-case (hyphens preferred over underscores in URLs). For applications with `applicationTypeSchemaId`, the system SHALL resolve effective routes by fetching the schema and merging schema-level routes with app-level routes. App-level routes take precedence on key conflicts, and conflicts are logged as warnings.

#### Scenario: Successful model retrieval

- **WHEN** authenticated user sends GET request to `/api/v1/deployments/dial-model/{id}`
- **THEN** system calls DIAL Core `/openai/models/{id}` with user's JWT token
- **AND** transforms response to `DialModelInfoDto`
- **AND** returns with HTTP 200

#### Scenario: Successful application retrieval with app-level routes only

- **WHEN** authenticated user sends GET request to `/api/v1/deployments/dial-application/{id}`
- **AND** the application has non-null `routes` from DIAL Core
- **AND** the application has no `applicationTypeSchemaId` (or the schema has no routes)
- **THEN** system returns the application with its app-level routes intact

#### Scenario: Successful application retrieval with schema-inherited routes

- **WHEN** authenticated user sends GET request to `/api/v1/deployments/dial-application/{id}`
- **AND** the application has `applicationTypeSchemaId` set and `routes == null`
- **THEN** system SHALL resolve routes from the application type schema via `SchemaRouteExtractor`
- **AND** return the application with the resolved schema routes in the `routes` field

#### Scenario: Application retrieval merges app-level and schema routes

- **WHEN** authenticated user sends GET request to `/api/v1/deployments/dial-application/{id}`
- **AND** the application has `applicationTypeSchemaId` set and non-null `routes`
- **AND** the schema also has `dial:applicationTypeRoutes`
- **THEN** system SHALL return the application with merged routes (schema as base, app-level overrides on conflict)
- **AND** log a warning for each conflicting route key

#### Scenario: Application with schema but schema has no routes

- **WHEN** authenticated user sends GET request to `/api/v1/deployments/dial-application/{id}`
- **AND** the application has `applicationTypeSchemaId` set and `routes == null`
- **AND** the schema does not contain `dial:applicationTypeRoutes`
- **THEN** system SHALL return the application with `routes: null`

#### Scenario: Schema fetch fails gracefully on detail endpoint

- **WHEN** authenticated user sends GET request to `/api/v1/deployments/dial-application/{id}`
- **AND** the application has `applicationTypeSchemaId` set and `routes == null`
- **AND** fetching the schema from DIAL Core fails
- **THEN** system SHALL return the application with `routes: null` (graceful degradation)
- **AND** log a warning about the schema fetch failure

#### Scenario: Invalid deployment type

- **WHEN** authenticated user sends GET request to `/api/v1/deployments/invalid-type/{id}`
- **THEN** system returns HTTP 400 Bad Request with error message listing valid types

#### Scenario: Deployment not found

- **WHEN** authenticated user sends GET request to `/api/v1/deployments/dial-model/{id}`
- **AND** DIAL Core returns HTTP 404
- **THEN** system returns HTTP 404 Not Found
