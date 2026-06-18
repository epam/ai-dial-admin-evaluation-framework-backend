# Dataset Clone

## Purpose
This spec describes the standalone dataset clone action: deep-copying a dataset (row + all test cases) into a new dataset that inherits the source's visibility and is unbound to any suite.

## Requirements

### Requirement: Clone a dataset
The system SHALL provide `POST /api/v1/datasets/{id}/clone` to deep-copy the dataset identified by `{id}` into a new dataset and return the new dataset as `DatasetResponseDto` with `201 Created` and an `ETag` header reflecting the clone's version.

Any source dataset MAY be cloned regardless of its visibility (PUBLIC or PRIVATE). The original source dataset SHALL remain unmodified.

The clone SHALL:
- inherit the source dataset's `visibility` (the clone is NOT forced to PRIVATE);
- be **unbound** to any test suite;
- inherit the source's `testCaseSchema`, `valid`, and `validationWarnings` verbatim, with no re-validation;
- start at `version` 0;
- copy every source test case with a freshly generated ID and file-reference rewrites from `@ef/datasets/{sourceId}/` to `@ef/datasets/{newId}/`.

DIAL files from the source dataset folder SHALL be copied to the new dataset folder before the DB transaction (file I/O is non-transactional).

The request body is a JSON object with two optional fields: `name` (`String`, max `ValidationConstants.MAX_DATASET_NAME_LENGTH`) and `description` (`String`, max 2000). The body MAY be empty or absent. When `name` is omitted or null, the clone name SHALL be derived via `DatasetCloneService.deriveCloneName(source.getName())`. When `description` is omitted or null, the clone SHALL inherit the source's `description` verbatim; overriding `description` SHALL NOT trigger re-validation.

#### Scenario: Clone a PUBLIC dataset with derived name
- **WHEN** `POST /api/v1/datasets/{id}/clone` is called with an empty body `{}` for a PUBLIC source dataset
- **THEN** `201 Created` is returned with a new PUBLIC dataset whose `id` differs from the source, whose name matches `deriveCloneName(source.getName())`, and an `ETag` header is present; the source dataset is unchanged

#### Scenario: Clone with explicit name
- **WHEN** `POST /api/v1/datasets/{id}/clone` is called with `{"name": "My Copy"}`
- **THEN** `201 Created` is returned with a new dataset named `"My Copy"`

#### Scenario: Clone with explicit description
- **WHEN** `POST /api/v1/datasets/{id}/clone` is called with `{"description": "Overridden desc"}`
- **THEN** `201 Created` is returned with a new dataset whose `description` is `"Overridden desc"`

#### Scenario: Clone without description inherits source description
- **WHEN** `POST /api/v1/datasets/{id}/clone` is called with no `description` field
- **THEN** the new dataset's `description` equals the source dataset's `description`

#### Scenario: Clone inherits source visibility
- **WHEN** a source dataset with visibility `V` is cloned
- **THEN** the new dataset's visibility equals `V` and the new dataset is not bound to any suite

#### Scenario: Test cases are copied with new IDs and rewritten file refs
- **WHEN** the clone operation succeeds
- **THEN** the new dataset contains a copy of every source test case, each with a new ID and file-refs rewritten from `@ef/datasets/{sourceId}/` to `@ef/datasets/{newId}/`; the source test cases are unchanged

#### Scenario: Name collision derives a numbered suffix
- **WHEN** `POST /api/v1/datasets/{id}/clone` is called twice for the same source without an explicit name
- **THEN** the first clone is named `"<source> (clone)"` and the second is named `"<source> (clone 2)"`

#### Scenario: Explicit name already exists returns 409
- **WHEN** `POST /api/v1/datasets/{id}/clone` is called with a `name` that already belongs to an existing dataset
- **THEN** `409 Conflict` with code `UNIQUE_CONSTRAINT_VIOLATION` is returned and no new dataset is persisted

#### Scenario: Name exceeding max length returns 400
- **WHEN** `POST /api/v1/datasets/{id}/clone` is called with a `name` longer than `ValidationConstants.MAX_DATASET_NAME_LENGTH`
- **THEN** `400 Bad Request` with code `VALIDATION_ERROR` is returned

#### Scenario: Non-existent source returns 404
- **WHEN** `POST /api/v1/datasets/{id}/clone` is called with an unknown dataset ID
- **THEN** `404 Not Found` is returned

#### Scenario: DB transaction failure cleans up copied files
- **WHEN** the DB transaction fails after DIAL files have been copied to the new dataset folder
- **THEN** the system performs best-effort deletion of the newly copied files; the source dataset remains unchanged
