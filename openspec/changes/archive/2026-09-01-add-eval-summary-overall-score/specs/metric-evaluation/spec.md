## MODIFIED Requirements

### Requirement: EvalSummary assembly from TestCaseRunResult
The system SHALL build one EvalSummary per TestCaseRunResult, copying context fields from the result and adding computed metric values. Each item SHALL carry a client-generated `id`, assigned before the batch write, so a subsequent per-row score computation can reference it without a re-query.
Status: **Implemented**

#### Scenario: Field mapping from result to summary
- **WHEN** an EvalSummary is built for a TestCaseRunResult
- **THEN** the batch write envelope SHALL carry `testSuiteId`, `testSuiteRunId`, `computationId`, and `computedAtMs` from the MetricEvaluationContext. Each item SHALL carry: a freshly generated `id`, `testCaseRunResultId` = result.id, `testCaseId`, `testCaseName`, `runIndex`, `testCaseData`, `extractedColumns`, `execDurationMs`, `responseStatusCode` from result. The `createdAtMs` is derived by the service from the run's creation timestamp (not set per-item).

#### Scenario: Non-SUCCESS result propagation
- **WHEN** a TestCaseRunResult has `executionStatus != SUCCESS`
- **THEN** the EvalSummary SHALL have `executionStatus` propagated from the result, `metricValues = {}`, `metricInfos = null` — no metric evaluation SHALL be attempted. A per-row score MAY still be attempted for this row (see `eval-summary-scoring`) since it depends only on the suite's `overallScore` definition and `metricValues`, not on `executionStatus`; an empty `metricValues` naturally yields a `null` score for `Mean`/`WeightedMean`.

#### Scenario: Metric error determines executionStatus
- **WHEN** all metrics evaluate successfully (no `type: "error"` outputs)
- **THEN** the EvalSummary SHALL have `executionStatus = SUCCESS`

#### Scenario: Any metric error or transport failure fails the summary
- **WHEN** at least one metric output field has `type: "error"` OR at least one TSMD evaluation fails with a transport error (worker exception)
- **THEN** the EvalSummary SHALL have `executionStatus = FAILED`

### Requirement: EvalSummary batch writing via service-layer client
The `EvalSummaryBatchWriteClient` SHALL convert internal EvalSummary models to the existing `EvalSummaryBatchWriteRequestDto` and delegate to `EvalSummaryService.batchCreate()`. The executor SHALL buffer EvalSummary records and flush them through the client at configurable thresholds. Immediately after each successful flush, the executor SHALL compute and write that batch's per-row `score`/`passed` (see the new per-row score requirement below) — a second write, not a follow-up `UPDATE` on the just-written rows.
Status: **Implemented**

#### Scenario: Batch flush on size
- **WHEN** the buffer reaches `metric-evaluation.batch-size` (default: 100) records
- **THEN** the executor SHALL flush the buffer via `EvalSummaryBatchWriteClient`, which converts models to DTOs and calls `EvalSummaryService.batchCreate()`, then compute and write per-row scores for that same batch

#### Scenario: Chunking to respect existing batch size limit
- **WHEN** the number of items to write exceeds the existing `analytics.eval-summaries.batch.max-items` limit
- **THEN** the client SHALL chunk items into multiple `batchCreate()` calls, each within the limit

#### Scenario: Final flush on completion
- **WHEN** all test cases have been processed
- **THEN** the executor SHALL flush any remaining buffered records via the client, then compute and write per-row scores for that final batch

#### Scenario: Flush on cancellation
- **WHEN** the run is cancelled during metric evaluation
- **THEN** the executor SHALL flush all accumulated records via the client before returning

#### Scenario: Batch write failure
- **WHEN** a batch write via the service fails
- **THEN** the executor SHALL set the cancellation signal, stop dispatching new evaluations, and log the error; `executor.shutdownNow()` will interrupt in-flight threads immediately via the `finally` block (no grace-period drain — metric evaluation is append-only)

## ADDED Requirements

### Requirement: Metric field names discovered once per metric evaluation run
Before iterating result pages, the executor SHALL discover the run's numeric metric field names once — via `runMetricSnapshotRepository.findByRunIdAndComputationId(...)` followed by `MetricFieldDiscoverer.discover(...)`, the same mechanism Phase 3 uses — and reuse that list for every flush's per-row score computation within the same `execute()` call. This SHALL be one query per `execute()` call, not one per flush, and SHALL guarantee a `Mean` overall score's divisor can never disagree between Phase 2 and Phase 3 for the same run.
Status: **Implemented**

#### Scenario: Field names discovered once, reused across flushes
- **WHEN** a run with multiple flush batches computes a `Mean` overall score
- **THEN** `runMetricSnapshotRepository.findByRunIdAndComputationId` SHALL be called exactly once for the whole `execute()` call, and every flush's per-row score computation SHALL use the same discovered field list

### Requirement: Per-row score computed and written immediately after each flush
Immediately after each Phase-2 flush's `EvalSummaryBatchWriteClient.batchWrite(...)` call succeeds, and before the buffer is cleared, the executor SHALL compute a per-row score for that batch (via `EvalSummaryRowScoreComputer`, see `eval-summary-scoring`) and write the results to `test_case_eval_scores` (via `EvalSummaryScoreService.batchCreate(...)`, see `metrics-storage`). This SHALL be skipped entirely when the suite's snapshotted `overallScore` definition is absent.
Status: **Implemented**

#### Scenario: Score computation skipped without a definition
- **WHEN** the suite's snapshotted `overallScore` is absent
- **THEN** `EvalSummaryRowScoreComputer.computeBatch` SHALL NOT be invoked and no `test_case_eval_scores` write SHALL occur for that run

#### Scenario: Score computation runs for every flush when a definition is configured
- **WHEN** the suite's snapshotted `overallScore` is present
- **THEN** every flush (size-triggered or final) SHALL be followed by exactly one score computation and, if any row produced a result, one `test_case_eval_scores` batch write scoped to that flush's row ids

#### Scenario: A failed score write is logged but does not cancel the run
- **WHEN** the per-row score computation or its batch write throws an unexpected error
- **THEN** the executor SHALL log the error and continue processing — this failure SHALL NOT set the cancellation signal, unlike a `test_case_eval_summaries` batch-write failure

## Implementation Notes
- `MetricEvaluationContext` carries `overallScoreDefinition` (`OverallScoreDefinition`) and `overallScoreThreshold` (`Double`), sourced from the run's snapshot (`snapshot.getOverallScore()` / `snapshot.getOverallScoreThreshold()`) in `TestSuiteEvaluationJob.buildMetricEvaluationContext`.
- `InProcessMetricEvaluationExecutor.buildItem` generates `EvalSummaryBatchWriteItemDto.id` via `UUID.randomUUID()` (replacing the id-generation that previously happened inside `EvalSummaryMapper.toEntity`); `EvalSummaryMapper.toEntity` now falls back to generating one only when the item's `id` is absent, preserving the external batch-write API's existing contract.
- `writeRowScores` (new private method on `InProcessMetricEvaluationExecutor`) computes `passed = (score != null && threshold != null) ? score >= threshold : null` in Java after receiving `EvalSummaryRowScoreComputer`'s `Map<UUID, Double>`.
