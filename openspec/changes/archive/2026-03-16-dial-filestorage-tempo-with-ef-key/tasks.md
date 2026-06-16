## 1. Configuration & Properties

- [x] 1.1 Create `DialFileStorageProperties` (`configuration.properties.dial`) with fields: `apiKey`, `bucketAlias`, `maxFileSizeBytes`, `maxFilesPerSuite`, `connectTimeoutMs`, `readTimeoutMs`. Add `@Validated`, `@NotBlank` on apiKey (not `@NotNull` — empty string from `${DIAL_EF_API_KEY:}` must be rejected). (done: class compiles, checkstyle passes)
- [x] 1.2 Add `dial.file-storage.*` defaults to `application.yml` and test `application.yml` (done: defaults match spec — 50MB, 100 files, 5s connect, 30s read, `@ef` alias)
- [x] 1.3 Remove `BlobStorageProperties` and `blob-storage.*` entries from `application.yml` (done: no references to old properties remain)

## 2. Verify DIAL Core Assumptions & DialFileClient

- [x] 2.0 **RESOLVED**: Confirmed that the EF API key can access public blobs in non-restricted subfolders. No JWT fallback needed.

- [x] 2.1 Create `DialFileMetadataDto` record in `client.dialcore` with fields: `name`, `parentPath`, `bucket`, `url`, `contentLength`, `contentType`, `createdAt`, `updatedAt` (done: record compiles)
- [x] 2.2 Create `DialFileClient` component in `client.dialcore` with methods: `upload`, `download` (returns InputStream), `delete`, `metadata`, `list`, `exists`, `getBucket`. Uses EF API key for auth (not user JWT). `getBucket()` uses lazy initialization: resolves on first call via `GET /v1/bucket`, caches result in thread-safe manner (e.g., `AtomicReference`), retries on next call if previous resolution failed (no failure caching). (done: all methods implemented, `@LogExecution` annotation present, lazy bucket caching works)
- [x] 2.3 Create `DialFileClientConfiguration` in `client.dialcore` — separate `RestClient` bean for file operations, configured with API key auth interceptor (not JWT), timeouts from `DialFileStorageProperties`, DIAL Core base URL. (done: bean wired, RestClient configured)
- [x] 2.4 Write unit tests for `DialFileClient` with mocked RestClient (done: upload, download, delete, metadata, list, exists, getBucket, error scenarios covered)
- [x] 2.5 Create `DialFileStorageHealthIndicator` — reports `UP` when EF bucket is resolved, `DOWN` when bucket discovery has failed. Register in `readiness` health group only (not `liveness`) to prevent k8s pod restarts before bucket resolution. Integrates with Spring Actuator health endpoint. (done: health indicator registered in readiness group, reports correct status)

## 3. File Reference Resolver

- [x] 3.1 Create `DialFileRefResolver` component in `service.domain` — methods: `resolveToRealPath(String fileRef)` (strips `files/` prefix, replaces `@ef` with real bucket, returns API path for DialFileClient), `buildEfRef(UUID suiteId, String filename)` (returns client-facing reference with `files/@ef/` prefix), `extractFilename(String fileRef)`. Injects cached bucket name from `DialFileClient.getBucket()`. Validates prefix whitelist (@ef, public). (done: all methods implemented, `@LogExecution` annotation)
- [x] 3.2 Write unit tests for `DialFileRefResolver` — resolve @ef alias, passthrough public, reject disallowed prefix, build EF ref, extract filename (done: all scenarios covered)

## 4. Remove PostgreSQL Blob Storage

- [x] 4.1 Create Flyway migration `V1.XX__DropBlobsTable.sql` in `db/migration/meta/POSTGRES/` to drop `blobs` table (done: migration file created with correct sequence number)
- [x] 4.2 Delete `BlobStorage` interface, `PostgresBlobStorage`, `BlobRepository`, `PostgresBlobRepository`, `BlobModel`, `BlobModelRowMapper`, `BlobMetadata`, `BlobReference` (done: all files removed, no compilation errors)
- [x] 4.3 Remove `BlobStorageFunctionalTests` and any test helpers referencing blob storage (done: test files removed)
- [x] 4.4 Update `docs/database-schema.md` to remove `blobs` table (done: schema doc reflects current state)

## 5. FileService & FileController Refactor

- [x] 5.1 Refactor `FileService` to delegate to `DialFileClient` + `DialFileRefResolver` instead of `BlobStorage`. Update validations: suite existence, file size limit, filename sanitization (allowed chars: alphanumeric, `-`, `_`, `.`, ` `, `(`, `)`; max 255 chars; reject `/\?#%*:|<>"`; no leading/trailing whitespace), filename uniqueness (via `dialFileClient.exists`), per-suite file count (via `dialFileClient.list`). (done: all methods updated, no BlobStorage references)
- [x] 5.2 Update `FileMetadataDto` — replace `id` (UUID) field with `path` (String, DIAL file reference). Remove `createdBy` (DIAL metadata doesn't track uploading user). Fields: `path`, `filename`, `contentType`, `sizeBytes`. (done: DTO compiles, old id/createdBy fields removed)
- [x] 5.3 Refactor `FileController` — change `{fileId}` path variable to `{filename:.+}` (regex pattern required to prevent Spring MVC suffix truncation of dotted filenames like `report.pdf`). Update upload to return DIAL file path. Download streams via `DialFileClient.download()`. Update OpenAPI annotations. (done: endpoints use filename-based paths with `:.+` regex, `@LogExecution` present)
- [x] 5.4 Update suite cascade delete in `TestSuiteService` — DB-first approach with **transaction boundary separation**: use `TransactionTemplate` (injected with `metaTransactionManager`) inside `delete()` to run the DB delete in a programmatic transaction, then call `FileService.deleteAllBySuiteId()` **after** the transaction has committed. **Do NOT use a `@Transactional` helper method on the same class** — Spring proxy-based AOP does not intercept self-calls. `deleteAllBySuiteId()` lists files via `DialFileClient.list()` and deletes each individually (best-effort, log WARN on individual failures, continue with remaining). This prevents holding the DB transaction open during slow DIAL API calls. (done: cascade delete uses TransactionTemplate + post-commit DIAL cleanup, transaction boundary verified)

## 6. FILE Type Validation Update

- [x] 6.1 Update `TestCaseValidationService` — FILE field validation changes from blob UUID existence check to DIAL file reference format + prefix whitelist validation + suite ownership check (for `@ef` refs, the suite ID in the path must match the owning suite). Remove all BlobStorage dependencies. (done: validates format, prefix, and suite ownership; no blob imports)
- [x] 6.2 Update FILE type in `SchemaFieldType` / type system reference — Java mapping changes from `String (blob UUID)` to `String (DIAL file path)`. (done: type system documentation/comments updated)
- [x] 6.3 Write unit tests for updated FILE field validation — valid @ef path, valid public path, invalid prefix, invalid format, null allowed, required null warning, cross-suite @ef reference warning (done: all scenarios covered)

## 7. MultipartFormDataRequestBodySerializer Update

- [x] 7.1 Refactor `MultipartFormDataRequestBodySerializer` — replace `BlobStorage.retrieve(uuid)` with `DialFileRefResolver.resolveToRealPath()` + `DialFileClient.download()`. Use `InputStreamResource` instead of `ByteArrayResource` for streaming. (done: no BlobStorage references, streaming works)
- [x] 7.2 Update unit tests for `MultipartFormDataRequestBodySerializer` — mock `DialFileClient` and `DialFileRefResolver`. Test file part with @ef ref, public ref, missing file (404), text parts unchanged. (done: all scenarios covered)

## 8. ZIP Export/Import Update

- [x] 8.1 Update `ZipExportService` — add `materializeFiles` parameter. When true: download file bytes from DIAL via `DialFileClient.download()` and embed in ZIP. When false: write raw DIAL file paths in CSV. Default to true when FILE fields exist. (done: both modes work, streaming)
- [x] 8.2 Update `ZipImportService` — extracted files are uploaded to DIAL at `{efBucket}/suites/{suiteId}/{uniqueFilename}` via `DialFileClient.upload()`. Sanitize original filenames from ZIP archive (replace forbidden chars with `_`) before generating unique DIAL filenames. CSV paths rewritten to `files/@ef/suites/{suiteId}/{uniqueFilename}`. (done: file upload targets DIAL, filename sanitization works, path rewriting works)
- [x] 8.3 Update export endpoint in controller to accept `materializeFiles` query param (done: param wired, OpenAPI annotation added)
- [x] 8.4 Update tests for ZIP export/import with DIAL file references (done: materialize=true, materialize=false, import ZIP, import CSV with DIAL URLs)

## 9. Functional Tests

- [x] 9.1 Create/update functional tests for `FileController` — upload, list, download, delete via DIAL proxy. Use mocked or stubbed DIAL Core file API. (done: all CRUD endpoints tested)
- [x] 9.2 Create/update functional tests for test case CRUD with DIAL file references in FILE fields — create test case with @ef ref, create with public ref, validation warnings for invalid prefix (done: scenarios covered)
- [x] 9.3 Create/update functional tests for suite cascade delete — verify DIAL file cleanup is invoked (done: delete triggers file cleanup)

## 10. Documentation & OpenAPI

- [x] 10.1 Update `docs/configuration.md` — add `dial.file-storage.*` properties, remove `blob-storage.*` properties (done: config doc current)
- [x] 10.2 Update OpenAPI examples for file endpoints and test case endpoints with FILE fields (done: examples reflect DIAL file paths)
- [x] 10.3 Update `openspec/specs/README.md` per Spec Index Maintenance Policy — add `dial-file-storage` and `dial-file-ref` entries, update `blob-storage` status (done: index reflects current specs)
- [x] 10.4 Update AGENTS.md per AGENTS.md Maintenance guidelines — update BlobStorage section to describe DIAL File Storage pattern, update Key Packages Reference if new packages added (done: relevant sections reflect the change)

## 11. Build Verification

- [x] 11.1 Run `./gradlew checkstyleMain checkstyleTest` — no violations (done: clean)
- [x] 11.2 Run `./gradlew test` — all tests pass (done: green)
- [x] 11.3 Run `./gradlew clean build` — full build succeeds (done: green)
