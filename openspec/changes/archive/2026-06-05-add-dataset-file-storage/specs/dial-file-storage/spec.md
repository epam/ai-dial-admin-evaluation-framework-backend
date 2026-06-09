# DIAL File Storage — Delta

## MODIFIED Requirements

### Requirement: File management REST API (proxy to DIAL)
The system SHALL provide file management endpoints scoped to test suites. These endpoints proxy file operations to DIAL Core via `DialFileClient`, using the EF service API key.

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/test-suites/{suiteId}/files` | Upload file |
| `GET` | `/api/v1/test-suites/{suiteId}/files` | List files |
| `GET` | `/api/v1/test-suites/{suiteId}/files/{filename}` | Download file |
| `DELETE` | `/api/v1/test-suites/{suiteId}/files/{filename}` | Delete file |

A peer family of dataset-scoped endpoints (`/api/v1/datasets/{datasetId}/files`) is defined in the `dataset-file-storage` capability and follows identical semantics (multipart upload, streaming download, filename validation, size limits, per-folder count limit). Both families proxy through `DialFileClient` and the EF service API key.

File identification uses filename (`{filename}`) since DIAL storage uses path-based addressing. **The `{filename}` path variable MUST use regex pattern `{filename:.+}`** in Spring MVC `@GetMapping`/`@DeleteMapping` annotations to prevent suffix pattern matching from truncating filenames at the last dot (e.g., `report.pdf` → `report`).

#### Scenario: Upload file
- **WHEN** authenticated user sends `POST /api/v1/test-suites/{suiteId}/files` with `multipart/form-data` containing a `file` part
- **AND** the test suite exists
- **THEN** the system SHALL upload the file to DIAL at `{efBucket}/suites/{suiteId}/{filename}` via `DialFileClient`
- **AND** return HTTP 201 with `FileMetadataDto` containing `path` (e.g., `files/@ef/suites/{suiteId}/{filename}`), `filename`, `contentType`, `sizeBytes`

Note: `createdBy` is NOT included in `FileMetadataDto` because DIAL Core's file metadata does not track the uploading user identity. The field is dropped from the API contract (breaking change, allowed).

#### Scenario: Upload file to non-existent suite
- **WHEN** user uploads a file for a non-existent `suiteId`
- **THEN** the system SHALL return HTTP 404 with error code `NOT_FOUND`

#### Scenario: Upload file exceeding size limit
- **WHEN** user uploads a file larger than `dial.file-storage.max-file-size-bytes` (default 50MB)
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Upload file with invalid filename
- **WHEN** user uploads a file whose filename contains characters outside the allowed set (alphanumeric, hyphen, underscore, dot, space, parentheses) or exceeds 255 characters, or starts/ends with whitespace
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` indicating the filename is invalid
- **NOTE**: Filenames with `/`, `\`, `?`, `#`, `%`, `*`, `:`, `|`, `<`, `>`, `"` are explicitly rejected because they break URL path routing or filesystem semantics

#### Scenario: Upload file with duplicate filename
- **WHEN** user uploads a file and a file with the same name already exists in the suite's DIAL folder
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` indicating filename must be unique per suite
- **NOTE**: Uniqueness is enforced via a check-then-upload pattern (`exists()` followed by `upload()`). Concurrent uploads of the same filename within the race window may both succeed (last-write-wins in DIAL). This is acceptable for v1

#### Scenario: Upload file exceeding per-suite file count limit
- **WHEN** user uploads a file and the suite already has the maximum number of files (default 100, configurable via `dial.file-storage.max-files-per-suite`)
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: List files
- **WHEN** user sends `GET /api/v1/test-suites/{suiteId}/files`
- **THEN** the system SHALL list files from DIAL at `{efBucket}/suites/{suiteId}/` and return a list of `FileMetadataDto` (mapped from DIAL metadata: `path`, `filename`, `contentType`, `sizeBytes`)

#### Scenario: Download file
- **WHEN** user sends `GET /api/v1/test-suites/{suiteId}/files/{filename}`
- **AND** the file exists in the suite's DIAL folder
- **THEN** the system SHALL return `ResponseEntity<StreamingResponseBody>` that pipes the file bytes from DIAL directly to the HTTP response via `DialFileClient.downloadTo()` with `Content-Type` matching the stored content type and `Content-Disposition: attachment; filename="{filename}"`
- **NOTE**: The controller uses `StreamingResponseBody` to avoid buffering the entire file in memory; `FileService.downloadTo(suiteId, filename, outputStream)` delegates to `DialFileClient.downloadTo()`

#### Scenario: Download non-existent file
- **WHEN** user sends `GET /api/v1/test-suites/{suiteId}/files/{filename}` for a non-existent file
- **THEN** the system SHALL return HTTP 404

#### Scenario: Delete file
- **WHEN** user sends `DELETE /api/v1/test-suites/{suiteId}/files/{filename}`
- **AND** the file exists in the suite's DIAL folder
- **THEN** the system SHALL delete the file from DIAL and return HTTP 204

#### Scenario: Delete non-existent file
- **WHEN** user sends `DELETE /api/v1/test-suites/{suiteId}/files/{filename}` for a non-existent file
- **THEN** the system SHALL return HTTP 404

### Requirement: File storage configuration
The system SHALL support configurable properties for DIAL file storage. The API key is defined at the top-level `dial.*` namespace (shared across features); file-storage-specific properties use `dial.file-storage.*`.

| Property | Default | Description |
|----------|---------|-------------|
| `dial.api-key` | (none, required) | EF service API key for DIAL Core — shared across all DIAL-related features |
| `dial.file-storage.bucket-alias` | `@ef` | Constant alias used in client-facing file paths |
| `dial.file-storage.max-file-size-bytes` | `52428800` (50MB) | Maximum upload file size |
| `dial.file-storage.max-files-per-suite` | `100` | Maximum files per test suite |
| `dial.file-storage.max-files-per-dataset` | `100` | Maximum files per dataset (added with the `dataset-file-storage` capability) |
| `dial.file-storage.connect-timeout-ms` | `5000` | Connection timeout for DIAL file API |
| `dial.file-storage.read-timeout-ms` | `30000` | Read timeout for DIAL file API |

#### Scenario: Default configuration
- **WHEN** no explicit file storage limits are configured
- **THEN** the system SHALL use 50MB max file size, 100 files per suite, and 100 files per dataset

#### Scenario: Custom configuration
- **WHEN** `dial.file-storage.max-file-size-bytes`, `dial.file-storage.max-files-per-suite`, and/or `dial.file-storage.max-files-per-dataset` are configured
- **THEN** the system SHALL use the configured values

#### Scenario: Missing API key
- **WHEN** `dial.api-key` is not configured
- **THEN** the system SHALL fail to start with a clear error message indicating the required property
