<!-- Delta note: Three requirements in the baseline metric-evaluation spec are affected by this change.
  1. "Graceful cancellation during metric evaluation" — REPLACED by the new requirement below named
     "Cancellation with hard shutdown during metric evaluation". The old requirement and ALL its scenarios
     (including "Grace period for in-flight calls") must be DELETED from the main spec and replaced with
     the new requirement below.
  2. "EvalSummary batch writing via service-layer client" — MODIFIED: only the "Batch write failure"
     scenario changes (grace-period drain replaced by shutdownNow). All five scenarios are reproduced
     below — only "Batch write failure" differs from the baseline; the other four are unchanged and
     must be preserved as-is during sync.
  3. "Configuration properties for metric evaluation" — MODIFIED: the "All properties with defaults"
     scenario in this delta replaces the baseline scenario (which still lists
     `cancellation-grace-period-ms` as 30000). The baseline scenario must be replaced by the one below.
     The two new scenarios ("cancellation-grace-period-ms is removed" and "Per-result timeout
     configurable via environment variable") are NEW and must be added.
  4. DUPLICATE CLEANUP: The baseline spec contains two `### Requirement: Aggregated TSMD bulk loading`
     sections — a pre-existing duplicate. During sync, if the baseline contains a second (shorter)
     `### Requirement: Aggregated TSMD bulk loading` block, remove it, keeping only the first full
     requirement block.
  5. NEW REQUIREMENT: "Per-result timeout for TSMD evaluations" with 3 scenarios — this entire
     requirement block MUST BE ADDED to the main spec. It does not exist in the baseline; it is
     a new requirement introduced by this change to separate per-result timeout semantics from
     cancellation semantics.
-->

## MODIFIED Requirements

### Requirement: Cancellation with hard shutdown during metric evaluation

Status: Implemented

The metric evaluation phase SHALL support cancellation via the same `AtomicBoolean` signal used by the deployment evaluation phase. This requirement REPLACES the baseline "Graceful cancellation during metric evaluation" requirement — the grace-period drain semantics are removed; Phase 2 uses hard shutdown (immediate thread interrupt) because metric evaluation is append-only and results can be regenerated.

#### Scenario: Cancellation stops new dispatches
- **WHEN** cancellation is signaled during metric evaluation
- **THEN** the executor SHALL stop dispatching new metric evaluation tasks

#### Scenario: Executor shutdown on any exit path
- **WHEN** the executor's `execute()` method exits (whether by normal completion, cancellation, or exception)
- **THEN** the executor SHALL call `executor.shutdownNow()` in the `finally` block unconditionally to release thread resources and interrupt any lingering in-flight threads (no grace-period drain — metric evaluation is append-only and results can be regenerated)

#### Scenario: Partial results preserved
- **WHEN** metric evaluation is cancelled
- **THEN** all EvalSummary records written before cancellation SHALL be preserved

### Requirement: Per-result timeout for TSMD evaluations

Status: Planned

The metric evaluation executor SHALL enforce a per-result timeout: the maximum wall time to wait for all TSMD futures on a single `TestCaseRunResult` before marking timed-out TSMDs as FAILED.

This is analogous to `requestTimeoutMs` in deployment evaluation (Phase 1). It is a separate concern from cancellation — it fires in normal operation when a metric provider call is slow, not only when the run is being cancelled.

#### Scenario: All TSMDs complete within timeout
- **WHEN** all TSMD futures for a result complete within `metric-evaluation.per-result-timeout-ms`
- **THEN** the EvalSummary is assembled from actual TSMD responses without interruption

#### Scenario: Slow TSMD exceeds per-result timeout
- **WHEN** one or more TSMD futures for a result do not complete within `metric-evaluation.per-result-timeout-ms`
- **THEN** the executor SHALL cancel the remaining futures, record timed-out TSMDs as errors in the EvalSummary, and continue to the next result

#### Scenario: Per-result timeout default aligned with HTTP read timeout
- **WHEN** the application starts without explicit `metric-evaluation.per-result-timeout-ms` configuration
- **THEN** it SHALL default to 150 000 ms — matching the metric provider HTTP read timeout so the HTTP layer resolves stuck calls before the future timeout fires

### Requirement: Configuration properties for metric evaluation

The system SHALL expose configurable properties under the `metric-evaluation` prefix.

#### Scenario: All properties with defaults
- **WHEN** the application starts
- **THEN** it SHALL read: `metric-evaluation.default-concurrency-per-provider` (default: 5), `metric-evaluation.batch-size` (default: 100), `metric-evaluation.per-result-timeout-ms` (default: 150000, configurable via env var `METRIC_EVAL_PER_RESULT_TIMEOUT_MS`), `metric-evaluation.retry.max-retries` (default: 0), `metric-evaluation.retry.retry-delay-ms` (default: 1000), `metric-evaluation.retry.retry-backoff-multiplier` (default: 2.0), `metric-evaluation.retry.max-retry-delay-ms` (default: 60000)

#### Scenario: cancellation-grace-period-ms is removed
- **WHEN** a deployment YAML contains `metric-evaluation.cancellation-grace-period-ms`
- **THEN** Spring Boot SHALL log an unknown-property warning but SHALL NOT fail startup; the property has no effect and operators should migrate to `metric-evaluation.per-result-timeout-ms`

#### Scenario: Per-result timeout configurable via environment variable
- **WHEN** `METRIC_EVAL_PER_RESULT_TIMEOUT_MS` is set in the environment
- **THEN** `metric-evaluation.per-result-timeout-ms` SHALL use that value

### Requirement: EvalSummary batch writing via service-layer client

Status: Implemented

The `EvalSummaryBatchWriteClient` SHALL convert internal EvalSummary models to the existing `EvalSummaryBatchWriteRequestDto` and delegate to `EvalSummaryService.batchCreate()`. The executor SHALL buffer EvalSummary records and flush them through the client at configurable thresholds.

#### Scenario: Batch flush on size
- **WHEN** the buffer reaches `metric-evaluation.batch-size` (default: 100) records
- **THEN** the executor SHALL flush the buffer via `EvalSummaryBatchWriteClient`, which converts models to DTOs and calls `EvalSummaryService.batchCreate()`

#### Scenario: Chunking to respect existing batch size limit
- **WHEN** the number of items to write exceeds the existing `analytics.eval-summaries.batch.max-items` limit
- **THEN** the client SHALL chunk items into multiple `batchCreate()` calls, each within the limit

#### Scenario: Final flush on completion
- **WHEN** all test cases have been processed
- **THEN** the executor SHALL flush any remaining buffered records via the client

#### Scenario: Flush on cancellation
- **WHEN** the run is cancelled during metric evaluation
- **THEN** the executor SHALL flush all accumulated records via the client before returning

> **Note**: On batch write failure, the cancellation signal is set and `executor.shutdownNow()` interrupts in-flight threads. The executor still attempts to flush remaining buffered records via `flushRemaining` in the `finally` block, but the batch that triggered the error is lost. This is acceptable because metric evaluation is append-only and results can be regenerated.

#### Scenario: Batch write failure
- **WHEN** a batch write via the service fails
- **THEN** the executor SHALL set the cancellation signal, stop dispatching new evaluations, and log the error; `executor.shutdownNow()` will interrupt in-flight threads immediately via the `finally` block (no grace-period drain — metric evaluation is append-only)
