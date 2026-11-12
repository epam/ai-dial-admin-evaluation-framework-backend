# OpenAPI Examples

## Purpose
This spec defines how request and response examples are added and maintained in the OpenAPI spec so FE, other BE, and BA have concrete payloads in Swagger UI and the exported spec.

Status: **Implemented**

## Key Terms
- **Minimal example**: Request example with only required fields (or minimal set needed to succeed).
- **Fully filled example**: Request example with all (or most) fields populated to show full structure.
- **Simple endpoint**: Endpoint with no parameters or only very simple parameters/body (e.g. single optional query param or trivial body).

## Requirements

### Requirement: Endpoints SHALL have at least two request examples when applicable
The system SHALL expose at least two request examples per endpoint—one minimal, one fully filled—when the endpoint has non-trivial request parameters or body. This rule MAY be skipped for endpoints with no parameters or only very simple parameters/body (e.g. a single optional query param or a trivial body).
Status: **Implemented**

#### Scenario: Endpoint with request body
- **WHEN** an endpoint accepts a request body (e.g. POST/PUT with DTO)
- **THEN** the OpenAPI spec SHALL include at least two request examples: one minimal (required fields only) and one fully filled (all or most fields)

#### Scenario: Endpoint with query/path params
- **WHEN** an endpoint has multiple query or path parameters that affect behavior
- **THEN** the OpenAPI spec SHALL include at least two request examples (minimal and fully filled) where it helps consumers

#### Scenario: Simple endpoint exception
- **WHEN** an endpoint has no parameters or only a single trivial parameter/body
- **THEN** the system MAY omit the second example or provide a single example; the minimal+full rule does not apply

### Requirement: Existing examples SHALL be updated when endpoint or request/response changes
When an API endpoint or its request/response definitions (DTOs, query params, path params) are changed, existing OpenAPI examples (DTO @Schema example, JSON files under openapi/examples/, etc.) SHALL be updated to reflect the new contract. AI agents and developers MUST keep examples in sync with the API so consumers (FE, other BE, BA) always see accurate payloads.
Status: **Implemented**

#### Scenario: DTO field added or removed
- **WHEN** a request or response DTO is modified (field added, removed, or renamed)
- **THEN** any @Schema example on that DTO and any JSON example file for affected endpoints SHALL be updated to match the new structure

#### Scenario: Endpoint parameters changed
- **WHEN** query parameters, path parameters, or request body contract of an endpoint are changed
- **THEN** request/response examples for that endpoint (JSON files under openapi/examples/) SHALL be updated so they remain valid and representative

#### Scenario: AI agent or developer changes endpoint
- **WHEN** an AI agent or developer modifies an endpoint or its request/response
- **THEN** they SHALL update existing OpenAPI examples for that endpoint as part of the same change

### Requirement: OpenAPI example JSON files SHALL be pretty-formatted
JSON files under `src/main/resources/openapi/examples/` SHALL be stored in a human-readable, pretty-formatted form. Each logical element (key-value pair, array element) SHALL appear on its own line with consistent indentation so that diffs and code review remain readable. One-line minified JSON SHALL NOT be used for these example files.
Status: **Implemented**

#### Scenario: New or updated example file
- **WHEN** an AI agent or developer adds or updates a JSON file under `openapi/examples/`
- **THEN** the file SHALL be written with pretty formatting (e.g. 2- or 4-space indentation, newline after opening `{`/`[` and before closing `}`/`]`, one property or array element per line)
- **AND** the file SHALL NOT be a single minified line unless the payload is trivial (e.g. a single short string or number)

#### Scenario: Consistency with existing examples
- **WHEN** existing example JSON files in the repo are already pretty-formatted
- **THEN** new or edited example files SHALL follow the same style so that the examples directory remains consistently readable

### Requirement: Example files SHALL resolve for trailing-wildcard endpoint mappings

Endpoints whose mapping ends in a trailing wildcard (used for path values that may themselves contain slashes, e.g. DIAL Core deployment IDs) are registered in the OpenAPI document with the `/**` intact. Example files SHALL still be injected for such operations: the example-file key SHALL be derived from the registered path with a trailing `/**` **dropped**, so that no example filename ever contains `*` (illegal in a Windows filename and hostile to shell globbing).

Concretely, `/api/v1/deployments/all/**` SHALL resolve examples named `api-v1-deployments-all-{method}-response-{status}-{name}.json`, and `/api/v1/deployments/{deploymentType}/**` SHALL resolve `api-v1-deployments-deploymentType-{method}-response-{status}-{name}.json`.

Because a mismatched key fails silently — the customizer simply finds no resource and the operation ships with no examples — at least one endpoint using a trailing-wildcard mapping SHALL be covered by a test asserting its examples are present in the generated OpenAPI document.

Status: **Implemented**

Implementation notes:
- Key derivation: `configuration.OpenApiExampleCustomizer#pathToKey` strips a trailing `/**` before the existing `/`→`-` and brace-stripping transforms.
- Regression cover: `DeploymentFunctionalTests#openApiSpecCarriesByIdLookupExamples` reads `/v3/api-docs` and asserts the `minimal` and `full` examples are injected for `/api/v1/deployments/all/**`.

#### Scenario: Examples resolve for a wildcard mapping
- **WHEN** an endpoint is mapped with a trailing `/**` and example files exist under the key formed by dropping that wildcard
- **THEN** the generated OpenAPI document SHALL carry those examples on the operation

#### Scenario: No example filename contains a wildcard character
- **WHEN** example files are added for a trailing-wildcard endpoint
- **THEN** their filenames SHALL contain no `*` character

#### Scenario: Silent example loss is guarded by a test
- **WHEN** a mapping's path shape changes such that its example key no longer matches its files
- **THEN** the test asserting examples in the generated OpenAPI document SHALL fail, rather than the endpoint silently shipping without examples

### Requirement: Responses SHALL be documented as application/json unless they declare another type

`OpenApiExampleCustomizer` attaches examples only to an operation's `application/json` media type (`OpenApiExampleCustomizer.MEDIA_TYPE_JSON`). The application SHALL set `springdoc.default-produces-media-type: application/json`, so every response that declares no content type — including the error responses produced by the exception handler (`ErrorView`) — is documented under `application/json` and can receive examples. The setting SHALL affect the generated document only; it SHALL NOT be replaced by adding `produces` to mappings purely for documentation, because `produces` also restricts runtime content negotiation (a non-JSON `Accept` gets 406). An operation whose body is not JSON (SSE, CSV/ZIP export, file download) SHALL declare its real media type explicitly (`produces` or `@ApiResponse(content = @Content(mediaType = ...))`) so it is not documented as JSON.

Status: **Implemented**

Implementation notes:
- `springdoc.default-produces-media-type` is set in `application.yml`; `FileController`'s download declares `application/octet-stream` on its 200 response.
- Mappings that already declare `produces = MediaType.APPLICATION_JSON_VALUE` (e.g. `RunMetricSnapshotController`) keep it; it is no longer required for examples to attach.

#### Scenario: Response without declared content type receives examples
- **WHEN** a controller method returns a JSON body, its mapping declares no `produces`, and a correctly named example file exists
- **THEN** the generated document SHALL list the response under `application/json` and `OpenApiExampleCustomizer` SHALL attach the example

#### Scenario: Error response receives examples
- **WHEN** an operation's error status (e.g. 409) is produced by the exception handler and a correctly named `...-response-409-{name}.json` file exists
- **THEN** the example SHALL attach under `application/json`, and its content SHALL follow the `ErrorView` shape (`path`, `method`, `status`, `code`, `error`, `message`)

#### Scenario: Non-JSON body keeps its own media type
- **WHEN** an operation streams a file, CSV or SSE body
- **THEN** it SHALL declare that media type explicitly and SHALL NOT be documented as `application/json`

### Requirement: Every example file SHALL be injected

Every file under `src/main/resources/openapi/examples/` SHALL resolve to an example in the generated document. A functional test SHALL fetch `/v3/api-docs` and fail, naming the file, when any example file is not injected (wrong path, method, status, name suffix or media type), so a stale or misnamed file cannot ship silently.

Status: **Implemented**

#### Scenario: Stale example file fails the build
- **WHEN** an endpoint moves or changes its status codes and its example file is left behind
- **THEN** the example-coverage functional test SHALL fail and list that file

## Implementation Notes

- SpringDoc OpenAPI (springdoc-openapi-starter-webmvc-ui) generates spec from annotations. DTO-level examples use @Schema(example = …). Operation-level request/response examples (minimal + full) are stored as JSON files under `src/main/resources/openapi/examples/` and injected into the spec by `OpenApiExampleCustomizer`. File naming: `{pathKey}-{method}-request-{name}.json` or `{pathKey}-{method}-response-{status}-{name}.json` (pathKey = registered path with a trailing '/**' dropped, then '/' → '-', braces stripped; name = "minimal" or "full"). Controllers declare only schema in @Content/@RequestBody; the customizer adds examples from classpath.
- OpenSpec global rule in openspec/config.yaml (rules.global) and this spec together define the "update examples on change" requirement for enforcement by AI agents and reviewers.
- AGENTS.md documents the conventions, the minimal+full rule with the simple-endpoint exception, and the resource-based example location/naming.
