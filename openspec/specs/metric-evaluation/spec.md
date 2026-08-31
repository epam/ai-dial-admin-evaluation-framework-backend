# Metric Evaluation

## Purpose
This spec defines the in-process metric evaluation engine — a second execution phase within the test suite run lifecycle that evaluates configured metrics against test case run results by calling metric provider `/evaluate` endpoints, and writes the results as EvalSummary records.

Status: **Implemented**

## Key Terms
- **MetricEvaluationExecutor**: Interface for metric evaluation execution strategies (analogous to `EvaluationExecutor` for deployment evaluation). Supports both in-process execution and future K8s Job delegation.
- **InProcessMetricEvaluationExecutor**: In-process implementation of `MetricEvaluationExecutor`. Orchestrates paginated result iteration, concurrent metric evaluation dispatch, RunMetricSnapshot capture, and EvalSummary batch writing.
- **MetricEvaluationWorker**: Evaluates a single TSMD against a single test case result — resolves bindings, calls provider `/evaluate`, handles retry.
- **BindingResolver**: Resolves TSMD config/input bindings against test case data and extracted columns from a TestCaseRunResult.
- **EvalSummaryBatchWriteClient**: Service-layer wrapper that converts internal EvalSummary models to batch write DTOs and delegates to `EvalSummaryService.batchCreate()`, chunking items to respect the existing batch size limit.
- **RunMetricSnapshotBatchWriteClient**: Service-layer wrapper that converts internal RunMetricSnapshot models to batch write DTOs and delegates to `RunMetricSnapshotService.batchCreate()`.
- **MetricEvaluationContext**: Immutable context carrier for a metric evaluation run — carries computationId, aggregated TSMDs grouped by provider, semaphores, cancellation signal, retry config.

## Requirements

### Requirement: Aggregated TSMD bulk loading
The repository SHALL support loading aggregated TSMDs for a test suite. Two variants SHALL exist:
- `findAllAggregatedByTestSuiteId(testSuiteId)` — loads ALL TSMDs regardless of `is_enabled` / `is_valid` state (used by revalidation and the aggregated-definition endpoint)
- `findAllEnabledAndValidAggregatedByTestSuiteId(testSuiteId)` — loads only TSMDs where `is_enabled = true AND is_valid = true` (used by the metric evaluation phase)
Status: **Implemented**

#### Scenario: findAllAggregatedByTestSuiteId — all TSMDs
- **WHEN** `findAllAggregatedByTestSuiteId(testSuiteId)` is called
- **THEN** it SHALL execute a 3-table JOIN (test_suite_metric_definitions + metric_declarations + metric_declaration_versions) and return `List<AggregatedMetricDefinition>` with all fields populated including `declarationProviderId` and `metricDeclarationName` — regardless of `is_enabled` or `is_valid`

#### Scenario: findAllEnabledAndValidAggregatedByTestSuiteId — filtered
- **WHEN** `findAllEnabledAndValidAggregatedByTestSuiteId(testSuiteId)` is called
- **THEN** it SHALL return only TSMDs where `is_enabled = true AND is_valid = true`

#### Scenario: No TSMDs for suite
- **WHEN** the test suite has no TSMDs
- **THEN** both methods SHALL return an empty list

#### Scenario: Disabled TSMD excluded from evaluation load
- **WHEN** a TSMD has `is_enabled = false` and `is_valid = true`
- **THEN** `findAllEnabledAndValidAggregatedByTestSuiteId` SHALL NOT include it in the result

#### Scenario: Invalid TSMD excluded from evaluation load
- **WHEN** a TSMD has `is_enabled = true` and `is_valid = false`
- **THEN** `findAllEnabledAndValidAggregatedByTestSuiteId` SHALL NOT include it in the result

### Requirement: Metric evaluation executor orchestration
`MetricEvaluationExecutor` is an interface; `InProcessMetricEvaluationExecutor` is the in-process implementation (mirroring the `EvaluationExecutor` / `InProcessEvaluationExecutor` pattern for deployment evaluation). `TestSuiteEvaluationJob` SHALL capture RunMetricSnapshots once, before Phase 1 begins, for every run (see "RunMetricSnapshot capture before evaluation") — snapshot capture is no longer the executor's own responsibility. The executor's `execute()` SHALL iterate all `TestCaseRunResult` records for the run using cursor-based pagination, and for each row: on a **non-inline** run, dispatch metric evaluations concurrently per provider for every enabled+valid TSMD, assemble EvalSummary records, and batch-write them to the analytics DB, exactly as before; on an **inline** run (`MetricEvaluationContext.inlineMode = true`), SUCCESS rows SHALL be skipped entirely — their EvalSummary was already written during Phase 1 by the inline evaluator — and only non-SUCCESS rows SHALL be processed, via the same propagate-only path used for every non-SUCCESS row today (see "Phase 2 propagate-only pass for inline runs"). The set of TSMDs loaded into `MetricEvaluationContext` SHALL be limited to those that are both enabled and valid (`is_enabled = true AND is_valid = true`), loaded once when the context is built — a TSMD enabled, disabled, or edited after that point is not picked up by the in-flight run, whether inline or not.

The executor SHALL write one EvalSummary per `TestCaseRunResult` row **regardless of how many TSMDs the context carries, including zero** — `test_case_eval_summaries` is the single surface from which run results are read, so a run whose suite has no enabled+valid TSMDs SHALL still produce readable rows. Such a row SHALL have `metric_values = {}` and no `metric_infos` value at all (the column is nullable and receives JSON `null`, matching what the metric output mapper already produces when it has no metric information to record). `run_metric_snapshots` SHALL receive rows only for the TSMDs actually present, so an empty TSMD list writes no snapshot rows. Consequently the absence of eval summaries SHALL NOT be used to signal "this suite has no metrics"; the signals for that are an empty `run_metric_snapshots` set for the computation and empty `metric_values` on the rows. Under inline mode this one-summary-per-row invariant SHALL still hold across the two writers: the union of inline-written SUCCESS-row summaries and the propagate-only pass's non-SUCCESS-row summaries SHALL cover exactly one EvalSummary per row, identically to non-inline mode.
Status: **Implemented**

#### Scenario: Successful metric evaluation for all test cases
- **WHEN** the metric evaluation phase starts for a completed non-inline run with TSMDs configured
- **THEN** the executor SHALL iterate all TestCaseRunResults for the run, evaluate all enabled+valid TSMDs for each SUCCESS result, merge outputs into EvalSummary records, and batch-write them to the analytics DB

#### Scenario: No TSMDs configured for suite
- **WHEN** the metric evaluation phase starts and the suite has no TSMDs
- **THEN** the executor SHALL iterate all TestCaseRunResults for the run and write one EvalSummary per row under the context's `computationId`, each with `metric_values = {}` and `metric_infos` absent (JSON `null` in the nullable column), and SHALL write no `run_metric_snapshots` rows

#### Scenario: All TSMDs disabled or invalid
- **WHEN** the suite has TSMDs but all are either `is_enabled = false` or `is_valid = false`
- **THEN** the executor SHALL behave exactly as for a suite with no TSMDs (empty TSMD list in context): one EvalSummary per result row with `metric_values = {}` and no `metric_infos` value, and no `run_metric_snapshots` rows

#### Scenario: Non-metric fields preserved on a metric-less run
- **WHEN** an EvalSummary is written for a run with an empty TSMD list
- **THEN** it SHALL carry the source row's `test_case_data`, `extracted_columns`, `extraction_warnings`, `turn_index`, `total_turns`, `exec_duration_ms`, and `response_status_code` unchanged

#### Scenario: Execution status propagation on a metric-less run
- **WHEN** a run with an empty TSMD list has both SUCCESS and non-SUCCESS `TestCaseRunResult` rows
- **THEN** each SUCCESS row's EvalSummary SHALL have `execution_status = SUCCESS` and each non-SUCCESS row's EvalSummary SHALL retain that row's own status

#### Scenario: No provider traffic on a metric-less run
- **WHEN** the metric evaluation phase runs with an empty TSMD list
- **THEN** the executor SHALL make no metric-provider `/evaluate` calls and SHALL evaluate no metric `condition` expressions

#### Scenario: Cursor-paginated result iteration
- **WHEN** the executor iterates TestCaseRunResults
- **THEN** it SHALL use cursor-based pagination (filtering by runId) to avoid loading all results into memory

#### Scenario: Cross-result parallelism
- **WHEN** multiple test case results are being processed
- **THEN** the executor SHALL dispatch metric evaluations across results concurrently — the provider semaphore controls the total concurrent `/evaluate` calls per provider

#### Scenario: Inline run skips SUCCESS rows in Phase 2
- **WHEN** a run's `MetricEvaluationContext.inlineMode` is `true`
- **THEN** the executor's row loop SHALL skip every SUCCESS row without dispatching any provider call, and SHALL process every non-SUCCESS row through the existing propagate-only path

### Requirement: Metrics are evaluated per turn row
Metric evaluation SHALL treat each turn's result row as an independent evaluation unit. Because each turn persists a scalar `extracted_columns` object (never arrays across turns), the existing binding-resolution path applies unchanged per turn; no multi-turn-specific reshaping occurs at the metric boundary.
Status: **Implemented**

#### Scenario: Each turn scored independently
- **WHEN** a multi-turn case has N SUCCESS turn rows
- **THEN** metrics are resolved and scored per turn row from that row's `test_case_data` and `extracted_columns`

### Requirement: Conditional gating integrated into per-result evaluation
For each result row, the executor SHALL build a condition context (`data`, `response`, `turn.index/total/last`) and evaluate each metric's `condition` before dispatch: skip-and-omit on false, dispatch on true, and record a metric-level error (without failing the row) on a broken condition. Only metrics actually dispatched are considered when recording per-metric timeout/failure. Under inline metric evaluation, a broken condition additionally aborts the request chain — see `conditional-metric-execution`'s "Broken condition aborts the chain under inline evaluation" scenario for the mode-scoped divergence from the non-inline behavior described here.
Status: **Implemented**

#### Scenario: Skipped metric is not dispatched
- **WHEN** a metric's condition is false for a turn row
- **THEN** the metric is not dispatched and contributes no value, and the row's status stays SUCCESS

#### Scenario: Condition error does not mark the row failed
- **WHEN** a metric's condition errors for a turn row
- **THEN** a `metricError::<name>` entry is recorded, the metric is not dispatched, and the row stays SUCCESS

### Requirement: Provider-bounded concurrency
Each metric provider SHALL have its own semaphore controlling the maximum number of concurrent `/evaluate` calls. The semaphore limit SHALL be configurable. For an inline run, `InlineMetricEvaluatorFactory` SHALL construct the same per-provider semaphores once at run start, sized from the same `metric-evaluation.default-concurrency-per-provider` property, and reuse them for every inline `evaluate()` call made during Phase 1 — so this property bounds inline provider-call concurrency during Phase 1 exactly as it bounds Phase 2's dispatch, using one shared dial rather than two.
Status: **Implemented**

#### Scenario: Semaphore per provider
- **WHEN** the metric evaluation phase starts with TSMDs from providers "dial" and "custom"
- **THEN** the executor SHALL create a separate semaphore for each provider, each initialized to the configured concurrency limit

#### Scenario: Semaphore acquired before /evaluate call
- **WHEN** a MetricEvaluationWorker is about to call `/evaluate`
- **THEN** it SHALL acquire the provider's semaphore before making the HTTP call and release it in a `finally` block

#### Scenario: Configurable concurrency
- **WHEN** the application starts
- **THEN** `metric-evaluation.default-concurrency-per-provider` SHALL control the semaphore limit (default: 5)

#### Scenario: Inline evaluation shares the Phase-2 concurrency limit
- **WHEN** an inline run's `InlineMetricEvaluatorFactory` builds its per-provider semaphores at run start
- **THEN** it SHALL size them from the same `metric-evaluation.default-concurrency-per-provider` value Phase 2 uses, bounding inline `/evaluate` concurrency during Phase 1 to the same limit

### Requirement: Inline metric evaluation mode is derived per run
A run's inline mode SHALL be derived, never configured. `InlineModeDetector` SHALL compute it from the run's `SuiteSnapshotDto` (the frozen config Phase 1 executes) plus the live enabled+valid TSMD list (what Phase 2 would otherwise evaluate) — never from the live suite, since a suite edited between run creation and dispatch must not change an in-flight run's mode. A run is inline iff `"$_metrics"` appears as a substring in any request-template JSON body serialization (`content` or `jsonataContent`, on the root template or any `additionalRequests[i].requestTemplate`) or in any enabled+valid TSMD's `configBindings`/`inputBindings` raw JSON string. A `suiteType = MCP_TOOL` run SHALL always be non-inline, since an MCP suite has no request chain for a metric output to feed. A false-positive match (the substring appears but is unreachable, e.g. inside an unused branch) SHALL still be treated as inline — it costs an inline-mode code path with no behavioral difference, so no JSONata AST walk is performed to rule it out. `InlineModeDetector` SHALL run only when `!skipDeploymentPhase`: a run dispatched with `skipDeploymentPhase = true` (the `importResultsAndEvaluate` import path, which has no Phase 1 to be inline about) SHALL be forced non-inline unconditionally, without invoking the detector at all, so Phase 2 evaluates every SUCCESS row exactly as it does for every other non-inline run.
Status: **Implemented**

#### Scenario: Root request body reference makes the run inline
- **WHEN** the suite's own `requestTemplate` JSON body contains `$_metrics`
- **THEN** the run is inline

#### Scenario: Additional-request body reference makes the run inline
- **WHEN** an `additionalRequests[i].requestTemplate` JSON body contains `$_metrics`
- **THEN** the run is inline

#### Scenario: jsonataContent reference makes the run inline
- **WHEN** a request body is authored in `jsonataContent` and its source text contains `$_metrics`
- **THEN** the run is inline

#### Scenario: TSMD binding reference makes the run inline
- **WHEN** an enabled+valid TSMD's `configBindings` or `inputBindings` raw JSON contains `$_metrics`
- **THEN** the run is inline

#### Scenario: Non-JSON-body surfaces are not scanned
- **WHEN** a suite's request body is multipart or URL-encoded, or `$_metrics` appears only in a URL template, header, or query parameter
- **THEN** that occurrence SHALL NOT be scanned and SHALL NOT make the run inline by itself

#### Scenario: MCP suite is never inline
- **WHEN** the run's suite has `suiteType = MCP_TOOL`
- **THEN** the run SHALL be non-inline regardless of any `$_metrics` text present anywhere in its configuration

#### Scenario: Suite without any $_metrics reference stays non-inline
- **WHEN** no scanned surface contains `$_metrics`
- **THEN** the run is non-inline and executes byte-identically to a run of this change never having shipped

#### Scenario: A run dispatched with skipDeploymentPhase is never inline
- **WHEN** a run is dispatched with `skipDeploymentPhase = true`
- **THEN** it SHALL be non-inline regardless of any `$_metrics` text present anywhere in its suite snapshot or TSMD bindings, and Phase 2 SHALL evaluate every SUCCESS row exactly as it does today for the eval-results-import path

### Requirement: Inline EvalSummary writing during Phase 1
For an inline run, each SUCCESS `TestCaseRunResult` row produced during Phase 1 SHALL have its enabled+valid TSMDs evaluated immediately after the row is built, and its EvalSummary SHALL be written directly by the inline evaluator — not deferred to Phase 2. This applies only to rows reaching `TurnLoopExecutor`'s CONTINUE branch (i.e. rows that are SUCCESS); the several row-construction paths that produce a non-SUCCESS row (deployment ABORT, a request-resolution exception, an empty turn plan, a chain-level exception, or `TestCaseRunner`'s synthetic error row) never invoke inline evaluation and are scored by the Phase-2 propagate-only pass instead. Inline evaluation SHALL be safe under concurrent dispatch: `TestCaseRunner` may invoke the evaluator from multiple test cases running on separate virtual threads at once (bounded by the run's `concurrencyLevel`), and no summary SHALL be lost or corrupted under that concurrency. Duration accounting mirrors Phase 2: the wall-clock time of each inline-evaluated TSMD's provider call SHALL be summed into that row's EvalSummary `metricEvalDurationMs` exactly as `MetricRowEvaluator` computes it for Phase 2 (see "Per-TSMD metric evaluation latency measurement"), and SHALL NOT be added to the `TestCaseRunResult`'s own `execDurationMs`, which continues to measure only the deployment call's latency.
Status: **Implemented**

#### Scenario: SUCCESS row is scored during Phase 1
- **WHEN** a SUCCESS row is built during an inline run
- **THEN** its TSMDs are evaluated and its EvalSummary is written before Phase 2 begins, without waiting for the row to be re-read from the analytics DB

#### Scenario: Non-SUCCESS rows are never evaluated inline
- **WHEN** a row is produced via an ABORT outcome, a request-resolution exception, an empty turn plan, a chain-level exception, or a synthetic error row
- **THEN** inline evaluation SHALL NOT run for that row; its EvalSummary is produced later by the Phase-2 propagate-only pass

#### Scenario: Concurrent dispatch loses no summaries
- **WHEN** `concurrencyLevel` is greater than 1 and multiple test cases are evaluated inline concurrently
- **THEN** every SUCCESS row's EvalSummary SHALL be present after the run completes, with none dropped or overwritten by a race

### Requirement: Inline metric failure aborts the chain; the row stays SUCCESS
When an inline-evaluated TSMD fails — transport failure after retries, a provider response of `type: "error"`, a per-result timeout, or a `condition` that throws, returns non-boolean, or evaluates to `null` — the chain SHALL abort: remaining turns of the current request and any later requests in the chain SHALL be skipped, while rows already produced SHALL persist. The failed TSMD's EvalSummary for that row SHALL be recorded as FAILED with the corresponding error entries, using the same semantics `buildItem` already applies for a Phase-2 failure. The result row's `executionStatus` SHALL remain `SUCCESS` — the deployment call itself succeeded; `executionStatus` reflects deployment-call outcome, not metric-scoring outcome. A `condition` that evaluates to a clean `false` SHALL NOT be treated as a failure: the metric is simply omitted for that row and the chain continues. Inline evaluation SHALL enforce `metric-evaluation.per-result-timeout-ms` per row — the same property and semantics `InProcessMetricEvaluationExecutor` already applies per batch in Phase 2, evaluated here against one row's dispatched TSMD futures instead of a page of rows; a TSMD not completing within that window is one of the transport-failure-shaped triggers for this requirement's chain-abort behavior.
Status: **Implemented**

#### Scenario: Metric transport failure aborts remaining requests
- **WHEN** an inline-evaluated TSMD's provider call fails after exhausting retries on request #0
- **THEN** request #1 and any later request SHALL NOT execute, but request #0's row SHALL persist with `executionStatus = SUCCESS` and a FAILED eval summary for that TSMD

#### Scenario: Broken condition aborts the chain under inline evaluation
- **WHEN** an inline-evaluated TSMD's `condition` throws or evaluates to a non-boolean value
- **THEN** the chain SHALL abort exactly as for a transport failure, diverging from the non-inline `conditional-metric-execution` behavior where a broken condition never fails the row or aborts anything

#### Scenario: Clean false condition does not abort
- **WHEN** an inline-evaluated TSMD's `condition` evaluates cleanly to `false`
- **THEN** the metric is omitted from that row's EvalSummary and the chain continues to the next turn or request

### Requirement: Phase 2 is propagate-only for inline runs
For an inline run, `InProcessMetricEvaluationExecutor`'s row loop SHALL skip every SUCCESS row (already scored inline) and SHALL process every non-SUCCESS row exactly as it does today via `buildPropagatedItem`, making no metric-provider calls for those rows. A non-inline run's Phase 2 behavior SHALL be completely unaffected by this requirement.
Status: **Implemented**

#### Scenario: No provider calls for an inline run's Phase 2
- **WHEN** Phase 2 runs for an inline run
- **THEN** zero `/evaluate` calls SHALL be made against any metric provider during Phase 2

#### Scenario: Non-SUCCESS rows still get a propagated summary
- **WHEN** an inline run has non-SUCCESS rows (e.g. from an aborted chain or a synthetic error row)
- **THEN** Phase 2 SHALL write a propagated EvalSummary for each of them, identically to how non-SUCCESS rows are handled today

#### Scenario: Non-inline runs are unaffected
- **WHEN** a run is non-inline
- **THEN** Phase 2 SHALL evaluate every SUCCESS row's TSMDs exactly as before this change

### Requirement: Final flush precedes Phase 2 and Phase 3
The inline evaluator's buffered EvalSummary writes SHALL be flushed to the analytics DB immediately after Phase 1 execution returns and before the Phase 2 / Phase 3 block begins. A safety-net `close()` call SHALL additionally occur in the job's `finally` block as a no-op when the flush already ran.
Status: **Implemented**

#### Scenario: Phase 3 sees the complete inline-written set
- **WHEN** Phase 3 (`MetricScoreComputationExecutor`) runs structured queries over eval summaries for an inline run
- **THEN** it SHALL see every SUCCESS row's inline-written summary, because the final flush completed before Phase 3 started

#### Scenario: Cancellation still flushes what was produced
- **WHEN** an inline run is cancelled mid-Phase-1
- **THEN** the inline evaluator's buffer SHALL still be flushed, so every summary produced before cancellation is persisted

### Requirement: TSMD set for a run is loaded once, before Phase 1
The TSMD list loaded into a run's `MetricEvaluationContext` SHALL be fixed at context-construction time, before Phase 1 dispatches any work, for every run — inline or not. A TSMD enabled, disabled, or edited after that point SHALL NOT be picked up by the in-flight run.
Status: **Implemented**

#### Scenario: Late TSMD edit is not picked up
- **WHEN** a TSMD is enabled, disabled, or its bindings edited after a run's `MetricEvaluationContext` has been built
- **THEN** the in-flight run SHALL continue to use the TSMD set as it existed at context-construction time, for both its inline and non-inline (propagate-only) evaluation

### Requirement: Known orphan EvalSummary cases (documented, not fixed)
Two pre-existing-shaped edge cases SHALL be documented as accepted, not remediated by this change: (a) when an exception escapes `TurnLoopExecutor`'s own try block inside `EvaluationWorker`'s chain-level catch, already-produced chain rows for that test case are discarded — any EvalSummary the inline evaluator already wrote for those rows becomes an orphan (a summary with no persisted row); (b) when `PostgresResultBatchWriter.doFlush` fails after inline EvalSummaries for that row batch have already been committed, the row batch is lost while its summaries remain — the same orphan shape. Both are rare, of the same character as pre-existing single-writer failure modes, and are explicitly accepted rather than fixed.
Status: **Implemented**

#### Scenario: Chain-level exception orphans inline summaries
- **WHEN** an exception escapes `TurnLoopExecutor`'s own try block during an inline run and `EvaluationWorker` discards the chain's rows for that test case
- **THEN** any EvalSummary the inline evaluator already wrote for those discarded rows SHALL remain in the analytics DB with no corresponding `TestCaseRunResult` row — accepted, not remediated

#### Scenario: Batch-flush failure after summaries are committed orphans them symmetrically
- **WHEN** `PostgresResultBatchWriter.doFlush` fails for a row batch whose rows' inline EvalSummaries were already committed
- **THEN** those summaries SHALL remain persisted with no corresponding row batch — accepted, not remediated

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
- **THEN** the worker SHALL abort the retry and throw an exception with the last known error

#### Scenario: Transport failure propagation to executor
- **WHEN** the worker throws an exception (transport failure, all retries exhausted)
- **THEN** the executor SHALL catch the per-TSMD exception via `CompletableFuture` error handling and map it to error entries in metricValues (null) and metricInfos (error message)

### Requirement: Binding resolution
The `BindingResolver` SHALL resolve TSMD config and input bindings against test case data and extracted columns from a `TestCaseRunResult`, producing `Map<String, Object>` for config and input. Resolution SHALL fail fast when a binding references a column that does not exist in the data map. For an `Expression` source, resolution SHALL additionally accept a per-row frame `{data, response, _metrics}` (`data` = the row's test case data, `response` = the row's extracted columns, `_metrics` = the run's accumulated metrics frame for an inline run, or an empty map for Phase 2's propagate-only pass and for any non-inline evaluation) and evaluate the binding's JSONata `expression` against that frame; see `tsmd-validation` for the source's shape and failure semantics.
Status: **Implemented**

#### Scenario: TestCase binding source
- **WHEN** a binding has `source: { $type: "TestCase", columnName: "expected" }` and the test case data contains `{"expected": "A planet"}`
- **THEN** the resolver SHALL produce `{"expected": "A planet"}` for that binding's property

#### Scenario: Response binding source
- **WHEN** a binding has `source: { $type: "Response", columnName: "model_answer" }` and the extracted columns contain `{"model_answer": "Earth is the third planet"}`
- **THEN** the resolver SHALL produce the value `"Earth is the third planet"` for that binding's property

#### Scenario: Constant binding source
- **WHEN** a binding has `source: { $type: "Constant", value: "gemini-2.5-flash-lite" }`
- **THEN** the resolver SHALL produce the literal value `"gemini-2.5-flash-lite"` for that binding's property

#### Scenario: Missing column in test case data fails fast
- **WHEN** a binding has `source: { $type: "TestCase", columnName: "score" }` and the test case `data` map does NOT contain the key `"score"` (i.e. `data.containsKey("score")` is false)
- **THEN** the resolver SHALL throw `IllegalArgumentException` with a message identifying the missing column and source type

#### Scenario: Missing column in extracted columns fails fast
- **WHEN** a binding has `source: { $type: "Response", columnName: "model_answer" }` and the extracted columns map does NOT contain the key `"model_answer"`
- **THEN** the resolver SHALL throw `IllegalArgumentException` with a message identifying the missing column and source type

#### Scenario: Present column with null value resolves to null
- **WHEN** a binding references a column that IS present in the data map (i.e. `data.containsKey(columnName)` is true) but its value is `null`
- **THEN** the resolver SHALL produce `null` for that binding's property (no exception thrown)

#### Scenario: Multiple bindings merged into single map
- **WHEN** input bindings contain `[{property: "actual", source: Response/model_answer}, {property: "ground_truth", source: TestCase/expected}]`
- **THEN** the resolver SHALL produce `{"actual": <value>, "ground_truth": <value>}`

#### Scenario: Expression binding source
- **WHEN** a binding has `source: { $type: "Expression", expression: "$_metrics.`judge`.score.value" }` and the per-row frame's `_metrics.judge.score.value` is `0.9`
- **THEN** the resolver SHALL produce `0.9` for that binding's property

### Requirement: EvaluationRequest construction
The system SHALL build an `EvaluationRequest` from resolved bindings and the metric declaration name.
Status: **Implemented**

#### Scenario: Request structure
- **WHEN** a metric evaluation request is constructed for TSMD "Accuracy" (declaration name: "exact_match") with resolved config `{}` and input `{"actual": "test", "ground_truth": "test"}`
- **THEN** the request SHALL be `{"metric_name": "exact_match", "config": {}, "input": {"actual": "test", "ground_truth": "test"}}`

#### Scenario: metric_name from declaration
- **WHEN** the request is built
- **THEN** `metric_name` SHALL be taken from `AggregatedMetricDefinition.metricDeclarationName` (the provider's metric name), NOT from the TSMD's display name

### Requirement: Output mapping to metricValues and metricInfos
The system SHALL map `EvaluationResponse` output fields to EvalSummary's `metricValues` and `metricInfos` JSONB columns, keyed by TSMD name. Output field names SHALL always come from the metric's actual output schema, never from synthetic placeholder keys.
Status: **Implemented**

#### Scenario: Value output without details
- **WHEN** an output field has `{type: "value", value: 1}` with no details
- **THEN** `metricValues[tsmdName][fieldName]` SHALL be `1` and no entry SHALL be added to metricInfos for that field

#### Scenario: Value output with details
- **WHEN** an output field has `{type: "value", value: 0.8, details: {"reason": "..."}}`
- **THEN** `metricValues[tsmdName][fieldName]` SHALL be `0.8` and `metricInfos[tsmdName][fieldName]` SHALL be `{"reason": "..."}`

#### Scenario: Error output
- **WHEN** an output field has `{type: "error", message: "Invalid pattern"}`
- **THEN** `metricValues[tsmdName][fieldName]` SHALL be `null` (explicit JSON null, preserved via ObjectNode.putNull) and `metricInfos[tsmdName][fieldName]` SHALL be `{"error": "Invalid pattern"}`

#### Scenario: Transport failure for a TSMD
- **WHEN** the `/evaluate` call for a TSMD fails with a transport error (HTTP 500, timeout, all retries exhausted) and the TSMD's output schema has field names `["recall", "precision", "f1", "mrr"]`
- **THEN** `metricValues[tsmdName]` SHALL contain `{"recall": null, "precision": null, "f1": null, "mrr": null}` (all output fields set to null) and `metricInfos[tsmdName]` SHALL contain `{"recall": {"error": "<exception message>"}, "precision": {"error": "<exception message>"}, "f1": {"error": "<exception message>"}, "mrr": {"error": "<exception message>"}}` (error entry per output field)

#### Scenario: Transport failure with empty output schema (fallback)
- **WHEN** the `/evaluate` call for a TSMD fails with a transport error AND the TSMD's output schema has no extractable field names (null, malformed, or empty properties)
- **THEN** `metricValues[tsmdName]` SHALL be an empty object `{}` and `metricInfos[tsmdName]` SHALL contain `{"error": "<exception message>"}`. The system SHALL log a WARN indicating the TSMD has an invalid output schema.

> **Clarifying note**: This fallback is the sole remaining exception to the per-field error format in `metricInfos`. It uses `{"error": "message"}` directly under the TSMD name (without a field-name wrapper) because no output field names are available. This case is defense-in-depth only — output schema validation (see tsmd-validation spec) prevents TSMDs with invalid schemas from entering evaluation under normal operation.

#### Scenario: Multiple TSMDs merged into single EvalSummary
- **WHEN** a test case has TSMDs "Accuracy" and "RAG Quality" both evaluated
- **THEN** `metricValues` SHALL contain keys for both TSMD names: `{"Accuracy": {...}, "RAG Quality": {...}}`

### Requirement: Output schema field extraction
The system SHALL provide an injectable `OutputSchemaFieldExtractor` component (in `service.domain`) that extracts output field names from a metric's output schema JSON string. This component SHALL be used by both the metric evaluation executor (to resolve field names for transport failure mapping) and the TSMD validation service (to validate output schema structure).
Status: **Implemented**

#### Scenario: Valid output schema with multiple fields
- **WHEN** `extractFieldNames()` is called with an output schema containing `{"properties": {"recall": {...}, "precision": {...}, "f1": {...}}}`
- **THEN** the method SHALL return `["recall", "precision", "f1"]`

#### Scenario: Valid output schema with single field
- **WHEN** `extractFieldNames()` is called with an output schema containing `{"properties": {"exact_match": {...}}}`
- **THEN** the method SHALL return `["exact_match"]`

#### Scenario: Null or blank schema string
- **WHEN** `extractFieldNames()` is called with a null or blank string
- **THEN** the method SHALL return an empty list

#### Scenario: Schema without properties key
- **WHEN** `extractFieldNames()` is called with a JSON string that has no `"properties"` key or where `"properties"` is not an object
- **THEN** the method SHALL return an empty list

#### Scenario: Malformed JSON schema
- **WHEN** `extractFieldNames()` is called with invalid JSON
- **THEN** the method SHALL log a WARN and return an empty list (graceful degradation)

### Requirement: Typed TSMD evaluation result carrier
The system SHALL replace the untyped `Map<String, Object>` (where values are `EvaluationResponseDto | Exception`) with a sealed interface `TsmdEvaluationResult` in `service.domain.job`. Both variants SHALL carry `outputFieldNames` (`List<String>`) extracted from the TSMD's output schema.
Status: **Implemented**

#### Scenario: Sealed interface with two variants
- **WHEN** a TSMD evaluation completes
- **THEN** the result SHALL be represented as either `TsmdEvaluationResult.Success(EvaluationResponseDto response, List<String> outputFieldNames)` or `TsmdEvaluationResult.Failure(Exception error, List<String> outputFieldNames)`

#### Scenario: Output field names extracted before evaluation dispatch
- **WHEN** the metric evaluation executor starts execution
- **THEN** it SHALL extract output field names for each TSMD using `OutputSchemaFieldExtractor` before dispatching async evaluations, and include them in every `TsmdEvaluationResult` (both success and failure)

#### Scenario: MetricOutputMapper consumes typed results
- **WHEN** `MetricOutputMapper.buildMetricValues()` and `buildMetricInfos()` are called
- **THEN** they SHALL accept `Map<String, TsmdEvaluationResult>` and use pattern matching on the sealed type (no `instanceof Object` checks)

#### Scenario: checkForErrors uses typed results
- **WHEN** `checkForErrors()` determines whether any TSMD evaluation failed
- **THEN** it SHALL accept `Map<String, TsmdEvaluationResult>` and check for `Failure` instances or `Success` instances containing error-type metric outputs

#### Scenario: TSMD with empty field names (defense-in-depth)
- **WHEN** a TSMD's output schema yields an empty field name list (should not happen after validation)
- **THEN** the output mapper SHALL produce an empty object `{}` in `metricValues` for that TSMD and record the error only in `metricInfos`

#### Scenario: Timeout fallback produces Failure with field names
- **WHEN** a TSMD evaluation times out and no result was recorded
- **THEN** the executor SHALL record a `Failure` with a `RuntimeException` and the pre-extracted output field names for that TSMD

### Requirement: Per-TSMD metric evaluation latency measurement

`InProcessMetricEvaluationExecutor` SHALL measure the elapsed wall-clock time of each dispatched TSMD's provider evaluation call (the `MetricEvaluationWorker.evaluate(...)` invocation, covering semaphore acquisition, request binding, and any retries/backoff), including calls that fail with a transport/interrupt error or are still in flight when the per-result timeout is reached. For each `TestCaseRunResult`, the executor SHALL compute the sum of these durations across all TSMDs actually dispatched for that result (excluding TSMDs whose `condition` produced a `ConditionError`, since no provider call was made for those), and pass the result as `metricEvalDurationMs` into the `EvalSummaryBatchWriteItemDto` it builds for that result.

Status: **Implemented**

#### Scenario: Successful TSMD calls contribute real latency

- **WHEN** a `TestCaseRunResult` dispatches two TSMDs and both complete successfully with elapsed times of 100ms and 300ms
- **THEN** the executor SHALL compute `metricEvalDurationMs = 400` for that result's `EvalSummaryBatchWriteItemDto`

#### Scenario: Failed TSMD call still contributes its elapsed time

- **WHEN** a dispatched TSMD's provider call fails with a transport error after 500ms of retries
- **THEN** that TSMD's 500ms elapsed time SHALL be included in the sum for that result, the same as a successful call would be

#### Scenario: Timed-out TSMD contributes elapsed time up to the timeout

- **WHEN** a dispatched TSMD's `CompletableFuture` has not completed by the time the per-result `allOf(...).get(timeoutMs)` join times out
- **THEN** the executor SHALL record that TSMD's elapsed time as the time from its dispatch to the timeout detection, and include it in the sum

#### Scenario: Condition-error TSMDs are excluded from the sum

- **WHEN** a TSMD's `condition` evaluates to a `ConditionError` (no provider call is made) while other TSMDs for the same result are dispatched normally
- **THEN** the `ConditionError` TSMD SHALL NOT contribute to the `metricEvalDurationMs` sum; only dispatched (`Success`/`Failure`) TSMDs are summed

#### Scenario: No TSMDs dispatched for a result

- **WHEN** a `TestCaseRunResult` has zero TSMDs dispatched (metric-less run, or a non-SUCCESS result row propagated without evaluation)
- **THEN** `metricEvalDurationMs` SHALL be `0`

### Requirement: EvalSummary assembly from TestCaseRunResult
The system SHALL build one EvalSummary per TestCaseRunResult, copying context fields from the result and adding computed metric values.
Status: **Implemented**

#### Scenario: Field mapping from result to summary
- **WHEN** an EvalSummary is built for a TestCaseRunResult
- **THEN** the batch write envelope SHALL carry `testSuiteId`, `testSuiteRunId`, `computationId`, and `computedAtMs` from the MetricEvaluationContext. Each item SHALL carry: `testCaseRunResultId` = result.id, `testCaseId`, `testCaseName`, `runIndex`, `testCaseData`, `extractedColumns`, `execDurationMs`, `responseStatusCode` from result. The `createdAtMs` is derived by the service from the run's creation timestamp (not set per-item).

#### Scenario: Non-SUCCESS result propagation
- **WHEN** a TestCaseRunResult has `executionStatus != SUCCESS`
- **THEN** the EvalSummary SHALL have `executionStatus` propagated from the result, `metricValues = {}`, `metricInfos = null` — no metric evaluation SHALL be attempted

#### Scenario: Metric error determines executionStatus
- **WHEN** all metrics evaluate successfully (no `type: "error"` outputs)
- **THEN** the EvalSummary SHALL have `executionStatus = SUCCESS`

#### Scenario: Any metric error or transport failure fails the summary
- **WHEN** at least one metric output field has `type: "error"` OR at least one TSMD evaluation fails with a transport error (worker exception)
- **THEN** the EvalSummary SHALL have `executionStatus = FAILED`

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
- **THEN** the executor SHALL flush all accumulated records via the client before returning

#### Scenario: Batch write failure
- **WHEN** a batch write via the service fails
- **THEN** the executor SHALL set the cancellation signal, stop dispatching new evaluations, and log the error; `executor.shutdownNow()` will interrupt in-flight threads immediately via the `finally` block (no grace-period drain — metric evaluation is append-only)

### Requirement: RunMetricSnapshot writing via service-layer client
The `RunMetricSnapshotBatchWriteClient` SHALL convert internal RunMetricSnapshot models to the existing `RunMetricSnapshotBatchWriteRequestDto` and delegate to `RunMetricSnapshotService.batchCreate()`.
Status: **Implemented**

#### Scenario: Snapshot write delegates to existing service
- **WHEN** RunMetricSnapshots are written before metric evaluation starts
- **THEN** the `RunMetricSnapshotBatchWriteClient` SHALL convert models to `RunMetricSnapshotBatchWriteRequestDto` and call `RunMetricSnapshotService.batchCreate()`

### Requirement: RunMetricSnapshot capture before evaluation
`TestSuiteEvaluationJob` SHALL capture RunMetricSnapshots immediately after building the run's `MetricEvaluationContext` (which mints `computationId`/`computedAtMs`) and before Phase 1 execution starts, for every run — inline or not, and on both the `skipDeploymentPhase` and normal branches. It uses the `computationId` and `computedAtMs` from the `MetricEvaluationContext` to batch-write one `RunMetricSnapshot` per TSMD capturing the metric configuration at evaluation time. On the normal branch (`!skipDeploymentPhase`), this write happens only after the run's inconsistent-snapshot guard passes: a run that fails that guard is marked FAILED and returns before `MetricEvaluationContext` is ever built, so it writes no `run_metric_snapshots` row at all. On the `skipDeploymentPhase` branch there is no such guard, so the write always happens once the context is built. On either branch, once `MetricEvaluationContext` is built the write itself is unconditional with respect to TSMD count — it happens even when the run's suite has no enabled+valid TSMDs (writing zero snapshot rows) — snapshot capture is no longer a side effect of `InProcessMetricEvaluationExecutor.execute()`.

This hoist changes behavior a second way beyond moving the write site: previously, `buildMetricEvaluationContext` and the snapshot write both sat inside `executeRunAsync`'s `if (!cancellationSignal.get())` guard around Phase 2, so a run cancelled while Phase 1 was still executing never reached that block and therefore never got a snapshot written at all. After this hoist, the same snapshot write happens unconditionally *before* Phase 1 starts, so a run cancelled mid-Phase-1 SHALL now still have its RunMetricSnapshots written — a behavior change for every run, inline or not, not only for inline runs.
Status: **Implemented**

#### Scenario: Snapshot fields populated from aggregated TSMD
- **WHEN** RunMetricSnapshots are created
- **THEN** each snapshot SHALL contain: `id` (new UUID), `computationId` (shared across all snapshots), `testSuiteRunId`, `tsmdId` = TSMD.id, `tsmdName` = TSMD.name, `metricDeclarationId`, `metricDeclarationVersionId`, `configBindings` = TSMD.configBindings, `inputBindings` = TSMD.inputBindings, `outputSchema` = TSMD.versionOutputSchema, `computedAtMs`

#### Scenario: Snapshots written before evaluation starts
- **WHEN** `TestSuiteEvaluationJob` builds the run's `MetricEvaluationContext`
- **THEN** RunMetricSnapshots SHALL be written to the analytics DB immediately afterward, before Phase 1 dispatches any deployment call and before any metric provider `/evaluate` call is made, on both the `skipDeploymentPhase` and the normal branch

#### Scenario: Snapshot write is not duplicated for an inline run
- **WHEN** a run is inline
- **THEN** RunMetricSnapshots are written exactly once, by `TestSuiteEvaluationJob`, and are not written again by the inline evaluator or by the Phase-2 propagate-only pass

#### Scenario: Cancellation mid-Phase-1 still yields a written snapshot
- **WHEN** a run's cancellation signal is set while Phase 1 is still executing
- **THEN** RunMetricSnapshots SHALL already have been written before the cancellation was observed, because the write happens unconditionally before Phase 1 starts — unlike the prior behavior, where such a run never reached the snapshot write at all

#### Scenario: Run failing the inconsistent-snapshot guard writes no run_metric_snapshots row
- **WHEN** a run on the normal branch (`!skipDeploymentPhase`) fails the inconsistent-snapshot guard
- **THEN** the run is marked FAILED and returns before `MetricEvaluationContext` is ever built, and no `run_metric_snapshots` row is written for that run

### Requirement: MetricProviderClient evaluate method
The `MetricProviderClient` SHALL support calling `POST /evaluate` on metric providers.
Status: **Implemented**

#### Scenario: Successful evaluation call
- **WHEN** `MetricProviderClient.evaluate(providerId, request)` is called
- **THEN** it SHALL use the provider's RestClient (from `MetricProviderRestClientFactory`) to POST to `/evaluate` with the request body and return the parsed `EvaluationResponseDto`

#### Scenario: Provider not configured
- **WHEN** `evaluate()` is called with a providerId that has no configured RestClient
- **THEN** it SHALL throw `IllegalArgumentException`

### Requirement: Cancellation with hard shutdown during metric evaluation

Status: Implemented

The metric evaluation phase SHALL support cancellation via the same `AtomicBoolean` signal used by the deployment evaluation phase. The grace-period drain semantics are removed; Phase 2 uses hard shutdown (immediate thread interrupt) because metric evaluation is append-only and results can be regenerated.

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

Status: Implemented

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

### Requirement: Configurable retry for metric evaluation
The metric evaluation worker SHALL support configurable retry with exponential backoff for `/evaluate` calls.
Status: **Implemented**

#### Scenario: Retry configuration properties
- **WHEN** the application starts
- **THEN** it SHALL read `metric-evaluation.retry.max-retries` (default: 0), `metric-evaluation.retry.retry-delay-ms` (default: 1000), `metric-evaluation.retry.retry-backoff-multiplier` (default: 2.0), `metric-evaluation.retry.max-retry-delay-ms` (default: 60000)

#### Scenario: Retryable conditions
- **WHEN** the `/evaluate` call fails with timeout, HTTP 429, or HTTP 5xx
- **THEN** the worker SHALL retry according to the configured policy

#### Scenario: Non-retryable conditions
- **WHEN** the `/evaluate` call fails with HTTP 4xx (except 429)
- **THEN** the worker SHALL NOT retry

### Requirement: Configuration properties for metric evaluation
The system SHALL expose configurable properties under the `metric-evaluation` prefix.
Status: **Implemented**

#### Scenario: All properties with defaults
- **WHEN** the application starts
- **THEN** it SHALL read: `metric-evaluation.default-concurrency-per-provider` (default: 5), `metric-evaluation.batch-size` (default: 100), `metric-evaluation.per-result-timeout-ms` (default: 150000, configurable via env var `METRIC_EVAL_PER_RESULT_TIMEOUT_MS`), `metric-evaluation.retry.max-retries` (default: 0), `metric-evaluation.retry.retry-delay-ms` (default: 1000), `metric-evaluation.retry.retry-backoff-multiplier` (default: 2.0), `metric-evaluation.retry.max-retry-delay-ms` (default: 60000)

#### Scenario: cancellation-grace-period-ms is removed
- **WHEN** a deployment YAML contains `metric-evaluation.cancellation-grace-period-ms`
- **THEN** Spring Boot SHALL log an unknown-property warning but SHALL NOT fail startup; the property has no effect and operators should migrate to `metric-evaluation.per-result-timeout-ms`

#### Scenario: Per-result timeout configurable via environment variable
- **WHEN** `METRIC_EVAL_PER_RESULT_TIMEOUT_MS` is set in the environment
- **THEN** `metric-evaluation.per-result-timeout-ms` SHALL use that value

### Requirement: End-to-end functional test for full run lifecycle with metric evaluation
A single e2e functional test SHALL verify the complete run lifecycle: deployment evaluation (Phase 1) → metric evaluation (Phase 2), covering success and failure paths within one test flow. The test SHALL live in `TestSuiteRunFunctionalTests` (existing class already has `@MockitoBean` for `DialCoreDeploymentInvoker` and `MetricProviderClient`). Mock setup logic SHALL be extracted into a dedicated helper class to keep the test readable.
Status: **Implemented**

#### Scenario: Full two-phase run with mixed success/failure test cases
- **WHEN** a test suite is configured with 2-3 test cases, TSMDs with bindings, and a mock deployment that returns SUCCESS for some test cases and FAILED/TIMEOUT for at least one test case, and the metric provider `/evaluate` mock returns valid `EvaluationResponse` objects
- **THEN** the run SHALL reach COMPLETED status after both phases execute

#### Scenario: 1:1 correspondence between TestCaseRunResults and EvalSummaries
- **WHEN** the run completes
- **THEN** the number of EvalSummary records SHALL equal the number of TestCaseRunResult records for the run, with each EvalSummary referencing its corresponding TestCaseRunResult via `testCaseRunResultId`

#### Scenario: Non-SUCCESS test case result propagates to EvalSummary
- **WHEN** a TestCaseRunResult has `executionStatus != SUCCESS` (e.g., TIMEOUT or FAILED)
- **THEN** the corresponding EvalSummary SHALL have the same non-SUCCESS `executionStatus`, `metricValues = {}`, and `metricInfos = null`. No `/evaluate` call SHALL have been made for that test case.

#### Scenario: SUCCESS test case result has metric values populated
- **WHEN** a TestCaseRunResult has `executionStatus = SUCCESS` and the metric provider returns valid outputs
- **THEN** the corresponding EvalSummary SHALL have `executionStatus = SUCCESS`, `metricValues` populated with the TSMD name as key and output field names/values as nested map, and `metricInfos` populated for outputs that include details

#### Scenario: RunMetricSnapshots captured correctly
- **WHEN** the run completes
- **THEN** RunMetricSnapshot records SHALL exist for each TSMD, all sharing the same `computationId`, with `tsmdName`, `metricDeclarationId`, `metricDeclarationVersionId`, `configBindings`, `inputBindings`, and `outputSchema` matching the TSMD configuration at evaluation time

#### Scenario: Mock setup isolated in helper class
- **WHEN** the test is implemented
- **THEN** mock configuration for `DialCoreDeploymentInvoker` (deployment responses per test case) and `MetricProviderClient.evaluate()` (metric responses) SHALL be encapsulated in a dedicated helper class (e.g., `MetricEvaluationTestHelper`), keeping the test method focused on assertions

## Implementation Notes
- Executor interface: `com.epam.aidial.evaluation.service.domain.job.MetricEvaluationExecutor`
- In-process executor: `com.epam.aidial.evaluation.service.domain.job.InProcessMetricEvaluationExecutor`
- Worker: `com.epam.aidial.evaluation.service.domain.job.MetricEvaluationWorker`
- Binding resolver: `com.epam.aidial.evaluation.service.domain.job.BindingResolver`
- EvalSummary client: `com.epam.aidial.evaluation.service.domain.job.EvalSummaryBatchWriteClient`
- RunMetricSnapshot client: `com.epam.aidial.evaluation.service.domain.job.RunMetricSnapshotBatchWriteClient`
- Context: `com.epam.aidial.evaluation.service.domain.job.MetricEvaluationContext`
- Client DTOs: `EvaluationRequestDto`, `EvaluationResponseDto`, `MetricOutputFieldDto`, `MetricErrorDto` in `client.metricprovider.dto`
- Config: `com.epam.aidial.evaluation.configuration.properties.MetricEvaluationProperties`
- Inline-mode detection: `com.epam.aidial.evaluation.service.domain.job.InlineModeDetector`
- Inline evaluator: `com.epam.aidial.evaluation.service.domain.job.InlineMetricEvaluatorImpl`, `InlineMetricEvaluatorFactory`, `MetricRowEvaluator`
- Snapshot hoist: `com.epam.aidial.evaluation.service.domain.job.TestSuiteEvaluationJob#buildMetricContextAndWriteSnapshot`
