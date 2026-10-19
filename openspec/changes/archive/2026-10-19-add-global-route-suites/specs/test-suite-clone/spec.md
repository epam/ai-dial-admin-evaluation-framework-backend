## MODIFIED Requirements

### Requirement: Clone request DTO
The system SHALL use a dedicated `TestSuiteCloneRequestDto` with `name` as `@NotBlank @Size(max = 255)` and all other suite-level fields as optional (nullable). Null fields SHALL mean "inherit from source." The DTO SHALL NOT include `suiteType` (always inherited), SHALL NOT include `routesRef` (always inherited from the source suite), SHALL NOT include `testCaseSchema` (schema lives on the dataset; suite has no schema field), and SHALL NOT include any test-case exclusion list. The DTO SHALL include an optional `datasetId` field; when supplied, the cloned suite SHALL reference the supplied dataset; when null/absent, it SHALL inherit the source suite's `datasetId`.
Status: **Implemented**

Overridable fields: `description`, `datasetId`, `deploymentRef`, `endpointRef`, `responseColumns`, `requestTemplate`, `inputBindings`, `mcpDeploymentRef`, `toolRef`, `argumentTemplate`.

**Note:** `suiteType` and `routesRef` are always inherited from the source suite and are not user-overridable (a clone of a ROUTE suite targets the same route reference; `routesRef` is not in the overridable list above). `testCaseSchema` is no longer on the suite — it lives on the dataset; users who want to clone a suite onto a fresh schema should create a new dataset (via `POST /api/v1/datasets`) and pass its id as `datasetId` in the clone request.

#### Scenario: Null override field inherits from source
- **WHEN** clone request has `description: null` (or absent) and source suite has `description: "Original"`
- **THEN** cloned suite SHALL have `description: "Original"`

#### Scenario: Non-null override field replaces source value
- **WHEN** clone request has `description: "New"` and source suite has `description: "Original"`
- **THEN** cloned suite SHALL have `description: "New"`

#### Scenario: Override field exceeds allowed length
- **WHEN** clone request provides an override field that exceeds its allowed length (e.g., `description` longer than 2000 characters, or `responseColumns` with more than 50 entries)
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`

#### Scenario: suiteType is always inherited from source
- **WHEN** source suite is `MCP_TOOL`
- **THEN** cloned suite SHALL have `suiteType = MCP_TOOL` (suiteType cannot be overridden via clone request)

#### Scenario: routesRef inherited when cloning a ROUTE suite
- **WHEN** client clones a ROUTE suite with `routesRef.name = "routes/platform/validate-route"` using `{"name": "Copy"}`
- **THEN** the cloned suite SHALL have `suiteType = ROUTE` and `routesRef` equal to the source's `routesRef`

#### Scenario: deploymentRef override on a ROUTE suite is rejected
- **WHEN** client clones a ROUTE suite and supplies a non-null `deploymentRef` override
- **THEN** the effective-suite hard validation SHALL reject the request with HTTP 400 `VALIDATION_ERROR` and message `deploymentRef must be empty for ROUTE suites`, and no clone SHALL be created

#### Scenario: datasetId inherited from source when absent
- **WHEN** clone request has `datasetId: null` (or absent)
- **THEN** cloned suite SHALL reference the source suite's `datasetId`

#### Scenario: testCaseSchema field is rejected
- **WHEN** client sends a clone request body containing a `testCaseSchema` field
- **THEN** system SHALL ignore the unknown field (per Jackson default) OR respond with HTTP 400 if strict-binding validation is enabled; in either case the cloned suite SHALL NOT carry a `testCaseSchema` (the field does not exist on `TestSuite` after this change)

#### Scenario: disabledTestCaseIds field is ignored
- **WHEN** client sends a clone request body containing a `disabledTestCaseIds` field
- **THEN** system SHALL ignore the unknown field and the cloned suite SHALL carry no exclusion state; narrowing the clone's runnable subset is done by setting its `testCaseFilter` through a subsequent suite update (see `suite-test-case-filter`)

## Implementation notes

- `TestSuiteMapper` (clone mapping copies `routesRef` from the source), `TestSuiteCloneService` (runs the shared suite write-time validator on the effective suite before persisting), `TestSuiteRequestValidator`.
- Tests: `TestSuiteMapperCloneTest` ("ROUTE clone inherits routesRef and keeps it in the validation request"). The 400 scenario for a `deploymentRef` override follows from the clone service running the shared validator on the effective suite (see "Clone enforces hard write-time validation on the effective suite"); it has no dedicated test.
