## MODIFIED Requirements

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

## ADDED Requirements

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
```

## Implementation notes
- Orchestration: `service.domain.TestSuiteCloneService` (auto-clone decision, validation against source schema, `disabledTestCaseIds` remap, cleanup). The remap goes through a new PUBLIC `TestSuiteMapper.remapDisabledIds(String json, Map<UUID, UUID> idMap)` that reuses the mapper's existing private serialize/deserialize round-trip (not `JsonbMapper`, which has no UUID-list method).
- New `service.domain.DatasetCloneService` performs the dataset-row insert, paginated test-case copy with `@ef/datasets` ref rewrite, clone-name derivation, and returns the old→new test-case id map. Uses `DatasetRepository`, `TestCaseRepository.findBatchByDatasetId`/`batchInsert`, `FileService`, `RevalidationProperties`.
- File copy: new `FileService.copyFilesBetweenDatasets`; cleanup via existing `FileService.deleteAllByDatasetId`.
- Trigger: existing `tg_test_suites_private_binding_guard` (`V1.22__IntroduceDataset.sql`) passes because the clone binds a freshly created dataset.
