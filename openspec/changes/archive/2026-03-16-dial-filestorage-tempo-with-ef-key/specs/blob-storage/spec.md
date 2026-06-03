# Blob Storage — Delta Spec (DIAL File Storage Migration)

## REMOVED Requirements

### Requirement: BlobStorage abstraction
**Reason**: Replaced by DIAL Core file storage integration. The `BlobStorage` interface, `PostgresBlobStorage` implementation, and all associated types (`BlobMetadata`, `BlobReference`) are removed. File operations are now handled by `DialFileClient` (see `dial-file-storage` spec).
**Migration**: All file operations use `DialFileClient`. FILE-type field values change from blob UUIDs to DIAL relative file paths. No data migration needed (no production data).

### Requirement: PostgreSQL Large Objects implementation
**Reason**: Replaced by DIAL Core file storage. The `blobs` table, `BlobRepository`, `BlobModel`, `BlobModelRowMapper`, and PostgreSQL Large Object management are removed. A Flyway migration SHALL drop the `blobs` table.
**Migration**: Drop `blobs` table via new Flyway migration. Remove all PostgreSQL LO code.

### Requirement: File size and count configuration
**Reason**: Replaced by equivalent configuration under `dial.file-storage.*` namespace. The `blob-storage.max-file-size-bytes` and `blob-storage.max-files-per-suite` properties are removed.
**Migration**: Use `dial.file-storage.max-file-size-bytes` and `dial.file-storage.max-files-per-suite` instead.

### Requirement: File management REST API
**Reason**: The REST API endpoints are preserved but re-implemented to delegate to `DialFileClient` instead of `BlobStorage`. The endpoint contract changes: file identification changes from UUID (`{fileId}`) to filename (`{filename}`), and the response `id` field is replaced by `path` containing the DIAL file reference. See `dial-file-storage` spec for the updated API contract.
**Migration**: Update client code to use filename-based paths and DIAL file references instead of UUIDs.
