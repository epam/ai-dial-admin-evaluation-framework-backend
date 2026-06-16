# DIAL File Storage

## Purpose
This spec describes the DIAL Core file storage integration for the Evaluation Framework. Covers the DialFileClient, EF service key and bucket management, file upload/download proxy, suite-scoped file lifecycle, and streaming file retrieval.

Status: **Planned**

## ADDED Requirements

### Requirement: DialFileClient component
The system SHALL provide a `DialFileClient` component in `client.dialcore` for interacting with DIAL Core's file storage API. The client SHALL use the EF service API key for authentication (not user JWT).

The client SHALL expose:
- `upload(String path, InputStream content, String filename, String contentType)` → `DialFileMetadataDto`
- `downloadTo(String path, OutputStream target)` — streaming download, pipes DIAL response directly to the target output stream via `RestClient.exchange()` without buffering the entire file in memory
- `download(String path)` → `byte[]` — convenience method that delegates to `downloadTo` with a `ByteArrayOutputStream`; used where in-memory bytes are needed (e.g., multipart body assembly)
- `delete(String path)` — deletes a single file
- `metadata(String path)` → `DialFileMetadataDto`
- `list(String folderPath)` → `List<DialFileMetadataDto>`
- `exists(String path)` → `boolean`
- `getBucket()` → `String`

All `path` parameters SHALL be fully resolved paths (real bucket name, not alias). The client SHALL NOT perform alias resolution — that is the responsibility of `DialFileRefResolver`.

`DialFileMetadataDto` SHALL contain: `name` (String), `parentPath` (String), `bucket` (String), `url` (String), `contentLength` (Long), `contentType` (String), `createdAt` (Long), `updatedAt` (Long).

#### Scenario: Upload file to DIAL
- **WHEN** service calls `dialFileClient.upload(path, inputStream, filename, contentType)`
- **THEN** the client SHALL send `PUT /v1/files/{path}` with multipart/form-data containing the file bytes and the EF API key in the `Api-Key` header
- **AND** return a `DialFileMetadataDto` with the uploaded file's metadata
- **NOTE**: The `InputStream` is fully read into memory before upload because Spring's `MultipartBodyBuilder` requires a `ByteArrayResource` with known `contentLength()`. This is acceptable given the `max-file-size-bytes` limit (default 50MB)

#### Scenario: Download file from DIAL (streaming)
- **WHEN** service calls `dialFileClient.downloadTo(path, outputStream)`
- **THEN** the client SHALL send `GET /v1/files/{path}` with the EF API key and pipe the response body directly to the target output stream via `RestClient.exchange()` without buffering the entire file in memory
- **NOTE**: On error responses, the `exchange()` callback SHALL read the error response body and include it in the thrown `DialCoreClientException` for diagnostic purposes

#### Scenario: Download file from DIAL (byte array convenience)
- **WHEN** service calls `dialFileClient.download(path)`
- **THEN** the client SHALL delegate to `downloadTo` with a `ByteArrayOutputStream` and return the resulting `byte[]`
- **NOTE**: This convenience method is used where in-memory bytes are required (e.g., multipart body assembly in `MultipartFormDataRequestBodySerializer`)

#### Scenario: Delete file from DIAL
- **WHEN** service calls `dialFileClient.delete(path)`
- **THEN** the client SHALL send `DELETE /v1/files/{path}` with the EF API key

#### Scenario: Get file metadata
- **WHEN** service calls `dialFileClient.metadata(path)`
- **THEN** the client SHALL send `GET /v1/metadata/files/{path}` with the EF API key and return a `DialFileMetadataDto`

#### Scenario: List folder contents
- **WHEN** service calls `dialFileClient.list(folderPath)`
- **THEN** the client SHALL send `GET /v1/metadata/files/{folderPath}` with the EF API key and return a list of `DialFileMetadataDto` for all items in the folder
- **NOTE**: The DIAL metadata endpoint returns a folder response object containing an `items` array; the client extracts and returns the items list

#### Scenario: List non-existent folder
- **WHEN** service calls `dialFileClient.list(folderPath)` and the folder does not exist in DIAL (HTTP 404)
- **THEN** the client SHALL return an empty list (not throw an exception)
- **NOTE**: This is critical for cascade delete — a newly created suite with no uploaded files has no folder in DIAL yet, so listing returns 404. Returning an empty list ensures cascade delete succeeds for suites without files

#### Scenario: Check file existence
- **WHEN** service calls `dialFileClient.exists(path)`
- **THEN** the client SHALL call `metadata(path)` and return `true` if the file exists, `false` if the DIAL Core returns 404

#### Scenario: File not found
- **WHEN** the DIAL Core returns HTTP 404 for a file operation (download, delete, metadata)
- **THEN** the client SHALL throw an appropriate exception (e.g., `DialCoreClientException`) that maps to `NOT_FOUND`

#### Scenario: DIAL Core unavailable
- **WHEN** the DIAL Core is unreachable during a file operation
- **THEN** the client SHALL throw a `DialCoreClientException` with appropriate error semantics (502/504)

### Requirement: EF bucket discovery and caching
The system SHALL resolve the EF bucket name lazily on first use by calling `GET /v1/bucket` with the EF service API key. The resolved bucket name SHALL be cached in a thread-safe manner for the lifetime of the application.

#### Scenario: Successful bucket discovery (first use)
- **WHEN** the first file operation triggers `DialFileClient.getBucket()` and DIAL Core is available
- **THEN** the system SHALL call `GET /v1/bucket` with `Api-Key: {configured-key}`, cache the returned `bucket` value, and return it

#### Scenario: Subsequent bucket access (cached)
- **WHEN** `DialFileClient.getBucket()` is called after a successful resolution
- **THEN** the system SHALL return the cached bucket name without making an API call

#### Scenario: Bucket discovery failure (DIAL Core unavailable)
- **WHEN** `DialFileClient.getBucket()` is called and DIAL Core is unavailable
- **THEN** the system SHALL throw an exception (the failure SHALL NOT be cached — the next call SHALL retry)
- **AND** `DialFileStorageHealthIndicator` SHALL report `DOWN` until the bucket is successfully resolved
- **NOTE**: The health indicator MUST be registered in the `readiness` health group only (not `liveness`) to prevent Kubernetes from restarting the pod before bucket resolution occurs
- **NOTE**: The health indicator MUST NOT expose the bucket name in its details (internal data). It SHALL report only the storage type (e.g., `"storage": "DIAL Core File Storage"`) and status

#### Scenario: Bucket used for all EF file operations
- **WHEN** any EF file operation targets the `@ef` alias
- **THEN** the system SHALL use the cached bucket name to construct the real DIAL path

### Requirement: File management REST API (proxy to DIAL)
The system SHALL provide file management endpoints scoped to test suites. These endpoints proxy file operations to DIAL Core via `DialFileClient`, using the EF service API key.

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/test-suites/{suiteId}/files` | Upload file |
| `GET` | `/api/v1/test-suites/{suiteId}/files` | List files |
| `GET` | `/api/v1/test-suites/{suiteId}/files/{filename}` | Download file |
| `DELETE` | `/api/v1/test-suites/{suiteId}/files/{filename}` | Delete file |

File identification changes from UUID (`{fileId}`) to filename (`{filename}`) since DIAL storage uses path-based addressing. **The `{filename}` path variable MUST use regex pattern `{filename:.+}`** in Spring MVC `@GetMapping`/`@DeleteMapping` annotations to prevent suffix pattern matching from truncating filenames at the last dot (e.g., `report.pdf` → `report`).

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

### Requirement: Suite cascade delete for EF-managed files
When a test suite is deleted, the system SHALL first delete the suite from the database (cascading to test cases via FK) within a `@Transactional` scope, then perform best-effort cleanup of EF-managed files in DIAL storage **outside the transaction** (after the DB transaction has committed). The DIAL folder path is constructible from the suite ID alone (`{efBucket}/suites/{suiteId}/`), so test case data is not needed for cleanup.

#### Scenario: Suite deletion cascades to DIAL files
- **WHEN** a test suite is deleted
- **THEN** the system SHALL first delete the suite from the database (FK cascade removes test cases) and commit the transaction
- **AND** after the transaction commits, list all files under `{efBucket}/suites/{suiteId}/` via `DialFileClient.list()` and delete each file individually via `DialFileClient.delete()`
- **AND** external file references (e.g., `files/public/...`) in test case data SHALL NOT be deleted (they are not owned by the suite)
- **NOTE**: DIAL cleanup MUST run outside the `@Transactional` scope to avoid holding the DB transaction open during potentially slow DIAL API calls
- **NOTE**: Use `TransactionTemplate` (with `metaTransactionManager`) for programmatic transaction control. Do NOT use a `@Transactional` helper method on the same class — Spring proxy-based AOP does not intercept self-calls

#### Scenario: Cascade delete with DIAL unavailable
- **WHEN** a test suite is deleted but DIAL Core is unavailable for file cleanup
- **THEN** the system SHALL log a warning at WARN level (orphaned DIAL files are acceptable; the suite and test cases are already removed from the database)

#### Scenario: Partial DIAL file cleanup failure
- **WHEN** some DIAL file deletions succeed but others fail during cascade cleanup
- **THEN** the system SHALL log each failure at WARN level and continue deleting remaining files (best-effort, no rollback)

### Requirement: File storage configuration
The system SHALL support configurable properties for DIAL file storage. The API key is defined at the top-level `dial.*` namespace (shared across features); file-storage-specific properties use `dial.file-storage.*`.

| Property | Default | Description |
|----------|---------|-------------|
| `dial.api-key` | (none, required) | EF service API key for DIAL Core — shared across all DIAL-related features |
| `dial.file-storage.bucket-alias` | `@ef` | Constant alias used in client-facing file paths |
| `dial.file-storage.max-file-size-bytes` | `52428800` (50MB) | Maximum upload file size |
| `dial.file-storage.max-files-per-suite` | `100` | Maximum files per test suite |
| `dial.file-storage.connect-timeout-ms` | `5000` | Connection timeout for DIAL file API |
| `dial.file-storage.read-timeout-ms` | `30000` | Read timeout for DIAL file API |

#### Scenario: Default configuration
- **WHEN** no explicit file storage limits are configured
- **THEN** the system SHALL use 50MB max file size and 100 files per suite

#### Scenario: Custom configuration
- **WHEN** `dial.file-storage.max-file-size-bytes` and `dial.file-storage.max-files-per-suite` are configured
- **THEN** the system SHALL use the configured values

#### Scenario: Missing API key
- **WHEN** `dial.api-key` is not configured
- **THEN** the system SHALL fail to start with a clear error message indicating the required property
