## Context

The Evaluation Framework (EF) currently stores binary files using PostgreSQL Large Objects via a `BlobStorage` abstraction. Files are scoped to test suites (`blobs` table with FK to `test_suites`), identified by UUIDs, and referenced in test case data as blob UUID strings. The `MultipartFormDataRequestBodySerializer` retrieves file bytes from `BlobStorage` at serialization time.

DIAL Core provides a file storage API (`/v1/files/{bucket}/{path}`) backed by configurable blob storage (S3, GCS, Azure Blob, filesystem). Files are organized in per-identity buckets and support access control, metadata, and sharing. The DIAL UI already has file picker components for DIAL storage.

This change replaces the PostgreSQL LO backend with DIAL Core file storage, using a dedicated EF service API key that owns a DIAL bucket for suite-scoped files. External DIAL files (public/org bucket) can also be referenced without upload.

## Goals / Non-Goals

**Goals:**
- Replace PostgreSQL Large Objects with DIAL Core file storage
- Enable referencing pre-existing DIAL files (public bucket) in test suites
- Maintain suite-scoped file management via EF API (upload, list, download, delete)
- Support streaming file retrieval for memory efficiency
- Support cross-environment export/import via materialized ZIP files

**Non-Goals:**
- User-personal bucket access (later phase)
- Auto-sharing files during eval runs (later phase)
- Token refresh for long-running evaluations (assume token lives through run)
- Service account auto-provisioning in DIAL Core
- File deduplication across suites

## Decisions

### Decision 1: EF-owned DIAL bucket with service API key

**Choice**: The EF service gets its own DIAL API key (`dial.api-key`), defined at the top level of the `dial` namespace for reuse across features. The service discovers its bucket name by calling `GET /v1/bucket` with this key. All file operations use this key.

**Bucket discovery mode**: Lazy initialization with retry. The bucket is resolved on first use (not at startup) to avoid blocking the service when DIAL Core is temporarily unavailable. `DialFileClient.getBucket()` uses a thread-safe cache (e.g., `AtomicReference` or `synchronized` block) with the following behavior:
- First call: invokes `GET /v1/bucket`, caches the result, returns it
- Subsequent calls: returns cached value (no API call)
- If the first call fails: throws an exception, does NOT cache the failure; next call retries
- `DialFileStorageHealthIndicator` reports `UP` when the bucket is cached and `DOWN` otherwise. The health indicator MUST NOT expose the bucket name in its details (internal data). **The health indicator MUST be registered in the `readiness` health group only** (not `liveness`) to prevent Kubernetes from restarting the pod before the first file operation triggers bucket resolution. Configure via `management.endpoint.health.group.readiness.include=dialFileStorage,...` in `application.yml`.

This means the service starts successfully even if DIAL Core is down, but file operations fail until the bucket is resolved. Non-file endpoints remain available immediately.

**Why not user JWT**: User JWTs expire and don't work for background eval runs. The EF key is long-lived and provides a stable identity.

**Why not public bucket directly**: Public bucket requires DIAL publication workflow. EF needs programmatic upload/delete without admin approval.

**Alternative considered**: Each user uploads to their own bucket, EF just stores references. Rejected because: no cascade delete on suite removal, no naming control, token expiry during eval runs.

### Decision 2: @ef bucket alias

**Choice**: Clients never see the real DIAL bucket name. File references use a constant alias `@ef` (configurable via `dial.file-storage.bucket-alias`). The `DialFileRefResolver` component translates at runtime, stripping the `files/` prefix and replacing aliases to produce API paths:
- `files/@ef/suites/{suiteId}/data.csv` → `{realBucket}/suites/{suiteId}/data.csv`
- `files/public/datasets/input.csv` → `public/datasets/input.csv` (alias passthrough, prefix stripped)

**Why**: Cross-environment portability. The same file reference works in dev and prod without rewriting data.

### Decision 3: Flat file layout per suite, unique filenames with sanitization

**Choice**: Files are stored at `{bucket}/suites/{suiteId}/{filename}` — flat, no subdirectories within a suite folder. Filenames must be unique per suite. Enforced at upload time.

**Filename validation**: Filenames are used as URL path variables (`{filename}` in `GET/DELETE .../files/{filename}`), so they must be URL-safe. Allowed characters: alphanumeric, hyphen (`-`), underscore (`_`), dot (`.`), space (` `), parentheses (`(`, `)`). Explicitly rejected: `/`, `\`, `?`, `#`, `%`, `*`, `:`, `|`, `<`, `>`, `"`. Max length: 255 characters. No leading/trailing whitespace. Validated at upload time.

**Why**: Simplicity. Subdirectories add complexity to list/delete operations and DIAL metadata queries. Unique filenames prevent ambiguity. Filename sanitization prevents URL routing issues and aligns with common filesystem constraints.

### Decision 4: Strict prefix whitelist for file references

**Choice**: FILE-type field values must start with `files/@ef/` or `files/public/`. Any other prefix is rejected with a validation error on save.

**Why**: Security boundary. Prevents referencing arbitrary DIAL resources (user buckets, shared files, etc.) until those are explicitly supported in later phases.

### Decision 5: Format validation only on save

**Choice**: When a test case is created/updated with a FILE field, the system validates:
- The value is a valid relative DIAL path
- The prefix is in the allowed whitelist (@ef, public)
No existence check is performed. If the file doesn't exist at eval time, the deployment call fails and the error is reported as-is.

**Why**: Existence checks add latency and create TOCTOU (time-of-check-time-of-use) race conditions. Files could be deleted between validation and execution anyway.

### Decision 6: DialFileClient as a new client component

**Choice**: New `DialFileClient` in `client.dialcore` package, alongside the existing `DialCoreClient`. Separate `RestClient` bean with its own timeout configuration. Uses the EF API key for auth (not user JWT). No retry logic in v1 — retry requirements for file operations are deferred to later phases.

Operations:
- `upload(String path, InputStream content, String filename, String contentType)` → `DialFileMetadataDto`
- `downloadTo(String path, OutputStream target)` — streaming download, pipes DIAL response directly to the target without buffering the entire file in memory
- `download(String path)` → `byte[]` — convenience method that delegates to `downloadTo` with a `ByteArrayOutputStream`; used where in-memory bytes are needed (e.g., multipart body assembly)
- `delete(String path)`
- `metadata(String path)` → `DialFileMetadataDto`
- `list(String folderPath)` → `List<DialFileMetadataDto>`
- `exists(String path)` → `boolean`
- `getBucket()` → `String` (lazy init on first call, cached; see Decision 1)

All path parameters are fully resolved (real bucket, not alias). The `DialFileRefResolver` translates before calling `DialFileClient`.

**Why separate from DialCoreClient**: Different auth mechanism (API key vs user JWT), different concerns (file CRUD vs deployment metadata), different timeout needs.

### Decision 7: Streaming file retrieval

**Choice**: `DialFileClient` provides two download methods:
- `downloadTo(String path, OutputStream target)` — streaming download via `RestClient.exchange()`, pipes DIAL response directly to the target output stream without buffering the entire file in memory. Used by `FileController` (via `StreamingResponseBody`) and `ZipExportService` for memory-efficient file delivery.
- `download(String path) → byte[]` — convenience method that delegates to `downloadTo` with a `ByteArrayOutputStream`. Used by `MultipartFormDataRequestBodySerializer` where in-memory bytes are needed for multipart body assembly.

The `FileController.download()` endpoint returns `ResponseEntity<StreamingResponseBody>` to pipe DIAL content directly to the HTTP response with minimal heap usage. The `FileService` exposes `downloadTo(suiteId, filename, OutputStream)` for streaming consumers.

**Why**: Memory efficiency for large files (up to 50MB max). Streaming avoids loading entire files into heap per concurrent download.

**Transaction note**: Unlike PostgreSQL LOs, DIAL file downloads don't require transaction boundaries, so streaming is straightforward.

**Multipart serializer note**: Spring's `MultipartBodyBuilder` requires `ByteArrayResource` (needs `contentLength()` for part headers), so the multipart serializer uses `download() → byte[]`. This is acceptable given the 50MB max file size limit and the fact that the multipart body must be fully assembled before sending.

### Decision 8: FileController stays as a proxy

**Choice**: Keep `FileController` with the same endpoint paths. It proxies upload/download/list/delete to DIAL Core via `DialFileClient`. The `FileService` validates (file size, filename uniqueness, suite existence) and delegates to `DialFileClient`.

**Endpoint changes**:
- Response `id` field changes from UUID to DIAL file path (e.g., `files/@ef/suites/{suiteId}/filename.ext`)
- Upload response includes the DIAL file path for the client to use in test case data
- Download uses the filename from the DIAL path
- **Spring MVC path variable note**: `{filename}` path variable MUST use regex pattern `{filename:.+}` in `@GetMapping`/`@DeleteMapping` annotations to prevent Spring's default suffix pattern matching from truncating filenames at the last dot (e.g., `report.pdf` → `report`)

**Why proxy instead of direct UI→DIAL**: EF controls naming convention, enforces uniqueness, manages suite-scoped lifecycle, and the EF API key is not exposed to the client.

### Decision 9: ZIP export with materializeFiles flag

**Choice**: Export endpoint gains an optional `materializeFiles` query parameter (default: `true` when FILE fields exist).
- `materializeFiles=true`: Download file bytes from DIAL, embed in ZIP (current behavior adapted to DIAL)
- `materializeFiles=false`: CSV contains raw DIAL file paths, no files/ directory in ZIP. Produces a plain CSV even if FILE fields exist.

**Why**: Cross-env portability when materializing; lightweight export when not.

### Decision 10: ZIP import adapts to content

**Choice**:
- ZIP with embedded `files/` directory: Upload each file to DIAL @ef bucket, rewrite CSV paths with new DIAL URLs, import test cases
- CSV with DIAL file paths: Import as-is, FILE fields keep the DIAL paths
- CSV with embedded files but no DIAL paths: Same as current ZIP import flow but targeting DIAL

### Decision 11: Suite cascade delete for EF-managed files

**Choice**: When a test suite is deleted, the system first deletes the suite from the database (FK cascade removes test cases), then performs best-effort DIAL file cleanup: list all files under `{bucket}/suites/{suiteId}/` and delete each individually. External file references (public bucket) are not touched.

**Transaction boundary**: The DIAL file cleanup MUST execute **outside** the `@Transactional` scope. The DB delete commits first; DIAL cleanup runs after. Implementation approach: use `TransactionTemplate` (injected with `metaTransactionManager`) inside `TestSuiteService.delete()` to run the DB delete in a programmatic transaction, then call `FileService.deleteAllBySuiteId()` after the transaction has committed. **Do NOT use a `@Transactional` helper method on the same class** — Spring's proxy-based AOP does not intercept self-calls, so the annotation would be silently ignored. Alternatives: `TransactionTemplate` (preferred — simplest), a separate `@Service` helper bean, or self-injection via `@Lazy`. This prevents holding the DB transaction open during potentially slow DIAL API calls (up to 30s × N files if DIAL is slow).

**Why DB-first**: The DIAL folder path is constructible from the suite ID alone, so test case data is not needed for cleanup. DB-first ensures the suite is consistently removed even if DIAL is temporarily unavailable. Orphaned DIAL files are acceptable (can be cleaned up later).

**Why list-then-delete**: DIAL Core may not support recursive folder deletion via a single API call. Listing first and deleting individually is universally compatible. Each individual deletion failure is logged at WARN level; remaining files continue to be deleted (best-effort, no rollback).

**Why not delete public files**: Public files are shared resources and must not be deleted by EF.

## Component Interaction Flow

```
Upload:
  UI → FileController.upload(suiteId, file)
     → FileService.upload(suiteId, file)
        validates: suite exists, file size, filename uniqueness
     → DialFileRefResolver.resolveToRealPath("@ef", suiteId, filename)
        → "{realBucket}/suites/{suiteId}/{filename}"
     → DialFileClient.upload(realPath, inputStream, filename, contentType)
        PUT /v1/files/{realBucket}/suites/{suiteId}/{filename}
        Api-Key: {EF_KEY}
     → returns FileMetadataDto with path "files/@ef/suites/{suiteId}/{filename}"

Download (streaming):
  UI → FileController.download(suiteId, filename)
     → FileService.getFileMetadata(suiteId, filename)  // for Content-Type header
     → Returns ResponseEntity<StreamingResponseBody> that calls:
        FileService.downloadTo(suiteId, filename, outputStream)
          → DialFileRefResolver.resolveToRealPath(...)
          → DialFileClient.downloadTo(realPath, outputStream)
             GET /v1/files/{realBucket}/suites/{suiteId}/{filename}
          → pipes DIAL response directly to HTTP response (no heap buffering)

Eval Run (file-as-bytes mode):
  EvaluationWorker → ResolvedRequestService.resolveRequest(...)
     → resolves "${{docRef}}" → "files/@ef/suites/{id}/data.csv"
     → MultipartFormDataRequestBodySerializer.serialize(...)
        → DialFileRefResolver.resolveToRealPath(...)
        → DialFileClient.download(realPath) → byte[]
        → wraps in ByteArrayResource for MultipartBodyBuilder

Suite Delete (DB-first, then best-effort DIAL cleanup — separate tx scopes):
  TestSuiteService.delete(suiteId)                   // NON-transactional orchestrator
     → TransactionTemplate.execute(() → {             // programmatic tx — commits on return
          testSuiteRepository.delete(suiteId)          // FK cascade removes test cases
        })
     → FileService.deleteAllBySuiteId(suiteId)         // AFTER tx commit, best-effort
        → DialFileRefResolver.resolveToRealPath("@ef", suiteId)
        → DialFileClient.list("{realBucket}/suites/{suiteId}/")
        → for each file: DialFileClient.delete(filePath)  // log WARN on failure, continue
```

## Configuration

```yaml
dial:
  api-key: ${DIAL_EF_API_KEY:}              # top-level — shared across features
  file-storage:
    bucket-alias: ${DIAL_FILE_STORAGE_BUCKET_ALIAS:@ef}
    max-file-size-bytes: ${DIAL_FILE_STORAGE_MAX_FILE_SIZE_BYTES:52428800}
    max-files-per-suite: ${DIAL_FILE_STORAGE_MAX_FILES_PER_SUITE:100}
    connect-timeout-ms: ${DIAL_FILE_STORAGE_CONNECT_TIMEOUT_MS:5000}
    read-timeout-ms: ${DIAL_FILE_STORAGE_READ_TIMEOUT_MS:30000}
```

The `DialFileClient` uses the same DIAL Core base URL (`dial.components.core.base-url`) but with its own `RestClient` bean configured with API key auth (not JWT interceptor).

## Risks / Trade-offs

- **[RESOLVED — Public bucket access via EF key]** → **Confirmed**: The EF API key can access public blobs in non-restricted subfolders. No fallback to user JWT needed. The `MultipartFormDataSerializer` downloads public files using the EF key directly.
- **[DIAL Core availability]** → File operations fail if DIAL Core is down. Mitigation: DIAL Core is already a hard dependency for deployment listing and invocation.
- **[EF API key management]** → Manual provisioning for v1. Mitigation: Document setup in configuration.md.
- **[Double hop for file bytes]** → Upload: UI → EF → DIAL. Mitigation: Acceptable for typical eval file sizes (CSVs, documents). Consider delegated upload in later phases.
- **[No existence validation on save]** → Broken file refs are discovered at eval time. Mitigation: Explicit design choice. Error is surfaced in eval results.
- **[Bucket name discovery]** → If DIAL Core is unavailable, the bucket cannot be resolved. Mitigation: Lazy initialization (resolve on first use, cache result, retry on failure). Health indicator reports DOWN until resolved. Service starts normally; file operations fail until bucket is discovered.
- **[createdBy dropped]** → DIAL file metadata does not include uploading user identity. `FileMetadataDto.createdBy` is removed. Acceptable for v1 (breaking changes allowed).
- **[DIAL Core error mapping]** → The spec defines behavior for 404 and "unavailable" but not for 401/403 (invalid/revoked API key), 413 (DIAL-side size limit), or 429 (rate limiting). Implementation SHOULD map 401/403 to a clear diagnostic error (e.g., `SERVICE_CONFIGURATION_ERROR` or log at ERROR with "DIAL API key is invalid or revoked") rather than a generic 502. Other HTTP errors (409, 413, 429) SHOULD be logged and propagated as `DialCoreClientException` with the upstream status. This is deferred to implementation — no separate spec scenarios needed.
- **[ZIP export latency with DIAL]** → ZIP export with `materializeFiles=true` makes one DIAL HTTP call per FILE field per row (vs. local PG read before). For large suites this may cause client-side HTTP timeouts. Implementation SHOULD set appropriate response timeouts and consider parallelizing downloads or adding a warning in OpenAPI docs about export duration for large suites. Not blocking for v1.
- **[File orphan accumulation]** → When test cases are updated/replaced/imported, old DIAL files are orphaned (no FK cascade from test case data to DIAL files). The 100-file-per-suite limit makes this more impactful than the previous PG blob orphan issue. Suite delete cleans all EF files, but intermediate cleanup is absent. Acceptable for v1; future phase could add a reconciliation job or import-time cleanup.

## Open Questions

- ~~**File ownership after suite delete**: If a FILE field in a test case references a public DIAL file, and the test suite is deleted, the public file is untouched. This is documented behavior, but should the system warn during delete?~~ **Resolved**: Expected behavior — public files are intentionally left untouched. FE will warn if needed.
- **DIAL file references as request body parameters**: FILE-type values can appear in JSON and form-data request bodies (via `${{placeholder}}` resolution). Should the system support passing DIAL file references directly as JSON string values (not downloading bytes), allowing the target deployment to fetch files from DIAL itself? This would avoid the double-hop (EF downloads from DIAL, then sends bytes to deployment) when the deployment has DIAL access. To be discussed in a later phase.
