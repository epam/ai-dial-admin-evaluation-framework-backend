# Typed `OverallScoreDefinition` for suite `overallScore`

A suite's run-level `overall` metric-score definition (`TestSuiteRequestDto`/`TestSuiteResponseDto`/`SuiteSnapshotDto`.`overallScore`) is a sealed, JSON-discriminated model in `com.epam.aidial.evaluation.runner.dto` (`evaluation-runner-core` module, shared with `eval-cli`), not a raw `Map<String, Object>`:

- `Mean` — no params
- `WeightedMean` — a `List<WeightedMetric>` of `{metricName, outputField, weight}`
- `CustomFunction` — the prior free-form raw `StructuredQuery` expression Map, unchanged escape hatch

`query.service.metricscore.OverallScoreDefinitionResolver` (a plain same-package collaborator of `MetricScoreComputationExecutor`) turns the typed definition into a `StructuredQuery` at Phase-3 computation time:

- `Mean` resolves against the run's **currently discovered** numeric metric fields (not anything persisted on the definition).
- **Aggregation leaf (`metricScoreAggregation`)** — a suite property (`AVG` default, `MIN`, `MAX`; captured in the run's suite snapshot) picks which per-test-case leaf of `test_case_metric_scores.metric_scores` is read (`metric_scores::<tsmd>.<field>::<leaf>`, built by `MetricField.aggregatedFieldName`). It applies to `Mean`/`WeightedMean`, the per-test-case score, the built-in per-metric statistics (AVG/P10/P90/MIN/MAX now run over `test_case_metric_scores` — one value per test case — instead of raw `eval_summaries`), the single-metric default overall, and run comparison (each run uses its own snapshot value). `CustomFunction` still runs over `eval_summaries` and ignores it.
- `WeightedMean` composes directly from its stored list (not cross-validated against the suite's configured TSMDs at write time — permissive; a missing metric's `avg` resolves to SQL `NULL` but is coalesced to `0` for that term via the `coalesce` DSL function, so it does not null the whole `overall` result).
- `CustomFunction` converts its Map via `objectMapper.convertValue(..., StructuredQuery.class)` (catch `JacksonException`, not `IllegalArgumentException`, on malformed input — log + skip).

`MetricScoreComputationContext.overallScoreDefinition` carries the typed value directly (no JSON-string round trip between the suite snapshot and Phase 3).

## Per-test-case `score` reuses the same resolved-query machinery — only `Mean`/`WeightedMean`; `CustomFunction` rejected

The same `OverallScoreDefinitionResolver` also drives a second computation: a per-test-case `score`/`passed` on each test case's `EvalSummary` rows, written to a sibling table `test_case_eval_scores` and joined back into the eval-summary read surface (see `docs/patterns/eval-summaries-read-surface.md`, `docs/database-schema.md`). This is **not** a second implementation — `TestCaseScoreComputer` (`query.service.metricscore`, a sibling of `OverallScoreDefinitionResolver`/`FilteredMetricScoreAggregator`) takes the `OverallScoreDefinition` it's given (which must be `Mean`/`WeightedMean`, never `CustomFunction`), resolves it the same way Phase 3 does, and grafts a `test_case_id IN (:testCaseIds)` filter plus a `GROUP BY test_case_id` onto the resulting `StructuredQuery` — turning a run-level aggregate into one value per test case, in one query per chunk of test cases (computed once after the last Phase-2 flush, in chunks of the batch size).

**Why the graft sets an explicit page instead of reusing the resolved query's own `page`**: the run-level aggregate is a single row, so `OverallScoreDefinitionResolver` never sets `page` on the query it builds — it's `null`. `StructuredQueryBuilder.applyPage` defaults a `null` page to a 100-row limit with no `ORDER BY`, so naively passing that `null` through the graft would silently cap the per-test-case result at an arbitrary 100 rows once grouped by `test_case_id`. `TestCaseScoreComputer.groupByTestCaseId` instead sets `new OffsetPage(0, chunkSize, false)` sized to the actual test-case-id batch (chunked to `MAX_LIMIT` so the translator's own clamp never kicks in either), so every test case in the batch gets a result.

**Which definition is fed in can now differ between the two scopes.** A suite has two independent optional fields: `overallScore` (always drives Phase 3's run-level `metric_score_result.overall`, unconditionally) and `testCaseOverallScore` (drives Phase 2's per-row scoring *instead of* `overallScore`, when configured — falling back to `overallScore` when absent, which is the common case and preserves the "one definition, two scopes" behavior described above). The fallback is resolved once, in `TestSuiteEvaluationJob.buildMetricEvaluationContext` (`snapshot.getTestCaseOverallScore() != null ? ... : snapshot.getOverallScore()`), before `MetricEvaluationContext` is built — `TestCaseScoreComputer` itself has no notion of "which suite field this came from," it just resolves whatever `OverallScoreDefinition` its caller passes.

| | Phase 3 `overall` (`MetricScoreComputationExecutor`) | Phase 2 per-test-case `score` (`TestCaseScoreComputer`) |
|---|---|---|
| Scope | One aggregate per `(run, computation)`, no `GROUP BY` | One value per test case, `GROUP BY test_case_id` grafted on |
| Definition used | Always `overallScore` | `testCaseOverallScore` (if configured, must be `Mean`/`WeightedMean`) or `overallScore` (if `testCaseOverallScore` absent, must not be `CustomFunction`) |
| Accepted types | `Mean`, `WeightedMean`, `CustomFunction` | `Mean`, `WeightedMean` only; `CustomFunction` rejected at write time with HTTP 400 |
| Written to | `metric_score_result` | `test_case_eval_scores` (joined into `EvalSummary.score`/`.passed` on read) |
| Timing | After all `EvalSummary` rows for the computation exist | Once per test case, in chunks, after the last flush has written all `EvalSummary` rows (in `finally`, so also after an early loop exit) |
| Precondition | None beyond `overallScore` being configured | Additionally gated per test case on `execution_status` — see below |

**Per-row scoring is additionally gated on a per-test-case `execution_status` aggregate.** Before invoking
`TestCaseScoreComputer`, `InProcessMetricEvaluationExecutor` computes each batch test case's
aggregated `execution_status` (`TestCaseExecutionStatusAggregator` — `FAILED` if any of that test case's
rows failed, else `SUCCESS`; see `docs/patterns/eval-summaries-read-surface.md`) and issues the score query
only for `SUCCESS`-aggregate test cases. A `FAILED`-aggregate test case's `score`/`passed` are written as
`NULL` without ever reaching this resolved-query machinery — this precondition is orthogonal to which
`OverallScoreDefinition` variant is in play (`Mean`/`WeightedMean`/`CustomFunction` are all skipped alike).

**Why grafting `id`/`GROUP BY id` is safe for any resolved query, with zero query-builder changes**: `StructuredQueryBuilder.buildAggregate`/`resolveGroupKey` already handles a select column that's also a `GROUP BY` key by referencing the select's own output alias rather than re-translating the expression — the exact mechanism a per-row `id` column needs, whether the query came from `Mean`/`WeightedMean` or an opaque `CustomFunction`.

**Where the type restriction comes from**: a `CustomFunction` that's inherently population-dependent (e.g. `roc_auc`, which needs an `array_agg` of *many* rows' labels/probabilities to rank against each other) doesn't get a meaningful per-test-case value even if the query executes without error — grouped by `test_case_id`, its `array_agg` would have exactly one element, so `roc_auc_score`'s `NULLIF(n_pos * n_neg, 0)` would be `NULL` for every test case. Rather than silently accept and degrade, the system rejects `CustomFunction` for `testCaseOverallScore` at write time (HTTP 400 `ValidationException`) — see the `test-suites` spec. `Mean`/`WeightedMean`, by design, always have a meaningful per-test-case interpretation: the average of (one average per metric per test case). If a suite needs a population-dependent `overall` for Phase 3 while keeping Phase 2 per-test-case scores well-defined, it should set `overallScore` to the `CustomFunction` and `testCaseOverallScore` to a `Mean`/`WeightedMean`.

**Future consideration**: if a future use case arises where a suite wants to compute a per-test-case value from multiple test cases' data (e.g. "this test case's score relative to the median"), that would require a different mechanism (nested subqueries, or a dedicated query builder for test-case-scoped population statistics), beyond the current single-definition model.

See also: [Query DSL function catalog](query-dsl-function-catalog.md).
