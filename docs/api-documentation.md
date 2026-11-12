# API documentation

The backend exposes an OpenAPI 3 spec and Swagger UI for consumers (FE, other BE, BA).

## Where to find the spec

| Artifact | URL (when app is running) |
|----------|---------------------------|
| **Swagger UI** | `http://localhost:8080/swagger-ui.html` |
| **OpenAPI JSON** | `http://localhost:8080/v3/api-docs` |

Use the JSON URL to import the spec into Postman, Insomnia, or code generators.

## Request and response examples

Most CRUD endpoints (test suites, datasets, test cases, metric definitions/declarations,
runs, costs, deployments) carry `minimal` and `full` request/response examples. Coverage is
not complete: e.g. the Query DSL endpoints (`/api/v1/queries/**`), health/actuator and the
run status SSE stream (`/api/v1/test-suite-runs/status-stream`) have none. When an operation has no example, rely on the DTO-level
`@Schema(example = ...)` values shown in the schema.

Operation-level examples are JSON files under `src/main/resources/openapi/examples/`,
injected into the spec by `OpenApiExampleCustomizer`. File names:

- `{pathKey}-{METHOD}-request-{name}.json`
- `{pathKey}-{METHOD}-response-{status}-{name}.json`

`pathKey` is the registered path with a trailing `/**` dropped, `/` replaced by `-` and
path-variable braces stripped (`/api/v1/deployments/all/**` → `api-v1-deployments-all`).
`METHOD` is upper case. `{name}` must be one of `OpenApiExampleCustomizer.EXAMPLE_NAMES`:
`minimal`, `full`, `multi-turn`, `mcp`, `route`, `sse`, `subset`, `with-bodies`, `jsonata-body`,
`chained`, `skill`, `cancelling`, `missing-file-warning`. A misnamed file fails silently (no example
appears), and `OpenApiExamplesFunctionalTests` fails the build for any file that is not injected.
Examples are injected only into `application/json` content. Responses without a declared type
(including exception-handler errors, which use the `ErrorView` shape) are documented as JSON via
`springdoc.default-produces-media-type`; a request example is injected only when the request body
has `application/json` content (multipart / CSV uploads get none). Full rules:
[openapi-examples spec](../openspec/specs/openapi-examples/spec.md).

## Query DSL

Ad-hoc analytics queries go through `POST /api/v1/queries/execute`; available entities and
their fields come from `GET /api/v1/queries/entities`,
`GET /api/v1/queries/entities/schema/{name}` and `.../schema/{name}/detailed`. Model
reference: [docs/query-dsl/](query-dsl/structured-query-model-v8.html). A browser client
lives in [local_env/query-console](../local_env/query-console/README.md).
