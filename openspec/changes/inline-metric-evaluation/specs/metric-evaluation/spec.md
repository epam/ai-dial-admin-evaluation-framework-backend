## MODIFIED Requirements

### Requirement: Metric evaluation executor orchestration
`MetricEvaluationExecutor` is an interface; `InProcessMetricEvaluationExecutor` is the in-process implementation (mirroring the `EvaluationExecutor` / `InProcessEvaluationExecutor` pattern for deployment evaluation). `TestSuiteEvaluationJob` SHALL capture RunMetricSnapshots once, before Phase 1 begins, for every run (see "RunMetricSnapshot capture before evaluation") — snapshot capture is no longer the executor's own responsibility. The executor's `execute()` SHALL iterate all `TestCaseRunResult` records for the run using cursor-based pagination, and for each row: on a **non-inline** run, dispatch metric evaluations concurrently per provider for every enabled+valid TSMD, assemble EvalSummary records, and batch-write them to the analytics DB, exactly as before; on an **inline** run (`MetricEvaluationContext.inlineMode = true`), SUCCESS rows SHALL be skipped entirely — their EvalSummary was already written during Phase 1 by the inline evaluator — and only non-SUCCESS rows SHALL be processed, via the same propagate-only path used for every non-SUCCESS row today (see "Phase 2 propagate-only pass for inline runs"). The set of TSMDs loaded into `MetricEvaluationContext` SHALL be limited to those that are both enabled and valid (`is_enabled = true AND is_valid = true`), loaded once when the context is built — a TSMD enabled, disabled, or edited after that point is not picked up by the in-flight run, whether inline or not.

The executor SHALL write one EvalSummary per `TestCaseRunResult` row **regardless of how many TSMDs the context carries, including zero** — `test_case_eval_summaries` is the single surface from which run results are read, so a run whose suite has no enabled+valid TSMDs SHALL still produce readable rows. Such a row SHALL have `metric_values = {}` and no `metric_infos` value at all (the column is nullable and receives JSON `null`, matching what the metric output mapper already produces when it has no metric information to record). `run_metric_snapshots` SHALL receive rows only for the TSMDs actually present, so an empty TSMD list writes no snapshot rows. Consequently the absence of eval summaries SHALL NOT be used to signal "this suite has no metrics"; the signals for that are an empty `run_metric_snapshots` set for the computation and empty `metric_values` on the rows. Under inline mode this one-summary-per-row invariant SHALL still hold across the two writers: the union of inline-written SUCCESS-row summaries and the propagate-only pass's non-SUCCESS-row summaries SHALL cover exactly one EvalSummary per row, identically to non-inline mode.

Status: **Planned**

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

### Requirement: RunMetricSnapshot capture before evaluation
`TestSuiteEvaluationJob` SHALL capture RunMetricSnapshots immediately after building the run's `MetricEvaluationContext` (which mints `computationId`/`computedAtMs`) and before Phase 1 execution starts, for every run — inline or not, and on both the `skipDeploymentPhase` and normal branches. It uses the `computationId` and `computedAtMs` from the `MetricEvaluationContext` to batch-write one `RunMetricSnapshot` per TSMD capturing the metric configuration at evaluation time. On the normal branch (`!skipDeploymentPhase`), this write happens only after the run's inconsistent-snapshot guard passes: a run that fails that guard is marked FAILED and returns before `MetricEvaluationContext` is ever built, so it writes no `run_metric_snapshots` row at all. On the `skipDeploymentPhase` branch there is no such guard, so the write always happens once the context is built. On either branch, once `MetricEvaluationContext` is built the write itself is unconditional with respect to TSMD count — it happens even when the run's suite has no enabled+valid TSMDs (writing zero snapshot rows) — snapshot capture is no longer a side effect of `InProcessMetricEvaluationExecutor.execute()`.

This hoist changes behavior a second way beyond moving the write site: today, `buildMetricEvaluationContext` and the snapshot write both sit inside `executeRunAsync`'s `if (!cancellationSignal.get())` guard around Phase 2 (`TestSuiteEvaluationJob:159-163`), so a run cancelled while Phase 1 is still executing never reaches that block and therefore never gets a snapshot written at all. After this hoist, the same snapshot write happens unconditionally *before* Phase 1 starts, so a run cancelled mid-Phase-1 SHALL now still have its RunMetricSnapshots written — a behavior change for every run, inline or not, not only for inline runs.

Status: **Planned**

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
- **THEN** RunMetricSnapshots SHALL already have been written before the cancellation was observed, because the write happens unconditionally before Phase 1 starts — unlike today, where such a run never reaches the snapshot write at all

#### Scenario: Run failing the inconsistent-snapshot guard writes no run_metric_snapshots row
- **WHEN** a run on the normal branch (`!skipDeploymentPhase`) fails the inconsistent-snapshot guard
- **THEN** the run is marked FAILED and returns before `MetricEvaluationContext` is ever built, and no `run_metric_snapshots` row is written for that run

### Requirement: Conditional gating integrated into per-result evaluation
For each result row, the executor SHALL build a condition context (`data`, `response`, `turn.index/total/last`) and evaluate each metric's `condition` before dispatch: skip-and-omit on false, dispatch on true, and record a metric-level error (without failing the row) on a broken condition. Only metrics actually dispatched are considered when recording per-metric timeout/failure. Under inline metric evaluation, a broken condition additionally aborts the request chain — see `conditional-metric-execution`'s "Broken condition aborts the chain under inline evaluation" scenario for the mode-scoped divergence from the non-inline behavior described here.

Status: **Implemented**

#### Scenario: Skipped metric is not dispatched
- **WHEN** a metric's condition is false for a turn row
- **THEN** the metric is not dispatched and contributes no value, and the row's status stays SUCCESS

#### Scenario: Condition error does not mark the row failed
- **WHEN** a metric's condition errors for a turn row
- **THEN** a `metricError::<name>` entry is recorded, the metric is not dispatched, and the row stays SUCCESS

### Requirement: Binding resolution
The `BindingResolver` SHALL resolve TSMD config and input bindings against test case data and extracted columns from a `TestCaseRunResult`, producing `Map<String, Object>` for config and input. Resolution SHALL fail fast when a binding references a column that does not exist in the data map. For an `Expression` source, resolution SHALL additionally accept a per-row frame `{data, response, _metrics}` (`data` = the row's test case data, `response` = the row's extracted columns, `_metrics` = the run's accumulated metrics frame for an inline run, or an empty map for Phase 2's propagate-only pass and for any non-inline evaluation) and evaluate the binding's JSONata `expression` against that frame; see `tsmd-validation` for the source's shape and failure semantics.

Status: **Planned**

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

### Requirement: Provider-bounded concurrency
Each metric provider SHALL have its own semaphore controlling the maximum number of concurrent `/evaluate` calls. The semaphore limit SHALL be configurable. For an inline run, `InlineMetricEvaluatorFactory` SHALL construct the same per-provider semaphores once at run start, sized from the same `metric-evaluation.default-concurrency-per-provider` property, and reuse them for every inline `evaluate()` call made during Phase 1 — so this property bounds inline provider-call concurrency during Phase 1 exactly as it bounds Phase 2's dispatch, using one shared dial rather than two.

Status: **Planned**

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

## ADDED Requirements

### Requirement: Inline metric evaluation mode is derived per run
A run's inline mode SHALL be derived, never configured. `InlineModeDetector` SHALL compute it from the run's `SuiteSnapshotDto` (the frozen config Phase 1 executes) plus the live enabled+valid TSMD list (what Phase 2 would otherwise evaluate) — never from the live suite, since a suite edited between run creation and dispatch must not change an in-flight run's mode. A run is inline iff `"$_metrics"` appears as a substring in any request-template JSON body serialization (`content` or `jsonataContent`, on the root template or any `additionalRequests[i].requestTemplate`) or in any enabled+valid TSMD's `configBindings`/`inputBindings` raw JSON string. A `suiteType = MCP_TOOL` run SHALL always be non-inline, since an MCP suite has no request chain for a metric output to feed. A false-positive match (the substring appears but is unreachable, e.g. inside an unused branch) SHALL still be treated as inline — it costs an inline-mode code path with no behavioral difference, so no JSONata AST walk is performed to rule it out. `InlineModeDetector` SHALL run only when `!skipDeploymentPhase`: a run dispatched with `skipDeploymentPhase = true` (the `importResultsAndEvaluate` import path, which has no Phase 1 to be inline about) SHALL be forced non-inline unconditionally, without invoking the detector at all, so Phase 2 evaluates every SUCCESS row exactly as it does for every other non-inline run.

Status: **Planned**

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

Status: **Planned**

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

Status: **Planned**

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

Status: **Planned**

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

Status: **Planned**

#### Scenario: Phase 3 sees the complete inline-written set
- **WHEN** Phase 3 (`MetricScoreComputationExecutor`) runs structured queries over eval summaries for an inline run
- **THEN** it SHALL see every SUCCESS row's inline-written summary, because the final flush completed before Phase 3 started

#### Scenario: Cancellation still flushes what was produced
- **WHEN** an inline run is cancelled mid-Phase-1
- **THEN** the inline evaluator's buffer SHALL still be flushed, so every summary produced before cancellation is persisted

### Requirement: TSMD set for a run is loaded once, before Phase 1
The TSMD list loaded into a run's `MetricEvaluationContext` SHALL be fixed at context-construction time, before Phase 1 dispatches any work, for every run — inline or not. A TSMD enabled, disabled, or edited after that point SHALL NOT be picked up by the in-flight run.

Status: **Planned**

#### Scenario: Late TSMD edit is not picked up
- **WHEN** a TSMD is enabled, disabled, or its bindings edited after a run's `MetricEvaluationContext` has been built
- **THEN** the in-flight run SHALL continue to use the TSMD set as it existed at context-construction time, for both its inline and non-inline (propagate-only) evaluation

### Requirement: Known orphan EvalSummary cases (documented, not fixed)
Two pre-existing-shaped edge cases SHALL be documented as accepted, not remediated by this change: (a) when an exception escapes `TurnLoopExecutor`'s own try block inside `EvaluationWorker`'s chain-level catch, already-produced chain rows for that test case are discarded — any EvalSummary the inline evaluator already wrote for those rows becomes an orphan (a summary with no persisted row); (b) when `PostgresResultBatchWriter.doFlush` fails after inline EvalSummaries for that row batch have already been committed, the row batch is lost while its summaries remain — the same orphan shape. Both are rare, of the same character as pre-existing single-writer failure modes, and are explicitly accepted rather than fixed.

Status: **Planned**

#### Scenario: Chain-level exception orphans inline summaries
- **WHEN** an exception escapes `TurnLoopExecutor`'s own try block during an inline run and `EvaluationWorker` discards the chain's rows for that test case
- **THEN** any EvalSummary the inline evaluator already wrote for those discarded rows SHALL remain in the analytics DB with no corresponding `TestCaseRunResult` row — accepted, not remediated

#### Scenario: Batch-flush failure after summaries are committed orphans them symmetrically
- **WHEN** `PostgresResultBatchWriter.doFlush` fails for a row batch whose rows' inline EvalSummaries were already committed
- **THEN** those summaries SHALL remain persisted with no corresponding row batch — accepted, not remediated
