## MODIFIED Requirements

### Requirement: Single test case evaluation (worker)
The `EvaluationWorker` SHALL resolve the request body, call the target deployment endpoint, capture the response (including streaming), extract response columns, and build a `TestCaseRunResult`. The worker SHALL track retry attempts and store the actual request body in results.

#### Scenario: Full request resolution
- **WHEN** a test case is dispatched for execution
- **THEN** the worker SHALL resolve the full request (URL, headers, query params, body) using `ResolvedRequestService.resolveRequest()` (template + bindings + test case data, with per-case overrides). The resolved body is a polymorphic `ResolvedBodyDto`. Suites without a request template are prevented at validation time (`isValid = false`), so the worker can always rely on a valid resolved request.

#### Scenario: Endpoint invocation uses pluggable serializer
- **WHEN** the worker sends the resolved request to the DIAL Core deployment
- **THEN** it SHALL use `RequestBodySerializerRegistry` to select the appropriate serializer based on the resolved body type, serialize the body, set the correct Content-Type header, and invoke the deployment

#### Scenario: Endpoint invocation (non-streaming)
- **WHEN** the resolved request is sent and the response `Content-Type` is NOT `text/event-stream`
- **THEN** the worker SHALL capture the full response body, HTTP status code, and timing (exec start, exec complete, duration)

#### Scenario: Endpoint invocation (streaming SSE)
- **WHEN** the resolved request is sent and the response `Content-Type` is `text/event-stream`
- **THEN** the worker SHALL accumulate SSE chunks via `StreamingResponseAccumulator`, assemble them into a complete response body (OpenAI chat-completions format), and record the assembled response

#### Scenario: Request timeout
- **WHEN** the endpoint does not respond within `requestTimeoutMs`
- **THEN** the worker SHALL abort the call, set `executionStatus = TIMEOUT`, record `responseBody = null`, `responseStatusCode = null`, and the elapsed time as `execDurationMs`

#### Scenario: Network error
- **WHEN** the endpoint call fails with a network-level error (connection refused, DNS failure, etc.)
- **THEN** the worker SHALL set `executionStatus = ERROR`, store the error message in `responseBody` as a JSON error envelope, and set `responseStatusCode = null`

#### Scenario: HTTP error from target (4xx/5xx)
- **WHEN** the endpoint returns an HTTP 4xx or 5xx status
- **THEN** the worker SHALL set `executionStatus = FAILED`, store the response body and status code as-is, and proceed (not retry unless retry is configured)

#### Scenario: Response column extraction
- **WHEN** a response body is captured (streaming or non-streaming)
- **THEN** the worker SHALL apply the suite's `responseColumns` definitions via `ResponseColumnExtractor`, storing extracted values in `extractedColumns` and any extraction failures in `extractionWarnings`

#### Scenario: Trace ID generation
- **WHEN** a test case call is made
- **THEN** the worker SHALL generate a unique `traceId` (UUID) per call and propagate it as `X-Correlation-Id` header to the deployment

#### Scenario: Request body stored in results
- **WHEN** the worker builds a `TestCaseRunResult`
- **THEN** the worker SHALL serialize and store the resolved request body in `requestBody` (JSONB). For JSON bodies, serialization is the content Map as JSON. For multipart bodies, serialization SHALL store a JSON representation of the resolved parts (part names, types, text values, file blob UUIDs — NOT the raw binary multipart encoding). For URL-encoded bodies, serialization SHALL store the key-value map as JSON. `requestBody` SHALL be null only when request resolution itself fails (ERROR status before HTTP call).

#### Scenario: Retry count tracked in results
- **WHEN** the worker completes execution of a test case (with or without retries)
- **THEN** the result SHALL include `retryCount` set to the number of retry attempts made (0 if no retries occurred, N if N retries were attempted before the final outcome)

#### Scenario: Log details populated on retries
- **WHEN** the worker completes execution with `retryCount > 0`
- **THEN** the result SHALL include `logDetails` containing a structured log of retry attempts: `{"retryAttempts": [{"attemptIndex": 1, "statusCode": <int|null>, "errorType": "<HTTP_ERROR|TIMEOUT|NETWORK_ERROR>", "durationMs": <long>}, ...]}`. Each entry represents one failed attempt before the final result.

#### Scenario: Log details null when no retries
- **WHEN** the worker completes execution with `retryCount = 0`
- **THEN** `logDetails` SHALL be null (not an empty object or empty array)
