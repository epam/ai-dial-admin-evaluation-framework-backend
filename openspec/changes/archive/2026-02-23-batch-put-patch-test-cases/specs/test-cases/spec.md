## ADDED Requirements

### Requirement: Batch update test cases (PUT)
The service SHALL allow batch full-update of test cases via `PUT /api/v1/test-suites/{testSuiteId}/test-cases` with a JSON array body. Each item SHALL contain an `id` (existing test case UUID) and all mutable fields (`testCaseName`, `data`, `requestTemplateOverride`, `inputBindingsOverride`, `enabled`). The operation SHALL be atomic (all-or-nothing within a single transaction). The operation SHALL NOT create new test cases — all IDs must reference existing test cases in the specified suite.

#### Scenario: Successful batch update
- **WHEN** client calls `PUT /api/v1/test-suites/{testSuiteId}/test-cases` with a valid array of items, each containing an `id` of an existing test case in the suite
- **THEN** system SHALL update all items, recalculate `isValid` for each, and return HTTP 200 with an ordered list of `TestCaseResponseDto` matching the input order

#### Scenario: Batch update recalculates validation per item
- **WHEN** client calls batch PUT and some items have data that fails schema validation
- **THEN** system SHALL save all items (including invalid ones with `isValid=false` and `validationWarnings`), and return the full list; validation warnings do NOT cause rollback

#### Scenario: Batch update with includeWarnings
- **WHEN** client calls `PUT .../test-cases?includeWarnings=true`
- **THEN** response items SHALL include `validationWarnings` for each test case; without the param, warnings SHALL be omitted

#### Scenario: Batch update with non-existent test case ID
- **WHEN** client calls batch PUT and any item `id` does not exist in the specified test suite
- **THEN** system SHALL respond with HTTP 404 and roll back all changes

#### Scenario: Batch update with test suite not found
- **WHEN** client calls batch PUT with a `testSuiteId` that does not exist
- **THEN** system SHALL respond with HTTP 404

#### Scenario: Batch update with empty array
- **WHEN** client calls batch PUT with an empty array `[]`
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR)

#### Scenario: Batch update exceeds max items
- **WHEN** client calls batch PUT with more items than the configured `test-case.batch.max-items` limit
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR) with a message indicating the maximum allowed batch size

#### Scenario: Batch update with duplicate IDs
- **WHEN** client calls batch PUT and the array contains two or more items with the same `id`
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR) with a message indicating duplicate IDs

#### Scenario: Batch update name uniqueness within batch
- **WHEN** client calls batch PUT and two items in the batch specify the same `testCaseName` (case-insensitive)
- **THEN** system SHALL respond with HTTP 409 (UNIQUE_CONSTRAINT_VIOLATION) with a message identifying the duplicate name

#### Scenario: Batch update name uniqueness with existing test cases
- **WHEN** client calls batch PUT and an item's `testCaseName` collides with an existing test case NOT included in the batch (case-insensitive, same suite)
- **THEN** system SHALL respond with HTTP 409 (UNIQUE_CONSTRAINT_VIOLATION) and roll back all changes

#### Scenario: Batch update validates override limits per item
- **WHEN** client calls batch PUT and any item's `requestTemplateOverride` or `inputBindingsOverride` exceeds configured limits
- **THEN** system SHALL respond with HTTP 400 and roll back all changes

### Requirement: Batch partial update test cases (PATCH)
The service SHALL allow batch partial-update of test cases via `PATCH /api/v1/test-suites/{testSuiteId}/test-cases` with a JSON array body. Each item SHALL be a JSON object containing a mandatory `id` field and any subset of patchable fields. Merge-patch semantics (RFC 7396) SHALL apply per item, identical to single-item PATCH. The operation SHALL be atomic (all-or-nothing within a single transaction).

#### Scenario: Successful batch patch
- **WHEN** client calls `PATCH /api/v1/test-suites/{testSuiteId}/test-cases` with a valid array of merge-patch items
- **THEN** system SHALL apply each patch to the corresponding test case, recalculate `isValid` for each, and return HTTP 200 with an ordered list of `TestCaseResponseDto` matching the input order

#### Scenario: Batch patch merges data at map level
- **WHEN** client calls batch PATCH with an item `{ "id": "...", "data": { "prompt": "new" } }`
- **THEN** system SHALL merge the `data` map (existing keys preserved, specified keys updated, keys set to null removed), identical to single PATCH behavior

#### Scenario: Batch patch clears override with null
- **WHEN** client calls batch PATCH with an item `{ "id": "...", "requestTemplateOverride": null }`
- **THEN** system SHALL clear the override (fall back to suite template) and recalculate `isValid`

#### Scenario: Batch patch with includeWarnings
- **WHEN** client calls `PATCH .../test-cases?includeWarnings=true`
- **THEN** response items SHALL include `validationWarnings` for each test case

#### Scenario: Batch patch with non-existent test case ID
- **WHEN** client calls batch PATCH and any item `id` does not exist in the specified test suite
- **THEN** system SHALL respond with HTTP 404 and roll back all changes

#### Scenario: Batch patch with missing id field
- **WHEN** client calls batch PATCH and any item does not contain an `id` field
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR)

#### Scenario: Batch patch with invalid id format
- **WHEN** client calls batch PATCH and any item's `id` is not a valid UUID
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR)

#### Scenario: Batch patch with empty array
- **WHEN** client calls batch PATCH with an empty array `[]`
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR)

#### Scenario: Batch patch exceeds max items
- **WHEN** client calls batch PATCH with more items than the configured limit
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR) with a message indicating the maximum allowed batch size

#### Scenario: Batch patch with duplicate IDs
- **WHEN** client calls batch PATCH and the array contains two or more items with the same `id`
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR)

#### Scenario: Batch patch final-state name uniqueness within batch
- **WHEN** client calls batch PATCH and the final names of two or more batch items are the same (case-insensitive) — considering the new name for items that include `testCaseName` in their patch, and the current (unchanged) name for items that do not
- **THEN** system SHALL respond with HTTP 409 (UNIQUE_CONSTRAINT_VIOLATION) with a message identifying the duplicate name

#### Scenario: Batch patch name uniqueness with existing test cases
- **WHEN** client calls batch PATCH and any item's final `testCaseName` collides with an existing test case NOT included in the batch (case-insensitive, same suite)
- **THEN** system SHALL respond with HTTP 409 (UNIQUE_CONSTRAINT_VIOLATION) and roll back all changes

### Requirement: Configurable batch size limit
The service SHALL enforce a configurable maximum number of items per batch request via `test-case.batch.max-items` application property. The default value SHALL be 256.

#### Scenario: Default batch limit
- **WHEN** no `test-case.batch.max-items` property is configured
- **THEN** system SHALL use the default limit of 256

#### Scenario: Custom batch limit
- **WHEN** `test-case.batch.max-items` is set to 100
- **THEN** system SHALL reject batch requests with more than 100 items

#### Scenario: Batch limit applied to both PUT and PATCH
- **WHEN** batch limit is configured
- **THEN** system SHALL apply the same limit to both batch PUT and batch PATCH endpoints
