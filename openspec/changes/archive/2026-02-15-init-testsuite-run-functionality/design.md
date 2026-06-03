## Context

Test suites exist as static configurations without execution capability. This design introduces the foundational infrastructure for asynchronous test suite execution, enabling users to trigger evaluations, track progress in real-time, and manage run history.

**Current State:**
- TestSuite domain is fully implemented (CRUD, filtering, sorting, pagination)
- No execution or job orchestration capability exists
- No async processing infrastructure beyond `@Async` annotation usage (see RevalidationService)
- No SSE or real-time status tracking mechanism

**Constraints:**
- Must follow Spring Boot 3.5.3 patterns
- PostgreSQL via JDBC only (no JPA/Hibernate)
- UUIDs stored as VARCHAR(36)
- Layered architecture: web → service → data.db
- Must support concurrent run executions
- Must handle job failures gracefully

## Goals / Non-Goals

**Goals:**
- Enable async test suite execution with immediate response (return runId)
- Track run lifecycle: PENDING → RUNNING → COMPLETED/FAILED/CANCELLED
- Provide real-time status updates to clients via SSE (support filtering: all runs, single run, by status, by test suite)
- Support run cancellation (interrupt in-progress execution)
- Support filtering, sorting, pagination of run history
- Allow deletion of runs with cascade cleanup
- Mock evaluation job with randomized duration (0-60s) and random failure (20% probability)
- Extensible run configuration (start with `numberOfRuns`)
- Dedicated job executor with configurable thread pool

**Non-Goals:**
- Actual evaluation/metrics computation (mocked with randomized sleep)
- Test case result storage (schema prepared, population deferred)
- Distributed execution across multiple instances
- Pause/resume functionality
- Historical metrics aggregation
- Job retry logic or failure recovery

## Decisions

### 1. Async Execution Pattern: Spring @Async with Dedicated Executor

**Decision:** Use Spring's `@Async` annotation with a dedicated `ThreadPoolTaskExecutor` for test suite run jobs.

**Rationale:**
- Already in use in the codebase (RevalidationService demonstrates the pattern)
- Dedicated thread pool isolates job execution from other async operations
- Configurable pool sizing for different deployment scales
- Prevents job execution from starving other async tasks
- Simple, lightweight, no external dependencies
- Thread pool configuration exposes key tuning parameters

**Alternatives Considered:**
- Spring Batch: Overkill for simple async execution; adds complexity
- External job queue (RabbitMQ, Kafka): Not needed for single-instance; over-engineering
- CompletableFuture: Less declarative, more manual threading management
- Shared default executor: Risk of resource contention with other async operations

**Implementation:**

**`@EnableAsync` on Application class** (project convention: all `@Enable*` annotations on the bootstrap main class):
```java
@SpringBootApplication
@EnableScheduling  // already present
@EnableAsync       // ADD — enables @Async processing
@EnableWebSecurity
// ... other annotations
public class Application { ... }
```

**Configuration Class** (bean definition only, no `@EnableAsync`):
```java
@Configuration
@LogExecution
public class AsyncConfiguration {

    @Bean(name = "testSuiteRunExecutor")
    public ThreadPoolTaskExecutor testSuiteRunExecutor(TestSuiteRunProperties props) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getExecutor().getCorePoolSize());
        executor.setMaxPoolSize(props.getExecutor().getMaxPoolSize());
        executor.setQueueCapacity(props.getExecutor().getQueueCapacity());
        executor.setThreadNamePrefix("test-suite-run-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
```

**Job Class** (`@Async` on separate bean to avoid self-invocation bypass):
```java
@Component
@LogExecution
public class TestSuiteEvaluationJob {

    @Async("testSuiteRunExecutor")
    public void executeRunAsync(UUID runId) {
        // Job execution logic — see Decision #4
    }
}
```

**Properties Class** (no field initializers — all defaults in application.yml):
```java
@Data
public class ExecutorProperties {
    @NotNull @Min(1) private Integer corePoolSize;
    @NotNull @Min(1) private Integer maxPoolSize;
    @NotNull @Min(0) private Integer queueCapacity;
}
```

### 2. Real-time Status Tracking: Server-Sent Events (SSE) with Filtering

**Decision:** Use Spring's `SseEmitter` for real-time status streaming with support for filtering multiple runs.

**Rationale:**
- Native HTTP-based (no WebSocket complexity)
- One-way server-to-client communication fits the use case (status updates only)
- Simple client integration (EventSource API in browsers)
- No additional dependencies required
- Filtering enables efficient monitoring of multiple runs without multiple connections
- Graceful fallback to polling if SSE connection fails

**Alternatives Considered:**
- WebSockets: Bidirectional communication not needed; adds complexity
- Short polling: Inefficient, increased server load, higher latency
- Long polling: More complex than SSE, no browser-native API
- One connection per run: Wasteful for dashboard views tracking multiple runs

**Implementation:**

**Endpoint:** `GET /api/v1/test-suite-runs/status-stream?runIds={ids}&testSuiteIds={ids}&statuses={statuses}`

**Query Parameters (all optional, combined with AND):**
- `runIds`: Comma-separated UUIDs (e.g., `runIds=uuid1,uuid2`) — stream updates for specific runs
- `testSuiteIds`: Comma-separated UUIDs — stream updates for runs of specific test suites
- `statuses`: Comma-separated statuses (PENDING,RUNNING,COMPLETED,FAILED,CANCELLED) — filter by status
- No parameters: Stream updates for ALL runs (useful for admin dashboards)

**Event Format:**
```json
{
  "runId": "uuid",
  "testSuiteId": "uuid",
  "status": "RUNNING",
  "message": "Executing evaluation...",
  "timestamp": 1739443800000
}
```
*Note: `progress` field (0-100) deferred to future when actual evaluation logic is implemented.*

**Connection Management:**
- Store emitters in `ConcurrentHashMap<String, SseEmitterWrapper>` keyed by connection ID
- `SseEmitterWrapper` contains emitter + filter criteria
- On status update, iterate emitters and send to matching connections
- Timeout: 30 minutes (configurable)
- Cleanup: Remove emitters on completion, timeout, or error

**Filtering Logic:**
```java
public void notifyStatusUpdate(TestSuiteRun run) {
    activeEmitters.values().forEach(wrapper -> {
        if (wrapper.matches(run)) {  // Apply filter criteria
            try {
                wrapper.getEmitter().send(buildStatusEvent(run));
            } catch (IOException e) {
                removeEmitter(wrapper.getId());
            }
        }
    });
}
```

### 3. Database Schema: test_suite_runs Table

**Decision:** Single table `test_suite_runs` with JSONB for run configuration and error details.

**Schema:**
```sql
CREATE TABLE test_suite_runs (
    id VARCHAR(36) PRIMARY KEY,
    test_suite_id VARCHAR(36) NOT NULL,
    test_run_name VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,  -- PENDING, RUNNING, COMPLETED, FAILED, CANCELLED
    run_config JSONB NOT NULL,    -- { numberOfRuns: int, testRunName: string?, ... }
    number_of_test_cases INTEGER NOT NULL,  -- snapshot at run creation
    started_at_ms BIGINT,            -- epoch ms
    completed_at_ms BIGINT,          -- epoch ms
    error_message TEXT,           -- User-friendly error message (exposed to clients)
    error_details JSONB,          -- Structured error info (exposed to clients)
    created_at_ms BIGINT NOT NULL,   -- epoch ms
    updated_at_ms BIGINT NOT NULL,   -- epoch ms
    FOREIGN KEY (test_suite_id) REFERENCES test_suites(id) ON DELETE CASCADE
);

CREATE SEQUENCE test_suite_run_name_seq START WITH 1 INCREMENT BY 1;

CREATE INDEX idx_test_suite_runs_test_suite_id ON test_suite_runs(test_suite_id);
CREATE INDEX idx_test_suite_runs_status ON test_suite_runs(status);
CREATE INDEX idx_test_suite_runs_created_at_ms ON test_suite_runs(created_at_ms DESC);
ALTER TABLE test_suite_runs ADD CONSTRAINT uq_test_suite_runs_suite_name UNIQUE (test_suite_id, test_run_name);
```

**Rationale:**
- Column names use `_ms` suffix for epoch millisecond columns (project convention matching `test_suites`, `test_cases`, `revalidation_tasks`)
- JSONB for `run_config`: Extensible without schema changes (future: timeout, parallelism, filters)
- JSONB for `error_details`: User-friendly structured error info (NOT debugging details)
- Separate `started_at_ms` and `completed_at_ms`: Enables duration calculations
- Indexes on `test_suite_id`, `status`, `created_at_ms`: Optimize common queries (filter by suite, status, recent runs)
- CASCADE delete: Automatically clean up runs when test suite is deleted
- JSONB parameters in repository use `PostgresJsonbSqlParameter.fromJson()` (existing utility for type-safe JSONB binding)

**Error Details Structure** (similar to ErrorView, user-facing):
```json
{
  "code": "EXECUTION_TIMEOUT",
  "category": "TIMEOUT",
  "message": "Test suite run exceeded maximum execution time",
  "details": {
    "timeoutMinutes": 30,
    "elapsedMinutes": 31
  }
}
```

**Error Categories (enum RunErrorCategory):**
- `VALIDATION`: Invalid run configuration or test suite state
- `TIMEOUT`: Execution exceeded time limits
- `RESOURCE_LIMIT`: Resource exhaustion (memory, thread pool)
- `TEST_SUITE_ERROR`: Test suite configuration or dependency issues
- `INTERNAL`: Unexpected internal errors

**Note:** Full stack traces and debugging info are logged via SLF4J but NEVER exposed in `error_details`. Only user-actionable information is stored/returned.

**Timestamp Management — TransactionTimestampContext:**
Repository methods follow the project-wide convention for timestamps:
- **@Transactional callers** (service methods): Repository uses `TransactionTimestampContext.getTimestamp()` internally for `created_at_ms`, `updated_at_ms`, `completed_at_ms` (same pattern as `PostgresTestSuiteRepository`, `PostgresTestCaseRepository`).
- **Non-transactional callers** (async job): Repository methods accept explicit timestamp parameters. The caller passes `System.currentTimeMillis()`.
- **Repository interface / implementation split**: Follows existing pattern — `TestSuiteRunRepository` interface + `PostgresTestSuiteRunRepository` with `@Repository`, `@LogExecution`, `@RequiredArgsConstructor`, `@ConditionalOnProperty(name = "datasource.vendor", havingValue = "POSTGRES")`.

**Status Enum Values:**
- `PENDING`: Run created, awaiting async execution
- `RUNNING`: Async job in progress
- `COMPLETED`: Job finished successfully
- `FAILED`: Job encountered error (error_message and error_details populated)
- `CANCELLED`: Job was cancelled by user request (via cancellation API or thread interruption)

### 4. Job Execution: Mock Job with Randomization

**Decision:** Mock evaluation with randomized duration (0-60 seconds) and random failure (20% probability).

**Rationale:**
- Actual evaluation logic is deferred (future implementation)
- Demonstrates async pattern and status transitions including failures
- Random duration simulates realistic variability in execution time
- Random failures enable testing error handling, retry UX, and failure scenarios
- Enables frontend integration and testing without waiting for full evaluation implementation

**Implementation:**

**Dependency Injection:** The job injects `TestSuiteRunRepository` and `TestSuiteRunSseService` directly (NOT `TestSuiteRunService`) to avoid circular dependency (service → job for dispatch, job → service for updates). The job calls repository methods that accept explicit timestamps since it runs outside `@Transactional` context.

```java
@Async("testSuiteRunExecutor")
public void executeRunAsync(UUID runId) {
    activeRunThreads.put(runId, Thread.currentThread());
    try {
        long now = System.currentTimeMillis();
        if (Thread.currentThread().isInterrupted()) {
            repository.updateToCancelled(runId, now, now);
            sseService.notifyStatusUpdate(repository.findById(runId).orElseThrow());
            return;
        }

        repository.updateToRunning(runId, now, now);
        sseService.notifyStatusUpdate(repository.findById(runId).orElseThrow());

        // Random duration: min-max from config
        int sleepMs = ThreadLocalRandom.current().nextInt(
            props.getMockJob().getMinDurationMs(),
            props.getMockJob().getMaxDurationMs() + 1);

        // Check for cancellation during sleep (poll every 500ms)
        long remaining = sleepMs;
        while (remaining > 0 && !Thread.currentThread().isInterrupted()) {
            long sleepChunk = Math.min(remaining, 500);
            Thread.sleep(sleepChunk);
            remaining -= sleepChunk;
        }

        now = System.currentTimeMillis();
        if (Thread.currentThread().isInterrupted()) {
            repository.updateToCancelled(runId, now, now);
            sseService.notifyStatusUpdate(repository.findById(runId).orElseThrow());
            return;
        }

        // Random failure: configurable probability
        boolean shouldFail = ThreadLocalRandom.current().nextDouble()
            < props.getMockJob().getFailureProbability();

        if (shouldFail) {
            var errorDetails = buildErrorDetails(
                "MOCK_FAILURE", RunErrorCategory.INTERNAL,
                "Mock job randomly failed (simulated error)",
                Map.of("simulatedFailure", true));
            repository.updateToFailed(runId, "Simulated evaluation failure",
                errorDetails, now, now);
        } else {
            repository.updateToCompleted(runId, now, now);
        }
        sseService.notifyStatusUpdate(repository.findById(runId).orElseThrow());

    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        long now = System.currentTimeMillis();
        repository.updateToCancelled(runId, now, now);
        sseService.notifyStatusUpdate(repository.findById(runId).orElseThrow());
    } catch (Exception e) {
        // Intentional broad catch: safety net to prevent runs stuck in RUNNING.
        // Without this, any unexpected exception (NPE, DB error, serialization)
        // would leave the run in RUNNING state indefinitely.
        log.error("Run failed unexpectedly: {}", runId, e);
        long now = System.currentTimeMillis();
        var errorDetails = buildErrorDetails(
            "UNEXPECTED_ERROR", RunErrorCategory.INTERNAL,
            "An unexpected error occurred during execution", null);
        repository.updateToFailed(runId, e.getMessage(), errorDetails, now, now);
        sseService.notifyStatusUpdate(repository.findById(runId).orElseThrow());
    } finally {
        activeRunThreads.remove(runId);
    }
}
```

**Configuration:**
```yaml
test-suite-run:
  mock-job:
    min-duration-ms: 0
    max-duration-ms: 60000
    failure-probability: 0.20  # 20% chance of FAILED status
```

**Future Extension:**
- Replace mock logic with actual evaluation orchestration
- Invoke test case execution logic
- Compute metrics and store results
- Real progress reporting (e.g., "5/10 runs completed")

### 5. Run Configuration Model

**Decision:** Start with minimal config: `{ numberOfRuns: int }`. Store as JSONB.

**Rationale:**
- JSONB enables adding fields without migration (e.g., `timeout`, `parallelism`, `filters`)
- DTO validation ensures `numberOfRuns >= 1`
- Java model uses `Map<String, Object>` or custom `RunConfig` class

**DTO:**
```java
@Data
public class RunConfigDto {
    @NotNull
    @Min(1)
    private Integer numberOfRuns;

    @Size(max = MAX_TEST_RUN_NAME_LENGTH)
    private String testRunName;  // optional, auto-generated if null
}
```

**Configurable max validation** — enforced in the service layer (not via `@Max` annotation, since annotation values must be compile-time constants). The service reads the max from `TestSuiteRunProperties.getRunConfig().getMaxNumberOfRuns()` and throws `ValidationException` if exceeded. This allows the maximum to be changed via `test-suite-run.run-config.max-number-of-runs` without recompilation. See Decision #8 `createRun()` for the full validation flow.

**Future Extensions:**
- `timeout`: Max execution time (minutes)
- `parallelism`: Concurrent test case execution
- `testCaseFilters`: Subset of test cases to run
- `metricConfig`: Which metrics to compute

### 6. Filtering and Sorting

**Decision:** Reuse existing patterns from TestSuiteController (FilterParser, SortParser, PageRequestParser).

**Filterable Fields:**
- `testSuiteId`: UUID
- `status`: Enum (PENDING, RUNNING, COMPLETED, FAILED, CANCELLED)
- `testRunName`: String
- `createdAt`: Long (epoch ms) range

**Sortable Fields:**
- `createdAt` (default: DESC)
- `startedAt`
- `completedAt`
- `status`
- `testRunName`

**Rationale:**
- Consistency with existing APIs
- Leverages existing infrastructure (no new parsing logic)
- Common queries: "all runs for test suite X", "all failed runs", "recent runs"

### 7. SSE Emitter Cleanup: Scheduled Task

**Decision:** Implement scheduled cleanup task to remove stale SSE emitters.

**Rationale:**
- Timeout alone may not clean up emitters if connections are abandoned without proper close
- Prevents memory leaks from orphaned emitters
- Detects and removes emitters with closed connections

**Implementation:**
- `@Scheduled` task runs every 5 minutes (configurable via `application.yml`)
- Iterates through active emitters, attempts to send heartbeat ping
- Removes emitters that fail heartbeat (connection closed/broken)
- Logs cleanup statistics (removed count, active count)

```java
@Scheduled(fixedDelayString = "${test-suite-run.sse.cleanup-interval-ms:300000}")
public void cleanupStaleEmitters() {
    var staleKeys = new ArrayList<String>();
    activeEmitters.forEach((connectionId, wrapper) -> {
        try {
            wrapper.getEmitter().send(SseEmitter.event().name("heartbeat").data("ping"));
        } catch (IOException e) {
            staleKeys.add(connectionId);
        }
    });
    staleKeys.forEach(activeEmitters::remove);
    log.info("SSE cleanup: removed {} stale emitters, {} active", staleKeys.size(), activeEmitters.size());
}
```

### 8. Concurrent Run Limits

**Decision:** Implement configurable concurrent run limits (global and per-suite).

**Rationale:**
- Prevents resource exhaustion from too many simultaneous runs
- Protects against accidental or malicious overload
- Per-suite limit prevents single suite from monopolizing resources
- Configurable for different deployment sizes

**Configuration:**
```yaml
test-suite-run:
  limits:
    max-concurrent-runs-global: 20  # Total concurrent runs across all suites
    max-concurrent-runs-per-suite: 5  # Max concurrent runs per individual suite
```

**Implementation:**
- Check limits before creating run in `createRun()`
- Count PENDING + RUNNING status rows
- Return 429 Too Many Requests if limit exceeded
- Error response includes retry-after hint and current active count

```java
public TestSuiteRunResponseDto createRun(UUID testSuiteId, RunConfigDto config) {
    ensureTestSuiteExists(testSuiteId);  // testSuiteRepository.existsById() → 404

    // Service-level validation for configurable max
    int maxRuns = properties.getRunConfig().getMaxNumberOfRuns();
    if (config.getNumberOfRuns() > maxRuns) {
        throw new ValidationException("numberOfRuns must not exceed " + maxRuns);
    }

    int globalActive = repository.countByStatuses(List.of(PENDING.name(), RUNNING.name()));
    if (globalActive >= limits.getMaxConcurrentRunsGlobal()) {
        throw new TooManyRunsException("Global concurrent run limit reached",
            Map.of("activeRunsGlobal", globalActive,
                    "maxRunsGlobal", limits.getMaxConcurrentRunsGlobal()));
    }

    int suiteActive = repository.countByTestSuiteIdAndStatuses(testSuiteId,
        List.of(PENDING.name(), RUNNING.name()));
    if (suiteActive >= limits.getMaxConcurrentRunsPerSuite()) {
        throw new TooManyRunsException("Suite concurrent run limit reached",
            Map.of("activeRunsForSuite", suiteActive,
                    "maxRunsPerSuite", limits.getMaxConcurrentRunsPerSuite()));
    }

    // Snapshot test case count, resolve name, persist with PENDING, dispatch async job...
}
```

**Error Response (429):**
```json
{
  "code": "TOO_MANY_REQUESTS",
  "message": "Concurrent run limit exceeded. Please wait for existing runs to complete.",
  "details": {
    "activeRunsGlobal": 20,
    "maxRunsGlobal": 20,
    "activeRunsForSuite": 5,
    "maxRunsPerSuite": 5
  }
}
```

### 9. Run Cancellation

**Decision:** Support cancellation of in-progress runs via thread interruption.

**Rationale:**
- Users need ability to stop long-running or erroneously triggered runs
- Cancellation is safer than deletion for RUNNING runs (proper cleanup)
- Thread interruption is the standard Java mechanism for cooperative cancellation
- Async jobs must check `Thread.currentThread().isInterrupted()` at regular intervals

**API:** `POST /api/v1/test-suite-runs/{id}/cancel`

**Status Transitions:**
- PENDING → CANCELLED (not yet started, mark as cancelled immediately)
- RUNNING → CANCELLED (interrupt thread, allow graceful shutdown)
- COMPLETED/FAILED/CANCELLED → 409 Conflict (cannot cancel terminal runs)

**Implementation:**

**Service Method** (`@Transactional` — uses `TransactionTimestampContext` via repository):
```java
public TestSuiteRunResponseDto cancelRun(UUID runId) {
    var run = repository.findById(runId)
        .orElseThrow(() -> new EntityNotFoundException("Run not found: " + runId));

    if (RunStatus.isTerminal(run.getStatus())) {
        throw new InvalidOperationException("Cannot cancel run with status: " + run.getStatus());
    }

    if (run.getStatus().equals(RunStatus.PENDING.name())) {
        // Optimistic update: only succeeds if still PENDING (race-safe)
        // Repository uses TransactionTimestampContext for completed_at_ms/updated_at_ms
        int updated = repository.updateStatusOptimistic(runId, CANCELLED.name(), PENDING.name());
        if (updated > 0) {
            var cancelled = repository.findById(runId).orElseThrow();
            sseService.notifyStatusUpdate(cancelled);
            return mapper.toDto(cancelled);
        }
        // If 0 rows affected, the async job already moved it to RUNNING — fall through
        run = repository.findById(runId).orElseThrow();
    }

    // RUNNING: interrupt the thread via job's thread registry
    evaluationJob.interruptRun(runId);
    // Status will be updated to CANCELLED by the async job when it detects interruption
    return mapper.toDto(run);
}
```

**Thread Tracking (owned by `TestSuiteEvaluationJob`):**
- `ConcurrentHashMap<UUID, Thread> activeRunThreads` field on the job
- Job registers itself on entry: `activeRunThreads.put(runId, Thread.currentThread())`
- Always removes in `finally` block: `activeRunThreads.remove(runId)`
- Exposes `interruptRun(UUID runId)` method for service to call:
  ```java
  public void interruptRun(UUID runId) {
      Thread thread = activeRunThreads.get(runId);
      if (thread != null) {
          thread.interrupt();
      }
  }
  ```

**Interruption Checks:**
- Check `Thread.currentThread().isInterrupted()` before starting job
- Poll interruption status during sleep (sleep in 500ms chunks)
- Catch `InterruptedException`, set status to CANCELLED, clean up resources

**Race Condition Mitigation (PENDING cancel vs. async job start):**
When cancelling a PENDING run, the service uses an optimistic SQL update: `UPDATE ... SET status = 'CANCELLED' WHERE id = :id AND status = 'PENDING'`. If the async job already transitioned the run to RUNNING between the status check and the update, the affected row count will be 0, and the service retries as a RUNNING cancellation (thread interruption). This prevents a lost cancel.

**Response:**
- 200 OK: Cancellation requested successfully (PENDING → immediate, RUNNING → async)
- 404 Not Found: Run does not exist
- 409 Conflict: Run is already in terminal status (completed/failed/cancelled)

### 10. Startup Reconciliation of Orphaned Runs

**Decision:** On application startup, reconcile all non-terminal runs (PENDING, RUNNING) by marking them as FAILED.

**Rationale:**
- In-memory state (active run threads, SSE emitters) is lost on application restart
- Runs left in PENDING or RUNNING status after restart are orphaned — no thread is executing them
- Without reconciliation, these runs would appear stuck indefinitely
- Marking as FAILED (rather than silently deleting) preserves audit trail and notifies users
- The reconciliation strategy is pluggable: when a real executor replaces the mock, the strategy can change to re-enqueue or query the executor

**Implementation:**

```java
@Component
@LogExecution
@RequiredArgsConstructor
@Slf4j
public class TestSuiteRunReconciliation {

    private final TestSuiteRunRepository repository;

    @Transactional
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOrphanedRuns() {
        int updated = repository.failOrphanedRuns(
            List.of(PENDING.name(), RUNNING.name()),
            FAILED.name(),
            "Run was orphaned due to application restart",
            buildErrorDetails("ORPHANED_RUN", RunErrorCategory.INTERNAL,
                "Run was not completed because the application restarted", null)
        );
        if (updated > 0) {
            log.info("Reconciliation: marked {} orphaned runs as FAILED", updated);
        } else {
            log.debug("Reconciliation: no orphaned runs found");
        }
    }
}
```

**Repository Method** (uses `TransactionTimestampContext` for timestamps — caller is `@Transactional`):
```sql
UPDATE test_suite_runs
SET status = :failedStatus,
    error_message = :errorMessage,
    error_details = :errorDetails::jsonb,
    completed_at_ms = :now,
    updated_at_ms = :now
WHERE status IN (:orphanedStatuses)
```

**Timing:** Uses `ApplicationReadyEvent` to ensure reconciliation completes after full context initialization but before the application serves traffic. Spring Boot does not serve HTTP requests until all `ApplicationReadyEvent` listeners complete (when run synchronously on the main thread). The `@Transactional` annotation ensures atomic batch update and enables `TransactionTimestampContext` for consistent timestamps.

**Future Extension:**
- Trigger reconciliation on other events (e.g., reconnection to external executor)
- Change strategy from "fail all" to "re-enqueue" or "query external executor status"
- The trigger mechanism (`@EventListener`) remains the same; only the strategy changes

### 11. Deletion Strategy

**Decision:** Hard delete with CASCADE to related resources. Return 204 No Content. Only allow deletion of runs in terminal status (COMPLETED, FAILED, CANCELLED).

**Rationale:**
- No soft delete requirement
- FOREIGN KEY CASCADE ensures referential integrity
- Future: When test results are added, CASCADE will clean them up automatically
- Deletion is idempotent (404 if already deleted)
- Cannot delete PENDING or RUNNING runs (must wait for completion or cancel first)

**Implementation:**
- Validation: Check if run status is terminal (COMPLETED, FAILED, CANCELLED) — reject non-terminal with 409 Conflict
- SQL: `DELETE FROM test_suite_runs WHERE id = ?`
- SSE cleanup: Close any active emitters for deleted runId

**Error Response (409) for non-terminal runs:**
```json
{
  "code": "INVALID_OPERATION",
  "message": "Cannot delete a test suite run with status RUNNING. Cancel it first and wait for completion.",
  "details": {
    "runId": "uuid",
    "currentStatus": "RUNNING",
    "suggestedAction": "POST /api/v1/test-suite-runs/{id}/cancel"
  }
}
```

### 12. Test Run Name and Test Case Count

**Decision:** Add `testRunName` (user-provided or auto-generated) and `numberOfTestCases` (snapshot) as first-class properties on TestSuiteRun.

**testRunName:**
- Optional in `RunConfigDto`; if omitted, auto-generated
- Auto-generation strategy: `"Run #<N>"` where N comes from a PostgreSQL sequence (`test_suite_run_name_seq`). Monotonically increasing, never reuses values even after deletion.
- UNIQUE constraint on `(test_suite_id, test_run_name)` enforced at DB level — user-provided names that collide return 409 `UNIQUE_CONSTRAINT_VIOLATION`
- Mutable after creation via `PATCH /api/v1/test-suite-runs/{id}`
- Stored as `test_run_name VARCHAR(255) NOT NULL` in DB

**Sequence:**
```sql
CREATE SEQUENCE test_suite_run_name_seq START WITH 1 INCREMENT BY 1;
```

**numberOfTestCases:**
- Computed at run creation: count of enabled and valid test cases in the suite (`WHERE is_enabled = true AND is_valid = true`)
- This is a preliminary startup snapshot; in a future iteration it will be derived from actual test results after execution completes
- Immutable after creation
- Stored as `number_of_test_cases INTEGER NOT NULL` in DB

**Update Endpoint:**
- `PATCH /api/v1/test-suite-runs/{id}` — updates mutable properties
- Request body: `TestSuiteRunUpdateDto` with optional `testRunName`
- Allowed in any status (mutable properties are status-independent)
- Returns updated `TestSuiteRunResponseDto`

## Risks / Trade-offs

### [Risk] Thread Pool Exhaustion
**Scenario:** Many concurrent runs could exhaust dedicated thread pool, blocking new executions.
**Mitigation:**
- **Dedicated `ThreadPoolTaskExecutor` isolates run jobs from other async operations**
- Configurable pool sizing via `application.yml` (core: 5, max: 10, queue: 50)
- Concurrent run limits prevent unbounded job submission (global: 20, per-suite: 5)
- Rejected execution policy: `AbortPolicy` — rejects submission, service catches `RejectedExecutionException` and marks run as FAILED with `RESOURCE_LIMIT` category
- Document thread pool tuning in `docs/configuration.md`
- Monitor thread pool metrics in production (active threads, queue size)
- Future: If load increases beyond single-instance capacity, consider external job queue (RabbitMQ, SQS)

### [Risk] SSE Connection Limits
**Scenario:** Large number of concurrent SSE connections could strain server resources.
**Mitigation:**
- Set SseEmitter timeout (30 minutes)
- **Scheduled cleanup task removes stale connections every 5 minutes**
- Client fallback: Poll `GET /api/v1/test-suite-runs/{id}` if SSE fails
- **Concurrent run limits prevent excessive SSE connections (max 20 global, 5 per suite)**
- Document SSE limitations in API spec
- Future: Consider Redis pub/sub for horizontal scaling

### [Risk] Long-running Transactions
**Scenario:** @Transactional on @Async method holds DB connection for entire job duration.
**Mitigation:**
- Do NOT annotate `executeRunAsync` with `@Transactional`
- Use service-level methods with transaction boundaries for status updates only
- Pattern: `transactionalStatusUpdate(runId, status)` instead of transaction per job

### [Risk] Race Conditions on Status Updates
**Scenario:** Multiple status updates could conflict (e.g., concurrent COMPLETED and FAILED).
**Mitigation:**
- Synchronize on runId or use optimistic locking (future enhancement)
- Current implementation: Async method is single-threaded per run (no concurrency within one run)
- Document that only one execution per run is supported

### [Trade-off] Mock Job vs. Real Implementation
**Decision:** Mock job with sleep enables frontend integration now, but requires future refactoring.
**Acceptance:** Explicitly scoped as mock; real evaluation logic is separate effort.

## Migration Plan

**Deployment Steps:**
1. Apply Flyway migration: `V1.6__CreateTestSuiteRunsTable.sql`
2. Deploy application with new endpoints (backward compatible, no breaking changes)
3. Verify thread pool configuration in `application.yml`
4. Update `docs/database-schema.md` with new table schema
5. Update `docs/configuration.md` with thread pool settings

**Rollback Strategy:**
- No breaking changes to existing APIs
- If issues arise, disable new endpoints via feature flag (future enhancement)
- Drop table with reverse migration if necessary: `DROP TABLE test_suite_runs CASCADE;`

**Data Migration:**
- None required (net new table)

**Configuration Changes:**
```yaml
# application.yml additions

test-suite-run:
  executor:  # Dedicated thread pool for run jobs
    core-pool-size: 5
    max-pool-size: 10
    queue-capacity: 50
  sse:
    timeout-minutes: 30
    cleanup-interval-ms: 300000  # 5 minutes
  mock-job:  # Mock evaluation configuration
    min-duration-ms: 0
    max-duration-ms: 60000
    failure-probability: 0.20  # 20% chance of FAILED
  run-config:
    max-number-of-runs: 64  # service-level validation ceiling for numberOfRuns
  limits:
    max-concurrent-runs-global: 20
    max-concurrent-runs-per-suite: 5
```

## Resolved Decisions (from Open Questions)

1. **SSE Emitter Cleanup Strategy**: ✅ **IMPLEMENTED**
   - Scheduled cleanup task running every 5 minutes (configurable)
   - Sends heartbeat pings to detect stale connections
   - See Decision #7 for full details

2. **Run Limits**: ✅ **IMPLEMENTED**
   - Global limit: 20 concurrent runs (configurable)
   - Per-suite limit: 5 concurrent runs (configurable)
   - Returns 429 Too Many Requests when limits exceeded
   - See Decision #8 for full details

3. **Progress Reporting**: ✅ **APPROVED - Deferred to future**
   - SSE should emit progress (e.g., "5/10 runs completed")
   - Implementation deferred until actual evaluation logic is added
   - Mock job will not report progress

4. **Error Detail Granularity**: ✅ **DEFINED**
   - User-friendly error structure (NOT debugging details)
   - Similar to ErrorView: `{ code, category, message, details }`
   - Error categories: VALIDATION, TIMEOUT, RESOURCE_LIMIT, TEST_SUITE_ERROR, INTERNAL
   - Stack traces and debugging info logged only, never exposed to clients
   - See Database Schema section for structure

5. **Pagination Default**: ✅ **APPROVED**
   - Default sort: `createdAt:desc` (most recent first)
   - Matches common use case (view recent runs)
