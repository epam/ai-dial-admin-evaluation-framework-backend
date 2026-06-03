# Polymorphic Request Body

## Purpose
This spec describes the polymorphic request body type system for supporting multiple content types (JSON, multipart/form-data, URL-encoded) in request templates. Covers the DTO hierarchies for template bodies, schema bodies, resolved bodies, and the pluggable serializer strategy.

Status: **Implemented**

## Requirements

### Requirement: RequestBodyDto polymorphic type hierarchy
The system SHALL define an abstract `RequestBodyDto` base class with Jackson `@JsonTypeInfo(use = Id.NAME, property = "contentType")` polymorphic deserialization. The `contentType` field SHALL serve as the discriminator.

Concrete variants:
- `JsonRequestBodyDto` (`contentType: "application/json"`) — `content: Map<String, Object>` (current body structure)
- `MultipartFormDataRequestBodyDto` (`contentType: "multipart/form-data"`) — `content: List<FormPartDto>`
- `UrlEncodedFormRequestBodyDto` (`contentType: "application/x-www-form-urlencoded"`) — `content: List<KeyValueTemplateDto>` (reuses existing key-value pair type, supports duplicate keys like `tag=a&tag=b`)

#### Scenario: JSON body deserialization
- **WHEN** the system receives a request template body with `"contentType": "application/json"`
- **THEN** it SHALL deserialize as `JsonRequestBodyDto` with `content` as `Map<String, Object>`

#### Scenario: Multipart body deserialization
- **WHEN** the system receives a request template body with `"contentType": "multipart/form-data"`
- **THEN** it SHALL deserialize as `MultipartFormDataRequestBodyDto` with `content` as `List<FormPartDto>`

#### Scenario: URL-encoded body deserialization
- **WHEN** the system receives a request template body with `"contentType": "application/x-www-form-urlencoded"`
- **THEN** it SHALL deserialize as `UrlEncodedFormRequestBodyDto` with `content` as `List<KeyValueTemplateDto>`

#### Scenario: Unknown content type rejected
- **WHEN** the system receives a request template body with an unrecognized `contentType`
- **THEN** deserialization SHALL fail with a validation error

### Requirement: FormPartDto structure
Each `FormPartDto` in a `MultipartFormDataRequestBodyDto.content` SHALL have:
- `name` (String, required, non-blank) — form field name in the multipart message
- `type` (enum: `text`, `file`, required) — determines how the part is serialized
- `value` (Object, required) — template placeholder (e.g., `"${{prompt}}"`) or constant value; for `file` type, resolves to a DIAL file reference path (e.g., `files/@ef/suites/{suiteId}/data.csv`)
- `filename` (String, nullable) — optional; may contain `${{var}}` placeholders; for file parts, overrides the filename in the multipart Content-Disposition

#### Scenario: Text form part
- **WHEN** a form part has `type: "text"` and `value: "${{prompt}}"`
- **THEN** the system SHALL resolve the placeholder and include the resolved string value as a text form field

#### Scenario: File form part
- **WHEN** a form part has `type: "file"` and `value: "${{document}}"` where `${{document}}` resolves to a DIAL file reference path
- **THEN** the system SHALL download the file bytes from DIAL storage via `DialFileClient` (after resolving the alias via `DialFileRefResolver`) and include them as a file part in the multipart message

#### Scenario: File part with custom filename
- **WHEN** a form part has `type: "file"`, `value: "${{document}}"`, and `filename: "${{doc_name:upload.pdf}}"`
- **THEN** the system SHALL use the resolved filename in the Content-Disposition header of that multipart part

### Requirement: RequestBodySchemaDto polymorphic type hierarchy
The system SHALL define an abstract `RequestBodySchemaDto` base class with Jackson `@JsonTypeInfo(use = Id.NAME, property = "contentType")` polymorphic deserialization.

Concrete variants:
- `JsonRequestBodySchemaDto` (`contentType: "application/json"`) — `schema: Map<String, Object>` (JSON Schema)
- `MultipartFormDataRequestBodySchemaDto` (`contentType: "multipart/form-data"`) — `parts: List<FormPartSchemaDto>`
- `UrlEncodedFormRequestBodySchemaDto` (`contentType: "application/x-www-form-urlencoded"`) — `schema: Map<String, Object>` (JSON Schema for flat fields)

#### Scenario: JSON schema deserialization
- **WHEN** `endpointRef.requestBodySchema` has `"contentType": "application/json"`
- **THEN** it SHALL deserialize as `JsonRequestBodySchemaDto` with `schema` field as JSON Schema

#### Scenario: Multipart schema deserialization
- **WHEN** `endpointRef.requestBodySchema` has `"contentType": "multipart/form-data"`
- **THEN** it SHALL deserialize as `MultipartFormDataRequestBodySchemaDto` with `parts` as `List<FormPartSchemaDto>`

### Requirement: FormPartSchemaDto structure
Each `FormPartSchemaDto` SHALL have:
- `name` (String, required) — part name
- `type` (enum: `text`, `file`, required) — determines part kind
- `required` (boolean) — whether this part is mandatory
- `schema` (Map<String, Object>, nullable) — JSON Schema for text parts' value constraints
- `allowedContentTypes` (List<String>, nullable) — MIME type constraints for file parts (e.g., `["application/pdf", "image/*"]`)
- `maxSizeBytes` (Long, nullable) — per-part file size limit

#### Scenario: Text part schema validation
- **WHEN** the endpoint schema declares a text part with `schema: {"type": "string", "maxLength": 1000}`
- **THEN** suite-level validation MAY produce warnings if the template variable's schema does not conform

#### Scenario: File part schema with MIME constraint
- **WHEN** the endpoint schema declares a file part with `allowedContentTypes: ["application/pdf"]`
- **THEN** suite-level validation MAY produce warnings if the test case's file content type does not match

### Requirement: ResolvedBodyDto polymorphic type hierarchy
The system SHALL define an abstract `ResolvedBodyDto` base class mirroring the template body hierarchy. Resolution SHALL produce the matching resolved variant.

Concrete variants:
- `ResolvedJsonBodyDto` — `content: Map<String, Object>` (fully resolved)
- `ResolvedMultipartBodyDto` — `parts: List<ResolvedFormPartDto>` (each part resolved)
- `ResolvedUrlEncodedBodyDto` — `entries: List<KeyValueTemplateDto>` (resolved key-value pairs)

`ResolvedFormPartDto` SHALL contain:
- `name` (String) — form field name
- `type` (enum: `text`, `file`)
- `resolvedValue` (Object) — resolved value (string for text, DIAL file reference path for file)
- `filename` (String, nullable) — resolved filename

#### Scenario: JSON body resolution
- **WHEN** a `JsonRequestBodyDto` is resolved
- **THEN** the result SHALL be a `ResolvedJsonBodyDto` with all placeholders in `content` recursively resolved (same as current behavior)

#### Scenario: Multipart body resolution
- **WHEN** a `MultipartFormDataRequestBodyDto` is resolved
- **THEN** the result SHALL be a `ResolvedMultipartBodyDto` with each part's `value` and `filename` resolved

#### Scenario: URL-encoded body resolution
- **WHEN** a `UrlEncodedFormRequestBodyDto` is resolved
- **THEN** the result SHALL be a `ResolvedUrlEncodedBodyDto` with each `KeyValueTemplateDto.value` placeholder resolved and stringified

### Requirement: RequestBodySerializer strategy
The system SHALL define a `RequestBodySerializer` interface for content-type-specific body serialization, with implementations registered in a `RequestBodySerializerRegistry`.

Interface:
- `boolean supports(ResolvedBodyDto body)`
- `SerializedBody serialize(ResolvedBodyDto body)` — returns a `SerializedBody` record containing `MediaType contentType` and `Object body`

The `body` field in `SerializedBody` is typed according to the content type:
- For JSON: `Map<String, Object>` (RestClient serializes via Jackson)
- For multipart: `MultiValueMap<String, HttpEntity<?>>` (from `MultipartBodyBuilder.build()`)
- For URL-encoded: `MultiValueMap<String, String>`

Implementations:
- `JsonRequestBodySerializer` — returns `SerializedBody(APPLICATION_JSON, contentMap)`
- `MultipartFormDataRequestBodySerializer` — uses `MultipartBodyBuilder`, downloads file bytes from DIAL storage via `DialFileClient.download()` (after resolving the alias via `DialFileRefResolver`) for file parts, wraps them in `ByteArrayResource` (required by `MultipartBodyBuilder` for `contentLength()`), and returns `SerializedBody(MULTIPART_FORM_DATA, multipartBody)`
- `UrlEncodedFormRequestBodySerializer` — converts `List<KeyValueTemplateDto>` to `MultiValueMap<String, String>`, returns `SerializedBody(APPLICATION_FORM_URLENCODED, formMap)`

#### Scenario: JSON serialization (current behavior)
- **WHEN** the resolved body is `ResolvedJsonBodyDto`
- **THEN** `JsonRequestBodySerializer` SHALL return a `SerializedBody` with Content-Type `application/json` and the content Map as body

#### Scenario: Multipart serialization with file parts
- **WHEN** the resolved body is `ResolvedMultipartBodyDto` containing a file part with DIAL file reference
- **THEN** `MultipartFormDataRequestBodySerializer` SHALL resolve the file reference via `DialFileRefResolver`, download file bytes from DIAL via `DialFileClient.download()` (the `byte[]` convenience method, acceptable given the 50MB max file size limit), wrap in `ByteArrayResource`, build a multipart body via `MultipartBodyBuilder`, and return a `SerializedBody` with Content-Type `multipart/form-data`

#### Scenario: URL-encoded serialization
- **WHEN** the resolved body is `ResolvedUrlEncodedBodyDto`
- **THEN** `UrlEncodedFormRequestBodySerializer` SHALL convert the `List<KeyValueTemplateDto>` entries to a `MultiValueMap<String, String>` (preserving duplicate keys) and return a `SerializedBody` with Content-Type `application/x-www-form-urlencoded`

#### Scenario: Serializer selection
- **WHEN** the system needs to serialize a resolved body
- **THEN** `RequestBodySerializerRegistry` SHALL iterate registered serializers and select the first one where `supports(body)` returns true

#### Scenario: No matching serializer
- **WHEN** no registered serializer supports the resolved body type
- **THEN** the system SHALL throw an `IllegalStateException`

#### Scenario: Service-layer integration flow
- **WHEN** the service layer needs to invoke a deployment with a resolved body
- **THEN** it SHALL:
  1. Call `registry.serialize(resolvedBody)` to get `SerializedBody`
  2. Set `headers.setContentType(serializedBody.contentType())`
  3. Pass `serializedBody.body()` to `DialCoreDeploymentInvoker.invoke()` as the body parameter
