## MODIFIED Requirements

### Requirement: Suite type discriminator

The `test_suites` table SHALL have a `suite_type VARCHAR(20) NOT NULL DEFAULT 'DEPLOYMENT'` column that discriminates between HTTP deployment suites, MCP tool suites and global DIAL Core Route suites. Valid values: `DEPLOYMENT`, `MCP_TOOL`, `ROUTE`.
Status: **Implemented**

#### Scenario: Existing suites default to DEPLOYMENT
- **WHEN** the migration runs on an existing database with test suites
- **THEN** all existing suites SHALL have `suite_type = 'DEPLOYMENT'`

#### Scenario: Create HTTP suite without specifying type
- **WHEN** client calls `POST /api/v1/test-suites` without `suiteType` field
- **THEN** the system SHALL default `suiteType` to `DEPLOYMENT`

#### Scenario: Create MCP suite with explicit type
- **WHEN** client calls `POST /api/v1/test-suites` with `"suiteType": "MCP_TOOL"`
- **THEN** the system SHALL create a suite with `suite_type = 'MCP_TOOL'`

#### Scenario: Create ROUTE suite with explicit type
- **WHEN** client calls `POST /api/v1/test-suites` with `"suiteType": "ROUTE"` and a valid ROUTE payload (see "Type-specific field validation")
- **THEN** the system SHALL create a suite with `suite_type = 'ROUTE'` and return `"suiteType": "ROUTE"` in the 201 response

#### Scenario: Suite type is immutable after creation
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with a different `suiteType` than the existing suite
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that suite type cannot be changed
- **AND** this rule applies equally to ROUTE suites (a DEPLOYMENT or MCP_TOOL suite cannot become ROUTE, and a ROUTE suite cannot become either)

### Requirement: Type-specific field validation

The system SHALL validate that suites have the correct fields for their type. For `ROUTE` suites the validation is stricter than for the other types: `routesRef`, `endpointRef` and `requestTemplate` are **hard-required**, and the DEPLOYMENT/MCP-specific fields are **rejected** (HTTP 400) rather than ignored. `routesRef` on a `DEPLOYMENT` or `MCP_TOOL` suite is likewise rejected. All violations are HTTP 400 with error code `VALIDATION_ERROR`; for a ROUTE suite the checks run in this order and the first violation is reported: `routesRef`, `endpointRef`, `requestTemplate` required; then `deploymentRef`, `mcpDeploymentRef`, `toolRef`, `argumentTemplate` must be empty (bean validation of `routesRef.name` runs before all of them).
Status: **Implemented**

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

#### Scenario: ROUTE suite requires routesRef
- **WHEN** client creates a ROUTE suite without `routesRef`
- **THEN** the system SHALL return HTTP 400 `VALIDATION_ERROR` with message `routesRef is required for ROUTE suites`

#### Scenario: ROUTE suite requires endpointRef
- **WHEN** client creates a ROUTE suite with `routesRef` but without `endpointRef`
- **THEN** the system SHALL return HTTP 400 `VALIDATION_ERROR` with message `endpointRef is required for ROUTE suites`

#### Scenario: ROUTE suite requires requestTemplate
- **WHEN** client creates a ROUTE suite with `routesRef` and `endpointRef` but without `requestTemplate`
- **THEN** the system SHALL return HTTP 400 `VALIDATION_ERROR` with message `requestTemplate is required for ROUTE suites`

#### Scenario: ROUTE suite rejects deploymentRef
- **WHEN** client creates or updates a ROUTE suite with a non-null `deploymentRef`
- **THEN** the system SHALL return HTTP 400 `VALIDATION_ERROR` with message `deploymentRef must be empty for ROUTE suites`

#### Scenario: ROUTE suite rejects mcpDeploymentRef
- **WHEN** client creates or updates a ROUTE suite with a non-null `mcpDeploymentRef`
- **THEN** the system SHALL return HTTP 400 `VALIDATION_ERROR` with message `mcpDeploymentRef must be empty for ROUTE suites`

#### Scenario: ROUTE suite rejects toolRef
- **WHEN** client creates or updates a ROUTE suite with a non-null `toolRef`
- **THEN** the system SHALL return HTTP 400 `VALIDATION_ERROR` with message `toolRef must be empty for ROUTE suites`

#### Scenario: ROUTE suite rejects argumentTemplate
- **WHEN** client creates or updates a ROUTE suite with a non-null `argumentTemplate`
- **THEN** the system SHALL return HTTP 400 `VALIDATION_ERROR` with message `argumentTemplate must be empty for ROUTE suites`

#### Scenario: routesRef rejected on DEPLOYMENT suite
- **WHEN** client creates or updates a DEPLOYMENT suite with a non-null `routesRef`
- **THEN** the system SHALL return HTTP 400 `VALIDATION_ERROR` with message `routesRef must be empty for DEPLOYMENT suites`

#### Scenario: routesRef rejected on MCP_TOOL suite
- **WHEN** client creates or updates an MCP_TOOL suite with a non-null `routesRef`
- **THEN** the system SHALL return HTTP 400 `VALIDATION_ERROR` with message `routesRef must be empty for MCP_TOOL suites`

#### Scenario: ROUTE suite accepts the HTTP request contract
- **WHEN** client creates a ROUTE suite with `routesRef`, `endpointRef`, `requestTemplate` and any of `inputBindings`, `responseColumns`, `additionalRequests`, `requestName`
- **THEN** the system SHALL accept the request, persist those fields and return them in the response (a non-empty `additionalRequests` is allowed on ROUTE suites; only MCP_TOOL suites reject it)

#### Scenario: ROUTE soft validation follows the HTTP path
- **WHEN** a ROUTE suite is created or updated with a well-formed request template, bindings and endpoint schema
- **THEN** suite-level soft validation SHALL run the same HTTP checks as for a DEPLOYMENT suite (template variables, bindings against the dataset schema, endpoint schema, request chain), with `isValid` and `validationWarnings` computed from them
- **AND** the static request-model check for `/openai/v1/responses` and `/anthropic/v1/messages` SHALL produce no warning, because a ROUTE suite has no `deploymentRef` to compare against

### Requirement: Suite response includes type and MCP fields

The `TestSuiteResponseDto` SHALL include `suiteType`, the MCP-specific fields when applicable, and `routesRef` (a `{ name }` object, non-null only for ROUTE suites).
Status: **Implemented**

#### Scenario: DEPLOYMENT suite response
- **WHEN** client retrieves a DEPLOYMENT suite
- **THEN** the response SHALL include `"suiteType": "DEPLOYMENT"` and the existing HTTP fields (`deploymentRef`, `endpointRef`, `requestTemplate`)
- **AND** MCP fields SHALL be null/absent
- **AND** `routesRef` SHALL be null/absent

#### Scenario: MCP_TOOL suite response
- **WHEN** client retrieves an MCP_TOOL suite
- **THEN** the response SHALL include `"suiteType": "MCP_TOOL"`, `mcpDeploymentRef`, `toolRef`, `argumentTemplate`
- **AND** HTTP fields SHALL be null/absent
- **AND** `routesRef` SHALL be null/absent

#### Scenario: ROUTE suite response
- **WHEN** client retrieves a ROUTE suite (`GET /api/v1/test-suites/{id}`, or as a list item)
- **THEN** the response SHALL include `"suiteType": "ROUTE"`, `routesRef`, `endpointRef`, `requestTemplate` and the other HTTP configuration fields (`inputBindings`, `responseColumns`, `requestName`, `additionalRequests`)
- **AND** `deploymentRef` SHALL be null/absent
- **AND** MCP fields (`mcpDeploymentRef`, `toolRef`, `argumentTemplate`) SHALL be null/absent

#### Scenario: DEPLOYMENT response has null routesRef
- **WHEN** client retrieves a DEPLOYMENT suite
- **THEN** `routesRef` SHALL be null/absent in the response

### Requirement: Suite list filtering by type

The list endpoint SHALL support filtering by suite type.
Status: **Implemented**

#### Scenario: Filter by DEPLOYMENT type
- **WHEN** client calls `GET /api/v1/test-suites?filter=suiteType:eq:DEPLOYMENT`
- **THEN** the system SHALL return only DEPLOYMENT suites

#### Scenario: Filter by MCP_TOOL type
- **WHEN** client calls `GET /api/v1/test-suites?filter=suiteType:eq:MCP_TOOL`
- **THEN** the system SHALL return only MCP_TOOL suites

#### Scenario: Filter by ROUTE type
- **WHEN** client calls `GET /api/v1/test-suites?filter=suiteType:eq:ROUTE`
- **THEN** the system SHALL return only ROUTE suites

#### Scenario: No filter returns all types
- **WHEN** client calls `GET /api/v1/test-suites` without suiteType filter
- **THEN** the system SHALL return DEPLOYMENT, MCP_TOOL and ROUTE suites

### Requirement: Create a TestSuite
The service SHALL allow creating a new TestSuite. The request body SHALL accept `suiteType` (optional, defaults to `DEPLOYMENT`) and `datasetId` (required — FK to `datasets.id`). For `DEPLOYMENT` suites: `requestTemplate`, `inputBindings`, `deploymentRef`, `endpointRef` (existing behavior — `deploymentRef` hard-required, `endpointRef`/`requestTemplate` soft-validated). For `MCP_TOOL` suites: `inputBindings`, `mcpDeploymentRef` (hard-required), `toolRef` (hard-required), `argumentTemplate` (soft-validated — null produces warning). For `ROUTE` suites: `routesRef` (hard-required), `endpointRef` and `requestTemplate` (both hard-required), `inputBindings`, `responseColumns`, `additionalRequests`, `requestName` (see "Type-specific field validation" and "Route reference on ROUTE suites"). `testCaseSchema` SHALL NOT appear on the suite request — it lives on the referenced dataset. `disabledTestCaseIds` SHALL NOT appear on the suite request — the runnable subset is narrowed by `testCaseFilter` (see `suite-test-case-filter`). The system SHALL perform type-specific validation and suite-level soft validation, sourcing the dataset's schema via `DatasetSchemaProvider` for binding cross-checks. Additionally, the system SHALL support cloning an existing suite via `POST /api/v1/test-suites/{sourceId}/clone` (see `test-suite-clone` spec).
Status: **Implemented**

#### Scenario: Valid DEPLOYMENT payload
- **WHEN** client calls `POST /api/v1/test-suites` with a valid body including `datasetId`, `deploymentRef`, `requestTemplate`, and `inputBindings` (see "Type-specific field validation" requirement for `deploymentRef` hard-requirement and `endpointRef`/`requestTemplate` soft-validation rules)
- **THEN** system SHALL create a new TestSuite with `suiteType = DEPLOYMENT`, perform suite-level soft validation against the dataset's schema, and return the created entity including `isValid` and `validationWarnings`

#### Scenario: Valid MCP_TOOL payload
- **WHEN** client calls `POST /api/v1/test-suites` with `"suiteType": "MCP_TOOL"`, valid `datasetId`, `mcpDeploymentRef`, `toolRef`, and `inputBindings`
- **THEN** system SHALL create a new TestSuite with `suiteType = MCP_TOOL`, perform MCP-specific validation against the dataset's schema, and return the created entity

#### Scenario: Valid ROUTE payload
- **WHEN** client calls `POST /api/v1/test-suites` with `"suiteType": "ROUTE"`, valid `datasetId`, `routesRef`, `endpointRef`, `requestTemplate` and optional `inputBindings`
- **THEN** system SHALL create a new TestSuite with `suiteType = ROUTE`, perform suite-level soft validation against the dataset's schema, and return HTTP 201 with the created entity including `routesRef`, `isValid` and `validationWarnings`, and a null `deploymentRef`

#### Scenario: testCaseSchema in body is rejected or ignored
- **WHEN** client sends a create body containing a `testCaseSchema` field
- **THEN** system SHALL ignore the field (per Jackson default) or respond with HTTP 400 if strict-binding is enabled; in either case the field SHALL NOT influence the persisted suite (schema is owned by the dataset)

#### Scenario: disabledTestCaseIds in body is ignored
- **WHEN** client sends a create or update body containing a `disabledTestCaseIds` field
- **THEN** system SHALL ignore the unknown field (unknown-property failure is disabled) and SHALL respond with the normal success status; the field SHALL NOT appear in the response and SHALL NOT influence which test cases the suite runs

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

#### Scenario: Clone via dedicated endpoint
- **WHEN** client calls `POST /api/v1/test-suites/{sourceId}/clone` with a valid clone request body
- **THEN** system SHALL deep-copy suite-level configuration (the cloned suite references the same dataset as the source by default; see `test-suite-clone` spec)

### Requirement: Update a TestSuite
The service SHALL allow updating an existing TestSuite by id. Suite type SHALL NOT be changeable on update. `datasetId` MAY be changed (re-binding the suite to a different dataset triggers synchronous re-validation against the new dataset's schema). When binding-relevant configuration fields change, the system SHALL perform synchronous suite-level re-validation; suite PUTs SHALL NOT spawn an async `RevalidationTask` (only dataset PUTs that mutate `testCaseSchema` spawn tasks — see the `datasets` spec).
Status: **Implemented**

#### Scenario: Existing id
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with a valid body
- **THEN** system SHALL update the existing TestSuite, recalculate suite-level `isValid` and `validationWarnings` against the referenced dataset's schema, and return the updated entity

#### Scenario: Missing id
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` for a non-existent TestSuite
- **THEN** system SHALL respond with HTTP 404

#### Scenario: Suite type change rejected
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with a `suiteType` different from the existing suite
- **THEN** system SHALL respond with HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Rebind to different dataset
- **WHEN** client calls PUT with a `datasetId` differing from the stored value, and the new dataset exists
- **THEN** system SHALL update `datasetId`, re-run synchronous suite validation against the new dataset's schema, recalculate `isValid` and `validationWarnings`, and return the updated suite

#### Scenario: Update MCP suite toolRef
- **WHEN** client updates `toolRef` of an MCP_TOOL suite (different inputSchema)
- **THEN** system SHALL recalculate suite-level `isValid` and `validationWarnings` synchronously; test case validity is not affected (test case validity is driven by dataset schema, not MCP tool schema)

#### Scenario: Update MCP suite argumentTemplate
- **WHEN** client updates `argumentTemplate` of an MCP_TOOL suite
- **THEN** system SHALL recalculate suite-level `isValid` and `validationWarnings` synchronously (argument variables re-extracted, bindings re-checked)

#### Scenario: Update embedded refs (DEPLOYMENT only)
- **WHEN** client updates `deploymentRef` and/or `endpointRef` of an existing DEPLOYMENT TestSuite
- **THEN** system SHALL persist the updated embedded objects and recalculate `isValid` and `validationWarnings`

#### Scenario: Update a ROUTE suite's routesRef
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` on a ROUTE suite with a different valid `routesRef` and/or updated `endpointRef` / `requestTemplate`
- **THEN** system SHALL persist the updated values, recalculate `isValid` and `validationWarnings`, and return the updated entity with the new `routesRef`; the hard-validation rules of "Type-specific field validation" apply to the PUT body

#### Scenario: Update inputBindings
- **WHEN** client updates `inputBindings` of an existing TestSuite (any type)
- **THEN** system SHALL recalculate `isValid` and `validationWarnings` synchronously (bindings re-checked against the dataset's schema and the suite's template variables)

#### Scenario: Suite PUT does not spawn async task
- **WHEN** any suite PUT completes (including binding-relevant changes)
- **THEN** system SHALL NOT spawn an async `RevalidationTask`; suite-side validation is synchronous; async tasks are only spawned by dataset PUTs that mutate `testCaseSchema`

## ADDED Requirements

### Requirement: Route reference on ROUTE suites
A `ROUTE` suite SHALL carry a `routesRef` object `{ "name": "<canonical route identity>" }` identifying the global DIAL Core Route it targets. `test_suites` SHALL have a nullable `routes_ref JSONB` column (no default, no index) that stores it, added by a Flyway migration; existing rows SHALL read back `routesRef: null`. `routesRef` SHALL be accepted on `POST /api/v1/test-suites` and `PUT /api/v1/test-suites/{id}` and returned on `GET /api/v1/test-suites/{id}`, on list items and (for ROUTE runs) on the run's suite snapshot.

`routesRef.name` SHALL be validated at the API boundary: not blank, at most 255 characters, and matching `^routes/platform/[A-Za-z0-9._%:@\[\]()-]+$`. A violation SHALL be rejected with HTTP 400 `VALIDATION_ERROR`; for a pattern violation the message SHALL contain `Field [routesRef.name]: Name must be a canonical DIAL Core Route identity: routes/platform/<name>`.

`routesRef` is **metadata only**: it identifies the route for display, filtering (Query DSL `routes_ref::name`) and future lookup. It SHALL NOT be used to build the invocation URL, and the system SHALL NOT verify that the named route exists in DIAL Core. The invocation path of a ROUTE suite is its resolved `requestTemplate.urlTemplate` (see `eval-execution-engine`).
Status: **Implemented**

#### Scenario: routesRef is persisted and returned
- **WHEN** client creates a ROUTE suite with `routesRef.name = "routes/platform/validate-route"` and then calls `GET /api/v1/test-suites/{id}`
- **THEN** the create response and the GET response SHALL both carry `routesRef` equal to `{ "name": "routes/platform/validate-route" }`

#### Scenario: Non-canonical name is rejected
- **WHEN** client submits a ROUTE suite whose `routesRef.name` does not match the canonical pattern (e.g. `validate-route` or `routes/other/x`)
- **THEN** the system SHALL respond HTTP 400 `VALIDATION_ERROR` whose message contains `Field [routesRef.name]: Name must be a canonical DIAL Core Route identity: routes/platform/<name>`

#### Scenario: Over-long name is rejected
- **WHEN** client submits a ROUTE suite whose `routesRef.name` is longer than 255 characters
- **THEN** the system SHALL respond HTTP 400 `VALIDATION_ERROR`

#### Scenario: Blank name is rejected
- **WHEN** client submits a ROUTE suite whose `routesRef.name` is empty or whitespace
- **THEN** the system SHALL respond HTTP 400 `VALIDATION_ERROR`

#### Scenario: Existing suites have no routesRef
- **WHEN** the migration adding `routes_ref` runs on a database with existing suites
- **THEN** every existing suite SHALL have `routes_ref IS NULL` and read back `routesRef: null`

#### Scenario: routesRef does not influence the invocation URL
- **WHEN** a ROUTE suite's `routesRef.name` is changed without changing its `requestTemplate.urlTemplate`
- **THEN** the path DIAL Core receives for a run or try-it-out of that suite SHALL be unchanged

## Implementation notes

- Data model and persistence: `TestSuite` (`routesRef` as JSON string), `TestSuiteRecordMapper`, `PostgresTestSuiteRepository`, `JsonbMapper`, `TestSuiteMapper`, Flyway `V1.37__AddRoutesRefToTestSuites.sql`, regenerated jOOQ sources, `docs/database-schema.md`.
- DTOs: `RouteReferenceDto` and `RunnerValidationConstants` (name pattern, message, max length), `TestSuiteRequestDto`, `TestSuiteResponseDto`, enum `SuiteType`.
- Write-time validation: `TestSuiteRequestValidator`; soft validation reuses the HTTP path of the suite validation service (the fixed-path model check is skipped when there is no `deploymentRef`).
- OpenAPI: `OpenApiExampleCustomizer` (`route` example name) and `src/main/resources/openapi/examples/api-v1-test-suites-*-route.json`.
- Tests: `TestSuiteRequestValidatorTest` (ROUTE nested class), `TestSuiteFunctionalTests` ("Should persist and return a ROUTE suite reference", "OpenAPI publishes ROUTE suite request and response examples"), `MetaTestDataHelper.createTestSuiteWithRoutesRef`.
- No test exercises the bean-validation path end-to-end; it is covered only by the annotations on `RouteReferenceDto`.
