Dataset# Dataset File Storage

## Purpose
This spec describes dataset-scoped file management for the Evaluation Framework. Covers the dataset-scoped file REST API (upload/list/download/delete) that proxies to DIAL Core via `DialFileClient`, best-effort cascade cleanup of EF-managed files on dataset delete, and the per-dataset file count configuration. It is the dataset peer of the suite-scoped `dial-file-storage` capability.

Status: **Implemented**

## Requirements

### Requirement: Dataset-scoped file management REST API

The system SHALL provide file management endpoints scoped to datasets. These endpoints proxy file operations to DIAL Core via `DialFileClient`, using the EF service API key, and mirror the suite-scoped endpoints defined in the `dial-file-storage` capability.

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/datasets/{datasetId}/files` | Upload file |
| `GET` | `/api/v1/datasets/{datasetId}/files` | List files |
| `GET` | `/api/v1/datasets/{datasetId}/files/{filename}` | Download file |
| `DELETE` | `/api/v1/datasets/{datasetId}/files/{filename}` | Delete file |

Files SHALL be stored in DIAL at `{efBucket}/datasets/{datasetId}/{filename}`. The `{filename}` path variable SHALL use Spring MVC regex pattern `{filename:.+}` to prevent suffix pattern truncation at the last dot.

Filename validation rules (allowed characters, max length 255, no leading/trailing whitespace) and file-size limits SHALL match the suite-scoped endpoints. Visibility (PUBLIC vs PRIVATE) does NOT affect access — any authenticated caller may upload, list, download, or delete files on any dataset they can otherwise see.

#### Scenario: Upload file to dataset
- **WHEN** authenticated user sends `POST /api/v1/datasets/{datasetId}/files` with `multipart/form-data` containing a `file` part
- **AND** the dataset exists
- **THEN** the system SHALL upload the file to DIAL at `{efBucket}/datasets/{datasetId}/{filename}` via `DialFileClient`
- **AND** return HTTP 201 with `FileMetadataDto` containing `path` (e.g., `files/@ef/datasets/{datasetId}/{filename}`), `filename`, `contentType`, `sizeBytes`

#### Scenario: Upload file to non-existent dataset
- **WHEN** user uploads a file for a non-existent `datasetId`
- **THEN** the system SHALL return HTTP 404 with error code `NOT_FOUND`

#### Scenario: Upload file exceeding size limit
- **WHEN** user uploads a file larger than `dial.file-storage.max-file-size-bytes` (default 50MB)
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Upload file with invalid filename
- **WHEN** user uploads a file whose filename contains characters outside the allowed set (alphanumeric, hyphen, underscore, dot, space, parentheses), exceeds 255 characters, or starts/ends with whitespace
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Upload file with duplicate filename
- **WHEN** user uploads a file and a file with the same name already exists in the dataset's DIAL folder
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` indicating filename must be unique per dataset

#### Scenario: Upload file exceeding per-dataset file count limit
- **WHEN** user uploads a file and the dataset already has the maximum number of files (default 100, configurable via `dial.file-storage.max-files-per-dataset`)
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`
- **NOTE**: The per-dataset count check is performed before upload (`list().size() >= max`) and is best-effort under concurrent uploads — two simultaneous uploads racing past the limit may both succeed (acceptable for v1, mirroring the suite-side semantics in `dial-file-storage`).

#### Scenario: List files
- **WHEN** user sends `GET /api/v1/datasets/{datasetId}/files`
- **THEN** the system SHALL list files from DIAL at `{efBucket}/datasets/{datasetId}/` and return a list of `FileMetadataDto`

#### Scenario: Download file
- **WHEN** user sends `GET /api/v1/datasets/{datasetId}/files/{filename}` and the file exists in the dataset's DIAL folder
- **THEN** the system SHALL return `ResponseEntity<StreamingResponseBody>` that pipes the file bytes from DIAL directly to the HTTP response via `DialFileClient.downloadTo()` with `Content-Type` matching the stored content type and `Content-Disposition: attachment; filename="{filename}"`

#### Scenario: Download non-existent file
- **WHEN** user sends `GET /api/v1/datasets/{datasetId}/files/{filename}` for a non-existent file
- **THEN** the system SHALL return HTTP 404

#### Scenario: Delete file
- **WHEN** user sends `DELETE /api/v1/datasets/{datasetId}/files/{filename}` and the file exists in the dataset's DIAL folder
- **THEN** the system SHALL delete the file from DIAL and return HTTP 204

#### Scenario: Delete non-existent file
- **WHEN** user sends `DELETE /api/v1/datasets/{datasetId}/files/{filename}` for a non-existent file
- **THEN** the system SHALL return HTTP 404

### Requirement: Dataset cascade delete for EF-managed files

When a dataset is deleted, the system SHALL perform best-effort cleanup of EF-managed files under `{efBucket}/datasets/{datasetId}/` **outside the deleting transaction** (after the DB transaction has committed). This applies to both PUBLIC explicit deletes (`DELETE /api/v1/datasets/{id}` on a PUBLIC dataset with no dependents) and PRIVATE-dataset cascade deletes triggered by suite delete.

The cleanup SHALL mirror the suite cascade: list the folder via `DialFileClient.list()`, delete each file individually via `DialFileClient.delete()`, and log warnings (not throw) on partial failure or DIAL unavailability.

#### Scenario: PUBLIC dataset delete cascades to DIAL files
- **WHEN** a PUBLIC dataset is explicitly deleted via `DELETE /api/v1/datasets/{id}`
- **THEN** the system SHALL first commit the DB delete, then list all files under `{efBucket}/datasets/{datasetId}/` and delete each via `DialFileClient.delete()`

#### Scenario: PRIVATE dataset cascade delete (via suite delete) cascades to DIAL files
- **WHEN** a suite is deleted and the suite is bound to a PRIVATE dataset, triggering the dataset to be unbound and deleted in the same flow
- **THEN** the system SHALL — after the DB transaction commits — delete the dataset's DIAL files in addition to deleting the suite's DIAL files

#### Scenario: Dataset cascade delete with DIAL unavailable
- **WHEN** a dataset is deleted but DIAL Core is unavailable for file cleanup
- **THEN** the system SHALL log a warning at WARN level (orphaned DIAL files are acceptable; the dataset and test cases are already removed from the database)

#### Scenario: Partial DIAL file cleanup failure on dataset delete
- **WHEN** some DIAL file deletions succeed but others fail during dataset cascade cleanup
- **THEN** the system SHALL log each failure at WARN level and continue deleting remaining files

### Requirement: Dataset file storage configuration

The system SHALL expose a configurable per-dataset file count limit alongside the existing suite limit.

| Property | Default | Description |
|----------|---------|-------------|
| `dial.file-storage.max-files-per-dataset` | `100` | Maximum files per dataset |

The existing `dial.file-storage.max-file-size-bytes` and `dial.file-storage.bucket-alias` properties SHALL apply to dataset-scoped uploads with the same semantics they have for suite-scoped uploads.

#### Scenario: Default per-dataset file count
- **WHEN** no explicit dataset file count limit is configured
- **THEN** the system SHALL use 100 files per dataset

#### Scenario: Custom per-dataset file count
- **WHEN** `dial.file-storage.max-files-per-dataset` is configured
- **THEN** the system SHALL enforce that value on dataset uploads
