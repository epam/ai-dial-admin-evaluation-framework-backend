# Test Suite Clone

## Purpose
This spec describes the deep-copy (clone) endpoint for test suites, covering entity cloning, file copying, file reference rewriting, and post-clone revalidation.

Status: **Implemented**

## Key Terms
- **Clone**: A deep copy of a test suite including its test cases, TSMDs, and DIAL files. Produces a new independent suite with new UUIDs for all entities.
- **File reference rewriting**: String replacement of `@ef/suites/{sourceId}/` with `@ef/suites/{newId}/` in all JSONB fields that may contain DIAL file references.

## Requirements

### Requirement: Clone a TestSuite
The system SHALL provide `POST /api/v1/test-suites/{sourceId}/clone` that creates a deep copy of the source suite. By default the cloned suite SHALL reference the **same `Dataset`** as the source suite (test cases are not copied; they are shared via the dataset reference). **Exception:** when the source suite is bound to a **PRIVATE** dataset AND the request supplies no `datasetId` override, the system SHALL clone the dataset (see "Clone of a PRIVATE-dataset suite clones the dataset") and bind the cloned suite to the new dataset. The request body SHALL accept a required `name` field and optional override fields. The response SHALL be HTTP 201 with `TestSuiteUpdateResultDto` containing the cloned suite.
Status: **Planned**

#### Scenario: Successful clone with name only (PUBLIC or unbound dataset)
- **WHEN** client calls `POST /api/v1/test-suites/{sourceId}/clone` with `{"name": "My Clone"}` and the source's dataset is PUBLIC or the source is unbound
- **THEN** system SHALL create a new test suite with all suite-level configuration inherited from the source, `name` set to "My Clone", `datasetId` inherited from the source, `disabledTestCaseIds` inherited from the source, `version` set to 0, fresh `createdAt`/`updatedAt` timestamps, and `createdBy` from JWT (or "anonymous" in no-security mode)
- **AND** system SHALL NOT create a new dataset and SHALL NOT copy any test-case rows
- **AND** system SHALL return HTTP 201 with the new suite

#### Scenario: Clone with field overrides
- **WHEN** client calls `POST /api/v1/test-suites/{sourceId}/clone` with `{"name": "Clone", "description": "New desc", "deploymentRef": {"id": "new-deployment-id", "name": "New Deployment"}}`
- **THEN** system SHALL create a new suite with `description` and `deploymentRef` overridden, all other fields (including `datasetId`) inherited from the source

#### Scenario: Clone with explicit datasetId override (PUBLIC or unbound source)
- **WHEN** client calls `POST /api/v1/test-suites/{sourceId}/clone` with `{"name": "Clone", "datasetId": "<other-dataset-id>"}`, the supplied dataset exists, and the source's dataset is PUBLIC or the source is unbound
- **THEN** system SHALL create the cloned suite bound to the supplied `datasetId` instead of the source's; the dataset SHALL NOT be cloned; suite-level synchronous validation runs against the supplied dataset's schema

#### Scenario: datasetId override redirecting a PRIVATE-dataset clone is rejected
- **WHEN** the source suite is bound to a PRIVATE dataset AND the clone request supplies a `datasetId` that differs from the source's bound dataset id
- **THEN** system SHALL reject the request with HTTP 409 and error code `PRIVATE_DATASET_REBIND_FORBIDDEN` (matching the suite-update rebind rule) and SHALL NOT create any suite, dataset, or test-case rows
- **AND** the source dataset SHALL remain bound only to the source suite (no silent rebind; the system invariant that a PRIVATE dataset is never orphaned is preserved)

#### Scenario: datasetId override naming the source's own PRIVATE dataset clones it
- **WHEN** the source suite is bound to a PRIVATE dataset AND the clone request supplies a `datasetId` equal to that same PRIVATE dataset id
- **THEN** system SHALL behave as if `datasetId` were omitted: it SHALL clone the PRIVATE dataset (new PRIVATE dataset + copied test cases) and bind the clone to the new dataset

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

### Requirement: Clone of a PRIVATE-dataset suite clones the dataset
When the source suite is bound to a dataset whose `visibility` is `PRIVATE` and the clone request supplies no `datasetId` override, the system SHALL create a new PRIVATE dataset that is an independent copy of the source dataset, copy all of the source dataset's test cases into it with new identifiers, copy dataset-scoped files, and bind the cloned suite to the new dataset. The dataset row, copied test cases, cloned suite, and cloned TSMDs SHALL all be persisted within a single transaction; DIAL file copies SHALL occur before the transaction and SHALL be cleaned up best-effort on failure.
Status: **Planned**

#### Scenario: New PRIVATE dataset is created and bound to the clone
- **WHEN** client clones a suite bound to a PRIVATE dataset named "My Data" with no `datasetId` override
- **THEN** system SHALL create a new dataset with `visibility = PRIVATE`, `name = "My Data (clone)"`, and the source dataset's `testCaseSchema`, `valid`, and `validationWarnings` copied verbatim
- **AND** the cloned suite's `datasetId` SHALL reference the new dataset
- **AND** the source dataset SHALL be left unchanged and SHALL remain bound only to the source suite
- **AND** the request SHALL succeed with HTTP 201 (no `PRIVATE_DATASET_ALREADY_BOUND` error)

#### Scenario: Test cases are copied with new identifiers
- **WHEN** the source PRIVATE dataset has N test cases and the clone proceeds
- **THEN** the new dataset SHALL contain N test cases, each with a new UUID, `datasetId` set to the new dataset, and `testCaseName`, `data`, `valid`, and `validationWarnings` copied from the corresponding source test case
- **AND** test cases SHALL be read and inserted in paginated batches

#### Scenario: disabledTestCaseIds are remapped to the new test-case identifiers
- **WHEN** the source suite's `disabledTestCaseIds` references one or more source test cases and the dataset is cloned
- **THEN** the cloned suite's `disabledTestCaseIds` SHALL reference the corresponding new test-case identifiers (not the source identifiers)

#### Scenario: Dataset-scoped file references are copied and rewritten
- **WHEN** a source test case's `data` contains a dataset-scoped file reference `@ef/datasets/{sourceDatasetId}/file.csv`
- **THEN** system SHALL copy the file to the new dataset's folder and the cloned test case's `data` SHALL reference `@ef/datasets/{newDatasetId}/file.csv`

#### Scenario: Clone dataset name collision is deduplicated
- **WHEN** a dataset named "My Data (clone)" already exists at clone time
- **THEN** system SHALL derive a unique name by appending a numeric suffix (e.g. "My Data (clone 2)") within the dataset name length limit

#### Scenario: Failure rolls back rows and cleans up copied files
- **WHEN** the clone transaction fails after dataset files were copied
- **THEN** the new dataset row and copied test-case rows SHALL NOT be persisted (transaction rollback)
- **AND** system SHALL attempt best-effort deletion of the files copied to the new dataset's folder

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

### Requirement: TSMD cloning
The system SHALL clone all test suite metric definitions from the source suite into the new suite. For each source TSMD, every field SHALL be copied verbatim into the cloned TSMD, **except**:
- `id` — SHALL be a freshly generated UUID
- `testSuiteId` — SHALL be the new suite's ID
- `createdAt`/`updatedAt` (or equivalent) — SHALL be fresh timestamps
- `configBindings`/`inputBindings` — copied but with suite-scoped file references rewritten per the "File reference rewriting" requirement below
- `isValid`/`validationWarnings` — determined by the revalidation rules below, not copied unconditionally

This default-copy rule means a new TSMD field introduced by a future change (e.g. `condition`, the JSONata conditional-metric-execution gating expression) is cloned automatically without requiring this spec to be updated — unless that field needs its own exception, which MUST be added to the list above.

The cloned TSMD's `isValid` and `validationWarnings` SHALL be determined by whether **TSMD revalidation is required**:

- **TSMD revalidation is required** when the clone request supplies a `datasetId` override that differs from the source suite's `datasetId`, OR supplies a `responseColumns` override. In this case the cloned TSMD's `isValid` and `validationWarnings` SHALL be recomputed synchronously, inside the clone transaction, against the resolved dataset's `testCaseSchema` and the cloned suite's `responseColumns`.
- **TSMD revalidation is NOT required** when the clone request supplies neither a differing `datasetId` override nor a `responseColumns` override (including the private-dataset auto-clone, where the cloned dataset's `testCaseSchema` is copied verbatim). In this case every input to TSMD validation is identical to the source, and the cloned TSMD's `isValid` and `validationWarnings` SHALL be copied verbatim from the source TSMD.

In both cases validation is synchronous and within the clone transaction; the clone SHALL NOT spawn an async `RevalidationTask` for TSMDs. Status: **Implemented**

#### Scenario: TSMDs are deep-copied
- **WHEN** source suite has 2 TSMDs
- **THEN** cloned suite SHALL have 2 TSMDs with identical configuration but new UUIDs

#### Scenario: TSMD condition is preserved
- **WHEN** a source TSMD has a non-null `condition` (JSONata gating expression)
- **THEN** the cloned TSMD SHALL carry the identical `condition` value, regardless of whether TSMD revalidation is required

#### Scenario: Paginated TSMD copying
- **WHEN** source suite has more TSMDs than the configured batch size
- **THEN** system SHALL read and insert TSMDs in paginated batches

#### Scenario: TSMD referencing deleted metric declaration is skipped
- **WHEN** source suite has a TSMD whose `metricDeclarationId` no longer exists in `metric_declarations`
- **THEN** that TSMD SHALL be silently excluded from the cloned suite (the INNER JOIN on `metric_declarations` excludes the orphaned row)
- **AND** no error SHALL be thrown; remaining valid TSMDs are cloned normally

#### Scenario: Validity is copied verbatim on a vanilla clone
- **WHEN** client clones with `{"name": "Copy"}` only (no `datasetId` or `responseColumns` override) and a source TSMD has `isValid = true` with empty `validationWarnings`
- **THEN** the corresponding cloned TSMD SHALL have `isValid = true` and the same `validationWarnings`, with no recompute performed

#### Scenario: Invalid source TSMD validity is preserved on a vanilla clone
- **WHEN** client clones with no `datasetId` or `responseColumns` override and a source TSMD has `isValid = false` with one or more `validationWarnings`
- **THEN** the corresponding cloned TSMD SHALL have `isValid = false` and the same `validationWarnings` copied verbatim

#### Scenario: Validity is copied verbatim on a private-dataset auto-clone
- **WHEN** the source suite is bound to a PRIVATE dataset, the client supplies no `datasetId` override, and the dataset is auto-cloned with its `testCaseSchema` copied verbatim
- **THEN** each cloned TSMD SHALL have its `isValid` and `validationWarnings` copied verbatim from the source TSMD (no recompute)

#### Scenario: Validity is recomputed when datasetId is overridden
- **WHEN** client clones with `{"name": "Copy", "datasetId": "<other-id>"}` where the supplied dataset's `testCaseSchema` differs such that a TSMD's test-case-bound parameter no longer resolves
- **THEN** that cloned TSMD's `isValid` and `validationWarnings` SHALL reflect synchronous revalidation against the supplied dataset's schema, independent of the source TSMD's stored validity

#### Scenario: Validity is recomputed when responseColumns is overridden
- **WHEN** client clones with a `responseColumns` override that removes a column referenced by a TSMD's response-bound parameter
- **THEN** that cloned TSMD's `isValid` SHALL be `false` with a corresponding unresolved-reference warning, reflecting synchronous revalidation against the overridden response columns

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

#### Scenario: Unbound source validates against an empty schema
- **WHEN** the source suite is unbound (no `datasetId`) and no `datasetId` override is supplied, so no dataset schema can be resolved
- **THEN** suite-level validation SHALL run against an empty schema (no schema lookup is attempted) and the clone SHALL succeed

### Requirement: Clone enforces hard write-time validation on the effective suite
`POST /api/v1/test-suites/{id}/clone` SHALL run the same **hard** write-time validation that `POST /api/v1/test-suites` and `PUT /api/v1/test-suites/{id}` run, evaluated against the **effective post-override suite** — the source suite's inherited configuration merged with whatever the clone request overrides. A violation SHALL be rejected with HTTP 400 (`VALIDATION_ERROR`) and the clone SHALL NOT be created; no partially-created suite, dataset or file copy SHALL remain.

The rules enforced SHALL be exactly those of create/update, notably: global response-column name uniqueness across the suite's whole request chain (the `responseColumns` override or inherited list, plus every `additionalRequests[i].responseColumns`); the suite-wide response-column union cap; reserved response-column names and JSONata expression syntax; request-template size and input-binding count limits per request; duplicate `templateVariable` within one request's bindings; endpoint-schema JSON-Schema validity; and the chain-length cap.

This is distinct from, and runs before, the synchronous **soft** validation that determines the clone's `isValid` / `validationWarnings` (see "Post-clone validation is synchronous only"): a hard violation aborts the clone with a 400, whereas a soft finding creates the clone and records a warning. The invariant is that a clone can never be persisted in a configuration that a `PUT` of the same effective content would reject.
Status: **Implemented**

#### Scenario: Override collides with an inherited additional request's column
- **WHEN** client clones a suite whose inherited `additionalRequests[0]` declares response column `answer`, supplying a `responseColumns` override that also declares `answer`
- **THEN** the system SHALL respond HTTP 400 (`VALIDATION_ERROR`) naming the duplicate column, and SHALL NOT create the clone

#### Scenario: Effective union exceeds the response-column cap
- **WHEN** the clone's effective suite — `responseColumns` override plus the inherited chain's columns — exceeds the suite-wide response-column union cap
- **THEN** the system SHALL respond HTTP 400 (`VALIDATION_ERROR`) and SHALL NOT create the clone

#### Scenario: Inherited-only violation is still caught
- **WHEN** client clones with `{"name": "Copy"}` and no overrides
- **THEN** hard validation SHALL still run against the inherited configuration, so a source suite that somehow holds an invalid chain cannot be propagated by cloning

#### Scenario: Valid clone is unaffected
- **WHEN** client clones a suite whose effective configuration violates no hard rule
- **THEN** the clone SHALL be created exactly as before this requirement existed, with its `isValid` / `validationWarnings` still determined by the synchronous soft validation

#### Scenario: Rejected clone leaves no residue
- **WHEN** a clone is rejected by hard validation
- **THEN** no cloned suite row, no cloned dataset, no cloned test cases, no cloned TSMDs and no copied suite-scoped files SHALL exist

### Requirement: Test suite runs are NOT cloned
The system SHALL NOT copy or associate any test suite runs from the source suite with the cloned suite. The cloned suite starts with zero runs. Status: **Implemented**

#### Scenario: No runs on cloned suite
- **WHEN** source suite has 5 completed test suite runs
- **THEN** cloned suite SHALL have 0 test suite runs

### Requirement: OpenAPI documentation
The clone endpoint SHALL have OpenAPI annotations including operation summary, request/response schemas, and example JSON files under `src/main/resources/openapi/examples/`. Status: **Implemented**

#### Scenario: Swagger UI shows clone endpoint
- **WHEN** user opens Swagger UI
- **THEN** the clone endpoint SHALL appear with description, request body schema, and response examples
