## MODIFIED Requirements

### Requirement: Single metric evaluation (worker)
The `MetricEvaluationWorker` SHALL evaluate a single TSMD against a single TestCaseRunResult by resolving bindings, building an `EvaluationRequest`, calling the provider's `/evaluate` endpoint, and returning the `EvaluationResponse`.
Status: **Implemented**

#### Scenario: Successful metric evaluation
- **WHEN** a worker evaluates TSMD "Accuracy" (metric: exact_match) for a test case result
- **THEN** the worker SHALL resolve bindings, build `EvaluationRequest` with `metric_name` from the metric declaration's name, `config` from resolved config bindings, `input` from resolved input bindings, call `POST /evaluate` on the provider, and return the `EvaluationResponse`

#### Scenario: Provider call failure with retry
- **WHEN** the `/evaluate` call fails with a retryable condition (timeout, 5xx, 429) and retry is configured
- **THEN** the worker SHALL retry up to `maxRetries` times with exponential backoff: `delay = min(retryDelayMs * retryBackoffMultiplier^(attemptIndex - 1), maxRetryDelayMs)`

#### Scenario: Non-retryable failure
- **WHEN** the `/evaluate` call fails with a non-retryable condition (4xx except 429)
- **THEN** the worker SHALL NOT retry and SHALL throw an exception with the error details

#### Scenario: All retries exhausted
- **WHEN** all retry attempts fail
- **THEN** the worker SHALL throw an exception with the error details from the last attempt

#### Scenario: Retry respects cancellation
- **WHEN** the run is cancelled while a retry backoff is in progress
- **THEN** the worker thread SHALL be interrupted, the backoff sleep SHALL end immediately with `InterruptedException`, and the worker SHALL propagate it without issuing another attempt

#### Scenario: Transport failure propagation to executor
- **WHEN** the worker throws an exception (transport failure, all retries exhausted)
- **THEN** the executor SHALL catch the per-TSMD exception via `CompletableFuture` error handling and map it to error entries in metricValues (null) and metricInfos (error message)

### Requirement: EvalSummary batch writing via service-layer client
The `EvalSummaryBatchWriteClient` SHALL convert internal EvalSummary models to the existing `EvalSummaryBatchWriteRequestDto` and delegate to `EvalSummaryService.batchCreate()`. The executor SHALL buffer EvalSummary records and flush them through the client at configurable thresholds.
Status: **Implemented**

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
- **THEN** the executor SHALL flush all records accumulated for fully evaluated results via the client before returning

#### Scenario: Batch write failure
- **WHEN** a batch write via the service fails
- **THEN** the executor SHALL log the error and let the failure propagate; the job SHALL mark the run FAILED with error category `INTERNAL` and code `ANALYTICS_WRITE_FAILED`. The failure SHALL NOT be expressed as a cancellation and the run SHALL NOT end `CANCELLED` because of it.

## REMOVED Requirements

### Requirement: Cancellation with hard shutdown during metric evaluation
**Reason**: Phase 2 no longer owns an executor nor polls an `AtomicBoolean`; cancellation is delivered by the run owner shutting down the shared run executor.
**Migration**: See "Cancellation via the run's shared executor" below; no configuration change.

## ADDED Requirements

### Requirement: Cancellation via the run's shared executor
The metric evaluation phase SHALL dispatch its per-TSMD tasks on the run's shared worker executor (the same executor Phase 1 used, provided through `MetricEvaluationContext`) and SHALL NOT create an executor of its own. Cancellation is delivered by the run owner shutting that executor down: in-flight metric-provider calls are interrupted immediately and further dispatch is rejected. There is no grace-period drain — metric evaluation is append-only and results can be regenerated.
Status: **Implemented**

#### Scenario: Cancellation stops new dispatches
- **WHEN** the run's executor has been shut down during metric evaluation
- **THEN** the next attempt to dispatch a TSMD task SHALL be rejected and the executor SHALL stop iterating results; the failure surfaces to the job, which records the run as CANCELLED

#### Scenario: In-flight metric calls are interrupted
- **WHEN** the run's executor is shut down while TSMD evaluations are in flight
- **THEN** those worker threads SHALL be interrupted at once; the per-result assembly SHALL treat interrupted TSMDs like failed ones for the row currently being assembled

#### Scenario: Executor lifecycle is owned by the run, not the phase
- **WHEN** the metric evaluation `execute()` method exits (normal completion, cancellation, or exception)
- **THEN** it SHALL flush its remaining buffer but SHALL NOT shut the executor down — the run owner shuts the executor down exactly once when the whole run finishes

#### Scenario: Partial results preserved
- **WHEN** metric evaluation is cancelled
- **THEN** all EvalSummary records written before cancellation SHALL be preserved

## Implementation Notes
- `MetricEvaluationContext` replaces `AtomicBoolean cancellationSignal` with `ExecutorService executor`;
  `InProcessMetricEvaluationExecutor` uses it for `CompletableFuture.runAsync` and drops its own
  `newVirtualThreadPerTaskExecutor()` and `shutdownNow()`.
- `MetricEvaluationWorker.sleepWithCancellation` is replaced by plain `Thread.sleep` (interruptible); the
  pre-attempt signal check is removed.
- `MetricScoreComputationContext` loses `cancellationSignal`; Phase 3 is sequential and is gated only by the job's
  phase-boundary check.
