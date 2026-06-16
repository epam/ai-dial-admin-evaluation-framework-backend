## Why

DIAL applications can define routes that accept `multipart/form-data` requests (including file uploads), but the evaluation framework hardcodes `Content-Type: application/json` for all deployment invocations. This prevents users from testing any DIAL app route that requires form data or file uploads. The framework needs a pluggable content-type architecture to support `multipart/form-data` now and extensible to new content types (e.g., `application/x-www-form-urlencoded`) in the future.

## What Changes

- **BREAKING** — `RequestTemplateDto.body` changes from `Map<String, Object>` to a polymorphic `RequestBodyDto` with `contentType` as Jackson discriminator. Existing JSON bodies become the `application/json` variant. New variants: `multipart/form-data` (with explicit part declarations) and `application/x-www-form-urlencoded`.
- **BREAKING** — `EndpointContractDto.requestBodySchema` changes from `Map<String, Object>` (JSON Schema) to a polymorphic `RequestBodySchemaDto` with `contentType` discriminator. Per-content-type schema structure (JSON Schema for JSON, per-part schema for multipart).
- **BREAKING** — `ResolvedRequestDto.body` changes from `Map<String, Object>` to a polymorphic `ResolvedBodyDto` mirroring the template body type hierarchy.
- New `SchemaFieldType.FILE` for test case schema fields that reference uploaded files.
- New `BlobStorage` abstraction with PostgreSQL Large Objects implementation (v1). Designed for future swap to S3/MinIO/DIAL Core Files API.
- New file management API scoped to test suites: upload, download, delete, list.
- `DialCoreDeploymentInvoker` no longer hardcodes `Content-Type: application/json` — delegates to a pluggable `RequestBodySerializer` strategy selected by body content type.
- Hybrid export/import: test suites with FILE fields export as ZIP (CSV + files); suites without FILE fields export as CSV (current behavior). Import accepts both formats.
- Flyway migration to wrap existing `request_template.body` and `endpoint_ref.requestBodySchema` JSONB values in the new polymorphic wrapper.

## Capabilities

### New Capabilities
- `blob-storage`: BlobStorage abstraction interface, PostgreSQL Large Objects implementation, file metadata table, file management REST API scoped to test suites (upload, download, delete, list), cascade cleanup on suite deletion.
- `polymorphic-request-body`: Polymorphic body type hierarchy for request templates (`RequestBodyDto`), endpoint schemas (`RequestBodySchemaDto`), and resolved requests (`ResolvedBodyDto`). Pluggable `RequestBodySerializer` strategy pattern for body serialization. Content-type-specific template resolution logic.

### Modified Capabilities
- `request-template`: `body` field type changes to polymorphic `RequestBodyDto`; template resolution becomes content-type-aware; template variable extraction handles new body structures (form parts, etc.).
- `test-suites`: `endpointRef.requestBodySchema` becomes polymorphic `RequestBodySchemaDto`; `requestContentType` is NOT added (derived from schema's contentType).
- `test-cases`: New `SchemaFieldType.FILE`; hybrid CSV/ZIP export for suites with FILE fields; ZIP import support.
- `try-it-out`: Resolved request body in response becomes polymorphic `ResolvedBodyDto`; try-it-out invocation uses pluggable serializer.
- `eval-execution-engine`: `EvaluationWorker` uses pluggable `RequestBodySerializer` instead of direct JSON body pass-through; multipart serializer reads file bytes from `BlobStorage`.
- `dial-core-client`: `DialCoreDeploymentInvoker` accepts pre-built request entity (headers + body bytes) instead of raw Object; removes hardcoded `Content-Type: application/json`.

## Impact

- **Database**: New `blobs` metadata table (meta datasource); Flyway migration for existing `test_suites.request_template` and `test_suites.endpoint_ref` JSONB columns; PostgreSQL Large Object storage.
- **API**: Breaking changes to request/response DTOs for test suite CRUD, test case export/import, try-it-out, resolved-request preview. All existing API consumers must update to handle polymorphic body structures.
- **Dependencies**: May need `spring-web` multipart support utilities (`MultipartBodyBuilder`); PostgreSQL JDBC Large Object API (`PGConnection.getLargeObjectAPI()`).
- **Affected packages**: `service.domain.dto`, `service.domain.mapper`, `service.domain`, `client.dialcore`, `web.controller`, `data.db.repository`, `data.db.model`, `data.db.mapper`.
- **Test infrastructure**: `MetaTestDataHelper` needs file upload helpers; functional tests for multipart invocation need DIAL Core mock/stub adjustments.
