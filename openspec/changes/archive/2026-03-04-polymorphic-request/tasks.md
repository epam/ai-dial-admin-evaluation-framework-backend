## 1. Polymorphic Body DTOs and Jackson Configuration

- [x] 1.1 Create `RequestBodyDto` abstract base class with `@JsonTypeInfo(use = Id.NAME, property = "contentType")` and `@JsonSubTypes` for the three variants
- [x] 1.2 Create `JsonRequestBodyDto` (contentType: `application/json`, content: `Map<String, Object>`)
- [x] 1.3 Create `MultipartFormDataRequestBodyDto` (contentType: `multipart/form-data`, content: `List<FormPartDto>`)
- [x] 1.4 Create `UrlEncodedFormRequestBodyDto` (contentType: `application/x-www-form-urlencoded`, content: `List<KeyValueTemplateDto>` — reuses existing type, supports duplicate keys)
- [x] 1.5 Create `FormPartDto` (name, type enum `text`/`file`, value, filename) with validation annotations
- [x] 1.6 Create `RequestBodySchemaDto` abstract base class with `@JsonTypeInfo` polymorphism
- [x] 1.7 Create `JsonRequestBodySchemaDto`, `MultipartFormDataRequestBodySchemaDto`, `UrlEncodedFormRequestBodySchemaDto`
- [x] 1.8 Create `FormPartSchemaDto` (name, type, required, schema, allowedContentTypes, maxSizeBytes)
- [x] 1.9 Create `ResolvedBodyDto` abstract base class with `@JsonTypeInfo` polymorphism
- [x] 1.10 Create `ResolvedJsonBodyDto`, `ResolvedMultipartBodyDto`, `ResolvedUrlEncodedBodyDto` (entries: `List<KeyValueTemplateDto>`), `ResolvedFormPartDto`
- [x] 1.11 Unit tests: verify Jackson round-trip serialization/deserialization for all body variants
- [x] 1.12 Unit tests: verify unknown contentType deserialization fails gracefully

## 2. Update RequestTemplateDto and EndpointContractDto

- [x] 2.1 Change `RequestTemplateDto.body` from `Map<String, Object>` to `RequestBodyDto` (nullable)
- [x] 2.2 Change `EndpointContractDto.requestBodySchema` from `Map<String, Object>` to `RequestBodySchemaDto` (nullable)
- [x] 2.3 Update `ResolvedRequestDto.body` from `Map<String, Object>` to `ResolvedBodyDto` (nullable)
- [x] 2.4 Update `JsonbMapper` deserialization to handle polymorphic body types in JSONB columns
- [x] 2.5 Update `SchemaValidationService` to validate JSON Schema within `JsonRequestBodySchemaDto.schema` and `FormPartSchemaDto.schema`
- [x] 2.6 Update MapStruct mappers affected by the body type changes
- [x] 2.7 Fix all compilation errors resulting from the type changes across the codebase

## 3. Flyway Migration

- [x] 3.1 Create Flyway migration to wrap existing `test_suites.request_template -> body` JSONB: `{"contentType": "application/json", "content": <old_body>}` (skip rows where body is null)
- [x] 3.2 Create Flyway migration to wrap existing `test_suites.endpoint_ref -> requestBodySchema` JSONB: `{"contentType": "application/json", "schema": <old_schema>}` (skip rows where requestBodySchema is null)
- [x] 3.3 Create Flyway migration to wrap existing `test_cases.request_template_override -> body` JSONB: same transformation as 3.1 for non-null overrides
- [x] 3.4 Test migration on sample data (empty body, null body, complex nested body)

## 4. Template Resolution (Content-Type Aware)

- [x] 4.1 Update `ResolvedRequestService` body resolution to dispatch by `RequestBodyDto` type
- [x] 4.2 Implement JSON body resolution (existing recursive `Map` walk — extract to dedicated method)
- [x] 4.3 Implement multipart body resolution: resolve each `FormPartDto.value` and `FormPartDto.filename` placeholders
- [x] 4.4 Implement URL-encoded body resolution: resolve each `KeyValueTemplateDto.value` placeholder and stringify all values
- [x] 4.5 Update `TemplateVariableExtractor` to extract variables from multipart `FormPartDto.value` and `FormPartDto.filename` fields
- [x] 4.6 Update `TemplateVariableExtractor` to extract variables from URL-encoded body content
- [x] 4.7 Unit tests: JSON body resolution (existing tests should still pass)
- [x] 4.8 Unit tests: multipart body resolution with text and file parts
- [x] 4.9 Unit tests: URL-encoded body resolution with stringification
- [x] 4.10 Unit tests: template variable extraction from multipart and URL-encoded bodies

## 5. Pluggable Body Serializer

- [x] 5.1 Create `RequestBodySerializer` interface (`supports`, `serialize` returning `SerializedBody` record with `MediaType contentType` and `Object body`)
- [x] 5.2 Create `SerializedBody` record (`MediaType contentType`, `Object body`)
- [x] 5.3 Create `JsonRequestBodySerializer` — returns `SerializedBody(APPLICATION_JSON, contentMap)`
- [x] 5.4 Create `MultipartFormDataRequestBodySerializer` — uses `MultipartBodyBuilder`, reads file bytes from `BlobStorage`, returns `SerializedBody(MULTIPART_FORM_DATA, multipartBody)`
- [x] 5.5 Create `UrlEncodedFormRequestBodySerializer` — converts `List<KeyValueTemplateDto>` to `MultiValueMap<String, String>`, returns `SerializedBody(APPLICATION_FORM_URLENCODED, formMap)`
- [x] 5.6 Create `RequestBodySerializerRegistry` — selects serializer by resolved body type
- [x] 5.7 Unit tests: each serializer produces correct Content-Type and body format
- [x] 5.8 Unit tests: registry selects correct serializer, throws on unknown type

## 6. DialCoreDeploymentInvoker Changes

- [x] 6.1 Remove hardcoded `setContentType(MediaType.APPLICATION_JSON)` from `invoke()` and `invokeWithStreaming()`
- [x] 6.2 Update invoker to pass Content-Type from provided headers (set by serializer) without overriding
- [x] 6.3 Update `TryItOutService` to use `RequestBodySerializerRegistry` before calling invoker (serialize → set Content-Type on headers → pass body to invoker)
- [x] 6.4 Update `EvaluationWorker` to use `RequestBodySerializerRegistry` before calling invoker (serialize → set Content-Type on headers → pass body to invoker)
- [x] 6.5 Update request body serialization in `EvaluationWorker` for analytics storage (JSON representation for all body types)
- [x] 6.6 Functional tests: try-it-out with JSON body (regression — must still work)

## 7. BlobStorage Abstraction and PostgreSQL LO Implementation

- [x] 7.1 Create `BlobStorage` interface in `service.domain` (store → BlobReference, retrieve → byte[], delete, deleteByTestSuiteId, exists)
- [x] 7.2 Create `BlobMetadata` and `BlobReference` records/classes
- [x] 7.3 Create Flyway migration for `blobs` metadata table (id, test_suite_id FK, oid, filename, content_type, size_bytes, created_by, created_at_ms)
- [x] 7.4 Create `BlobMetadataRowMapper`
- [x] 7.5 Create `BlobRepository` interface and `PostgresBlobRepository` implementation
- [x] 7.6 Create `PostgresBlobStorage` implementation using `LargeObjectManager` from PostgreSQL JDBC
- [x] 7.7 Create `BlobStorageProperties` for configurable limits (max-file-size-bytes, max-files-per-suite)
- [x] 7.8 Add configuration defaults in `application.yml`
- [x] 7.9 Unit/integration tests: store, retrieve, delete blob via PostgreSQL LO
- [x] 7.10 Integration test: deleteByTestSuiteId removes all blobs and LOs

## 8. File Management REST API

- [x] 8.1 Create `FileService` (upload, download, delete, list — delegates to `BlobStorage`)
- [x] 8.2 Create `FileMetadataDto` response DTO
- [x] 8.3 Create `FileController` with endpoints: POST upload, GET list, GET download, DELETE
- [x] 8.4 Add file size and count validation in upload (check limits from `BlobStorageProperties`)
- [x] 8.5 Verify test suite existence before file operations
- [x] 8.6 Add OpenAPI annotations to `FileController`
- [x] 8.7 Update test suite cascade delete to call `blobStorage.deleteByTestSuiteId()`
- [x] 8.8 Add `MetaTestDataHelper` methods for file fixture creation/cleanup in tests
- [x] 8.9 Functional tests: upload, list, download, delete files
- [x] 8.10 Functional tests: upload to non-existent suite (404), exceed size limit (400), exceed count limit (400)
- [x] 8.11 Functional tests: suite deletion cascades to files

## 9. SchemaFieldType.FILE and Test Case Validation

- [x] 9.1 Add `FILE` to `SchemaFieldType` enum
- [x] 9.2 Update `TestCaseValidationService` to validate FILE field values (check blob UUID exists and belongs to suite)
- [x] 9.3 Update `CsvCellParser` to handle FILE type (no auto-inference — requires schema declaration)
- [x] 9.4 Unit tests: FILE field validation (valid blob, missing blob, wrong suite blob, null value)
- [x] 9.5 Functional tests: create test case with FILE field referencing uploaded blob

## 10. Hybrid CSV/ZIP Export

- [x] 10.1 Create `ZipExportService` — builds ZIP with `test-cases.csv` + `files/` directory, streaming to output
- [x] 10.2 Update `CsvExportService` or `TestCaseController` export endpoint to check for FILE fields and delegate to `ZipExportService` when present
- [x] 10.3 Set correct response Content-Type and Content-Disposition headers based on export format
- [x] 10.4 Handle FILE columns in CSV: write relative path (`files/{rowIndex}/{fieldName}/{filename}`) instead of blob UUID — rowIndex is 1-based CSV row number
- [x] 10.5 Stream file bytes from BlobStorage into ZIP entries (no full in-memory buffering)
- [x] 10.6 Functional tests: export suite without FILE fields → CSV (regression)
- [x] 10.7 Functional tests: export suite with FILE fields → ZIP containing CSV + files

## 11. Hybrid CSV/ZIP Import

- [x] 11.1 Update import endpoint to detect file format (`.csv` vs `.zip` by extension or content)
- [x] 11.2 Create `ZipImportService` — extracts CSV from archive, processes FILE columns by finding files in archive and uploading to BlobStorage
- [x] 11.3 Handle missing files in archive: set FILE field to null with validation warning
- [x] 11.4 Handle CSV import for suites with FILE fields: treat FILE columns as raw string values (blob UUIDs)
- [x] 11.5 Update import preview endpoint to support ZIP format (preview CSV within archive)
- [x] 11.6 Functional tests: import ZIP with files → test cases created with correct blob references
- [x] 11.7 Functional tests: import ZIP with missing file → warning generated, FILE field null
- [x] 11.8 Functional tests: import CSV for suite with FILE fields → blob UUIDs stored as strings

## 12. Suite-Level Validation Updates

- [x] 12.1 Add content-type mismatch warning: if `endpointRef.requestBodySchema.contentType` differs from `requestTemplate.body.contentType`
- [x] 12.2 Update template variable extraction and binding validation for polymorphic body types
- [x] 12.3 Functional tests: suite validation with multipart template and matching schema
- [x] 12.4 Functional tests: suite validation with content-type mismatch warning

## 13. Try-It-Out Integration

- [x] 13.1 Update `TryItOutService` to use `RequestBodySerializerRegistry` for body serialization before deployment invocation
- [x] 13.2 Update `TryItOutResponseDto` serialization to handle polymorphic `ResolvedBodyDto`
- [x] 13.3 Functional tests: try-it-out with JSON template (regression)
- [x] 13.4 Functional tests: try-it-out with multipart template (file uploaded, multipart sent to deployment)
- [x] 13.5 Functional tests: try-it-out with URL-encoded template

## 14. Evaluation Engine Integration

- [x] 14.1 Update `EvaluationWorker` to use `RequestBodySerializerRegistry` for body serialization
- [x] 14.2 Update `EvaluationWorker.serializeBody()` to produce JSON representation for all body types (for analytics storage)
- [x] 14.3 Functional tests: evaluation run with multipart template (end-to-end)

## 15. Documentation and Cleanup

- [x] 15.1 Update `docs/database-schema.md` with `blobs` table and JSONB schema changes
- [x] 15.2 Update `docs/configuration.md` with blob-storage configuration properties
- [x] 15.3 Update OpenAPI examples for test suite CRUD (polymorphic body in request template and endpoint schema)
- [x] 15.4 Run `./gradlew checkstyleMain checkstyleTest` — fix any violations
- [x] 15.5 Run `./gradlew test` — verify all tests pass (including regression)
