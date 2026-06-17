# Test Case Bulk Delete by IDs

## Purpose

Describes the bulk delete endpoint that accepts an explicit list of test case UUIDs and deletes all matching rows in a single request, returning per-ID outcome (deleted vs. not found) with partial-success semantics.

## Requirements

### Requirement: Bulk delete test cases by explicit UUID list
The service SHALL provide a `DELETE /api/v1/datasets/{datasetId}/test-cases:bulk` endpoint that accepts a JSON body containing a list of test case UUIDs and deletes all matching rows belonging to the specified dataset in a single database transaction. The operation uses partial-success semantics: IDs present in the dataset are deleted; IDs absent from the dataset are reported as `notFound` without aborting the transaction. The response SHALL always be HTTP 200 with a body containing two UUID lists — `deleted` (IDs that were removed) and `notFound` (IDs that were not found in the dataset) — both preserving the input ordering. No Flyway migration is required; the endpoint operates on the existing `test_cases` table.

#### Scenario: All requested IDs found and deleted
- **WHEN** client calls `DELETE /api/v1/datasets/{datasetId}/test-cases:bulk` with a body `{"ids": [id1, id2]}` and both IDs exist in the dataset
- **THEN** system SHALL delete both rows, return HTTP 200 with `{"deleted": [id1, id2], "notFound": []}`, and the test cases SHALL no longer be retrievable

#### Scenario: Mixed — some IDs found, some not found
- **WHEN** client calls the endpoint with a body containing IDs where a subset exists in the dataset and the rest do not
- **THEN** system SHALL delete only the existing rows, return HTTP 200 with `deleted` containing the found-and-removed IDs and `notFound` containing the remaining IDs, in input order

#### Scenario: No requested IDs found
- **WHEN** client calls the endpoint with IDs that do not exist in the dataset
- **THEN** system SHALL perform no deletions, return HTTP 200 with `{"deleted": [], "notFound": [<all input ids>]}`

#### Scenario: Dataset not found
- **WHEN** client calls the endpoint with a `datasetId` that does not exist
- **THEN** system SHALL return HTTP 404 (NOT_FOUND) without executing any deletion

#### Scenario: Input ordering preserved in response
- **WHEN** client calls the endpoint with IDs `[A, B, C]` where A and C exist but B does not
- **THEN** system SHALL return `{"deleted": [A, C], "notFound": [B]}` preserving the input sequence within each list

### Requirement: Request body validation for bulk delete by IDs
The service SHALL validate the request body before executing any database operation. Validation errors SHALL be returned as HTTP 400 (VALIDATION_ERROR) without performing any deletions. The dedicated validator component (`TestCaseBulkDeleteValidator`) in `service.domain` SHALL enforce all validation rules.

#### Scenario: Empty IDs list rejected
- **WHEN** client calls the endpoint with `{"ids": []}` or omits `ids`
- **THEN** system SHALL return HTTP 400 (VALIDATION_ERROR) with an appropriate message

#### Scenario: Null element in IDs list rejected
- **WHEN** client calls the endpoint with a body containing a null element in the `ids` array (e.g., `{"ids": [null, "uuid"]}`)
- **THEN** system SHALL return HTTP 400 (VALIDATION_ERROR)

#### Scenario: Duplicate IDs rejected
- **WHEN** client calls the endpoint with a body that contains the same UUID more than once in the `ids` array
- **THEN** system SHALL return HTTP 400 (VALIDATION_ERROR) identifying the duplicate

#### Scenario: IDs count exceeds configured cap
- **WHEN** client calls the endpoint with a body whose `ids` count exceeds the `test-case.bulk.max-delete-ids` configuration value
- **THEN** system SHALL return HTTP 400 (VALIDATION_ERROR) before executing any deletion

### Requirement: Configurable cap on IDs per bulk delete request
The service SHALL enforce a configurable upper limit on the number of UUIDs accepted in a single bulk delete request, controlled by the property `test-case.bulk.max-delete-ids` (default: 10 000). Operators SHALL be able to increase or decrease this cap without redeploying code. The cap SHALL be validated before any database access.

#### Scenario: Request within cap succeeds
- **WHEN** client calls the endpoint with an `ids` count at or below `test-case.bulk.max-delete-ids`
- **THEN** system SHALL process the request normally

#### Scenario: Request exceeding cap is rejected
- **WHEN** client calls the endpoint with an `ids` count strictly greater than `test-case.bulk.max-delete-ids`
- **THEN** system SHALL return HTTP 400 (VALIDATION_ERROR) stating the cap and the received count

### Requirement: Bulk delete operates within a single transaction
The service SHALL execute the bulk delete in a single `@Transactional("metaTransactionManager")` boundary. Either all eligible rows are deleted and the transaction commits, or a system error rolls back the entire operation. Absent IDs do not constitute an error and SHALL NOT trigger a rollback.

#### Scenario: System error causes full rollback
- **WHEN** an unexpected database error occurs during the bulk delete execution
- **THEN** system SHALL roll back the transaction so no rows are deleted, and return an HTTP 5xx error

#### Scenario: Not-found IDs do not cause rollback
- **WHEN** some IDs in the request are absent from the dataset but no system error occurs
- **THEN** system SHALL commit the transaction, deleting the IDs that were present
