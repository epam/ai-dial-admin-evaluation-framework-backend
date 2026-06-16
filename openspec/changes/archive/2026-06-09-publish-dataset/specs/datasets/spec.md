## ADDED Requirements

### Requirement: Dataset publish endpoint
The system SHALL provide `POST /api/v1/datasets/{id}/publish` to promote a dataset to `PUBLIC` visibility with an optional name and description update in a single atomic operation. The endpoint SHALL accept a body `{ "name": "<string>", "description": "<string>" }` where both fields are optional — when omitted the current values are preserved. The service SHALL acquire a `SELECT ... FOR UPDATE` row lock on the dataset row before reading and writing (same locking discipline as `PATCH /datasets/{id}/visibility`). The response SHALL be HTTP 200 with the updated `DatasetResponseDto`. When neither visibility nor name nor description change, the call is a no-op: the system SHALL return the current dataset without writing to the database and without bumping `version`.
Status: **Implemented**

#### Scenario: Publish PRIVATE dataset without metadata update
- **WHEN** client calls `POST /api/v1/datasets/{id}/publish` with an empty body `{}` on a PRIVATE dataset
- **THEN** system SHALL set `visibility` to `PUBLIC`, preserve existing `name` and `description`, bump `version`, update `updatedAt`, and return HTTP 200 with the updated `DatasetResponseDto`

#### Scenario: Publish PRIVATE dataset with name and description
- **WHEN** client calls `POST /api/v1/datasets/{id}/publish` with `{ "name": "My Dataset", "description": "For catalogue" }` on a PRIVATE dataset
- **THEN** system SHALL set `visibility` to `PUBLIC`, persist the provided `name` and `description`, bump `version`, and return HTTP 200 with the updated `DatasetResponseDto` reflecting all three changes

#### Scenario: Publish already-PUBLIC dataset with no metadata change is a no-op
- **WHEN** client calls `POST /api/v1/datasets/{id}/publish` with an empty body `{}` on a dataset already `PUBLIC`
- **THEN** system SHALL return HTTP 200 with the current `DatasetResponseDto` unchanged; `version` SHALL NOT be incremented

#### Scenario: Publish already-PUBLIC dataset with new name updates metadata
- **WHEN** client calls `POST /api/v1/datasets/{id}/publish` with `{ "name": "New Name" }` on a dataset already `PUBLIC`
- **THEN** system SHALL update `name`, bump `version`, update `updatedAt`, and return HTTP 200 with the updated `DatasetResponseDto`; `visibility` SHALL remain `PUBLIC`

#### Scenario: Publish with duplicate name returns 409
- **WHEN** client calls `POST /api/v1/datasets/{id}/publish` with a `name` that already exists on another dataset (case-insensitive)
- **THEN** system SHALL return HTTP 409 with error code `UNIQUE_CONSTRAINT_VIOLATION`

#### Scenario: Publish name exceeds maximum length returns 400
- **WHEN** client calls `POST /api/v1/datasets/{id}/publish` with a `name` longer than 263 characters
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Publish description exceeds maximum length returns 400
- **WHEN** client calls `POST /api/v1/datasets/{id}/publish` with a `description` longer than 2000 characters
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Publish non-existent dataset returns 404
- **WHEN** client calls `POST /api/v1/datasets/{id}/publish` for an unknown id
- **THEN** system SHALL return HTTP 404 with error code `NOT_FOUND`
