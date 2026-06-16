# Test Suites — Delta

## ADDED Requirements

### Requirement: Suite references a dataset (required `datasetId`)
Every TestSuite SHALL reference exactly one Dataset via a mandatory `datasetId` field (UUID, NOT NULL FK in `test_suites.dataset_id` to `datasets.id` with `ON DELETE RESTRICT`). The reference SHALL be required on both create and update. Many suites may share one dataset; a suite cannot exist without a dataset.
Status: **Planned**

#### Scenario: Create rejects missing datasetId
- **WHEN** client calls `POST /api/v1/test-suites` with a body that omits `datasetId` (or sends it as null/blank)
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`

#### Scenario: Create rejects unknown datasetId
- **WHEN** client calls `POST /api/v1/test-suites` with `datasetId` referring to a non-existent dataset
- **THEN** system SHALL respond with HTTP 404 (or HTTP 400 with `VALIDATION_ERROR` per project convention for FK pre-checks); the suite SHALL NOT be persisted

#### Scenario: Update can rebind suite to a different dataset
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with a `datasetId` differing from the current value, and the new dataset exists
- **THEN** system SHALL update the suite's `datasetId`, recalculate suite-level `isValid` and `validationWarnings` against the new dataset's schema, and return the updated entity. If the rebind invalidates bindings or metric definitions, those flags surface in `validationWarnings` and the affected child entities have their own `isValid` flags recomputed via existing per-entity synchronous validation paths.

#### Scenario: Update rejects unknown datasetId
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with a `datasetId` referring to a non-existent dataset
- **THEN** system SHALL respond with HTTP 404 (or HTTP 400 per FK pre-check convention)

#### Scenario: Suite delete does not cascade to dataset
- **WHEN** client deletes a TestSuite via `DELETE /api/v1/test-suites/{id}`
- **THEN** the referenced dataset SHALL remain intact (deletion is `ON DELETE RESTRICT` on the suite-side FK); only the suite row and its owned children (TSMDs, runs, etc.) SHALL be removed

#### Scenario: Dataset delete rejects when suites reference it
- **WHEN** client calls `DELETE /api/v1/datasets/{id}` and any suite has `datasetId = <id>`
- **THEN** system SHALL respond with HTTP 409 (RESTRICT enforced by FK); users must delete or rebind those suites first

### Requirement: Per-suite `disabledTestCaseIds`
TestSuite SHALL carry a `disabledTestCaseIds` field — a list of UUIDs (JSONB array in storage, list of strings on the wire) defaulting to `[]` — naming test cases owned by the suite's dataset that SHALL NOT participate in runs of this suite. The list is denormalized on the suite (no junction table at this stage). Stale IDs in the list (referring to deleted test cases) are tolerated: they are naturally ignored by set-membership semantics in the snapshot-phase query and do not cause errors. The list is read by the snapshot-phase query (`findValidByDatasetIdExcludingIds`) to exclude rows.
Status: **Planned**

#### Scenario: New suite starts with empty disable list
- **WHEN** client creates a TestSuite without `disabledTestCaseIds`
- **THEN** system SHALL persist `disabledTestCaseIds = []`

#### Scenario: Suite create/update accepts disabledTestCaseIds
- **WHEN** client creates or updates a TestSuite with `disabledTestCaseIds: ["uuid-1", "uuid-2"]`
- **THEN** system SHALL persist the list verbatim; no referential check against the dataset's test cases is performed (stale ids are tolerated)

#### Scenario: Disabled test cases excluded from runs
- **WHEN** a run is started for a suite whose `disabledTestCaseIds` contains a test case present in the suite's dataset
- **THEN** that test case SHALL NOT appear in `test_case_run_inputs` for the run; `numberOfTestCases` SHALL reflect only included cases (see `suite-run-snapshot` spec)

#### Scenario: Disabled list size cap
- **WHEN** client sends `disabledTestCaseIds` larger than `ValidationConstants.MAX_DISABLED_TC_IDS` (fixed at 10000)
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`

#### Scenario: Non-UUID entries rejected
- **WHEN** client sends `disabledTestCaseIds` with an entry that is not a valid UUID string
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`

#### Scenario: Stale id silently tolerated
- **WHEN** `disabledTestCaseIds` contains an id for a test case that was deleted from the dataset (or never existed)
- **THEN** the run snapshot phase SHALL produce a coherent set of inputs ignoring the stale id; no error is raised

## MODIFIED Requirements

### Requirement: Create a TestSuite
The service SHALL allow creating a new TestSuite. The request body SHALL accept `suiteType` (optional, defaults to `DEPLOYMENT`), `datasetId` (required — FK to `datasets.id`), and `disabledTestCaseIds` (optional, default `[]`). For `DEPLOYMENT` suites: `requestTemplate`, `inputBindings`, `deploymentRef`, `endpointRef` (existing behavior — `deploymentRef` hard-required, `endpointRef`/`requestTemplate` soft-validated). For `MCP_TOOL` suites: `inputBindings`, `mcpDeploymentRef` (hard-required), `toolRef` (hard-required), `argumentTemplate` (soft-validated — null produces warning). `testCaseSchema` SHALL NOT appear on the suite request — it lives on the referenced dataset. The system SHALL perform type-specific validation and suite-level soft validation, sourcing the dataset's schema via `DatasetSchemaProvider` for binding cross-checks. Additionally, the system SHALL support cloning an existing suite via `POST /api/v1/test-suites/{sourceId}/clone` (see `test-suite-clone` spec).
Status: **Planned**

#### Scenario: Valid DEPLOYMENT payload
- **WHEN** client calls `POST /api/v1/test-suites` with a valid body including `datasetId`, `deploymentRef`, `requestTemplate`, and `inputBindings` (see "Type-specific field validation" requirement for `deploymentRef` hard-requirement and `endpointRef`/`requestTemplate` soft-validation rules)
- **THEN** system SHALL create a new TestSuite with `suiteType = DEPLOYMENT`, perform suite-level soft validation against the dataset's schema, and return the created entity including `isValid`, `validationWarnings`, and `disabledTestCaseIds = []`

#### Scenario: Valid MCP_TOOL payload
- **WHEN** client calls `POST /api/v1/test-suites` with `"suiteType": "MCP_TOOL"`, valid `datasetId`, `mcpDeploymentRef`, `toolRef`, and `inputBindings`
- **THEN** system SHALL create a new TestSuite with `suiteType = MCP_TOOL`, perform MCP-specific validation against the dataset's schema, and return the created entity

#### Scenario: testCaseSchema in body is rejected or ignored
- **WHEN** client sends a create body containing a `testCaseSchema` field
- **THEN** system SHALL ignore the field (per Jackson default) or respond with HTTP 400 if strict-binding is enabled; in either case the field SHALL NOT influence the persisted suite (schema is owned by the dataset)

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
Status: **Planned**

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

#### Scenario: Update inputBindings
- **WHEN** client updates `inputBindings` of an existing TestSuite (any type)
- **THEN** system SHALL recalculate `isValid` and `validationWarnings` synchronously (bindings re-checked against the dataset's schema and the suite's template variables)

#### Scenario: Suite PUT does not spawn async task
- **WHEN** any suite PUT completes (including binding-relevant changes)
- **THEN** system SHALL NOT spawn an async `RevalidationTask`; suite-side validation is synchronous; async tasks are only spawned by dataset PUTs that mutate `testCaseSchema`

### Requirement: Delete a TestSuite
The service SHALL allow deleting a TestSuite by id. The deletion SHALL cascade only to suite-owned children (TSMDs, runs, etc.). The referenced `Dataset` SHALL NOT be deleted (it may be shared with other suites). Test cases SHALL NOT be deleted (they live in the dataset and are reachable from any other suite referencing the same dataset). The delete response SHALL NOT include a `deletedTestCases` count.
Status: **Planned**

#### Scenario: Existing id
- **WHEN** client calls `DELETE /api/v1/test-suites/{id}` for an existing TestSuite
- **THEN** system SHALL delete the suite and its owned children (TSMDs, runs); the response SHALL be HTTP 204 (or HTTP 200 with a delete-response body per project convention) and SHALL NOT carry `deletedTestCases`

#### Scenario: Cascade delete suite-owned children only
- **WHEN** system deletes a TestSuite
- **THEN** it SHALL delete suite-owned children (TSMDs, runs, eval-summaries) but SHALL NOT delete test cases under the dataset nor the dataset itself

#### Scenario: Missing id
- **WHEN** client calls `DELETE /api/v1/test-suites/{id}` for a non-existent TestSuite
- **THEN** system SHALL respond with HTTP 404

### Requirement: Suite-level soft validation (`isValid` + `validationWarnings`)
The TestSuite response SHALL include `isValid` (boolean) and `validationWarnings` (structured list, same format as TestCase validation warnings). Suite-level validation covers template + bindings configuration correctness, cross-checked against the **referenced dataset's** `testCaseSchema` (resolved via `DatasetSchemaProvider`) and the suite's `responseColumns` / `endpointRef`. Suite-level validation is **independent of test case data**. Suite `isValid` is recalculated on every create or update. TestCase `isValid` covers data-specific checks only — the two layers are independent.
Status: **Planned**

#### Scenario: Create returns suite validation result
- **WHEN** client creates a TestSuite with `requestTemplate` and `inputBindings`
- **THEN** the response SHALL include `isValid` and `validationWarnings` reflecting suite-level checks (urlTemplate, binding coverage, binding references against the dataset's schema, template conformance to endpoint schema)

#### Scenario: Update recalculates suite validation
- **WHEN** client updates `requestTemplate`, `inputBindings`, `endpointRef`, `responseColumns`, or `datasetId`
- **THEN** system SHALL recalculate `isValid` and `validationWarnings` for the suite against the (possibly newly-bound) dataset's schema

#### Scenario: Suite valid — no warnings
- **WHEN** all suite-level checks pass (urlTemplate valid, all required variables bound, all bindings reference fields in the dataset's schema and template variables, template conforms to endpoint schema)
- **THEN** `isValid` SHALL be `true` and `validationWarnings` SHALL be empty

#### Scenario: Suite invalid — warnings produced
- **WHEN** any suite-level check fails (e.g., urlTemplate null, required variable unbound, binding references field not in dataset schema)
- **THEN** `isValid` SHALL be `false` and `validationWarnings` SHALL contain structured warning objects (`fieldName`, `path`, `message`, optional `code`)

#### Scenario: Suite with no request template produces warning
- **WHEN** TestSuite has `requestTemplate: null`
- **THEN** `isValid` SHALL be `false` and `validationWarnings` SHALL include a warning ("urlTemplate is required for request assembly") — same as when `requestTemplate` is non-null but `urlTemplate` is null

#### Scenario: Suite validation accessible without test cases
- **WHEN** a TestSuite has just been created and the referenced dataset has no test cases yet
- **THEN** the response SHALL still include `isValid` and `validationWarnings` from suite-level checks (dataset schema is sufficient context)

#### Scenario: Suite revalidated when dataset schema changes
- **WHEN** the referenced dataset's `testCaseSchema` is updated (via dataset PUT) and the dataset-rooted `RevalidationTask` runs Phase 2
- **THEN** the suite's `isValid` and `validationWarnings` SHALL be refreshed by the task's per-suite handler (see `datasets` spec for Phase 2 semantics)

### Requirement: Suite delete response shape
The suite delete endpoint SHALL respond with HTTP 200 (or 204 per project convention) and a body containing only the deleted suite's `id` (and `name`, `version`, timestamps if the project's delete-response convention so dictates). The body SHALL NOT include a `deletedTestCases` count.
Status: **Planned**

#### Scenario: Delete response excludes deletedTestCases
- **WHEN** client deletes a TestSuite and the response carries a body
- **THEN** the body SHALL NOT include a `deletedTestCases` field; test cases are not owned by the suite and are not affected by suite deletion

## REMOVED Requirements

### Requirement: testCaseSchema structure and validation
**Reason**: `testCaseSchema` is no longer a TestSuite field. It moves to the `Dataset` entity. All structural-validation rules (list of `FieldDefinitionDto`, max size, unique names, type enum) are restated in the `datasets` spec under "Dataset testCaseSchema structure and validation".
**Migration**: Clients that previously sent `testCaseSchema` on TestSuite create/update SHALL now send it as part of the `DatasetRequestDto` to `POST /api/v1/datasets` or `PUT /api/v1/datasets/{id}`. The migration to existing rows is handled by the change's Flyway migration (see proposal/design): each pre-existing suite's schema is copied verbatim into a new dataset whose id equals the suite's id.

### Requirement: testCaseSchema field name MUST NOT contain `:` (colon)
**Reason**: `testCaseSchema` field-name validation moves to the `datasets` spec with the schema itself.
**Migration**: Same as above — the rule continues to apply, now enforced at the dataset level. The migration preserves existing field names verbatim; if any pre-existing suite carried a colon-bearing field name, the same constraint will surface as a validation error on the next dataset PUT until the field is renamed.

### Requirement: Schema-driven data cleanup on TestSuite schema change
**Reason**: Schema mutation now happens on the dataset, not the suite. Data cleanup (stripping removed-field keys from TestCase `data` maps) is owned by `DatasetService.update()` and applies to every test case under the dataset. The behavior is restated in the `datasets` spec under "Schema-driven data cleanup on dataset schema change".
**Migration**: No client-visible behavior change; the cleanup still runs, but on dataset PUT instead of suite PUT.
