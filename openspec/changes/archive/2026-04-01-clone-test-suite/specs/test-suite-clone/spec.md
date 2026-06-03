# Test Suite Clone

## Purpose
This spec describes the deep-copy (clone) endpoint for test suites, covering entity cloning, file copying, file reference rewriting, and post-clone revalidation.

Status: **Planned**

## Key Terms
- **Clone**: A deep copy of a test suite including its test cases, TSMDs, and DIAL files. Produces a new independent suite with new UUIDs for all entities.
- **File reference rewriting**: String replacement of `@ef/suites/{sourceId}/` with `@ef/suites/{newId}/` in all JSONB fields that may contain DIAL file references.

## ADDED Requirements

### Requirement: Clone a TestSuite
The system SHALL provide `POST /api/v1/test-suites/{sourceId}/clone` that creates a deep copy of the source suite. The request body SHALL accept a required `name` field and optional override fields. The response SHALL be HTTP 201 with `TestSuiteUpdateResultDto` containing the cloned suite and a revalidation task. Status: **Planned**

#### Scenario: Successful clone with name only
- **WHEN** client calls `POST /api/v1/test-suites/{sourceId}/clone` with `{"name": "My Clone"}`
- **THEN** system SHALL create a new test suite with all configuration inherited from the source, `name` set to "My Clone", `version` set to 0, fresh `createdAt`/`updatedAt` timestamps, and `createdBy` from JWT (or "anonymous" in no-security mode)
- **AND** system SHALL return HTTP 201 with the new suite and a revalidation task

#### Scenario: Clone with field overrides
- **WHEN** client calls `POST /api/v1/test-suites/{sourceId}/clone` with `{"name": "Clone", "description": "New desc", "deploymentRef": {"id": "new-deployment-id", "name": "New Deployment"}}`
- **THEN** system SHALL create a new suite with `description` and `deploymentRef` overridden to the provided values (`deploymentRef` is a `DeploymentReferenceDto` object, not a plain string), and all other fields inherited from the source

#### Scenario: Source suite not found
- **WHEN** client calls `POST /api/v1/test-suites/{sourceId}/clone` with a non-existent `sourceId`
- **THEN** system SHALL respond with HTTP 404

#### Scenario: Duplicate name
- **WHEN** client calls `POST /api/v1/test-suites/{sourceId}/clone` with a `name` that already exists
- **THEN** system SHALL respond with HTTP 409 and error code `UNIQUE_CONSTRAINT_VIOLATION`

#### Scenario: Missing name
- **WHEN** client calls `POST /api/v1/test-suites/{sourceId}/clone` without a `name` field or with a blank name
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`

#### Scenario: Unauthenticated request is rejected
- **WHEN** security mode is `oidc` AND a clone request is made without a valid authentication token
- **THEN** the system SHALL respond with HTTP 401

### Requirement: Clone request DTO
The system SHALL use a dedicated `TestSuiteCloneRequestDto` with `name` as `@NotBlank @Size(max = 255)` and all other suite-level fields as optional (nullable). Null fields SHALL mean "inherit from source." The DTO SHALL NOT include `suiteType` (always inherited). Status: **Planned**

Overridable fields: `description`, `deploymentRef`, `endpointRef`, `testCaseSchema`, `responseColumns`, `requestTemplate`, `inputBindings`, `mcpDeploymentRef`, `toolRef`, `argumentTemplate`.

**Note:** `suiteType` is always inherited from the source suite and is not user-overridable. It is used internally by the clone service when mapping to `TestSuiteRequestDto` for synchronous suite validation — the validator branches on `suiteType` to apply type-specific rules (e.g., `deploymentRef` is required for `DEPLOYMENT` type). This is an implementation concern and does not affect the user-facing clone DTO.

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

### Requirement: Test case cloning
The system SHALL clone all test cases from the source suite into the new suite. Each cloned test case SHALL receive a new UUID, the new suite's ID as `testSuiteId`, and fresh `createdAt`/`updatedAt` timestamps. The `testCaseName`, `data`, `requestTemplateOverride`, `inputBindingsOverride`, and `enabled` fields SHALL be copied from the source. `isValid` SHALL be set to `false` (pending revalidation). Status: **Planned**

#### Scenario: Test cases are deep-copied
- **WHEN** source suite has 3 test cases
- **THEN** cloned suite SHALL have 3 test cases with identical names and data but new UUIDs and the cloned suite's ID

#### Scenario: Paginated copying for large suites
- **WHEN** source suite has more test cases than the configured batch size
- **THEN** system SHALL read and insert test cases in paginated batches using the revalidation batch size

#### Scenario: Empty source suite
- **WHEN** source suite has 0 test cases
- **THEN** cloned suite SHALL have 0 test cases (no error)

### Requirement: TSMD cloning
The system SHALL clone all test suite metric definitions from the source suite into the new suite. Each cloned TSMD SHALL receive a new UUID, the new suite's ID as `testSuiteId`, and fresh timestamps. The `name`, `metricDeclarationId`, `metricDeclarationVersionId`, `enabled`, `configBindings`, and `inputBindings` fields SHALL be copied. `isValid` SHALL be set to `false`. Status: **Planned**

#### Scenario: TSMDs are deep-copied
- **WHEN** source suite has 2 TSMDs
- **THEN** cloned suite SHALL have 2 TSMDs with identical configuration but new UUIDs

#### Scenario: Paginated TSMD copying
- **WHEN** source suite has more TSMDs than the configured batch size
- **THEN** system SHALL read and insert TSMDs in paginated batches

#### Scenario: TSMD referencing deleted metric declaration is skipped
- **WHEN** source suite has a TSMD whose `metricDeclarationId` no longer exists in `metric_declarations`
- **THEN** that TSMD SHALL be silently excluded from the cloned suite (the INNER JOIN on `metric_declarations` excludes the orphaned row)
- **AND** no error SHALL be thrown; remaining valid TSMDs are cloned normally

### Requirement: DIAL file cloning
The system SHALL copy all files from the source suite's DIAL storage folder (`{bucket}/suites/{sourceId}/`) to the cloned suite's folder (`{bucket}/suites/{newId}/`). File copy SHALL happen before the database transaction. Status: **Planned**

#### Scenario: Files are copied
- **WHEN** source suite has files `a.csv` and `b.json` in DIAL storage
- **THEN** after clone, the new suite's folder SHALL contain `a.csv` and `b.json` with identical content

#### Scenario: Missing file is skipped gracefully
- **WHEN** source suite's file listing includes a file that no longer exists (deleted externally)
- **THEN** system SHALL log a warning and continue cloning without that file (no error thrown)

#### Scenario: File copy cleanup on transaction failure
- **WHEN** files are copied successfully but the subsequent DB transaction fails
- **THEN** system SHALL attempt best-effort cleanup of the copied files

### Requirement: File reference rewriting
The system SHALL rewrite all DIAL file references in JSONB fields from `@ef/suites/{sourceId}/` to `@ef/suites/{newId}/` using string replacement. This applies to suite-level fields (`inputBindings`, `requestTemplate`, `argumentTemplate`), test case fields (`data`, `requestTemplateOverride`, `inputBindingsOverride`), and TSMD fields (`configBindings`, `inputBindings`). Status: **Planned**

#### Scenario: File refs in test case data are rewritten
- **WHEN** source test case has `data: {"file": "@ef/suites/aaa/input.csv"}`
- **THEN** cloned test case SHALL have `data: {"file": "@ef/suites/bbb/input.csv"}` where `bbb` is the new suite ID

#### Scenario: File refs in suite-level bindings are rewritten
- **WHEN** source suite has an input binding with `constantValue: "@ef/suites/aaa/config.json"`
- **THEN** cloned suite SHALL have the binding with `constantValue: "@ef/suites/bbb/config.json"`

#### Scenario: File refs in TSMD bindings are rewritten
- **WHEN** source TSMD has a `configBindings` or `inputBindings` entry with `constantValue: "@ef/suites/aaa/metric-config.json"`
- **THEN** cloned TSMD SHALL have the binding with `constantValue: "@ef/suites/bbb/metric-config.json"`

#### Scenario: Non-file-ref strings are not affected
- **WHEN** a JSONB field contains a string that does not match the `@ef/suites/{sourceId}/` pattern
- **THEN** that string SHALL remain unchanged after cloning

### Requirement: Post-clone async revalidation
The system SHALL always trigger async revalidation after clone completes (same mechanism as `RevalidationService.startRevalidation`). The async revalidation covers all cloned test cases and all cloned TSMDs. The suite entity itself is NOT revalidated by the async step — it is validated synchronously during the clone flow (see suite validation requirement below). The revalidation task SHALL be included in the clone response. Status: **Planned**

#### Scenario: Revalidation is triggered
- **WHEN** clone completes successfully
- **THEN** system SHALL start an async revalidation task for the new suite
- **AND** the clone response SHALL include the revalidation task with status `PENDING`

#### Scenario: Cloned entities start as invalid
- **WHEN** clone inserts test cases and TSMDs
- **THEN** all cloned test cases and TSMDs SHALL have `isValid = false` until async revalidation completes

#### Scenario: Cloned suite validation is determined synchronously at clone time
- **WHEN** clone is created
- **THEN** the cloned suite's `isValid` and `validationWarnings` SHALL be determined by synchronous suite-level validation (`SuiteValidationService`) at clone time — the suite may be valid or invalid depending on its configuration; it is NOT permanently set to `false`

### Requirement: Test suite runs are NOT cloned
The system SHALL NOT copy or associate any test suite runs from the source suite with the cloned suite. The cloned suite starts with zero runs. Status: **Planned**

#### Scenario: No runs on cloned suite
- **WHEN** source suite has 5 completed test suite runs
- **THEN** cloned suite SHALL have 0 test suite runs

### Requirement: OpenAPI documentation
The clone endpoint SHALL have OpenAPI annotations including operation summary, request/response schemas, and example JSON files under `src/main/resources/openapi/examples/`. Status: **Planned**

#### Scenario: Swagger UI shows clone endpoint
- **WHEN** user opens Swagger UI
- **THEN** the clone endpoint SHALL appear with description, request body schema, and response examples
