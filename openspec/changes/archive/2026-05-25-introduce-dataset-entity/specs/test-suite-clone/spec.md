# Test Suite Clone — Delta

## MODIFIED Requirements

### Requirement: Clone a TestSuite
The system SHALL provide `POST /api/v1/test-suites/{sourceId}/clone` that creates a deep copy of the source suite. The cloned suite SHALL reference the **same `Dataset`** as the source suite (test cases are not copied; they are shared via the dataset reference). The request body SHALL accept a required `name` field and optional override fields. The response SHALL be HTTP 201 with `TestSuiteUpdateResultDto` containing the cloned suite.
Status: **Planned**

#### Scenario: Successful clone with name only
- **WHEN** client calls `POST /api/v1/test-suites/{sourceId}/clone` with `{"name": "My Clone"}`
- **THEN** system SHALL create a new test suite with all suite-level configuration inherited from the source, `name` set to "My Clone", `datasetId` inherited from the source, `disabledTestCaseIds` inherited from the source, `version` set to 0, fresh `createdAt`/`updatedAt` timestamps, and `createdBy` from JWT (or "anonymous" in no-security mode)
- **AND** system SHALL return HTTP 201 with the new suite

#### Scenario: Clone with field overrides
- **WHEN** client calls `POST /api/v1/test-suites/{sourceId}/clone` with `{"name": "Clone", "description": "New desc", "deploymentRef": {"id": "new-deployment-id", "name": "New Deployment"}}`
- **THEN** system SHALL create a new suite with `description` and `deploymentRef` overridden, all other fields (including `datasetId`) inherited from the source

#### Scenario: Clone with explicit datasetId override
- **WHEN** client calls `POST /api/v1/test-suites/{sourceId}/clone` with `{"name": "Clone", "datasetId": "<other-dataset-id>"}` and the supplied dataset exists
- **THEN** system SHALL create the cloned suite bound to the supplied `datasetId` instead of the source's; suite-level synchronous validation runs against the new dataset's schema

#### Scenario: Clone with unknown datasetId
- **WHEN** client supplies a `datasetId` that does not exist
- **THEN** system SHALL respond with HTTP 404 (or 400 with `VALIDATION_ERROR` per project convention for referential check failures)

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
The system SHALL use a dedicated `TestSuiteCloneRequestDto` with `name` as `@NotBlank @Size(max = 255)` and all other suite-level fields as optional (nullable). Null fields SHALL mean "inherit from source." The DTO SHALL NOT include `suiteType` (always inherited) and SHALL NOT include `testCaseSchema` (schema lives on the dataset; suite has no schema field). The DTO SHALL include an optional `datasetId` field; when supplied, the cloned suite SHALL reference the supplied dataset; when null/absent, it SHALL inherit the source suite's `datasetId`.
Status: **Planned**

Overridable fields: `description`, `datasetId`, `deploymentRef`, `endpointRef`, `responseColumns`, `requestTemplate`, `inputBindings`, `mcpDeploymentRef`, `toolRef`, `argumentTemplate`, `disabledTestCaseIds`.

**Note:** `suiteType` is always inherited from the source suite and is not user-overridable. `testCaseSchema` is no longer on the suite — it lives on the dataset; users who want to clone a suite onto a fresh schema should create a new dataset (via `POST /api/v1/datasets`) and pass its id as `datasetId` in the clone request.

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

#### Scenario: datasetId inherited from source when absent
- **WHEN** clone request has `datasetId: null` (or absent)
- **THEN** cloned suite SHALL reference the source suite's `datasetId`

#### Scenario: testCaseSchema field is rejected
- **WHEN** client sends a clone request body containing a `testCaseSchema` field
- **THEN** system SHALL ignore the unknown field (per Jackson default) OR respond with HTTP 400 if strict-binding validation is enabled; in either case the cloned suite SHALL NOT carry a `testCaseSchema` (the field does not exist on `TestSuite` after this change)

### Requirement: DIAL file cloning
The system SHALL copy suite-level files from the source suite's DIAL storage folder (`{bucket}/suites/{sourceId}/`) to the cloned suite's folder (`{bucket}/suites/{newId}/`). File copy SHALL happen before the database transaction. Only files referenced by suite-level configuration (multipart bodies in `requestTemplate`, file references in `inputBindings`, TSMD-level `configBindings`/`inputBindings`) are within scope. Test-case-scoped files are NOT copied because test cases are not cloned — they remain in the shared dataset and continue to reference their existing paths.
Status: **Planned**

#### Scenario: Files referenced by suite-level configuration are copied
- **WHEN** source suite has files `a.csv` and `b.json` in DIAL storage referenced from its `requestTemplate` or `inputBindings`
- **THEN** after clone, the new suite's folder SHALL contain `a.csv` and `b.json` with identical content

#### Scenario: Missing file is skipped gracefully
- **WHEN** source suite's file listing includes a file that no longer exists (deleted externally)
- **THEN** system SHALL log a warning and continue cloning without that file (no error thrown)

#### Scenario: File copy cleanup on transaction failure
- **WHEN** files are copied successfully but the subsequent DB transaction fails
- **THEN** system SHALL attempt best-effort cleanup of the copied files

#### Scenario: Test-case files are not copied
- **WHEN** the source suite's dataset contains test cases whose `data` references files under `@ef/suites/{sourceId}/`
- **THEN** those test-case-scoped files SHALL NOT be copied (test cases are not cloned)

### Requirement: File reference rewriting
The system SHALL rewrite all suite-scoped DIAL file references in JSONB fields from `@ef/suites/{sourceId}/` to `@ef/suites/{newId}/` using string replacement. This applies to **suite-level** fields only — `inputBindings`, `requestTemplate`, `argumentTemplate` — and to TSMD fields (`configBindings`, `inputBindings`). Test-case-level data and overrides are NOT rewritten by clone (test cases are not copied; they remain in the shared dataset and reference their dataset-scoped paths unchanged — see the file-reference-scheme follow-up referenced in the change's design.md).
Status: **Planned**

#### Scenario: File refs in suite-level bindings are rewritten
- **WHEN** source suite has an input binding with `constantValue: "@ef/suites/aaa/config.json"`
- **THEN** cloned suite SHALL have the binding with `constantValue: "@ef/suites/bbb/config.json"`

#### Scenario: File refs in TSMD bindings are rewritten
- **WHEN** source TSMD has a `configBindings` or `inputBindings` entry with `constantValue: "@ef/suites/aaa/metric-config.json"`
- **THEN** cloned TSMD SHALL have the binding with `constantValue: "@ef/suites/bbb/metric-config.json"`

#### Scenario: Non-file-ref strings are not affected
- **WHEN** a JSONB field contains a string that does not match the `@ef/suites/{sourceId}/` pattern
- **THEN** that string SHALL remain unchanged after cloning

#### Scenario: Test case file refs are NOT rewritten
- **WHEN** test cases under the source's dataset have `data` entries referencing `@ef/suites/{sourceId}/...` (legacy paths) or `@ef/datasets/{datasetId}/...` (future paths)
- **THEN** clone SHALL NOT rewrite those references (test cases are not copied — they remain owned by the dataset shared between source and clone)

## ADDED Requirements

### Requirement: Post-clone validation is synchronous only
The system SHALL determine the cloned suite's `isValid` and `validationWarnings` synchronously at clone time via `SuiteValidationService` (and `TestSuiteMetricDefinitionService` where applicable) against the resolved dataset's schema. Clone SHALL NOT spawn an async `RevalidationTask` — neither for a vanilla clone (same dataset, no overrides) nor for a clone with `datasetId` override. The dataset's existing test-case set is already valid against the dataset's schema by construction (or has been flagged via an existing dataset-rooted revalidation task); cloning a suite does not require re-coercing test-case data. Async tasks are spawned only by dataset PUTs that mutate `testCaseSchema` (see the `datasets` spec).
Status: **Planned**

#### Scenario: No revalidation task for vanilla clone
- **WHEN** client clones with `{"name": "Copy"}` only (no overrides) and the source suite was valid
- **THEN** the cloned suite SHALL be created with `isValid = true` (matching source) and the clone response SHALL NOT include an async `RevalidationTask`

#### Scenario: No revalidation task when datasetId is overridden
- **WHEN** client clones with `{"name": "Copy", "datasetId": "<other-id>"}` and the supplied dataset exists
- **THEN** the cloned suite's bindings, response columns, and metric definitions SHALL be re-validated synchronously against the new dataset's schema (matching the suite-PUT-rebind path documented in the `test-suites` spec); `isValid` / `validationWarnings` reflect the result; the clone response SHALL NOT include an async `RevalidationTask`

#### Scenario: Cloned suite validation is determined synchronously at clone time
- **WHEN** clone is created
- **THEN** the cloned suite's `isValid` and `validationWarnings` SHALL be determined by synchronous suite-level validation (`SuiteValidationService` against the resolved dataset's schema) at clone time

## REMOVED Requirements

### Requirement: Post-clone async revalidation
**Reason**: Under the dataset-rooted model, async `RevalidationTask`s are spawned only by dataset PUTs that mutate `testCaseSchema`. Cloning a suite — even with a `datasetId` override — never mutates a dataset's schema, so it cannot legitimately trigger an async revalidation task. The cloned suite's validity is determined synchronously by `SuiteValidationService` (and `TestSuiteMetricDefinitionService`) at clone time against the resolved dataset's schema. The previous baseline behavior of returning an async task in the clone response is replaced by the new `Post-clone validation is synchronous only` requirement.
**Migration**: Clients that previously polled the returned `RevalidationTaskDto` from a clone response SHALL instead read the cloned suite's `isValid` / `validationWarnings` directly from the synchronous clone response (HTTP 201).

### Requirement: Test case cloning
**Reason**: Test cases are no longer owned by suites — they live in datasets. Cloning a suite means cloning its execution-time configuration only; test cases are shared via the cloned suite's reference to the same dataset (or a different dataset if the user overrode `datasetId`). The previous behavior of deep-copying test case rows into a new suite's space no longer applies.
**Migration**: Users who want a fresh set of test cases independent from the source suite's data should create a new dataset (`POST /api/v1/datasets`), populate it (CSV import or per-case CRUD), and clone the suite with `{"name": "...", "datasetId": "<new-dataset-id>"}`.

