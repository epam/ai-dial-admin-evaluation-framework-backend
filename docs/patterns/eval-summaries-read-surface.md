# `test_case_eval_summaries` is the single read surface for run results

Clients never branch to `test_case_run_results` depending on whether the suite has metrics, so Phase 2 writes **one eval summary per result row for any TSMD count, including zero**: a metric-less run's rows carry `metric_values = {}` and `metric_infos` JSON `null`, with no `run_metric_snapshots`.

Do **not** reintroduce a zero-TSMD early return in `InProcessMetricEvaluationExecutor` — the existing per-result path degenerates correctly (empty semaphore map never indexed, nothing dispatched, `allOf(empty)` completes immediately).

The duplicated `test_case_data` / `extracted_columns` across the two analytics tables is intentional, not an optimization target.

**Corollary:** an empty eval-summary list does **not** mean "this suite has no metrics" — the honest signals are empty `run_metric_snapshots` for the computation and empty `metric_values` on the rows.
