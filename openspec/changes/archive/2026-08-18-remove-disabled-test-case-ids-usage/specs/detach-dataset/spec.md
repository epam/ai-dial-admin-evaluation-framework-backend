## MODIFIED Requirements

### Requirement: Detach suite from PUBLIC dataset
Status: Implemented

The system SHALL provide `POST /api/v1/test-suites/{id}/detach-dataset` to fork the suite's bound PUBLIC dataset into a new PRIVATE clone and rebind the suite to the clone in a single atomic operation. The original PUBLIC dataset SHALL remain unmodified.

The request body is a JSON object with an optional `name` field (`String`, max `ValidationConstants.MAX_DATASET_NAME_LENGTH`). When `name` is omitted or null, the clone name SHALL be derived via `DatasetCloneService.deriveCloneName(source.getName())`. The response is `200 OK` with `TestSuiteResponseDto` reflecting the updated `datasetId`.

The clone SHALL be PRIVATE, inherit the source dataset's `testCaseSchema`, `valid`, and `validationWarnings` verbatim (no re-validation), and have its test cases copied with fresh IDs and file-reference rewrites from `@ef/datasets/{sourceId}/` to `@ef/datasets/{newId}/`. Detach SHALL rebind `dataset_id` only — no suite-level state references individual test-case identifiers, so no id remapping is performed. DIAL files from the source dataset folder SHALL be copied to the new dataset folder before the DB transaction.

#### Scenario: Detach from PUBLIC dataset with derived name
- **WHEN** `POST /api/v1/test-suites/{id}/detach-dataset` is called with `{}` for a suite bound to a PUBLIC dataset
- **THEN** `200 OK` is returned with the suite's `datasetId` pointing to a new PRIVATE dataset whose name matches `deriveCloneName(source.getName())`; the original PUBLIC dataset is unchanged

#### Scenario: Detach from PUBLIC dataset with custom name
- **WHEN** `POST /api/v1/test-suites/{id}/detach-dataset` is called with `{"name": "My Private Copy"}` for a suite bound to a PUBLIC dataset
- **THEN** `200 OK` is returned with the new PRIVATE dataset named `"My Private Copy"`

#### Scenario: Test cases are copied with remapped IDs
- **WHEN** the detach operation succeeds
- **THEN** the new PRIVATE dataset contains a copy of every test case from the source, each with a new ID and file-refs rewritten to `@ef/datasets/{newId}/`; the suite's `dataset_id` points at the new dataset and no other suite column is rewritten

#### Scenario: Suite runnable subset after detach is filter-defined only
- **WHEN** a suite whose `test_suites.disabled_test_case_ids` holds a legacy non-empty value is detached from its PUBLIC dataset
- **THEN** the detached suite's runnable subset SHALL be the valid test cases of the new PRIVATE dataset matching its `testCaseFilter` when set, unaffected by the retained legacy value

#### Scenario: Suite bound to PRIVATE dataset returns 409
- **WHEN** `POST /api/v1/test-suites/{id}/detach-dataset` is called for a suite bound to a PRIVATE dataset
- **THEN** `409 Conflict` is returned (the dataset is already exclusive)

#### Scenario: Suite with no dataset bound returns 409
- **WHEN** `POST /api/v1/test-suites/{id}/detach-dataset` is called for a suite that has no bound dataset
- **THEN** `409 Conflict` is returned

#### Scenario: Non-existent suite returns 404
- **WHEN** `POST /api/v1/test-suites/{id}/detach-dataset` is called with an unknown suite ID
- **THEN** `404 Not Found` is returned

#### Scenario: DB transaction failure cleans up copied files
- **WHEN** the DB transaction fails after DIAL files have been copied to the new dataset folder
- **THEN** the system performs best-effort deletion of the newly copied files; the original dataset and suite remain unchanged

## Implementation notes
- Orchestration is unchanged in shape: `TestSuiteService.detachDataset` runs pre-TX
  `DatasetCloneService.copyDatasetFiles`, then a `TransactionTemplate` (`metaTransactionManager`) running
  `DatasetCloneService.cloneRowAndTestCases` (now `void`) + `TestSuiteRepository.updateDatasetId(suiteId,
  newDatasetId, updatedAt)` — the remapped-exclusions parameter is removed from that signature — with
  best-effort `FileService.deleteAllByDatasetId` cleanup on failure.
