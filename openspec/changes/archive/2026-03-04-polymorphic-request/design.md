## Context

The evaluation framework currently hardcodes `Content-Type: application/json` when invoking DIAL deployments. The `RequestTemplateDto.body` is `Map<String, Object>`, serialized by Jackson's `ObjectMapper`. This makes it impossible to test DIAL app routes that accept `multipart/form-data` (e.g., file upload endpoints) or `application/x-www-form-urlencoded`.

Key current state:
- `DialCoreDeploymentInvoker` sets `httpHeaders.setContentType(MediaType.APPLICATION_JSON)` unconditionally
- `ResolvedRequestDto.body` is `Map<String, Object>` — no way to express form parts
- `EndpointContractDto.requestBodySchema` is `Map<String, Object>` (JSON Schema) — no multipart schema
- Test case data supports `STRING, INTEGER, NUMBER, BOOLEAN, OBJECT, ARRAY` — no FILE type
- No file storage infrastructure exists

## Goals / Non-Goals

**Goals:**
- Support `multipart/form-data` requests with file uploads to DIAL deployments
- Support `application/x-www-form-urlencoded` requests
- Pluggable architecture: adding new content types requires only new subclass + serializer
- File storage abstraction with PostgreSQL LO implementation for v1
- File management API scoped to test suites
- Hybrid CSV/ZIP export/import for suites with FILE fields
- Flyway migration for backward-compatible data transformation

**Non-Goals:**
- Auto-discovery of content type from DIAL Core app schema (future post-v1 DIAL Core feature)
- S3/MinIO/DIAL Core Files API integration (future — abstraction supports it)
- Multipart response handling (responses remain JSON or SSE)
- Binary response body storage (out of scope)
- File content indexing or search

## Decisions

### D1: Polymorphic body type hierarchy with `contentType` discriminator

**Decision**: `RequestTemplateDto.body` changes from `Map<String, Object>` to an abstract `RequestBodyDto` using Jackson `@JsonTypeInfo(property = "contentType")` polymorphism.

**Variants:**

| Variant | `contentType` | `content` type | Purpose |
|---------|---------------|----------------|---------|
| `JsonRequestBodyDto` | `application/json` | `Map<String, Object>` | Current behavior (wrapped) |
| `MultipartFormDataRequestBodyDto` | `multipart/form-data` | `List<FormPartDto>` | Explicit form parts |
| `UrlEncodedFormRequestBodyDto` | `application/x-www-form-urlencoded` | `List<KeyValueTemplateDto>` | Flat key-value pairs (reuses existing type, supports duplicate keys) |

**`FormPartDto`:**
- `name` (String, required) — form field name
- `type` (enum: `text`, `file`) — determines serialization
- `value` (Object) — template placeholder or constant value; for `file` type, resolves to blob UUID
- `filename` (String, nullable) — optional, may contain `${{var}}` placeholders; for file parts, overrides the stored filename

**Why this over alternatives:**
- *Alternative: separate `bodyContentType` field + same `Map` body* — rejected because the body structure genuinely differs per content type (Map vs List of parts). A flat Map can't express per-part metadata (name, type, filename).
- *Alternative: convention-based file detection (magic prefix like `blob::`)* — rejected because magic strings are fragile and the serializer can't distinguish text from file without out-of-band info.

### D2: Polymorphic endpoint body schema

**Decision**: `EndpointContractDto.requestBodySchema` changes from `Map<String, Object>` to `RequestBodySchemaDto` with the same `contentType` discriminator.

| Variant | `contentType` | Schema structure |
|---------|---------------|-----------------|
| `JsonRequestBodySchemaDto` | `application/json` | `schema: Map<String, Object>` (JSON Schema) |
| `MultipartFormDataRequestBodySchemaDto` | `multipart/form-data` | `parts: List<FormPartSchemaDto>` |
| `UrlEncodedFormRequestBodySchemaDto` | `application/x-www-form-urlencoded` | `schema: Map<String, Object>` (JSON Schema for flat fields) |

**`FormPartSchemaDto`:**
- `name` (String) — part name
- `type` (enum: `text`, `file`)
- `required` (boolean)
- `schema` (Map<String, Object>, nullable) — JSON Schema for text parts
- `allowedContentTypes` (List<String>, nullable) — MIME type constraints for file parts
- `maxSizeBytes` (Long, nullable) — size limit for file parts

**`requestContentType`** is NOT added to `EndpointContractDto` — it's derivable from `requestBodySchema.contentType`.

### D3: Polymorphic resolved body

**Decision**: `ResolvedRequestDto.body` changes from `Map<String, Object>` to `ResolvedBodyDto` (abstract).

| Variant | Content |
|---------|---------|
| `ResolvedJsonBodyDto` | `content: Map<String, Object>` (fully resolved body tree) |
| `ResolvedMultipartBodyDto` | `parts: List<ResolvedFormPartDto>` (each part resolved) |
| `ResolvedUrlEncodedBodyDto` | `entries: List<KeyValueTemplateDto>` (resolved key-value pairs) |

`ResolvedFormPartDto` mirrors `FormPartDto` but with all template placeholders resolved and file references ready for byte fetching.

### D4: Pluggable `RequestBodySerializer` strategy

**Decision**: New strategy interface in `service.domain`:

```
RequestBodySerializer
  boolean supports(ResolvedBodyDto body)
  SerializedBody serialize(ResolvedBodyDto body)
```

`SerializedBody` is a record containing: `MediaType contentType`, `Object body`. The `body` object type depends on the content type:
- For JSON: `Map<String, Object>` (RestClient serializes via Jackson)
- For multipart: `MultiValueMap<String, HttpEntity<?>>` (from `MultipartBodyBuilder.build()`)
- For URL-encoded: `MultiValueMap<String, String>`

Implementations:
- `JsonRequestBodySerializer` — returns `SerializedBody(APPLICATION_JSON, contentMap)`
- `MultipartFormDataRequestBodySerializer` — uses Spring `MultipartBodyBuilder`, reads file bytes from `BlobStorage`, returns `SerializedBody(MULTIPART_FORM_DATA, multipartBody)`
- `UrlEncodedFormRequestBodySerializer` — converts `List<KeyValueTemplateDto>` to `MultiValueMap<String, String>`, returns `SerializedBody(APPLICATION_FORM_URLENCODED, formMap)`

A `RequestBodySerializerRegistry` (injectable `@Component`) selects the correct serializer by body type.

Service-layer call flow:
1. `SerializedBody serialized = registry.serialize(resolvedBody);`
2. `headers.setContentType(serialized.contentType());`
3. `invoker.invoke(method, path, headers, queryParams, serialized.body());`

**Why strategy over modifying invoker**: The invoker (`DialCoreDeploymentInvoker`) is in the `client.dialcore` package. Serialization logic (including `BlobStorage` access for file parts) is a service concern. The serializer produces a `SerializedBody` that the service layer passes to the content-type-agnostic invoker, maintaining the layering boundary. The serializer interface is decoupled from `RestClient` internals — it returns plain Spring/JDK types.

### D5: BlobStorage abstraction with PostgreSQL LO

**Decision**: New `BlobStorage` interface in `service.domain`:

```
BlobReference store(InputStream data, BlobMetadata metadata, UUID testSuiteId)
byte[] retrieve(UUID blobId)
void delete(UUID blobId)
void deleteByTestSuiteId(UUID testSuiteId)
boolean exists(UUID blobId)
```

**Why `byte[]` for retrieve instead of `InputStream`**: PostgreSQL Large Objects require an active transaction for reading. Returning `InputStream` would force callers to manage transaction boundaries (the stream dies when the transaction ends), making the abstraction leaky. Alternatives considered:

- *Callback pattern (`void retrieve(UUID, OutputStream)`)* — cleanly streams LO → target within a tight transaction for download and ZIP export, but Spring's `MultipartBodyBuilder` requires in-memory `Resource`/bytes, so the multipart serializer can't use callbacks. Helps 2 of 3 callers; adds interface complexity for marginal gain within the 50MB limit.
- *Return `ByteArrayInputStream`* — semantically dishonest (looks like streaming, loads everything in memory).
- *`@Transactional` on caller* — ties up DB connections during HTTP response transfer or external calls; goes against the codebase pattern where CSV export uses no outer transaction.
- *Managed resource wrapping transaction* — complex, leak-prone if not closed.

Since v1 enforces a 50MB file size limit and `MultipartBodyBuilder` requires in-memory bytes regardless, `byte[]` is the honest contract. The `store()` method keeps `InputStream` since the caller controls the write lifecycle within a transaction. Future optimization: if file size limits are raised, a callback-based `retrieveTo(UUID, OutputStream)` can be added for download/export paths while keeping `byte[]` for the multipart serializer. When DIAL File Storage is added (OQ1), external files can be truly streamed without PG transaction constraints.

v1 implementation: `PostgresBlobStorage` using PostgreSQL Large Objects (`PGConnection.getLargeObjectAPI()`).

**Metadata table** (`blobs` in meta datasource):

| Column | Type | Description |
|--------|------|-------------|
| `id` | `VARCHAR(36) PK` | Blob UUID |
| `test_suite_id` | `VARCHAR(36) FK NOT NULL` | Owning test suite |
| `oid` | `BIGINT NOT NULL` | PostgreSQL Large Object OID |
| `filename` | `VARCHAR(255)` | Original filename |
| `content_type` | `VARCHAR(255)` | MIME type |
| `size_bytes` | `BIGINT NOT NULL` | File size |
| `created_by` | `VARCHAR(255) NOT NULL` | Who uploaded |
| `created_at_ms` | `BIGINT NOT NULL` | Upload timestamp |

Cascade: when a test suite is deleted, all its blobs (metadata + LO data) are deleted.

**Why PostgreSQL LO over alternatives:**
- *BYTEA columns* — limited to ~1GB, loaded fully into memory. LO supports streaming and up to 4TB.
- *External storage (S3/MinIO)* — adds infrastructure dependency. LO is zero-dependency since we already use PostgreSQL.
- *DIAL Core Files API* — would create circular dependency (framework → Core for files, Core → framework for eval).

### D6: File management API scoped to test suites

**Endpoints:**

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/test-suites/{suiteId}/files` | Upload file (multipart/form-data) |
| `GET` | `/api/v1/test-suites/{suiteId}/files` | List files (paginated) |
| `GET` | `/api/v1/test-suites/{suiteId}/files/{fileId}` | Download file (stream bytes) |
| `DELETE` | `/api/v1/test-suites/{suiteId}/files/{fileId}` | Delete file |

Upload returns `FileMetadataDto` (id, filename, contentType, sizeBytes, createdBy, createdAt).
Download streams bytes with `Content-Disposition: attachment`.

Configurable limits: max file size (default 50MB), max files per suite (default 100).

### D7: `SchemaFieldType.FILE` and test case data

**Decision**: Add `FILE` to `SchemaFieldType` enum. A FILE field in `testCaseSchema` indicates the test case data value is a blob UUID string.

Test case data example: `{ "prompt": "Analyze this", "document": "a1b2c3d4-e5f6-..." }`

Validation: when a test case field is declared as `FILE` type, the validator checks that the referenced blob UUID exists and belongs to the same test suite.

### D8: Hybrid CSV/ZIP export/import

**Export logic:**
1. Check if suite's `testCaseSchema` contains any `FILE` type fields
2. If no FILE fields → export as CSV (current behavior, unchanged)
3. If FILE fields present → export as ZIP archive:
   - `test-cases.csv` — FILE columns contain relative paths (e.g., `files/{rowIndex}/{fieldName}/{filename}`)
   - `files/` directory — organized by row index (1-based CSV row number) and field name for guaranteed uniqueness

**Export endpoint**: `GET /api/v1/test-suites/{testSuiteId}/test-cases/export` (without `.csv` extension since response may be CSV or ZIP). Sets `Content-Type` and `Content-Disposition` dynamically based on format.

**Import logic:**
1. Detect file extension: `.csv` → current CSV import flow; `.zip` → ZIP import flow
2. ZIP import: extract CSV, parse it, for each FILE column value find the file by relative path in the archive, upload to BlobStorage, map blob UUID into test case data

**Content-Type detection**: import endpoint accepts `multipart/form-data`. File extension determines the processing path.

### D9: `DialCoreDeploymentInvoker` changes

**Decision**: The invoker's `invoke()` and `invokeWithStreaming()` methods change signature:
- Remove hardcoded `setContentType(MediaType.APPLICATION_JSON)`
- Accept a pre-serialized body along with headers that already contain the correct Content-Type (set by the serializer)
- The serializer sets `Content-Type` on the headers before the invoker sends the request

This keeps the invoker content-type-agnostic while the serializer (service layer) handles content-type-specific logic.

### D10: Flyway migration for existing data

**Decision**: Single Flyway migration rewrites JSONB columns:

1. `test_suites.request_template` → wrap `body` field: `{"contentType": "application/json", "content": <old_body>}`
2. `test_suites.endpoint_ref` → wrap `requestBodySchema` field: `{"contentType": "application/json", "schema": <old_schema>}`
3. `test_cases.request_template_override` → same transformation as (1) where non-null

Uses PostgreSQL JSONB operators (`jsonb_set`, `jsonb_build_object`) for in-place transformation.

## Risks / Trade-offs

**[Large Object cleanup on suite deletion]** → PostgreSQL LOs are not automatically garbage-collected when the `blobs` metadata row is deleted. The deletion flow must explicitly call `lo_unlink` for each LO OID within the same transaction. Mitigation: `BlobStorage.deleteByTestSuiteId()` handles both metadata and LO cleanup atomically.

**[ZIP export memory pressure]** → Large suites with many files could produce large ZIP archives. Mitigation: stream ZIP construction directly to `HttpServletResponse.getOutputStream()` without buffering the full archive in memory. File bytes are streamed from BlobStorage per-entry.

**[Migration on large datasets]** → JSONB rewrite migration touches every row in `test_suites` and `test_cases`. For large deployments this could be slow. Mitigation: breaking changes are acceptable in current project phase; migration runs once on deploy.

**[Multipart file size in memory during invocation]** → When the `MultipartFormDataRequestBodySerializer` reads file bytes from BlobStorage, it streams them into the multipart builder. Spring's `MultipartBodyBuilder` buffers parts in memory. Mitigation: enforce per-file and per-request size limits. For v1, the maximum file size (50MB default) is acceptable for in-memory buffering.

**[Jackson polymorphism with JSONB]** → The `contentType` discriminator must be preserved correctly through serialize/deserialize cycles in JSONB columns. Mitigation: integration tests covering round-trip serialization for all variants. Jackson's `@JsonTypeInfo` with `As.PROPERTY` is well-tested and reliable.

## Open Questions

### OQ1: DIAL File Storage support alongside PostgreSQL LO

**Context**: Some DIAL apps can accept the same test files in two ways:
- **(a) as files/blobs in request** — binary data sent inline via `multipart/form-data`
- **(b) as links to DIAL File Storage** — a URL/reference to a file already stored in DIAL's file system

The current design (D5) uses PostgreSQL LO as the sole blob backend. However, a future (or near-term) requirement is to support DIAL File Storage as an additional or alternative storage backend. This would allow users to configure the "file link type" per test suite or per DIAL app — choosing whether files are sent as binary blobs in the request or as DIAL File Storage references.

**Impact on current design**:
- The `BlobStorage` abstraction (D5) is already designed for pluggability — the interface is backend-agnostic. A `DialFileStorageBlobStorage` implementation could be added without changing the interface contract.
- However, the **invocation path differs fundamentally**: sending a file as a multipart blob (current flow via `MultipartFormDataRequestBodySerializer`) vs. sending a DIAL file reference URL (a string value in the request body) are different serialization strategies, not just different storage backends.
- This may require a new `FormPartDto.type` value (e.g., `file_ref` or `dial_file`) or a configuration flag on the test suite / file that controls how FILE fields are resolved during invocation.

**Decision**: Defer to post-v1. The current `BlobStorage` abstraction and `FormPartDto.type` enum are extensible enough to accommodate this. The key architectural decision (storage backend vs. invocation strategy) needs further exploration once DIAL File Storage API capabilities are better understood.

**To resolve**: What is the DIAL File Storage API contract? Can the framework upload files to it? Does it return a stable URL? Can the same file be referenced both as a blob and as a link? Answers will shape whether this is a new `BlobStorage` implementation, a new `FormPartDto.type`, or both.
