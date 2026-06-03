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

## Implementation Notes
- SpringDoc OpenAPI (springdoc-openapi-starter-webmvc-ui) generates spec from annotations. DTO-level examples use @Schema(example = …). Operation-level request/response examples (minimal + full) are stored as JSON files under `src/main/resources/openapi/examples/` and injected into the spec by `OpenApiExampleCustomizer`. File naming: `{pathKey}-{method}-request-{name}.json` or `{pathKey}-{method}-response-{status}-{name}.json` (pathKey = path with '/' → '-', braces stripped; name = "minimal" or "full"). Controllers declare only schema in @Content/@RequestBody; the customizer adds examples from classpath.
- OpenSpec global rule in openspec/config.yaml (rules.global) and this spec together define the "update examples on change" requirement for enforcement by AI agents and reviewers.
- AGENTS.md documents the conventions, the minimal+full rule with the simple-endpoint exception, and the resource-based example location/naming.
