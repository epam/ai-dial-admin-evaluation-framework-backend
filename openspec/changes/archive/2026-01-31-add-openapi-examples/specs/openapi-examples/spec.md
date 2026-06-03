# OpenAPI Examples

## Purpose
This spec defines how request and response examples are added and maintained in the OpenAPI spec so FE, other BE, and BA have concrete payloads in Swagger UI and the exported spec.

Status: **Planned** (implementation in progress via change add-openapi-examples)

## Key Terms
- **Minimal example**: Request example with only required fields (or minimal set needed to succeed).
- **Fully filled example**: Request example with all (or most) fields populated to show full structure.
- **Simple endpoint**: Endpoint with no parameters or only very simple parameters/body (e.g. single optional query param or trivial body).

## ADDED Requirements

### Requirement: Endpoints SHALL have at least two request examples when applicable
The system SHALL expose at least two request examples per endpoint—one minimal, one fully filled—when the endpoint has non-trivial request parameters or body. This rule MAY be skipped for endpoints with no parameters or only very simple parameters/body (e.g. a single optional query param or a trivial body).
Status: **Planned**

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
Status: **Planned**

#### Scenario: DTO field added or removed
- **WHEN** a request or response DTO is modified (field added, removed, or renamed)
- **THEN** any @Schema example or @ExampleObject that uses that DTO SHALL be updated to match the new structure

#### Scenario: Endpoint parameters changed
- **WHEN** query parameters, path parameters, or request body contract of an endpoint are changed
- **THEN** request/response examples for that endpoint (JSON files under openapi/examples/) SHALL be updated so they remain valid and representative

#### Scenario: AI agent or developer changes endpoint
- **WHEN** an AI agent or developer modifies an endpoint or its request/response
- **THEN** they SHALL update existing OpenAPI examples for that endpoint as part of the same change

## Implementation Notes
- SpringDoc OpenAPI (springdoc-openapi-starter-webmvc-ui) generates spec from annotations; examples are added via @Schema(example = …) and @Content(examples = @ExampleObject(...)).
- OpenSpec global rule in openspec/config.yaml (rules.global) and this spec together define the “update examples on change” requirement for enforcement by AI agents and reviewers.
- AGENTS.md documents the conventions, the minimal+full rule with the simple-endpoint exception, and the resource-based example location/naming.
