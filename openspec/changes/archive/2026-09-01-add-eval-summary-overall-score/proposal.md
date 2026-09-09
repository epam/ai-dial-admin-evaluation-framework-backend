## Why

Today a suite's `overallScore` definition (`Mean`/`WeightedMean`/`CustomFunction`) and `overallScoreThreshold` only ever produce one aggregate number **per run** (Phase 3, `MetricScoreComputationExecutor` → `metric_score_result`), and the threshold is a purely live suite property the client compares itself against — there is no per-test-case pass/fail signal, and nothing ties a threshold value to the specific run it was evaluated against. Consumers (grid UI, exports, CI-style gating) need to know, for each individual test case run result, whether it scored above or below the suite's bar, using the threshold **as configured when that run executed** — not whatever the suite's threshold happens to be today.

## What Changes

- Freeze `TestSuite.overallScoreThreshold` into `SuiteSnapshotDto` at run-start time (alongside the already-snapshotted `overallScore`), reversing an earlier explicit decision that kept it live-only (`test-suites` spec, "Not included in `SuiteSnapshotDto`... SHALL NOT perform any comparison").
- Compute a per-row `score` (Double) and `passed` (Boolean) for every `EvalSummary` by **reusing the existing Phase-3 SQL machinery**: `OverallScoreDefinitionResolver` already turns a suite's `overallScore` definition into a `StructuredQuery`; a new `EvalSummaryRowScoreComputer` grafts an `id IN (:rowIds)` filter and a `GROUP BY id` onto that same resolved query, turning the run-level aggregate into one value per row, in **one SQL query per Phase-2 flush batch** (not per row). This is issued right after each batch's `test_case_eval_summaries` insert — a second write, not a follow-up `UPDATE` on the same row.
- Results are written to a **new table**, `test_case_eval_scores`, keyed by `eval_summary_id`, and LEFT-JOINed back into the existing `EvalSummaryResponseDto`/`EvalSummaryDetailResponseDto` read surface — not a new endpoint.
- All three definition types (`Mean`/`WeightedMean`/`CustomFunction`) are attempted uniformly, since the mechanism is generic SQL reuse, not a hand-written Java evaluator. A `CustomFunction` that is inherently population-dependent (e.g. `roc_auc`, which needs many rows' labels/probabilities ranked against each other) still cannot produce a meaningful single-row value — grouped by `id`, its aggregate degenerates to SQL `NULL` for every row, which the reused `roc_auc_score` function already does natively (`NULLIF(n_pos * n_neg, 0)`), requiring no special-case code.
- `passed = score >= threshold` (inclusive). Either operand being `null` makes `passed` `null`.
- Filtering/sorting by `score`/`passed` via the eval-summaries list API is explicitly deferred to a follow-up change.

## Capabilities

### New Capabilities
- `eval-summary-scoring`: Per-row overall score and pass/fail, computed by reusing the resolved `OverallScoreDefinition` query with a per-row `GROUP BY id` graft (`EvalSummaryRowScoreComputer`), covering `Mean`/`WeightedMean`/`CustomFunction` uniformly; population-dependent functions degrade to a null score per row rather than failing; `score >= threshold` comparison.

### Modified Capabilities
- `suite-run-snapshot`: `SuiteSnapshotDto`/`SuiteSnapshotBuilder` gain `overallScoreThreshold`, captured at snapshot time from the live `TestSuite`.
- `metrics-storage`: new `test_case_eval_scores` table; `EvalSummary` model gains `score`/`passed`, populated via a LEFT JOIN in `PostgresEvalSummaryRepository`, not native columns on `test_case_eval_summaries`; read/export DTOs expose them unchanged.
- `metric-evaluation`: `MetricEvaluationContext` carries `overallScoreDefinition`/`overallScoreThreshold` from the snapshot; `InProcessMetricEvaluationExecutor` discovers the run's metric field names once, threads a client-generated `id` through each `EvalSummaryBatchWriteItemDto` so it's known before the batch insert, and issues one `EvalSummaryRowScoreComputer` query plus one `test_case_eval_scores` batch write per flush.
- `test-suites`: Amend the requirement stating the threshold is "not included in `SuiteSnapshotDto`" and "the system SHALL NOT perform any comparison" — the threshold is now snapshotted, and a per-row (not run-level `overall`) comparison is now performed server-side.

## Impact

- **Schema**: new migration `src/main/resources/db/migration/analytics/POSTGRES/V1.19__CreateTestCaseEvalScoresTable.sql` (new table, does not touch `test_case_eval_summaries`); regenerate jOOQ sources (`./gradlew generateJooq`).
- **Code**: new `EvalSummaryRowScoreComputer` (`query.service.metricscore`, sibling of `OverallScoreDefinitionResolver`/`FilteredMetricScoreAggregator`), new `EvalSummaryScore` model / `EvalSummaryScoreRepository` + `PostgresEvalSummaryScoreRepository` / `EvalSummaryScoreService` / `EvalSummaryScoreBatchWriteItemDto`; changes to `SuiteSnapshotDto`, `SuiteSnapshotBuilder`, `MetricEvaluationContext`, `TestSuiteEvaluationJob`, `InProcessMetricEvaluationExecutor`, `EvalSummaryBatchWriteItemDto` (gains `id`), `EvalSummaryMapper`, `EvalSummaryRecordMapper`, `PostgresEvalSummaryRepository` (LEFT JOIN added to all four query builders), `EvalSummaryResponseDto`/`EvalSummaryDetailResponseDto` (`@Schema` text only).
- **API**: eval-summary read/list/export responses gain `score`/`passed` (nullable, `NON_NULL`-omitted when absent) — additive, non-breaking.
- **Docs**: `docs/database-schema.md` (new table + migration-history entry, `overall_score_threshold` column description updated to reflect snapshot capture), `docs/patterns/eval-summaries-read-surface.md`, `docs/patterns/overall-score-definition.md` (per-row score as query reuse, not a second implementation), `docs/key-packages.md`.
- **No changes** to Phase 3 (`MetricScoreComputationExecutor`, `metric_score_result`, `OverallScoreDefinitionResolver` itself) — the per-row mechanism reuses its output, it does not modify it.
