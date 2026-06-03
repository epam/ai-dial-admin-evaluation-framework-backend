## Why

The current file storage uses PostgreSQL Large Objects, which ties files to the meta database and prevents reuse of existing DIAL ecosystem resources. DIAL Core provides a file storage API with built-in access control, and DIAL apps can accept file references directly. Migrating to DIAL file storage enables reuse of existing UI components, DIAL file resources, and simplifies the architecture by removing PostgreSQL LO management. No production data exists — breaking changes are allowed.

## What Changes

- **BREAKING** — Remove PostgreSQL Large Objects-based file storage entirely (BlobStorage interface, PostgresBlobStorage, BlobRepository, `blobs` table, Flyway migration V1.11)
- **BREAKING** — FILE-type fields in test case data change from blob UUIDs to DIAL relative file paths (e.g., `files/@ef/suites/{suiteId}/filename.ext` or `files/public/path/to/file.ext`)
- Introduce EF-owned DIAL bucket with service API key — files uploaded via our API are stored in the EF bucket organized by suite (`suites/{suiteId}/filename`). The actual bucket name is resolved at startup via `GET /v1/bucket` using the EF key; clients use a constant alias (`@ef`) that is never exposed as the real bucket name
- Introduce `DialFileClient` component for DIAL Core file API operations (upload, download, delete, list metadata) using the EF service API key
- FileController stays as a proxy: upload/download/list/delete operations are forwarded to DIAL Core via DialFileClient with the EF key, maintaining suite-scoped file management
- Support two file reference sources: EF-managed files (`files/@ef/...`) and external public/org DIAL files (`files/public/...`), with a strict prefix whitelist
- FILE field validation: format-only on save (valid relative path, allowed prefix); broken refs fail at eval time on the deployment call
- `MultipartFormDataRequestBodySerializer` changes to download file bytes from DIAL (via DialFileClient) using streaming (`InputStream` instead of `byte[]`)
- ZIP export gains `materializeFiles` flag: when true, downloads file bytes from DIAL and embeds them in the ZIP (for cross-env portability); when false, CSV contains raw DIAL URLs
- ZIP import: if ZIP contains embedded files, uploads them to DIAL @ef bucket and rewrites CSV with new DIAL URLs; if CSV contains DIAL URLs, imports as-is
- Suite cascade delete: DB-first (FK cascade removes test cases), then best-effort DIAL file cleanup via list-then-delete; external refs (public files) are not touched
- Unique filenames enforced per suite (flat, no subdirectories); filename sanitization rejects URL-unsafe characters (`/\?#%*:|<>"`) and limits length to 255 chars
- New configuration properties under `dial.file-storage.*` (api-key, bucket-alias, max-file-size-bytes, max-files-per-suite)

## Capabilities

### New Capabilities
- `dial-file-storage`: DIAL Core file storage integration — DialFileClient, EF service key/bucket management, file reference resolution (@ef alias → real bucket), file upload/download proxy, suite-scoped file lifecycle, streaming file retrieval
- `dial-file-ref`: DIAL file reference model — relative path format, @ef and public prefix whitelist, FileRefResolver for alias-to-bucket translation, validation rules for FILE-type schema fields

### Modified Capabilities
- `blob-storage`: **BREAKING** — Entire capability replaced. Remove PostgreSQL LO implementation, BlobStorage interface, BlobRepository, blobs table. FileController and FileService are re-implemented to delegate to DialFileClient instead of BlobStorage.
- `test-cases`: FILE type in test case schema changes from `String (blob UUID)` to `String (DIAL relative file path)`. TestCaseValidationService changes validation logic for FILE fields (format + prefix check instead of blob existence check). ZIP export/import adapts to DIAL file references.
- `polymorphic-request-body`: `MultipartFormDataRequestBodySerializer` changes from `blobStorage.retrieve(uuid)` to `dialFileClient.download(dialPath)` with streaming support.

## Impact

### Summary
- **Implemented** — Planned

### API Impact
- File upload/download/list/delete endpoints remain at same paths but response `id` field changes from UUID to DIAL file path; `createdBy` field is removed (DIAL metadata does not track uploading user)
- FILE-type field values in test case data change format (UUID → relative DIAL path)
- New query param `materializeFiles` on ZIP export endpoint

### New Packages / Classes
- `client.dialcore.DialFileClient` — DIAL Core file API client
- `client.dialcore.DialFileClientConfiguration` — RestClient bean setup for file operations
- `service.domain.DialFileRefResolver` — resolves `@ef` alias to actual bucket name
- `configuration.properties.dial.DialFileStorageProperties` — configuration properties

### Removed Packages / Classes
- `service.domain.BlobStorage` (interface)
- `service.domain.PostgresBlobStorage` (implementation)
- `data.db.repository.BlobRepository` / `PostgresBlobRepository`
- `data.db.model.BlobModel`
- `data.db.mapper.BlobModelRowMapper`
- `service.domain.dto.BlobMetadata` / `BlobReference`
- Flyway migration `V1.11__CreateBlobsTable.sql` (replaced with drop-table migration)

### Configuration Changes
New properties (must update `docs/configuration.md`):
- `dial.file-storage.api-key` — EF service API key for DIAL Core file operations
- `dial.file-storage.bucket-alias` — constant alias for EF bucket (default: `@ef`)
- `dial.file-storage.max-file-size-bytes` — max upload size (default: 50MB)
- `dial.file-storage.max-files-per-suite` — max files per suite (default: 100)

Removed properties:
- `blob-storage.max-file-size-bytes`
- `blob-storage.max-files-per-suite`

### DB Migration Impact
- New Flyway migration to drop `blobs` table and associated Large Objects
- No new tables needed (file metadata is managed by DIAL Core)

### Security/Permissions Impact
- EF service API key must be provisioned in DIAL Core and configured via environment variable
- File operations use EF key (not user JWT) — no user-level file ACL in our service
- Public DIAL files are readable by any authenticated caller via the EF key

### Risks
- DIAL Core availability becomes a dependency for file operations (upload, download, eval runs)
- EF API key management is manual for v1 (no auto-provisioning)
- Token expiry during long eval runs is acknowledged but out of scope for v1 (EF key is long-lived)

### Rollout
- Breaking change — no migration path needed (no production data)
- Clean deployment: configure `DIAL_EF_API_KEY` env var, deploy new version

### Test Plan
- Unit tests for DialFileClient, DialFileRefResolver, updated serializers
- Functional tests for FileController with mocked/stubbed DIAL Core file API
- Functional tests for test case CRUD with DIAL file references
- Functional tests for ZIP export/import with materialized and non-materialized files
- Functional tests for suite cascade delete (verify DIAL file cleanup)
