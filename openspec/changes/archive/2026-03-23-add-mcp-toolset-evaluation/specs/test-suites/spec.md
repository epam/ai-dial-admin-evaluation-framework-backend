# Test Suites — MCP Extension (Delta)

## ADDED Requirements

### Requirement: Suite type discriminator

The `test_suites` table SHALL have a `suite_type VARCHAR(20) NOT NULL DEFAULT 'DEPLOYMENT'` column that discriminates between HTTP deployment suites and MCP tool suites. Valid values: `DEPLOYMENT`, `MCP_TOOL`.

#### Scenario: Existing suites default to DEPLOYMENT
- **WHEN** the migration runs on an existing database with test suites
- **THEN** all existing suites SHALL have `suite_type = 'DEPLOYMENT'`

#### Scenario: Create HTTP suite without specifying type
- **WHEN** client calls `POST /api/v1/test-suites` without `suiteType` field
- **THEN** the system SHALL default `suiteType` to `DEPLOYMENT`

#### Scenario: Create MCP suite with explicit type
- **WHEN** client calls `POST /api/v1/test-suites` with `"suiteType": "MCP_TOOL"`
- **THEN** the system SHALL create a suite with `suite_type = 'MCP_TOOL'`

#### Scenario: Suite type is immutable after creation
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with a different `suiteType` than the existing suite
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that suite type cannot be changed

### Requirement: MCP deployment reference on MCP suites

MCP test suites SHALL store a `mcp_deployment_ref JSONB` containing the MCP-capable deployment metadata: `id` (required), `type` (required — `dial-toolset` or `dial-application`), `name` (optional), `transport` (optional). This reference can point to either a DIAL toolset or an application with MCP interface.

#### Scenario: MCP suite with mcpDeploymentRef
- **WHEN** client creates an MCP suite with `mcpDeploymentRef: {"id": "confluence-search", "type": "dial-toolset", "name": "Confluence Search"}`
- **THEN** the system SHALL persist the reference and return it in responses

#### Scenario: mcpDeploymentRef required for MCP suites
- **WHEN** client creates or updates an MCP suite without `mcpDeploymentRef`
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: MCP suite with application mcpDeploymentRef
- **WHEN** client creates an MCP suite with `mcpDeploymentRef: {"id": "text-classifier", "type": "dial-application", "name": "Text Classifier"}`
- **THEN** the system SHALL persist the reference (applications with MCP interface use the same invocation path as toolsets)

#### Scenario: mcpDeploymentRef null for DEPLOYMENT suites
- **WHEN** client creates a DEPLOYMENT suite with `mcpDeploymentRef` field present
- **THEN** the system SHALL ignore the field (DEPLOYMENT suites do not use mcpDeploymentRef)

### Requirement: Tool reference on MCP suites

MCP test suites SHALL store a `tool_ref JSONB` containing the selected tool's metadata: `name` (required), `description` (optional), `inputSchema` (Map, required), `outputSchema` (Map, nullable).

#### Scenario: MCP suite with toolRef
- **WHEN** client creates an MCP suite with `toolRef: {"name": "confluence_search", "inputSchema": {...}}`
- **THEN** the system SHALL persist the reference and return it in responses

#### Scenario: toolRef required for MCP suites
- **WHEN** client creates or updates an MCP suite without `toolRef`
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: toolRef includes inputSchema
- **WHEN** client creates an MCP suite with toolRef
- **THEN** `toolRef.inputSchema` SHALL be present (used for argument form generation and validation)

#### Scenario: toolRef includes optional outputSchema
- **WHEN** the selected tool has an `outputSchema`
- **THEN** client SHALL include it in `toolRef.outputSchema` (used for response column suggestions)

### Requirement: Argument template on MCP suites

MCP test suites SHALL store an `argument_template JSONB` containing the tool call argument template with `${{variable}}` placeholders and constant values. This is the MCP equivalent of `requestTemplate` for HTTP suites.

#### Scenario: MCP suite with argumentTemplate
- **WHEN** client creates an MCP suite with `argumentTemplate: {"arguments": {"query": "${{search_query}}", "limit": 10}}`
- **THEN** the system SHALL persist the template and return it in responses

#### Scenario: argumentTemplate structure
- **WHEN** an argument template is provided
- **THEN** it SHALL contain an `arguments` map where values are either constants or `${{variable}}` / `${{variable:default}}` placeholders

#### Scenario: argumentTemplate null for DEPLOYMENT suites
- **WHEN** client creates a DEPLOYMENT suite with `argumentTemplate` field present
- **THEN** the system SHALL ignore the field

### Requirement: Type-specific field validation

The system SHALL validate that suites have the correct fields for their type.

#### Scenario: DEPLOYMENT suite follows existing soft-validation pattern
- **WHEN** client creates a DEPLOYMENT suite
- **THEN** existing validation rules SHALL apply unchanged: only `deploymentRef` is hard-required (HTTP 400 if absent); `endpointRef` and `requestTemplate` follow the existing soft-validation pattern (null produces `isValid = false` with validation warnings, not HTTP 400)

#### Scenario: DEPLOYMENT suite ignores MCP fields
- **WHEN** client creates a DEPLOYMENT suite with `mcpDeploymentRef`, `toolRef`, or `argumentTemplate`
- **THEN** the system SHALL ignore these fields (not persist them)

#### Scenario: MCP_TOOL suite requires mcpDeploymentRef and toolRef
- **WHEN** client creates an MCP_TOOL suite without `mcpDeploymentRef` or `toolRef`
- **THEN** the system SHALL return HTTP 400

#### Scenario: MCP_TOOL suite ignores HTTP fields
- **WHEN** client creates an MCP_TOOL suite with `deploymentRef`, `endpointRef`, or `requestTemplate`
- **THEN** the system SHALL ignore these fields (not persist them)

#### Scenario: MCP suite validation — argumentTemplate warning
- **WHEN** an MCP_TOOL suite has `argumentTemplate: null`
- **THEN** `isValid` SHALL be `false` and `validationWarnings` SHALL include a warning indicating argument template is recommended for tool evaluation

### Requirement: Suite response includes type and MCP fields

The `TestSuiteResponseDto` SHALL include `suiteType` and the MCP-specific fields when applicable.

#### Scenario: DEPLOYMENT suite response
- **WHEN** client retrieves a DEPLOYMENT suite
- **THEN** the response SHALL include `"suiteType": "DEPLOYMENT"` and the existing HTTP fields (`deploymentRef`, `endpointRef`, `requestTemplate`)
- **AND** MCP fields SHALL be null/absent

#### Scenario: MCP_TOOL suite response
- **WHEN** client retrieves an MCP_TOOL suite
- **THEN** the response SHALL include `"suiteType": "MCP_TOOL"`, `mcpDeploymentRef`, `toolRef`, `argumentTemplate`
- **AND** HTTP fields SHALL be null/absent

### Requirement: Suite list filtering by type

The list endpoint SHALL support filtering by suite type.

#### Scenario: Filter by DEPLOYMENT type
- **WHEN** client calls `GET /api/v1/test-suites?filter=suiteType:eq:DEPLOYMENT`
- **THEN** the system SHALL return only DEPLOYMENT suites

#### Scenario: Filter by MCP_TOOL type
- **WHEN** client calls `GET /api/v1/test-suites?filter=suiteType:eq:MCP_TOOL`
- **THEN** the system SHALL return only MCP_TOOL suites

#### Scenario: No filter returns all types
- **WHEN** client calls `GET /api/v1/test-suites` without suiteType filter
- **THEN** the system SHALL return both DEPLOYMENT and MCP_TOOL suites

## MODIFIED Requirements

### Requirement: Create a TestSuite
The service SHALL allow creating a new TestSuite. The request body SHALL accept `suiteType` (optional, defaults to `DEPLOYMENT`). For `DEPLOYMENT` suites: `testCaseSchema`, `requestTemplate`, `inputBindings`, `deploymentRef`, `endpointRef` (existing behavior — `deploymentRef` hard-required, `endpointRef`/`requestTemplate` soft-validated). For `MCP_TOOL` suites: `testCaseSchema`, `inputBindings`, `mcpDeploymentRef` (hard-required), `toolRef` (hard-required), `argumentTemplate` (soft-validated — null produces warning). The system SHALL perform type-specific validation and suite-level soft validation.
Status: **Planned**

#### Scenario: Valid DEPLOYMENT payload
- **WHEN** client calls `POST /api/v1/test-suites` with a valid body including `deploymentRef`, `testCaseSchema`, `requestTemplate`, and `inputBindings` (see "Type-specific field validation" requirement for `deploymentRef` hard-requirement and `endpointRef`/`requestTemplate` soft-validation rules)
- **THEN** system SHALL create a new TestSuite with `suiteType = DEPLOYMENT`, perform suite-level soft validation, and return the created entity including `isValid` and `validationWarnings`

#### Scenario: Valid MCP_TOOL payload
- **WHEN** client calls `POST /api/v1/test-suites` with `"suiteType": "MCP_TOOL"`, valid `mcpDeploymentRef`, `toolRef`, `testCaseSchema`, and `inputBindings`
- **THEN** system SHALL create a new TestSuite with `suiteType = MCP_TOOL`, perform MCP-specific validation, and return the created entity

#### Scenario: CreatedBy attribution
- **WHEN** `config.rest.security.mode=oidc` and an authenticated client creates a TestSuite
- **THEN** system SHALL store `createdBy` from JWT subject

#### Scenario: Missing author is rejected in OIDC mode
- **WHEN** `config.rest.security.mode=oidc` and a request is not authenticated (no user detected)
- **THEN** system SHALL reject the request with HTTP 401

#### Scenario: Anonymous author allowed only in no-security mode
- **WHEN** `config.rest.security.mode=none` and an unauthenticated client creates a TestSuite
- **THEN** system SHALL store `createdBy` as `anonymous`

#### Scenario: Embedded deployment and endpoint references (DEPLOYMENT only)
- **WHEN** client calls `POST /api/v1/test-suites` with `suiteType = DEPLOYMENT` and valid `deploymentRef` and `endpointRef`
- **THEN** system SHALL persist those objects as part of the TestSuite and return them in the response

### Requirement: Update a TestSuite
The service SHALL allow updating an existing TestSuite by id. Suite type SHALL NOT be changeable on update. When type-specific config fields change, the system SHALL trigger re-validation of existing TestCases.
Status: **Planned**

#### Scenario: Existing id
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with a valid body
- **THEN** system SHALL update the existing TestSuite, recalculate suite-level `isValid` and `validationWarnings`, and return the updated entity

#### Scenario: Missing id
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` for a non-existent TestSuite
- **THEN** system SHALL respond with HTTP 404

#### Scenario: Suite type change rejected
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with a `suiteType` different from the existing suite
- **THEN** system SHALL respond with HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Update MCP suite toolRef triggers re-validation
- **WHEN** client updates `toolRef` of an MCP_TOOL suite (different inputSchema)
- **THEN** system SHALL trigger re-validation of existing TestCases against the new tool's inputSchema

#### Scenario: Update MCP suite argumentTemplate triggers re-validation
- **WHEN** client updates `argumentTemplate` of an MCP_TOOL suite
- **THEN** system SHALL trigger re-validation (argument variables re-extracted, bindings re-checked)

#### Scenario: Update embedded refs (DEPLOYMENT only)
- **WHEN** client updates `deploymentRef` and/or `endpointRef` of an existing DEPLOYMENT TestSuite
- **THEN** system SHALL persist the updated embedded objects, recalculate `isValid` and `validationWarnings`, trigger re-validation of existing TestCases, and return the updated suite

#### Scenario: Update testCaseSchema triggers re-validation
- **WHEN** client updates `testCaseSchema` of an existing TestSuite (any type)
- **THEN** system SHALL trigger re-validation of existing TestCases against the new schema

#### Scenario: Update inputBindings triggers re-validation
- **WHEN** client updates `inputBindings` of an existing TestSuite (any type)
- **THEN** system SHALL trigger re-validation of TestCases (required fields re-checked)

## Implementation Notes
- Flyway migration: `V{next}__AddMcpFieldsToTestSuites.sql` — adds `suite_type`, `mcp_deployment_ref`, `tool_ref`, `argument_template` columns
- Modified model: `TestSuite` — add `suiteType`, `mcpDeploymentRef`, `toolRef`, `argumentTemplate` fields
- Modified DTOs: `TestSuiteRequestDto`, `TestSuiteResponseDto` — add new fields with type-specific validation
- Modified mapper: `TestSuiteMapper` — map new fields
- Modified repository: `PostgresTestSuiteRepository` — include new columns in SELECT/INSERT/UPDATE
- Modified service: `TestSuiteService` — type-specific validation and field handling
- FilterWhitelists: add `suiteType` to test suite filter whitelist
- `docs/database-schema.md` must be updated
