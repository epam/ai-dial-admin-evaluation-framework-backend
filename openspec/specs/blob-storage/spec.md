# Blob Storage

## Purpose
This spec described the original blob storage system using PostgreSQL Large Objects. It has been **superseded** by DIAL Core file storage (see `dial-file-storage` and `dial-file-ref` specs).

Status: **Superseded**

**Migration**: All file operations now use `DialFileClient` (`client.dialcore`). FILE-type field values changed from blob UUIDs to DIAL relative file paths (`files/@ef/suites/{suiteId}/{filename}`). The `blobs` table was dropped via Flyway migration `V1.14__DropBlobsTable.sql`. Configuration moved from `blob-storage.*` to `dial.file-storage.*`. No data migration needed (no production data at time of change).

## Removed Requirements

### ~~Requirement: BlobStorage abstraction~~
**REMOVED** — Replaced by `DialFileClient` in `client.dialcore`. See `dial-file-storage` spec.

### ~~Requirement: PostgreSQL Large Objects implementation~~
**REMOVED** — The `blobs` table, `BlobRepository`, `BlobModel`, `BlobModelRowMapper`, and PostgreSQL Large Object management are removed.

### ~~Requirement: File management REST API~~
**REMOVED** — The REST API endpoints are preserved but re-implemented to delegate to `DialFileClient` instead of `BlobStorage`. File identification changed from UUID (`{fileId}`) to filename (`{filename}`), and the response `id` field was replaced by `path` containing the DIAL file reference. See `dial-file-storage` spec for the updated API contract.

### ~~Requirement: File size and count configuration~~
**REMOVED** — Replaced by equivalent configuration under `dial.file-storage.*` namespace. See `dial-file-storage` spec.
