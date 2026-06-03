## MODIFIED Requirements

### Requirement: Run configuration model
The run request body SHALL contain a `runConfig` object. `runConfig` SHALL support: `numberOfRuns` (integer, required, `@Min(1)`, validated against a configurable maximum in the service layer, default 64), `testRunName` (String, optional — a user-provided name for the run), `execution` (ExecutionSettingsDto, optional — concurrency, timeout, and rate limiting settings), and `retry` (RetryPolicyDto, optional — retry behavior for failed calls). The `runConfig` SHALL be stored as JSONB to allow future extension without schema migration.

#### Scenario: Valid numberOfRuns
- **WHEN** client sends `runConfig: { "numberOfRuns": 5 }`
- **THEN** system SHALL accept the configuration and persist it as JSONB

#### Scenario: numberOfRuns below minimum
- **WHEN** client sends `runConfig: { "numberOfRuns": 0 }` or negative value
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`

#### Scenario: numberOfRuns above maximum
- **WHEN** client sends `runConfig: { "numberOfRuns": 65 }` (exceeds configured max, default 64)
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`

#### Scenario: numberOfRuns is null
- **WHEN** client sends `runConfig: { "numberOfRuns": null }` or omits `numberOfRuns`
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`

#### Scenario: testRunName provided in config
- **WHEN** client sends `runConfig: { "numberOfRuns": 5, "testRunName": "Regression Run #3" }`
- **THEN** system SHALL use the provided `testRunName` as the run's `testRunName`

#### Scenario: testRunName omitted in config
- **WHEN** client sends `runConfig` without `testRunName` or with `testRunName: null`
- **THEN** system SHALL auto-generate a unique human-readable name for the run (see testRunName auto-generation requirement)

#### Scenario: Minimal runConfig (90% of users)
- **WHEN** client sends `runConfig: { "numberOfRuns": 3 }` without `execution` or `retry`
- **THEN** system SHALL accept the configuration, apply system default execution settings (sequential, 30s timeout, no retry, no rate limit), and persist as JSONB

#### Scenario: Full runConfig with execution settings
- **WHEN** client sends:
  ```json
  {
    "runConfig": {
      "numberOfRuns": 5,
      "execution": {
        "concurrencyLevel": 10,
        "requestTimeoutMs": 120000,
        "rateLimitRps": 5.0
      },
      "retry": {
        "maxRetries": 2,
        "retryDelayMs": 2000,
        "retryBackoffMultiplier": 2.0
      }
    }
  }
  ```
- **THEN** system SHALL validate all fields against system maximums and persist the full configuration as JSONB

#### Scenario: Execution settings validation — concurrencyLevel
- **WHEN** `execution.concurrencyLevel` is provided
- **THEN** it SHALL be >= 1 and <= system max (configurable, default 50). Values outside range SHALL result in HTTP 400

#### Scenario: Execution settings validation — requestTimeoutMs
- **WHEN** `execution.requestTimeoutMs` is provided
- **THEN** it SHALL be >= 1000 and <= system max (configurable, default 600000). Values outside range SHALL result in HTTP 400

#### Scenario: Execution settings validation — rateLimitRps
- **WHEN** `execution.rateLimitRps` is provided
- **THEN** it SHALL be >= 0.1. Values below SHALL result in HTTP 400. Null means no rate limit.

#### Scenario: Retry settings validation — maxRetries
- **WHEN** `retry.maxRetries` is provided
- **THEN** it SHALL be >= 0 and <= system max (configurable, default 10). Values outside range SHALL result in HTTP 400

#### Scenario: Retry settings validation — retryDelayMs
- **WHEN** `retry.retryDelayMs` is provided
- **THEN** it SHALL be >= 100 and <= system max (configurable, default 60000). Values outside range SHALL result in HTTP 400

#### Scenario: Retry settings validation — retryBackoffMultiplier
- **WHEN** `retry.retryBackoffMultiplier` is provided
- **THEN** it SHALL be >= 1.0 and <= 10.0. Values outside range SHALL result in HTTP 400

#### Scenario: Partial execution settings
- **WHEN** client provides `execution: { "concurrencyLevel": 5 }` without `requestTimeoutMs` or `rateLimitRps`
- **THEN** system SHALL accept the partial settings; omitted fields SHALL use system defaults

### Requirement: Mock evaluation job
**This requirement is replaced by the real evaluation execution engine.** The mock evaluation job (random sleep, random failure, fake result generation) is removed. See `eval-execution-engine` spec for the replacement.

#### Scenario: Mock job replaced by real executor
- **WHEN** the real evaluation engine is deployed
- **THEN** `TestSuiteEvaluationJob` SHALL delegate to `EvaluationExecutor.execute()` instead of performing mock sleep and generating fake results

### Requirement: Configuration properties
The service SHALL expose configurable properties for executor, SSE, execution settings, retry defaults, and concurrent run limits under the `test-suite-run` prefix. Mock job properties are removed.

#### Scenario: Executor properties
- **WHEN** the application starts
- **THEN** it SHALL read `test-suite-run.executor.core-pool-size` (default 5), `test-suite-run.executor.max-pool-size` (default 10), and `test-suite-run.executor.queue-capacity` (default 50)

#### Scenario: SSE properties
- **WHEN** the application starts
- **THEN** it SHALL read `test-suite-run.sse.timeout-minutes` (default 30) and `test-suite-run.sse.cleanup-interval-ms` (default 300000)

#### Scenario: Execution defaults and limits
- **WHEN** the application starts
- **THEN** it SHALL read `test-suite-run.execution.default-concurrency-level` (default 1), `test-suite-run.execution.max-concurrency-level` (default 50), `test-suite-run.execution.default-request-timeout-ms` (default 30000), `test-suite-run.execution.max-request-timeout-ms` (default 600000), `test-suite-run.execution.result-batch-size` (default 100), `test-suite-run.execution.max-response-size-bytes` (default 5242880), and `test-suite-run.execution.cancellation-grace-period-ms` (default 30000)

#### Scenario: Retry defaults and limits
- **WHEN** the application starts
- **THEN** it SHALL read `test-suite-run.retry.default-max-retries` (default 0), `test-suite-run.retry.max-max-retries` (default 10), `test-suite-run.retry.default-retry-delay-ms` (default 1000), `test-suite-run.retry.max-retry-delay-ms` (default 60000 — serves dual role: validation ceiling for user-provided `retryDelayMs` AND cap on computed exponential backoff delay), `test-suite-run.retry.default-retry-backoff-multiplier` (default 2.0), and `test-suite-run.retry.max-retry-backoff-multiplier` (default 10.0)

#### Scenario: Concurrent run limit properties
- **WHEN** the application starts
- **THEN** it SHALL read `test-suite-run.limits.max-concurrent-runs-global` (default 20) and `test-suite-run.limits.max-concurrent-runs-per-suite` (default 5)

#### Scenario: Run config validation properties
- **WHEN** the application starts
- **THEN** it SHALL read `test-suite-run.run-config.max-number-of-runs` (default 64) for the service-level validation ceiling on `numberOfRuns`

#### Scenario: Execution header blacklist property
- **WHEN** the application starts
- **THEN** it SHALL read `test-suite-run.execution.header-blacklist` — a list of header names that are system-managed and cannot be set by users via `requestTemplate.headers`. Default: `[Authorization, Host, Content-Length, Transfer-Encoding, Connection, X-Correlation-Id]`.

#### Scenario: Mock job properties removed
- **WHEN** the real executor is deployed
- **THEN** `test-suite-run.mock-job.*` properties SHALL no longer be read or used

## ADDED Requirements

### Requirement: Header blacklist validation at suite save time
The system SHALL validate `requestTemplate.headers` against the configured header blacklist when a test suite is created or updated. Suites with blacklisted headers SHALL be marked as invalid with a validation warning.

#### Scenario: Blacklisted header detected during save
- **WHEN** a test suite is created or updated with `requestTemplate.headers` that include a header on the system blacklist (e.g., `Authorization`)
- **THEN** the suite SHALL be marked `isValid = false` with a `validationWarning` describing which header(s) are blacklisted (e.g., "Header 'Authorization' is system-managed and cannot be set in request template"). Blacklist comparison SHALL be **case-insensitive** (HTTP headers are case-insensitive per RFC 7230).

#### Scenario: No blacklisted headers
- **WHEN** a test suite is created or updated with `requestTemplate.headers` that contain no blacklisted headers
- **THEN** this validation check SHALL pass (other validation rules still apply)

#### Scenario: Multiple blacklisted headers
- **WHEN** a test suite's `requestTemplate.headers` includes multiple blacklisted headers (e.g., `Authorization` and `Host`)
- **THEN** ALL blacklisted headers SHALL be reported in the `validationWarning` (not just the first one)

#### Scenario: Validation integrated with existing pipeline
- **WHEN** header blacklist validation is performed
- **THEN** it SHALL be integrated into the existing `SuiteValidationService` pipeline alongside other validation checks (e.g., JSONata expression validation, deployment validation)

### Requirement: TestSuiteRunResponseDto structure
The response DTO for a test suite run SHALL include all relevant run information, including the extended `runConfig` with execution and retry settings.

#### Scenario: Response fields
- **WHEN** system returns a `TestSuiteRunResponseDto`
- **THEN** it SHALL include: `id` (UUID), `testSuiteId` (UUID), `testRunName` (String — user-provided or auto-generated), `status` (String — one of PENDING, RUNNING, COMPLETED, FAILED, CANCELLED), `runConfig` (object with `numberOfRuns`, optional `testRunName`, optional `execution`, optional `retry`), `numberOfTestCases` (int — snapshot of enabled and valid test cases at run creation), `startedAt` (Long, nullable, epoch ms — set when status becomes RUNNING), `completedAt` (Long, nullable, epoch ms — set when status becomes COMPLETED, FAILED, or CANCELLED), `errorMessage` (String, nullable — user-friendly error message for FAILED runs), `errorDetails` (object, nullable — structured error info for FAILED runs), `createdAt` (Long, epoch ms), `updatedAt` (Long, epoch ms)

#### Scenario: Error details structure for FAILED runs
- **WHEN** a run has status FAILED and `errorDetails` is non-null
- **THEN** `errorDetails` SHALL contain: `code` (String — machine-readable error code), `category` (String — one of VALIDATION, TIMEOUT, RESOURCE_LIMIT, TEST_SUITE_ERROR, INTERNAL), `message` (String — user-friendly description), `details` (object, nullable — additional context)

#### Scenario: Null fields for non-terminal runs
- **WHEN** a run has status PENDING or RUNNING
- **THEN** `completedAt`, `errorMessage`, and `errorDetails` SHALL be null
