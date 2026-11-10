# Dataset Clone

## Purpose
This spec describes the standalone dataset clone action: deep-copying a PUBLIC dataset (row + all test cases) into a new unbound PUBLIC dataset. PRIVATE datasets cannot be cloned standalone (the clone would be unbound, violating the single-suite-binding invariant); they are cloned only alongside their owning suite (see test-suite-clone).

## Requirements

### Requirement: Clone a dataset

Status: **Implemented**

The system SHALL provide `POST /api/v1/datasets/{id}/clone` to deep-copy the dataset identified by `{id}` into a new dataset and return the new dataset as `DatasetResponseDto` with `201 Created` and an `ETag` header reflecting the clone's version.

Only PUBLIC datasets MAY be cloned through this endpoint. Cloning a PRIVATE dataset SHALL be rejected with `400 Bad Request` (code `PRIVATE_DATASET_REQUIRES_SUITE_BINDING`) before any side effect, because the resulting clone would be unbound and a PRIVATE dataset must be bound to exactly one suite. The original source dataset SHALL remain unmodified.

The clone SHALL:
- be PUBLIC (inheriting the source's PUBLIC visibility);
- be **unbound** to any test suite;
- inherit the source's `testCaseSchema`, `valid`, and `validationWarnings` verbatim, with no re-validation;
- start at `version` 0;
- copy every source test case with a freshly generated ID and file-reference rewrites from `@ef/datasets/{sourceId}/` to `@ef/datasets/{newId}/`.

DIAL files from the source dataset folder SHALL be copied to the new dataset folder before the DB transaction (file I/O is non-transactional).

The request body is a JSON object with two optional fields: `name` (`String`, max `ValidationConstants.MAX_DATASET_NAME_LENGTH`) and `description` (`String`, max 2000). The body MAY be empty or absent. When `name` is omitted or null, the clone name SHALL be derived via `DatasetCloneService.deriveCloneName(source.getName())`. When `description` is omitted or null, the clone SHALL inherit the source's `description` verbatim; overriding `description` SHALL NOT trigger re-validation.

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| Clone a PUBLIC dataset with derived name | `POST /api/v1/datasets/{id}/clone` is called with an empty body `{}` for a PUBLIC source dataset | `201 Created` is returned with a new PUBLIC dataset whose `id` differs from the source, whose name matches `deriveCloneName(source.getName())`, and an `ETag` header is present; the source dataset is unchanged |
| Clone with explicit name | `POST /api/v1/datasets/{id}/clone` is called with `{"name": "My Copy"}` | `201 Created` is returned with a new dataset named `"My Copy"` |
| Clone with explicit description | `POST /api/v1/datasets/{id}/clone` is called with `{"description": "Overridden desc"}` | `201 Created` is returned with a new dataset whose `description` is `"Overridden desc"` |
| Clone without description inherits source description | `POST /api/v1/datasets/{id}/clone` is called with no `description` field | the new dataset's `description` equals the source dataset's `description` |
| Clone is PUBLIC and unbound | a PUBLIC source dataset is cloned | the new dataset's visibility is PUBLIC and the new dataset is not bound to any suite |
| Cloning a PRIVATE dataset is rejected | `POST /api/v1/datasets/{id}/clone` is called for a PRIVATE source dataset | `400 Bad Request` with code `PRIVATE_DATASET_REQUIRES_SUITE_BINDING` is returned, no new dataset is persisted, and no DIAL files are copied |
| Test cases are copied with new IDs and rewritten file refs | the clone operation succeeds | the new dataset contains a copy of every source test case, each with a new ID and file-refs rewritten from `@ef/datasets/{sourceId}/` to `@ef/datasets/{newId}/`; the source test cases are unchanged |
| Name collision derives a numbered suffix | `POST /api/v1/datasets/{id}/clone` is called twice for the same source without an explicit name | the first clone is named `"<source> (clone)"` and the second is named `"<source> (clone 2)"` |
| Explicit name already exists returns 409 | `POST /api/v1/datasets/{id}/clone` is called with a `name` that already belongs to an existing dataset | `409 Conflict` with code `UNIQUE_CONSTRAINT_VIOLATION` is returned and no new dataset is persisted |
| Name exceeding max length returns 400 | `POST /api/v1/datasets/{id}/clone` is called with a `name` longer than `ValidationConstants.MAX_DATASET_NAME_LENGTH` | `400 Bad Request` with code `VALIDATION_ERROR` is returned |
| Non-existent source returns 404 | `POST /api/v1/datasets/{id}/clone` is called with an unknown dataset ID | `404 Not Found` is returned |
| DB transaction failure cleans up copied files | the DB transaction fails after DIAL files have been copied to the new dataset folder | the system performs best-effort deletion of the newly copied files; the source dataset remains unchanged |
| SKILL column survives clone | a suite or dataset with a `SKILL` column and values is cloned | the clone SHALL have the same `SKILL` column type and the same string values, and no file copy SHALL be attempted for them |
