## ADDED Requirements

### Requirement: Get test suite run configuration
The service SHALL provide `GET /api/v1/test-suite-runs/config` returning the run-related limits the backend currently enforces, so clients can bound run-form inputs without a trial submission. The response SHALL be a JSON object with a `maxConcurrencyLevel` integer field equal to the configured `test-suite-run.execution.max-concurrency-level`. The endpoint SHALL be protected by the same authentication as other test suite run endpoints.
Status: **Implemented**

#### Scenario: Returns configured max concurrency level
- **WHEN** `test-suite-run.execution.max-concurrency-level` is configured to `N` and a client calls `GET /api/v1/test-suite-runs/config`
- **THEN** system SHALL respond with HTTP 200 and body `{"maxConcurrencyLevel": N}`

#### Scenario: Value matches the enforced ceiling
- **WHEN** a client submits a run with `execution.concurrencyLevel` greater than the `maxConcurrencyLevel` returned by `GET /api/v1/test-suite-runs/config`
- **THEN** system SHALL reject the run with HTTP 400

#### Scenario: Literal path is not treated as a run id
- **WHEN** a client calls `GET /api/v1/test-suite-runs/config`
- **THEN** system SHALL NOT resolve `config` as a run id (no 400 for an invalid UUID, no 404 `NOT_FOUND`)

#### Implementation notes
- `TestSuiteRunController`, `TestSuiteRunConfigService`, `TestSuiteRunConfigResponseDto`

## MODIFIED Requirements

### Requirement: Configuration properties
The service SHALL expose configurable properties for SSE, execution settings, retry defaults, and concurrent run limits under the `test-suite-run` prefix, and SHALL honour Spring Boot's `spring.threads.virtual.enabled` as the thread mode of the run job executor and of every run's worker executor.
Status: **Implemented**

#### Scenario: Thread mode property
- **WHEN** the application starts
- **THEN** it SHALL read `spring.threads.virtual.enabled` (environment variable `VIRTUAL_THREADS_ENABLED`, default `true`) and use it to decide whether run job threads and run worker threads are virtual (`true`) or platform (`false`) threads

#### Scenario: SSE properties
- **WHEN** the application starts
- **THEN** it SHALL read `test-suite-run.sse.timeout-minutes` (default 30) and `test-suite-run.sse.cleanup-interval-ms` (default 300000)

#### Scenario: Execution defaults and limits
- **WHEN** the application starts
- **THEN** it SHALL read `test-suite-run.execution.default-concurrency-level` (default 1), `test-suite-run.execution.max-concurrency-level` (default 5; environment variable `TEST_SUITE_RUN_EXECUTION_MAX_CONCURRENCY_LEVEL`), `test-suite-run.execution.default-request-timeout-ms` (default 600000), `test-suite-run.execution.max-request-timeout-ms` (default 1200000), `test-suite-run.execution.result-batch-size` (default 100), and `test-suite-run.execution.max-response-size-bytes` (default 52428800)

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


### Requirement: Run configuration model
The run request body SHALL contain a `runConfig` object. `runConfig` SHALL support: `numberOfRuns` (integer, required, `@Min(1)`, validated against a configurable maximum in the service layer, default 64), `testRunName` (String, optional — a user-provided name for the run), `execution` (ExecutionSettingsDto, optional — concurrency, timeout, and rate limiting settings), and `retry` (RetryPolicyDto, optional — retry behavior for failed calls). The `runConfig` SHALL be stored as JSONB to allow future extension without schema migration.
Status: **Implemented**

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
- **THEN** system SHALL accept and persist the configuration; execution SHALL resolve configured defaults (sequential, 600000 ms timeout, no retry, no rate limit with the shipped configuration)

#### Scenario: Full runConfig with execution settings
- **WHEN** client sends:
  ```json
  {
    "runConfig": {
      "numberOfRuns": 5,
      "execution": {
        "concurrencyLevel": 5,
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
- **THEN** it SHALL be >= 1000 and <= system max (configurable, default 1200000). Values outside range SHALL result in HTTP 400

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

