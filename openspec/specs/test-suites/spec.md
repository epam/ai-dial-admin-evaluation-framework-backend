# Test Suites

## Purpose
This spec describes TestSuite authoring and management via the backend REST API.

Status: **Planned** (dataset-rooted re-scoping). CRUD, deploymentRef, endpointRef, requestTemplate, inputBindings, cascade delete remain in place. `testCaseSchema` and TestCases are managed under `Dataset` (see `specs/datasets/spec.md` and `specs/test-cases/spec.md`).

## Key Terms
- **TestSuite**: A named container for evaluation execution configuration. Has a `suiteType` discriminator (`DEPLOYMENT` or `MCP_TOOL`). DEPLOYMENT suites have `deploymentRef`, `endpointRef`, `requestTemplate`, and `inputBindings`. MCP_TOOL suites have `mcpDeploymentRef`, `toolRef`, `argumentTemplate`, and `inputBindings`. Every suite references one `Dataset` via a mandatory `datasetId` (the dataset owns `testCaseSchema` and the test-case rows). Each suite may carry a `disabledTestCaseIds` list to opt out of specific test cases at run time.

## Requirements

### Requirement: List TestSuites (paginated)
The service SHALL provide a paginated endpoint to list TestSuites.
Status: **Implemented**

#### Scenario: Default pagination
- **WHEN** client calls `GET /api/v1/test-suites` without params
- **THEN** response SHALL be a page of TestSuites with default `page=0` and `size=20`

#### Scenario: Pagination bounds
- **WHEN** client calls `GET /api/v1/test-suites?page=<p>&size=<s>`
- **THEN** `page` SHALL be \(>= 0\) and `size` SHALL be between 1 and 100

#### Scenario: Sorting
- **WHEN** client calls `GET /api/v1/test-suites?sort=<field>[,<asc|desc>]` (repeatable)
- **THEN** system SHALL apply sorting using a whitelist of allowed fields

#### Scenario: Structured filtering (`filter`)
- **WHEN** client calls `GET /api/v1/test-suites?filter=<field>:<op>:<value>` (repeatable)
- **THEN** system SHALL apply AND-combined filters using a TestSuite-specific whitelist of fields and operators

#### Scenario: Filter by id (exact match)
- **WHEN** client calls `GET /api/v1/test-suites?filter=id:eq:<uuid>`
- **THEN** system SHALL return only the TestSuite with that exact id (or an empty page if not found)

#### Scenario: Filter by id (set membership)
- **WHEN** client calls `GET /api/v1/test-suites?filter=id:in:<uuid1>,<uuid2>`
- **THEN** system SHALL return only TestSuites whose id is in the provided comma-separated list

#### Scenario: Filter by description (substring, case-insensitive)
- **WHEN** client calls `GET /api/v1/test-suites?filter=description:co:evaluation`
- **THEN** system SHALL return only TestSuites whose `description` contains `"evaluation"` (case-insensitive)

#### Scenario: Filter by updatedAt range
- **WHEN** client calls `GET /api/v1/test-suites?filter=updatedAt:ge:1700000000000&filter=updatedAt:lt:1800000000000`
- **THEN** system SHALL return only TestSuites last updated within that epoch-millisecond range (AND semantics)

### Requirement: Suite references a dataset (optional `datasetId`)
A TestSuite SHALL reference at most one Dataset via an OPTIONAL `datasetId` field (UUID, NULLABLE FK in `test_suites.dataset_id` to `datasets.id`). The reference SHALL be optional on both create and update; suites with `datasetId = null` SHALL be persisted in an **unbound** state and SHALL be retrievable and editable, but SHALL NOT be runnable (see the "Trigger a test suite run" requirement in the `test-suite-runs` spec for the run-start guard). Many suites MAY share a single PUBLIC dataset; at most one suite MAY be bound to a given PRIVATE dataset at any time (enforced application-side and by the trigger defined in the `datasets` spec).
Status: **Planned**

#### Scenario: Create without datasetId allowed (unbound suite)
- **WHEN** client calls `POST /api/v1/test-suites` with a body that omits `datasetId` or sends it as `null`
- **THEN** system SHALL create the suite with `datasetId = null`; the suite is in the unbound state and can be configured further; it cannot run until a `datasetId` is set

#### Scenario: Create with valid datasetId succeeds
- **WHEN** client calls `POST /api/v1/test-suites` with a `datasetId` referring to an existing dataset whose visibility allows the binding (PUBLIC always allows; PRIVATE allows iff it has zero current bindings — enforced by the trigger)
- **THEN** system SHALL create the suite with the given `datasetId`

#### Scenario: Create rejects unknown datasetId
- **WHEN** client calls `POST /api/v1/test-suites` with `datasetId` referring to a non-existent dataset
- **THEN** system SHALL respond with HTTP 404 (or HTTP 400 with `VALIDATION_ERROR` per project convention for FK pre-checks); the suite SHALL NOT be persisted

#### Scenario: Update can rebind PUBLIC-bound suite to a different dataset
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with a `datasetId` differing from the current value, the new dataset exists, AND the current dataset's `visibility = PUBLIC` (or current `datasetId` is `null`)
- **THEN** system SHALL update the suite's `datasetId`, recalculate suite-level `isValid` and `validationWarnings` against the new dataset's schema, and return the updated entity

#### Scenario: Update rejects rebind/unbind when current dataset is PRIVATE
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with a `datasetId` differing from the current value (including `null`), and the stored dataset's `visibility = PRIVATE`
- **THEN** system SHALL respond with HTTP 409 and error code `PRIVATE_DATASET_REBIND_FORBIDDEN` (see "Reject rebind/unbind when current dataset is PRIVATE" requirement)

#### Scenario: Update rejects unknown datasetId
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with a `datasetId` referring to a non-existent dataset
- **THEN** system SHALL respond with HTTP 404 (or HTTP 400 per FK pre-check convention)

#### Scenario: Suite delete cascade is visibility-conditional
- **WHEN** client deletes a TestSuite via `DELETE /api/v1/test-suites/{id}`
- **THEN** if the suite is bound to a PRIVATE dataset, the dataset SHALL be cascade-deleted in the same transaction (see "Suite delete cascades a PRIVATE dataset" requirement); if the suite is bound to a PUBLIC dataset or is unbound, the dataset SHALL remain intact; in all cases the suite row and its owned children (TSMDs, runs, eval-summaries) SHALL be removed

#### Scenario: Dataset delete behavior depends on visibility
- **WHEN** client calls `DELETE /api/v1/datasets/{id}` and one or more `TestSuite` rows reference this dataset
- **THEN** if the dataset's `visibility = PUBLIC`, system SHALL respond with HTTP 409 (FK RESTRICT) — users must rebind or delete those suites first; if the dataset's `visibility = PRIVATE`, system SHALL atomically unbind the single bound suite (`dataset_id := NULL`) and delete the dataset (see the PRIVATE delete requirement in the `datasets` spec)

### Requirement: Reject rebind/unbind when current dataset is PRIVATE
When a TestSuite is currently bound to a `PRIVATE` dataset, `PATCH /api/v1/test-suites/{id}` (or any other update mechanism) SHALL reject any request that changes `datasetId` — including setting it to a different dataset id OR to `null`. The rejection SHALL be HTTP 409 with error code `PRIVATE_DATASET_REBIND_FORBIDDEN`. The constraint exists because a PRIVATE dataset belongs exclusively to its one bound suite; unbinding it would orphan the dataset, and rebinding it elsewhere would violate the "one PRIVATE dataset, one suite" invariant. Users who want to swap the dataset of a PRIVATE-bound suite SHALL first delete the PRIVATE dataset (which unbinds the suite) and then PATCH the suite with the new `datasetId`.
Status: **Planned**

#### Scenario: Rebind PRIVATE-bound suite to different dataset rejected
- **WHEN** client calls `PATCH /api/v1/test-suites/{id}` with a `datasetId` differing from the stored value, and the stored dataset's `visibility = PRIVATE`
- **THEN** system SHALL respond with HTTP 409 and error code `PRIVATE_DATASET_REBIND_FORBIDDEN`; the suite's `datasetId` SHALL remain unchanged

#### Scenario: Unbind PRIVATE-bound suite (set to null) rejected
- **WHEN** client calls `PATCH /api/v1/test-suites/{id}` with `datasetId: null` on a suite whose stored dataset's `visibility = PRIVATE`
- **THEN** system SHALL respond with HTTP 409 and error code `PRIVATE_DATASET_REBIND_FORBIDDEN`; the suite's `datasetId` SHALL remain unchanged

#### Scenario: Rebind PUBLIC-bound suite to different dataset succeeds
- **WHEN** client calls `PATCH /api/v1/test-suites/{id}` with a `datasetId` differing from the stored value, and the stored dataset's `visibility = PUBLIC`
- **THEN** system SHALL update `datasetId`, re-run suite-level validation against the new dataset's schema, and return the updated suite (the new dataset's visibility does not influence acceptance; see "Concurrent PRIVATE-binding prevention" in the `datasets` spec for the trigger-side guard on the target side)

#### Scenario: Unbind PUBLIC-bound suite (set to null) succeeds
- **WHEN** client calls `PATCH /api/v1/test-suites/{id}` with `datasetId: null` on a suite whose stored dataset's `visibility = PUBLIC`
- **THEN** system SHALL set `datasetId = null` and return the updated suite; the suite enters the unbound state

### Requirement: Suite delete cascades a PRIVATE dataset
When `DELETE /api/v1/test-suites/{id}` is called on a suite bound to a `PRIVATE` dataset, the system SHALL — within the same meta transaction that removes the suite — delete the bound `PRIVATE` dataset row. Test cases under that dataset cascade via the existing FK. The suite's own runs, TSMDs, and eval-summaries cascade via existing FKs (no behavior change). The suite-delete on a `PUBLIC`-bound or unbound suite leaves the dataset untouched (unchanged baseline behavior).
Status: **Planned**

#### Scenario: Delete suite bound to PRIVATE dataset cascades dataset
- **WHEN** client calls `DELETE /api/v1/test-suites/{id}` on a suite bound to a PRIVATE dataset
- **THEN** the suite row SHALL be deleted; the bound PRIVATE dataset row SHALL be deleted; the PRIVATE dataset's test cases SHALL be cascade-deleted; all suite-owned children (TSMDs, runs, eval-summaries) SHALL be cascade-deleted via existing FKs; the response SHALL be HTTP 204 (or HTTP 200 with the deleted suite body per project convention)

#### Scenario: Delete suite bound to PUBLIC dataset preserves dataset
- **WHEN** client calls `DELETE /api/v1/test-suites/{id}` on a suite bound to a PUBLIC dataset
- **THEN** the suite row SHALL be deleted; the PUBLIC dataset SHALL remain intact (still discoverable via `GET /api/v1/datasets/{id}` and the list endpoint, possibly bound to other suites)

#### Scenario: Delete unbound suite touches no dataset
- **WHEN** client calls `DELETE /api/v1/test-suites/{id}` on a suite whose `datasetId IS NULL`
- **THEN** the suite row SHALL be deleted; no dataset SHALL be affected

#### Scenario: PRIVATE-cascade is atomic with suite delete
- **WHEN** any step of the PRIVATE-cascade delete path fails (dataset delete, test-case cascade, or any suite-owned cascade)
- **THEN** the entire transaction SHALL roll back; the suite, its bound PRIVATE dataset, and all cascaded children SHALL remain unchanged

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

### Requirement: Get TestSuite by id
The service SHALL allow retrieving a TestSuite by its id.
Status: **Implemented**

#### Scenario: Existing id
- **WHEN** client calls `GET /api/v1/test-suites/{id}` for an existing TestSuite
- **THEN** system SHALL return the TestSuite

#### Scenario: Missing id
- **WHEN** client calls `GET /api/v1/test-suites/{id}` for a non-existent TestSuite
- **THEN** system SHALL respond with HTTP 404

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

### Requirement: Suite delete response shape
The suite delete endpoint SHALL respond with HTTP 200 (or 204 per project convention) and a body containing only the deleted suite's `id` (and `name`, `version`, timestamps if the project's delete-response convention so dictates). The body SHALL NOT include a `deletedTestCases` count.
Status: **Planned**

#### Scenario: Delete response excludes deletedTestCases
- **WHEN** client deletes a TestSuite and the response carries a body
- **THEN** the body SHALL NOT include a `deletedTestCases` field; test cases are not owned by the suite and are not affected by suite deletion

### Requirement: Unique TestSuite name
The service SHALL enforce that TestSuite `name` is unique across all suites (case-insensitive). `"MyTest"` and `"mytest"` are considered duplicates. Create and update SHALL reject requests that would result in a duplicate name with HTTP 409 Conflict and error code `UNIQUE_CONSTRAINT_VIOLATION`. The error message SHALL include the duplicated name.

#### Scenario: Duplicate name on create
- **WHEN** client calls `POST /api/v1/test-suites` with a body whose `name` already exists for another TestSuite (case-insensitive match)
- **THEN** system SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION`, and message including the duplicated name

#### Scenario: Duplicate name on update
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with a body whose `name` already exists for a different TestSuite (case-insensitive match, another id)
- **THEN** system SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION`, and message including the duplicated name

#### Scenario: Update to own current name succeeds
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with a body whose `name` equals the suite's current name (no change, or only case change)
- **THEN** system SHALL accept the request and return HTTP 200 (no 409)

#### Scenario: Case variation is duplicate
- **WHEN** a TestSuite named `"Alpha"` exists and client creates a TestSuite named `"alpha"`
- **THEN** system SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION`

### Requirement: Valid JSON Schema for endpoint schemas
The service SHALL reject create/update requests when `endpointRef.requestBodySchema`, `endpointRef.responseBodySchema`, or any `endpointRef.parameters[i].schema` contains invalid JSON Schema (e.g. invalid `type` value). Validation SHALL be against JSON Schema Draft-07 rules (meta-schema or equivalent).

#### Scenario: Invalid type in requestBodySchema
- **WHEN** client calls POST or PUT with `endpointRef.requestBodySchema` containing a property with invalid `type` (e.g. `"type": "abc"`)
- **THEN** system SHALL respond with HTTP 400 and a clear validation error message

#### Scenario: Invalid type in responseBodySchema
- **WHEN** client calls POST or PUT with `endpointRef.responseBodySchema` containing invalid `type` (e.g. root or property `"type": "abc"`)
- **THEN** system SHALL respond with HTTP 400 and a clear validation error message

#### Scenario: Invalid type in parameter schema
- **WHEN** client calls POST or PUT with `endpointRef.parameters[i].schema` containing invalid `type`
- **THEN** system SHALL respond with HTTP 400 and a clear validation error message

#### Scenario: Schema with $ref rejected (v1)
- **WHEN** client calls POST or PUT with any schema (requestBodySchema, responseBodySchema, parameters[i].schema) containing `$ref` keyword
- **THEN** system SHALL respond with HTTP 400 and indicate `$ref` is not supported in v1

### Requirement: EndpointContractDto schemas are optional
The `endpointRef.requestBodySchema` and `endpointRef.responseBodySchema` fields SHALL be optional (nullable). It SHALL be possible to define a TestSuite with only a `requestTemplate` and no endpoint schemas. Schemas improve usability via validation but are not required.

#### Scenario: Create TestSuite without endpoint schemas
- **WHEN** client creates a TestSuite with `endpointRef` containing `method` and `relativeUrlPattern` but no `requestBodySchema` or `responseBodySchema`
- **THEN** system SHALL accept the request

#### Scenario: Schema-based validation is skipped when schema absent
- **WHEN** `endpointRef.requestBodySchema` is null
- **THEN** system SHALL skip template-vs-schema validation (no schema warnings generated)

### Requirement: Suite-level soft validation (`isValid` + `validationWarnings`)
The TestSuite response SHALL include `isValid` (boolean) and `validationWarnings` (structured list, same format as TestCase validation warnings). Suite-level validation covers **configuration correctness only**. Test-case presence is **not** a component of stored suite validity and does not affect `isValid` or `validationWarnings` in the suite GET response. A bound suite with zero runnable test cases MAY be `isValid = true`; the run path enforces the presence requirement at run-creation time (see `test-suite-runs` spec).

Suite-level configuration correctness covers: template + bindings, cross-checked against the **referenced dataset's** `testCaseSchema` (resolved via `DatasetSchemaProvider`) and the suite's `responseColumns` / `endpointRef`. This dimension is **independent of test case data**.

`isValid` SHALL be `true` only when the configuration dimension produces no warnings. `isValid` SHALL be recalculated on every suite create or update, and on dataset bind/detach and dataset schema revalidation (Phase 2). TestCase `isValid` (test-case **data** validation) is owned by the test-case domain and is never triggered by suite validation — the configuration and data layers remain independent.

Status: **Implemented**

#### Scenario: Create returns suite validation result
- **WHEN** client creates a TestSuite with `requestTemplate` and `inputBindings`
- **THEN** the response SHALL include `isValid` and `validationWarnings` reflecting suite-level configuration checks (urlTemplate, binding coverage, binding references against the dataset's schema, template conformance to endpoint schema)

#### Scenario: Update recalculates suite validation
- **WHEN** client updates `requestTemplate`, `inputBindings`, `endpointRef`, `responseColumns`, or `datasetId`
- **THEN** system SHALL recalculate `isValid` and `validationWarnings` for the suite against the (possibly newly-bound) dataset's schema

#### Scenario: Suite valid — no warnings
- **WHEN** all suite-level configuration checks pass (urlTemplate valid, all required variables bound, all bindings reference fields in the dataset's schema and template variables, template conforms to endpoint schema)
- **THEN** `isValid` SHALL be `true` and `validationWarnings` SHALL be empty

#### Scenario: Suite invalid — configuration warnings produced
- **WHEN** any suite-level configuration check fails (e.g., urlTemplate null, required variable unbound, binding references field not in dataset schema)
- **THEN** `isValid` SHALL be `false` and `validationWarnings` SHALL contain structured warning objects (`fieldName`, `path`, `message`, optional `code`)

#### Scenario: Suite with no request template produces warning
- **WHEN** TestSuite has `requestTemplate: null`
- **THEN** `isValid` SHALL be `false` and `validationWarnings` SHALL include a warning ("urlTemplate is required for request assembly") — same as when `requestTemplate` is non-null but `urlTemplate` is null

#### Scenario: Bound suite with no test cases is still config-valid
- **WHEN** a bound suite's configuration is valid but the referenced dataset has no test cases, or all are invalid, or all are excluded by `disabledTestCaseIds`
- **THEN** `isValid` SHALL be `true` and `validationWarnings` SHALL be empty (test-case presence is not a suite-validity concern; the run path enforces it separately)

#### Scenario: Unbound suite is not subject to the runnable-test-case rule
- **WHEN** a suite has `datasetId == null`
- **THEN** `isValid` SHALL reflect configuration checks only and SHALL NOT carry a `NO_TEST_CASES` warning

#### Scenario: Suite revalidated when dataset schema changes
- **WHEN** the referenced dataset's `testCaseSchema` is updated (via dataset PUT) and the dataset-rooted `RevalidationTask` runs Phase 2
- **THEN** the suite's `isValid` and `validationWarnings` SHALL be refreshed by the task's per-suite handler, reflecting configuration correctness against the new schema (see `datasets` spec for Phase 2 semantics)

### Requirement: No stored roles in field definitions
The service SHALL NOT store role annotations (INPUT, FACT) on field definitions. Field roles are emergent — derived by clients from `inputBindings` (fields referenced by bindings = inputs, fields without bindings = fact candidates).

#### Scenario: Field role derivation
- **WHEN** client requests a TestSuite with `testCaseSchema` and `inputBindings`
- **THEN** the response SHALL NOT include role information in field definitions; clients derive roles from bindings

### Requirement: Parameter definitions have required in
The service SHALL reject create/update requests when any entry in `endpointRef.parameters` has missing or null `in` (query, path, or header).

#### Scenario: Parameter without in
- **WHEN** client sends `endpointRef.parameters` with an entry that has null or missing `in`
- **THEN** system SHALL respond with HTTP 400 and indicate the invalid parameter

#### Scenario: Parameter with invalid in value
- **WHEN** client sends `endpointRef.parameters` with an entry that has an unsupported `in` value (e.g. `"cookie"`)
- **THEN** system SHALL respond with HTTP 400 (Bean Validation rejects invalid enum)

### Requirement: Suite type discriminator

The `test_suites` table SHALL have a `suite_type VARCHAR(20) NOT NULL DEFAULT 'DEPLOYMENT'` column that discriminates between HTTP deployment suites and MCP tool suites. Valid values: `DEPLOYMENT`, `MCP_TOOL`.
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

#### Scenario: Suite type is immutable after creation
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with a different `suiteType` than the existing suite
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that suite type cannot be changed

### Requirement: SuiteType enum in data model

`TestSuite.suiteType` SHALL be typed as `SuiteType` enum (not `String`) in the data model class. `TestSuiteRowMapper` SHALL call `SuiteType.fromValue(rs.getString("suite_type"))` to map the DB value. Callers of `testSuite.getSuiteType()` SHALL compare directly against enum constants (e.g. `SuiteType.MCP_TOOL`) rather than using string comparison or helper methods. `SuiteType.fromValue()` SHALL throw `IllegalArgumentException` on unrecognized values (fail-fast).
Status: **Implemented**

#### Scenario: DEPLOYMENT suite type mapped from DB
- **WHEN** the DB row has `suite_type = 'DEPLOYMENT'`
- **THEN** `TestSuite.suiteType` is `SuiteType.DEPLOYMENT`

#### Scenario: MCP_TOOL suite type mapped from DB
- **WHEN** the DB row has `suite_type = 'MCP_TOOL'`
- **THEN** `TestSuite.suiteType` is `SuiteType.MCP_TOOL`

#### Scenario: Invalid suite type fails fast
- **WHEN** the DB row has an unrecognized `suite_type` value
- **THEN** `SuiteType.fromValue()` throws `IllegalArgumentException`

#### Scenario: MCP branching uses enum comparison
- **WHEN** `EvaluationWorker` (or any service) needs to branch on MCP vs deployment
- **THEN** it compares `testSuite.getSuiteType() == SuiteType.MCP_TOOL` directly (no string comparison or helper method)

---

### Requirement: MCP deployment reference on MCP suites

MCP test suites SHALL store a `mcp_deployment_ref JSONB` containing the MCP-capable deployment metadata: `id` (required), `type` (required — `dial-toolset` or `dial-application`), `name` (optional), `transport` (optional). This reference can point to either a DIAL toolset or an application with MCP interface.
Status: **Implemented**

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
Status: **Implemented**

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
Status: **Implemented**

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

### Requirement: Suite response includes type and MCP fields

The `TestSuiteResponseDto` SHALL include `suiteType` and the MCP-specific fields when applicable.
Status: **Implemented**

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
Status: **Implemented**

#### Scenario: Filter by DEPLOYMENT type
- **WHEN** client calls `GET /api/v1/test-suites?filter=suiteType:eq:DEPLOYMENT`
- **THEN** the system SHALL return only DEPLOYMENT suites

#### Scenario: Filter by MCP_TOOL type
- **WHEN** client calls `GET /api/v1/test-suites?filter=suiteType:eq:MCP_TOOL`
- **THEN** the system SHALL return only MCP_TOOL suites

#### Scenario: No filter returns all types
- **WHEN** client calls `GET /api/v1/test-suites` without suiteType filter
- **THEN** the system SHALL return both DEPLOYMENT and MCP_TOOL suites

### Requirement: Test suite supports detach-dataset action
The system SHALL expose a `detach-dataset` action endpoint on the test-suite resource at `POST /api/v1/test-suites/{id}/detach-dataset`. Full contract is defined in the `detach-dataset` capability spec.

#### Scenario: Detach action is available on the test-suite resource
- **WHEN** a client calls `POST /api/v1/test-suites/{id}/detach-dataset`
- **THEN** the system processes the request according to the `detach-dataset` capability spec and returns `TestSuiteResponseDto`

### Requirement: Per-suite `overallScore` on the suite API
The suite create and update request bodies SHALL accept an optional `overallScore` field — a JSON object holding a structured-query `StructuredQuery` expression that defines the run-level `overall` metric score for the suite. The system SHALL persist it verbatim to `test_suites.overall_score` (JSONB) and SHALL return it, as a JSON object, on the suite read (`GET`) and in create/update responses. When omitted or `null`, the column SHALL be left/stored as NULL, preserving the built-in default behavior (see `metric-score-statistics`). `overallScore` SHALL NOT affect suite validity (`isValid`/`validationWarnings`); suite validity remains configuration-only. The expression SHALL be stored opaquely and SHALL NOT be validated as a runnable query at write time (a malformed or non-runnable expression surfaces at run-level computation, not at suite persistence).
Status: **Implemented**

#### Scenario: Set overallScore on update and read it back
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with an `overallScore` object referencing one specific metric column (`metric::<metricName>::<outputField>`)
- **THEN** system SHALL respond HTTP 200 with the updated suite whose `overallScore` equals the submitted object, and a subsequent `GET /api/v1/test-suites/{id}` SHALL return the same `overallScore`

#### Scenario: Set overallScore on create
- **WHEN** client calls `POST /api/v1/test-suites` with a valid body including an `overallScore` object
- **THEN** system SHALL create the suite persisting `overall_score` and return it in the response body

#### Scenario: Omitted overallScore leaves the column null
- **WHEN** client creates or updates a suite without an `overallScore` field
- **THEN** system SHALL store `overall_score` as NULL and the suite response SHALL omit `overallScore` (or return it as null)

#### Scenario: overallScore does not affect suite validity
- **WHEN** client sets `overallScore` on an otherwise valid suite
- **THEN** the suite's `isValid` and `validationWarnings` SHALL be unchanged by the presence or content of `overallScore`

## Implementation Notes
- REST API: `com.epam.aidial.evaluation.web.controller.TestSuiteController`
- Service: `com.epam.aidial.evaluation.service.domain.TestSuiteService`
- Repository: `com.epam.aidial.evaluation.data.db.repository.PostgresTestSuiteRepository`
- DB migrations: `V1.1__InitTestSuitesTable.sql`, `V1.2__TestSuiteAggregateTables.sql` (deployment_ref, endpoint_ref, test_cases_definition, version; test_cases, revalidation_tasks).
- Flyway migration: `V1.14__AddMcpFieldsToTestSuites.sql` — adds `suite_type`, `mcp_deployment_ref`, `tool_ref`, `argument_template` columns
- Modified model: `TestSuite` — add `suiteType`, `mcpDeploymentRef`, `toolRef`, `argumentTemplate` fields
- Modified DTOs: `TestSuiteRequestDto`, `TestSuiteResponseDto` — add new fields with type-specific validation
- Modified mapper: `TestSuiteMapper` — map new fields
- Modified repository: `PostgresTestSuiteRepository` — include new columns in SELECT/INSERT/UPDATE
- Modified service: `TestSuiteService` — type-specific validation and field handling
- FilterWhitelists: `TEST_SUITES` — `suiteType` (EQ, IN), `id` (EQ, IN), `description` (CO), `updatedAt` (GT, GTE, LT, LTE), plus existing `name`, `createdBy`, `createdAt`
- Multi-step: a `multiStep` boolean field on `data.db.model.TestSuite`, `TestSuiteRequestDto`, `TestSuiteResponseDto`, `SuiteSnapshotDto`. There is no per-turn binding field; the suite's single `inputBindings` and `requestTemplate` are reused per turn, and per-turn variation comes from array-valued test-case columns at execution time (see multi-step-conversation). Validation extends `service.domain.SuiteValidationService.validateDeploymentSuite` (messages-array body check + normal `inputBindings` cross-validation via the existing `BindingValidator`). Turn count is derived per test case at execution time and capped at `ValidationConstants.MAX_CONVERSATION_STEPS`.
- `overallScore` (per-suite): DTO fields `TestSuiteRequestDto.overallScore` / `TestSuiteResponseDto.overallScore` (`Map<String, Object>`), per the JSONB-as-object convention. Conversion via `JsonbMapper.mapOverallScore(Map)` (write) / `mapOverallScore(String)` (read). Mapping in `TestSuiteMapper` `toEntity` / `update` / `toDto` (clone already preserves it via `toCloneEntity`). Column pre-exists: `V1.23__AddOverallScoreToTestSuites.sql` (no new migration).

### Requirement: Multi-step suite configuration fields
A `DEPLOYMENT` `TestSuite` SHALL support a `multiStep` boolean (default `false`). When `multiStep == true`, the suite uses its regular single `inputBindings` and `requestTemplate`, exactly like a single-step suite; per-turn variation comes from array-valued test-case columns at execution time (see multi-step-conversation). The `multiStep` flag SHALL be accepted on create/update, persisted, returned in the suite response, and captured in the suite snapshot. There SHALL be no suite-level per-turn binding configuration.
Status: **Planned**

#### Scenario: Create a multi-step suite
- **WHEN** a client creates a `DEPLOYMENT` suite with `multiStep: true` and a single `inputBindings`
- **THEN** the suite SHALL persist the `multiStep` flag and `inputBindings`
- **AND** the suite response SHALL include `multiStep`

#### Scenario: multiStep defaults to false
- **WHEN** a client creates a suite without specifying `multiStep`
- **THEN** the stored `multiStep` SHALL be `false`
- **AND** the suite SHALL behave exactly as a single-step suite

### Requirement: Multi-step suite validation
For a suite with `multiStep == true`, suite soft-validation SHALL mark the suite invalid (adding a validation warning) when either of the following holds: the resolved `requestTemplate` body is not JSON with a top-level `messages` array; or the single `inputBindings` fail the existing per-binding cross-validation (template-variable match and test-case-schema resolution). A suite with no violations SHALL be valid. Turn count and array-shape checks are per-test-case data concerns evaluated at execution time, not suite-validation concerns.
Status: **Planned**

#### Scenario: Non-messages body is invalid for multi-step
- **WHEN** a suite has `multiStep == true` and its request body is multipart, url-encoded, or JSON without a top-level `messages` array
- **THEN** the suite SHALL be marked invalid with a validation warning

#### Scenario: Bad binding is invalid
- **WHEN** a suite has `multiStep == true` and a binding references a missing template variable or an unknown test-case field
- **THEN** the suite SHALL be marked invalid with a validation warning

#### Scenario: Valid multi-step suite
- **WHEN** a suite has `multiStep == true`, a JSON body with a top-level `messages` array, and `inputBindings` that pass cross-validation
- **THEN** the suite SHALL be valid

## Open Questions / TODO
- Add explicit validation rules for `name`/`description` in DTOs (current behavior depends on DTO constraints).
