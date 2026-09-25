# `test_case_eval_summaries` is the single read surface for run results

Clients never branch to `test_case_run_results` depending on whether the suite has metrics, so Phase 2 writes **one eval summary per result row for any TSMD count, including zero**: a metric-less run's rows carry `metric_values = {}` and `metric_infos` JSON `null`, with no `run_metric_snapshots` (a **meta** table since meta V1.32, unlike the eval-summary rows themselves).

Do **not** reintroduce a zero-TSMD early return in `InProcessMetricEvaluationExecutor` — the existing per-result path degenerates correctly (empty semaphore map never indexed, nothing dispatched, `allOf(empty)` completes immediately).

The duplicated `test_case_data` / `extracted_columns` across the two analytics tables is intentional, not an optimization target.

**Corollary:** an empty eval-summary list does **not** mean "this suite has no metrics" — the honest signals are empty `run_metric_snapshots` for the computation (read from meta) and empty `metric_values` on the rows.

## `score`/`passed` are a sibling table, joined in — not a follow-up `UPDATE`

Each row's `score`/`passed` live in a separate table, `test_case_eval_scores` (see `docs/database-schema.md`), not as columns on `test_case_eval_summaries` itself. `InProcessMetricEvaluationExecutor` writes them in a **second batch write immediately after** each flush's `test_case_eval_summaries` insert — one extra SQL query per flush batch (`EvalSummaryRowScoreComputer`, reusing `OverallScoreDefinitionResolver`'s output with an `id IN (:rowIds)` + `GROUP BY id` graft), not a follow-up `UPDATE` on the same row and not one query per row. `PostgresEvalSummaryRepository` LEFT JOINs `test_case_eval_scores` back in on every read tier (list/export/detail), so `score`/`passed` appear on the same `EvalSummaryResponseDto`/`EvalSummaryDetailResponseDto` a client already reads.

The generic Query DSL's `eval_summaries` entity (`PostgresEvalSummaryEntityResolver`, see `docs/patterns/query-dsl-entity-resolution.md`) joins the same sibling table too, so `score`/`passed` are also filterable/groupable/selectable via `POST /api/v1/queries/execute` — e.g. `group_by: ["passed"]` to count pass/fail within a run, or selecting `score` alongside `test_case_name` in row mode. This is a separate join from the one above (a narrowed derived-table projection, not the raw generated table — see that doc for why), but the same underlying data and the same null semantics.

A metric-less run, a suite with no *effective* per-row definition configured (`testCaseOverallScore` if set, else `overallScore` — see `docs/patterns/overall-score-definition.md`), or a `CustomFunction` whose aggregate is itself degenerate for a single row (e.g. `roc_auc`) all still write one `test_case_eval_summaries` row per result — the corresponding `test_case_eval_scores` row is simply absent (or present with `score = NULL`), which a LEFT JOIN surfaces as `score = null, passed = null`, the same "honest null, not a missing row" pattern as `metric_values = {}` above.

## `execution_status` on `test_case_eval_scores` is its own aggregate, not a copy

`test_case_eval_scores.execution_status` is **not** a denormalized copy of the owning
`test_case_eval_summaries.execution_status`. It is a per-test-case, per-computation aggregate — `FAILED`
if *any* of that test case's rows (every `run_index`/`request_index`/`turn_index` combination) has
`execution_status <> SUCCESS`, else `SUCCESS` — computed by `TestCaseExecutionStatusAggregator`
(`query.service.metricscore`), a query directly against `test_case_eval_summaries`, deliberately kept
separate from `TestCaseMetricScoreAggregator`/`test_case_metric_scores_aggregated`. That aggregator's
result set is driven by `jsonb_each(metric_values)`, so a row with `metric_values = '{}'` (every metric
condition-skipped, or the row failed before any metric ran) never appears in it; `TestCaseExecutionStatusAggregator`
scans the base table directly, so it sees every row regardless of whether a numeric metric fired.

A metric execution failure (a provider-reported per-field error, or a transport/timeout `Failure`) already
flips its own row's `execution_status` to `FAILED` (`InProcessMetricEvaluationExecutor.checkForErrors`), so
this aggregate correctly folds both metric failures and upstream execution failures into one `FAILED`
signal. A `condition`-skipped metric (`ConditionDecision.isSkip()`) never changes a row's own
`execution_status`, so it has no effect on the aggregate.

When a test case's aggregate is `FAILED`, `score`/`passed` are written as `NULL` and the per-test-case
score SQL query is never issued for that test case at all — "don't compute a score from partially-failed
data." This is also why a `FAILED` test case can now get a `test_case_eval_scores` row with `score = NULL`
even when it has *zero* numeric metric samples (e.g. an upstream `TIMEOUT` before any metric ran):
previously such a test case would have been silently absent from the table (filtered out because it had no
computed score); now a `FAILED` aggregate unconditionally forces a row to exist. A `SUCCESS`-aggregate test
case with zero numeric samples (e.g. every metric condition-skipped) is unaffected by this and stays absent
from the table, exactly as before — only `FAILED` is the deliberate exception to "row exists iff a score was
computable."

