# `test_case_eval_summaries` is the single read surface for run results

Clients never branch to `test_case_run_results` depending on whether the suite has metrics, so Phase 2 writes **one eval summary per result row for any TSMD count, including zero**: a metric-less run's rows carry `metric_values = {}` and `metric_infos` JSON `null`, with no `run_metric_snapshots`.

Do **not** reintroduce a zero-TSMD early return in `InProcessMetricEvaluationExecutor` — the existing per-result path degenerates correctly (empty semaphore map never indexed, nothing dispatched, `allOf(empty)` completes immediately).

The duplicated `test_case_data` / `extracted_columns` across the two analytics tables is intentional, not an optimization target.

**Corollary:** an empty eval-summary list does **not** mean "this suite has no metrics" — the honest signals are empty `run_metric_snapshots` for the computation and empty `metric_values` on the rows.

## `score`/`passed` are a sibling table, joined in — not a follow-up `UPDATE`

Each row's `score`/`passed` live in a separate table, `test_case_eval_scores` (see `docs/database-schema.md`), not as columns on `test_case_eval_summaries` itself. `InProcessMetricEvaluationExecutor` writes them in a **second batch write immediately after** each flush's `test_case_eval_summaries` insert — one extra SQL query per flush batch (`EvalSummaryRowScoreComputer`, reusing `OverallScoreDefinitionResolver`'s output with an `id IN (:rowIds)` + `GROUP BY id` graft), not a follow-up `UPDATE` on the same row and not one query per row. `PostgresEvalSummaryRepository` LEFT JOINs `test_case_eval_scores` back in on every read tier (list/export/detail), so `score`/`passed` appear on the same `EvalSummaryResponseDto`/`EvalSummaryDetailResponseDto` a client already reads.

The generic Query DSL's `eval_summaries` entity (`PostgresEvalSummaryEntityResolver`, see `docs/patterns/query-dsl-entity-resolution.md`) joins the same sibling table too, so `score`/`passed` are also filterable/groupable/selectable via `POST /api/v1/queries/execute` — e.g. `group_by: ["passed"]` to count pass/fail within a run, or selecting `score` alongside `test_case_name` in row mode. This is a separate join from the one above (a narrowed derived-table projection, not the raw generated table — see that doc for why), but the same underlying data and the same null semantics.

A metric-less run, a suite with no `overallScore` definition configured, or a `CustomFunction` whose aggregate is itself degenerate for a single row (e.g. `roc_auc`) all still write one `test_case_eval_summaries` row per result — the corresponding `test_case_eval_scores` row is simply absent (or present with `score = NULL`), which a LEFT JOIN surfaces as `score = null, passed = null`, the same "honest null, not a missing row" pattern as `metric_values = {}` above.
