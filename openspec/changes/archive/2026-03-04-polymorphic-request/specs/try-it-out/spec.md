## MODIFIED Requirements

### Requirement: TryItOutResponseDto structure
The response SHALL be an envelope containing the resolved request, the DIAL Core response, and timing information.

#### Scenario: Response structure
- **WHEN** system returns a try-it-out response
- **THEN** `TryItOutResponseDto` SHALL include:
  - `resolvedRequest` (`ResolvedRequestDto`) — the resolved URL, query params, headers, and body (`ResolvedBodyDto`, polymorphic — JSON, multipart, or URL-encoded variant) that were sent to DIAL Core
  - `response` (`TryItOutCoreResponseDto`) — the deployment's response containing `statusCode` (int) and `body` (Object, nullable — parsed JSON or raw string)
  - `durationMs` (Long) — wall-clock time for the DIAL Core invocation in milliseconds

#### Scenario: DIAL Core returns error status
- **WHEN** DIAL Core returns 4xx or 5xx status code
- **THEN** the try-it-out endpoint SHALL still return HTTP 200
- **AND** the `response.statusCode` SHALL contain the actual upstream status code
- **AND** the `response.body` SHALL contain the upstream response body

#### Scenario: Resolved request with multipart body
- **WHEN** the test suite uses a `multipart/form-data` request template
- **THEN** `resolvedRequest.body` SHALL be a `ResolvedMultipartBodyDto` showing the resolved form parts (text values and file blob UUIDs)

#### Scenario: Resolved request with URL-encoded body
- **WHEN** the test suite uses a `application/x-www-form-urlencoded` request template
- **THEN** `resolvedRequest.body` SHALL be a `ResolvedUrlEncodedBodyDto` showing the resolved `List<KeyValueTemplateDto>` entries

### Requirement: Try-it-out invocation uses pluggable serializer
The try-it-out service SHALL use `RequestBodySerializerRegistry` to serialize the resolved body before invoking the DIAL Core deployment, instead of relying on the invoker's hardcoded JSON serialization.

#### Scenario: JSON body invocation (current behavior preserved)
- **WHEN** the resolved body is `ResolvedJsonBodyDto`
- **THEN** the system SHALL serialize as JSON and invoke DIAL Core with `Content-Type: application/json`

#### Scenario: Multipart body invocation
- **WHEN** the resolved body is `ResolvedMultipartBodyDto`
- **THEN** the system SHALL build a multipart request (reading file bytes from BlobStorage for file parts), and invoke DIAL Core with `Content-Type: multipart/form-data`

#### Scenario: URL-encoded body invocation
- **WHEN** the resolved body is `ResolvedUrlEncodedBodyDto`
- **THEN** the system SHALL build a URL-encoded form body and invoke DIAL Core with `Content-Type: application/x-www-form-urlencoded`
