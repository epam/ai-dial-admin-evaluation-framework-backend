# Typed `OverallScoreDefinition` for suite `overallScore`

A suite's run-level `overall` metric-score definition (`TestSuiteRequestDto`/`TestSuiteResponseDto`/`SuiteSnapshotDto`.`overallScore`) is a sealed, JSON-discriminated model in `com.epam.aidial.evaluation.runner.dto` (`evaluation-runner-core` module, shared with `eval-cli`), not a raw `Map<String, Object>`:

- `Mean` — no params
- `WeightedMean` — a `List<WeightedMetric>` of `{metricName, outputField, weight}`
- `CustomFunction` — the prior free-form raw `StructuredQuery` expression Map, unchanged escape hatch

`query.service.metricscore.OverallScoreDefinitionResolver` (a plain same-package collaborator of `MetricScoreComputationExecutor`) turns the typed definition into a `StructuredQuery` at Phase-3 computation time:

- `Mean` resolves against the run's **currently discovered** numeric metric fields (not anything persisted on the definition).
- `WeightedMean` composes directly from its stored list (not cross-validated against the suite's configured TSMDs at write time — permissive; a missing metric's `avg` resolves to SQL `NULL` but is coalesced to `0` for that term via the `coalesce` DSL function, so it does not null the whole `overall` result).
- `CustomFunction` converts its Map via `objectMapper.convertValue(..., StructuredQuery.class)` (catch `JacksonException`, not `IllegalArgumentException`, on malformed input — log + skip).

`MetricScoreComputationContext.overallScoreDefinition` carries the typed value directly (no JSON-string round trip between the suite snapshot and Phase 3).

## Per-row `score` reuses the same resolved-query machinery — usually the same definition, optionally a different one

The same `OverallScoreDefinitionResolver` also drives a second computation: a per-row `score`/`passed` on each `EvalSummary`, written to a sibling table `test_case_eval_scores` and joined back into the eval-summary read surface (see `docs/patterns/eval-summaries-read-surface.md`, `docs/database-schema.md`). This is **not** a second implementation — `EvalSummaryRowScoreComputer` (`query.service.metricscore`, a sibling of `OverallScoreDefinitionResolver`/`FilteredMetricScoreAggregator`) takes whatever `OverallScoreDefinition` it's given, resolves it the same way Phase 3 does, and grafts an `id IN (:rowIds)` filter plus a `GROUP BY id` onto the resulting `StructuredQuery` — turning a run-level aggregate into one row per id, in one query per Phase-2 flush batch. `Mean`/`WeightedMean`/`CustomFunction` are all attempted uniformly per row this way, whichever definition is fed in.

**Which definition is fed in can now differ between the two scopes.** A suite has two independent optional fields: `overallScore` (always drives Phase 3's run-level `metric_score_result.overall`, unconditionally) and `testCaseOverallScore` (drives Phase 2's per-row scoring *instead of* `overallScore`, when configured — falling back to `overallScore` when absent, which is the common case and preserves the "one definition, two scopes" behavior described above). The fallback is resolved once, in `TestSuiteEvaluationJob.buildMetricEvaluationContext` (`snapshot.getTestCaseOverallScore() != null ? ... : snapshot.getOverallScore()`), before `MetricEvaluationContext` is built — `EvalSummaryRowScoreComputer` itself has no notion of "which suite field this came from," it just resolves whatever `OverallScoreDefinition` its caller passes.

| | Phase 3 `overall` (`MetricScoreComputationExecutor`) | Phase 2 per-row `score` (`EvalSummaryRowScoreComputer`) |
|---|---|---|
| Scope | One aggregate per `(run, computation)`, no `GROUP BY` | One value per `EvalSummary` row, `GROUP BY id` grafted on |
| Definition used | Always `overallScore` | `testCaseOverallScore` if configured, else `overallScore` |
| Written to | `metric_score_result` | `test_case_eval_scores` (joined into `EvalSummary.score`/`.passed` on read) |
| Timing | After all `EvalSummary` rows for the computation exist | Right after each flush's own batch is written (not after the whole run) |

**Why grafting `id`/`GROUP BY id` is safe for any resolved query, with zero query-builder changes**: `StructuredQueryBuilder.buildAggregate`/`resolveGroupKey` already handles a select column that's also a `GROUP BY` key by referencing the select's own output alias rather than re-translating the expression — the exact mechanism a per-row `id` column needs, whether the query came from `Mean`/`WeightedMean` or an opaque `CustomFunction`.

**Where the reuse still hits a real limit**: a `CustomFunction` that's inherently population-dependent (e.g. `roc_auc`, which needs an `array_agg` of *many* rows' labels/probabilities to rank against each other) doesn't get a meaningful per-row value just because the query executes without error — grouped by `id`, its `array_agg` has exactly one element, so `roc_auc_score`'s `NULLIF(n_pos * n_neg, 0)` is `NULL` for every row (see `docs/database-schema.md`'s `roc_auc_score` section). This is a genuine mathematical boundary, not a plumbing gap: no amount of query-graft cleverness gives a population statistic a single-row meaning. `EvalSummaryRowScoreComputer.requireGroupableShape` additionally rejects (logs, doesn't execute) a `CustomFunction` that already specifies its own `groupBy` — rather than silently overwriting it — since such a query's author had something else in mind.

See also: [Query DSL function catalog](query-dsl-function-catalog.md).
