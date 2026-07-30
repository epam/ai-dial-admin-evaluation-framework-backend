## MODIFIED Requirements

### Requirement: Metric evaluation executor orchestration
`MetricEvaluationExecutor` is an interface; `InProcessMetricEvaluationExecutor` is the in-process implementation (mirroring the `EvaluationExecutor` / `InProcessEvaluationExecutor` pattern for deployment evaluation). The implementation SHALL capture RunMetricSnapshots, iterate all `TestCaseRunResult` records for the run using cursor-based pagination, dispatch metric evaluations concurrently per provider, assemble EvalSummary records, and batch-write them to the analytics DB. The set of TSMDs loaded into `MetricEvaluationContext` SHALL be limited to those that are both enabled and valid (`is_enabled = true AND is_valid = true`).

The executor SHALL write one EvalSummary per `TestCaseRunResult` row **regardless of how many TSMDs the context carries, including zero** — `test_case_eval_summaries` is the single surface from which run results are read, so a run whose suite has no enabled+valid TSMDs SHALL still produce readable rows. Such a row SHALL have `metric_values = {}` and no `metric_infos` value at all (the column is nullable and receives JSON `null`, matching what the metric output mapper already produces when it has no metric information to record). `run_metric_snapshots` SHALL receive rows only for the TSMDs actually present, so an empty TSMD list writes no snapshot rows. Consequently the absence of eval summaries SHALL NOT be used to signal "this suite has no metrics"; the signals for that are an empty `run_metric_snapshots` set for the computation and empty `metric_values` on the rows.
Status: **Planned**

#### Scenario: Successful metric evaluation for all test cases
- **WHEN** the metric evaluation phase starts for a completed run with TSMDs configured
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

## Implementation notes

- `com.epam.aidial.evaluation.service.domain.job.InProcessMetricEvaluationExecutor` — the empty-TSMD early return is removed; the existing per-result path is reused unchanged for the metric-less case (no dispatch, `buildPropagatedItem` still handles non-SUCCESS rows).
- `com.epam.aidial.evaluation.service.domain.job.MetricOutputMapper` — unchanged. `buildMetricValues` already returns an empty object for an empty TSMD map, and `buildMetricInfos` already returns `null` when it has no metric information to record; the nullable `metric_infos` column (V1.5) accepts that directly, so no write-path change is needed.
- `com.epam.aidial.evaluation.service.domain.job.RunMetricSnapshotBatchWriteClient` — already no-ops on an empty snapshot list; no change.
- `com.epam.aidial.evaluation.service.domain.analytics.EvalSummaryService` — `validateMetricValues` already accepts an empty JSON object; no change.
