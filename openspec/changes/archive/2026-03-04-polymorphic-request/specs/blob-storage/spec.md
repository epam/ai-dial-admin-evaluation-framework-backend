## ADDED Requirements

### Requirement: BlobStorage abstraction
The system SHALL provide a `BlobStorage` interface for storing, retrieving, and deleting binary large objects. The interface SHALL be implementation-agnostic to allow future replacement of the underlying storage backend (e.g., S3, MinIO, DIAL Core Files API).

The interface SHALL expose:
- `store(InputStream data, BlobMetadata metadata, UUID testSuiteId)` → `BlobReference`
- `retrieve(UUID blobId)` → `byte[]`
- `delete(UUID blobId)`
- `deleteByTestSuiteId(UUID testSuiteId)`
- `exists(UUID blobId)` → `boolean`

The `retrieve()` method returns `byte[]` because PostgreSQL Large Objects require an active transaction for reading — returning `InputStream` would force callers to manage transaction boundaries. Since v1 enforces a maximum file size (default 50MB), in-memory buffering is acceptable.

`BlobMetadata` SHALL contain: `filename` (String), `contentType` (String), `sizeBytes` (long).
`BlobReference` SHALL contain: `id` (UUID), `filename` (String), `contentType` (String), `sizeBytes` (long), `createdBy` (String), `createdAt` (Long, epoch ms).

#### Scenario: Store a blob
- **WHEN** service calls `blobStorage.store(inputStream, metadata, testSuiteId)`
- **THEN** the system SHALL persist the binary data and metadata, associate it with the given test suite, and return a `BlobReference` with a generated UUID

#### Scenario: Retrieve a blob
- **WHEN** service calls `blobStorage.retrieve(blobId)` for an existing blob
- **THEN** the system SHALL return the blob's binary content as `byte[]`

#### Scenario: Retrieve non-existent blob
- **WHEN** service calls `blobStorage.retrieve(blobId)` for a non-existent blob
- **THEN** the system SHALL throw an appropriate exception (e.g., `EntityNotFoundException`)

#### Scenario: Delete a blob
- **WHEN** service calls `blobStorage.delete(blobId)`
- **THEN** the system SHALL remove both the metadata and the binary data

#### Scenario: Delete all blobs for a test suite
- **WHEN** service calls `blobStorage.deleteByTestSuiteId(testSuiteId)`
- **THEN** the system SHALL remove all blobs (metadata and binary data) associated with that test suite

### Requirement: PostgreSQL Large Objects implementation
The v1 `BlobStorage` implementation SHALL use PostgreSQL Large Objects for binary storage. A `blobs` metadata table in the meta datasource SHALL track blob metadata and the PostgreSQL LO OID.

The `blobs` table SHALL have columns:
- `id` VARCHAR(36) PK
- `test_suite_id` VARCHAR(36) NOT NULL (FK to test_suites)
- `oid` BIGINT NOT NULL (PostgreSQL Large Object OID)
- `filename` VARCHAR(255)
- `content_type` VARCHAR(255)
- `size_bytes` BIGINT NOT NULL
- `created_by` VARCHAR(255) NOT NULL
- `created_at_ms` BIGINT NOT NULL

#### Scenario: Store uses PostgreSQL Large Object API
- **WHEN** `PostgresBlobStorage.store()` is called
- **THEN** the system SHALL create a Large Object via `LargeObjectManager.createLO()`, write the `InputStream` bytes to it, insert a row in the `blobs` table with the generated OID, and return a `BlobReference`

#### Scenario: Retrieve reads Large Object into byte array
- **WHEN** `PostgresBlobStorage.retrieve()` is called
- **THEN** the system SHALL open the Large Object by OID within a transaction, read all bytes into a `byte[]`, and return the array

#### Scenario: Delete removes both metadata and Large Object
- **WHEN** `PostgresBlobStorage.delete()` is called
- **THEN** the system SHALL call `lo_unlink` for the LO OID and delete the `blobs` row, both within the same transaction

#### Scenario: Suite deletion cascades to blobs
- **WHEN** a test suite is deleted
- **THEN** the system SHALL delete all associated blob metadata rows and their Large Objects (via `deleteByTestSuiteId`)

### Requirement: File management REST API
The system SHALL provide file management endpoints scoped to test suites.

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/test-suites/{suiteId}/files` | Upload file |
| `GET` | `/api/v1/test-suites/{suiteId}/files` | List files (paginated) |
| `GET` | `/api/v1/test-suites/{suiteId}/files/{fileId}` | Download file |
| `DELETE` | `/api/v1/test-suites/{suiteId}/files/{fileId}` | Delete file |

#### Scenario: Upload file
- **WHEN** authenticated user sends `POST /api/v1/test-suites/{suiteId}/files` with `multipart/form-data` containing a `file` part
- **AND** the test suite exists
- **THEN** the system SHALL store the file via `BlobStorage`, return HTTP 201 with `FileMetadataDto` containing `id`, `filename`, `contentType`, `sizeBytes`, `createdBy`, `createdAt`

#### Scenario: Upload file to non-existent suite
- **WHEN** user uploads a file for a non-existent `suiteId`
- **THEN** the system SHALL return HTTP 404 with error code `NOT_FOUND`

#### Scenario: Upload file exceeding size limit
- **WHEN** user uploads a file larger than the configured maximum (default 50MB)
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Upload file exceeding per-suite file count limit
- **WHEN** user uploads a file and the suite already has the maximum number of files (default 100)
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: List files
- **WHEN** user sends `GET /api/v1/test-suites/{suiteId}/files`
- **THEN** the system SHALL return a paginated list of `FileMetadataDto` for files belonging to that suite

#### Scenario: Download file
- **WHEN** user sends `GET /api/v1/test-suites/{suiteId}/files/{fileId}`
- **AND** the file exists and belongs to the suite
- **THEN** the system SHALL stream the file bytes with `Content-Type` matching the stored content type and `Content-Disposition: attachment; filename="{filename}"`

#### Scenario: Download non-existent file
- **WHEN** user sends `GET /api/v1/test-suites/{suiteId}/files/{fileId}` for a non-existent file
- **THEN** the system SHALL return HTTP 404

#### Scenario: Download file from wrong suite
- **WHEN** user sends `GET /api/v1/test-suites/{suiteId}/files/{fileId}` where the file exists but belongs to a different suite
- **THEN** the system SHALL return HTTP 404

#### Scenario: Delete file
- **WHEN** user sends `DELETE /api/v1/test-suites/{suiteId}/files/{fileId}`
- **AND** the file exists and belongs to the suite
- **THEN** the system SHALL delete the blob and return HTTP 204

#### Scenario: Delete non-existent file
- **WHEN** user sends `DELETE /api/v1/test-suites/{suiteId}/files/{fileId}` for a non-existent file
- **THEN** the system SHALL return HTTP 404

### Requirement: File size and count configuration
The system SHALL support configurable limits for file uploads via application properties.

#### Scenario: Default file size limit
- **WHEN** no explicit limit is configured
- **THEN** the system SHALL use 50MB as the maximum file size

#### Scenario: Default file count limit
- **WHEN** no explicit limit is configured
- **THEN** the system SHALL allow up to 100 files per test suite

#### Scenario: Custom limits
- **WHEN** `blob-storage.max-file-size-bytes` and `blob-storage.max-files-per-suite` are configured
- **THEN** the system SHALL use the configured values
