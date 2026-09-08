## ADDED Requirements

### Requirement: Dedicated run job executor
The service SHALL execute each test suite run job on a dedicated executor (bean `testSuiteRunExecutor`), separate from the default Spring async executor. The executor SHALL start one new thread per run for the run's whole lifetime — no thread pool, no queue, no executor-level concurrency limit — so that the number of concurrently executing runs is governed solely by the concurrent run limits (`test-suite-run.limits.*`). The threads SHALL be virtual when `spring.threads.virtual.enabled` is `true` (default) and platform threads when it is `false`, so that a single switch moves the whole JVM to platform threads for sampling/profiling.
Status: **Planned**

#### Scenario: Executor isolation
- **WHEN** test suite run jobs are dispatched
- **THEN** they SHALL execute on the dedicated executor, not the default Spring async executor

#### Scenario: One thread per run
- **WHEN** N runs are admitted by the concurrent run limits and dispatched
- **THEN** all N jobs SHALL start executing immediately on N distinct threads; none SHALL wait in a queue behind a pool size

#### Scenario: Virtual threads by default
- **WHEN** `spring.threads.virtual.enabled` is `true` (the default)
- **THEN** run job threads and each run's worker threads SHALL be virtual threads

#### Scenario: Platform threads on opt-out
- **WHEN** `spring.threads.virtual.enabled` is `false`
- **THEN** run job threads and each run's worker threads SHALL be platform daemon threads, visible to sampling profilers and thread dumps; cancellation and interrupt behaviour SHALL be identical to the virtual-thread case

#### Scenario: Thread naming
- **WHEN** the executor creates threads
- **THEN** run job thread names SHALL use the prefix `test-suite-run-` and run worker thread names the prefix `run-worker-` for identification in logs, thread dumps and profilers

#### Scenario: Rejected execution after shutdown
- **WHEN** the executor rejects a run job at dispatch time (e.g. it has been closed because the application context is shutting down)
- **THEN** the service SHALL treat the `RejectedExecutionException` by marking the run as FAILED with error category `RESOURCE_LIMIT` and code `EXECUTOR_REJECTED` and notify SSE clients

#### Scenario: Context close interrupts in-flight jobs
- **WHEN** the application context is closed while run jobs are executing
- **THEN** the executor SHALL interrupt the in-flight job threads immediately rather than waiting for the runs to finish; any run left in a non-terminal status SHALL be reconciled at the next startup

## MODIFIED Requirements

### Requirement: Trigger a test suite run
The service SHALL provide `POST /api/v1/test-suites/{testSuiteId}/runs` to create and trigger a new test suite run. The endpoint SHALL validate the request, verify the suite is bound to a dataset, persist a run record with status PENDING (including `testRunName` and `numberOfTestCases` snapshot), dispatch an async job, and return the run details immediately (without waiting for job completion). The unbound-suite guard (`datasetId IS NULL`) SHALL run before the `valid = false` check, so unbound suites SHALL surface as HTTP 409 with error code `SUITE_HAS_NO_DATASET` regardless of their validation state. An additional **run-time presence check** SHALL be performed after the `valid = false` guard: the service SHALL count the runnable test cases for the bound dataset — those that are valid and (when the suite has a `testCaseFilter`) match that filter — and if the count is zero, SHALL respond with HTTP 409 `INVALID_OPERATION` with message "Suite has no valid and enabled test cases". No run record SHALL be persisted and no async job SHALL be dispatched when this check fails. The count SHALL NOT consider any other exclusion source; `test_suites.disabled_test_case_ids` is not read.

Guard order:
1. Suite not found → 404 `NOT_FOUND`
2. Unbound (`datasetId == null`) → 409 `SUITE_HAS_NO_DATASET`
3. Config-invalid (`isValid == false`) → 409 `INVALID_OPERATION`
4. Zero runnable test cases → 409 `INVALID_OPERATION`
5. Concurrent run limits → 429 `TOO_MANY_REQUESTS`

Status: **Implemented**

#### Scenario: Successful run trigger
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/runs` with a valid `RunConfigDto` body and the test suite exists and is bound to a dataset
- **THEN** system SHALL create a `test_suite_runs` record with status `PENDING`, populate `testRunName` (from config or auto-generated), snapshot `numberOfTestCases` from the bound dataset's runnable test case count (valid, and matching `testCaseFilter` when set), dispatch an async evaluation job on the dedicated executor, and return HTTP 202 Accepted with the `TestSuiteRunResponseDto`

#### Scenario: Test suite not found
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/runs` with a non-existent `testSuiteId`
- **THEN** system SHALL respond with HTTP 404 and error code `NOT_FOUND`

#### Scenario: Unbound suite (datasetId is null) rejected
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/runs` for an existing suite whose `datasetId IS NULL`
- **THEN** system SHALL respond with HTTP 409 and error code `SUITE_HAS_NO_DATASET`; no run record SHALL be persisted and no async job SHALL be dispatched; this check SHALL run before the `valid = false` guard so the dataset-binding failure mode is reported even when the suite would also fail validation

#### Scenario: Invalid run configuration
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/runs` with an invalid body (e.g., `numberOfRuns` is null, zero, negative, or exceeds maximum)
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`

#### Scenario: Global concurrent run limit exceeded
- **WHEN** client triggers a run but the total count of active (PENDING + RUNNING + CANCELLING) runs across all suites has reached the configured global limit
- **THEN** system SHALL respond with HTTP 429 Too Many Requests with error code `TOO_MANY_REQUESTS` and a message indicating the global limit was reached, including current and maximum counts in `details`

#### Scenario: Per-suite concurrent run limit exceeded
- **WHEN** client triggers a run but the count of active (PENDING + RUNNING + CANCELLING) runs for the target test suite has reached the configured per-suite limit
- **THEN** system SHALL respond with HTTP 429 Too Many Requests with error code `TOO_MANY_REQUESTS` and a message indicating the per-suite limit was reached, including current and maximum counts in `details`

#### Scenario: Test suite not in valid state
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/runs` for a bound test suite that has `valid = false` (failed validation)
- **THEN** system SHALL respond with HTTP 409 Conflict and error code `INVALID_OPERATION` with a message indicating the test suite is not in a valid state (this check applies only to suites that pass the `SUITE_HAS_NO_DATASET` guard)

#### Scenario: Bound suite with no runnable test cases rejected
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/runs` for a config-valid, bound suite whose dataset has zero runnable test cases — because none are valid, or none match the suite's `testCaseFilter`
- **THEN** system SHALL respond with HTTP 409 Conflict and error code `INVALID_OPERATION` with message "Suite has no valid and enabled test cases"; no run record SHALL be persisted and no async job SHALL be dispatched

#### Scenario: Runnable count ignores legacy stored exclusions
- **WHEN** a config-valid, bound suite carries a non-empty `test_suites.disabled_test_case_ids` value stored by an earlier version of the product
- **THEN** the runnable count and the persisted `numberOfTestCases` SHALL equal the number of valid, `testCaseFilter`-matching test cases in the dataset, as if the stored value were empty
- **AND** a `testCaseFilter` matching only test cases named in that stored value SHALL still produce a successful run (no 409)

#### Scenario: Runnable count honors testCaseFilter
- **WHEN** a config-valid, bound suite has valid test cases but its `testCaseFilter` matches a non-empty subset of them
- **THEN** the zero-runnable guard SHALL pass and the persisted `numberOfTestCases` SHALL equal the count of the filter-matching subset

#### Scenario: Executor rejects job submission
- **WHEN** the run is created successfully but the dedicated executor rejects the submission at async dispatch time (e.g. once the executor has been closed because the application context is shutting down)
- **THEN** the run SHALL have been persisted with status PENDING and HTTP 202 returned to the client. The service SHALL catch `RejectedExecutionException` in the post-commit callback, mark the run as FAILED with error category `RESOURCE_LIMIT` and code `EXECUTOR_REJECTED`, and log a warning


### Requirement: Configuration properties
The service SHALL expose configurable properties for SSE, execution settings, retry defaults, and concurrent run limits under the `test-suite-run` prefix, and SHALL honour Spring Boot's `spring.threads.virtual.enabled` as the thread mode of the run job executor and of every run's worker executor.
Status: **Implemented**

#### Scenario: Executor properties
- **WHEN** a deployment YAML still contains `test-suite-run.executor.core-pool-size`, `test-suite-run.executor.max-pool-size` or `test-suite-run.executor.queue-capacity`
- **THEN** the application SHALL start normally and the properties SHALL have no effect (the run job executor is thread-per-run with no pool or queue)

#### Scenario: Thread mode property
- **WHEN** the application starts
- **THEN** it SHALL read `spring.threads.virtual.enabled` (environment variable `VIRTUAL_THREADS_ENABLED`, default `true`) and use it to decide whether run job threads and run worker threads are virtual (`true`) or platform (`false`) threads

#### Scenario: SSE properties
- **WHEN** the application starts
- **THEN** it SHALL read `test-suite-run.sse.timeout-minutes` (default 30) and `test-suite-run.sse.cleanup-interval-ms` (default 300000)

#### Scenario: Execution defaults and limits
- **WHEN** the application starts
- **THEN** it SHALL read `test-suite-run.execution.default-concurrency-level` (default 1), `test-suite-run.execution.max-concurrency-level` (default 50), `test-suite-run.execution.default-request-timeout-ms` (default 30000), `test-suite-run.execution.max-request-timeout-ms` (default 600000), `test-suite-run.execution.result-batch-size` (default 100), and `test-suite-run.execution.max-response-size-bytes` (default 5242880)

#### Scenario: Cancellation grace period property is removed
- **WHEN** a deployment YAML still contains `test-suite-run.execution.cancellation-grace-period-ms`
- **THEN** the application SHALL start normally and the property SHALL have no effect (cancellation interrupts in-flight work immediately; there is no drain period to configure)

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

## REMOVED Requirements

### Requirement: Dedicated async executor
**Reason**: The pooled `ThreadPoolTaskExecutor` (core 5 / max 10 / queue 50) parked one platform thread per run and capped effective run concurrency at the core size (5) regardless of `test-suite-run.limits.max-concurrent-runs-global`.
**Migration**: Replaced by "Dedicated run job executor" (thread-per-run, thread mode from `spring.threads.virtual.enabled`). Remove `TEST_SUITE_RUN_EXECUTOR_CORE_POOL_SIZE`, `TEST_SUITE_RUN_EXECUTOR_MAX_POOL_SIZE`, `TEST_SUITE_RUN_EXECUTOR_QUEUE_CAPACITY` from deployment manifests; they are ignored if present. Tune concurrency with `test-suite-run.limits.*` only.
