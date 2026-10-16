# Computation Versioning (No `is_latest` flag)

Entities versioned by `computation_id` (UUID) use append-only writes — the analytics result tables, and the meta `run_metric_snapshots` table that captures each computation's metric catalog. "Latest" is resolved at query time (`ORDER BY computed_at_ms DESC LIMIT 1`); API callers pass `computation=<uuid>` or `computation=latest` (or omit).

**Resolution reads `test_case_eval_summaries`, not `run_metric_snapshots`** — `ComputationResolver.resolve` delegates to `EvalSummaryRepository.findLatestComputationId` (backed by `idx_eval_summaries_run_computed_at`), so "latest" means "latest computation with readable rows" and a metric-less run still resolves; do not re-point it at the snapshot table. Since meta V1.32 that table is in the **meta** database, so re-pointing it would also turn a single-datasource read into a cross-database one.

The one legitimate snapshot-based lookup is `EvalSummariesSchemaProvider`, which asks a *metric-catalog* question ("which `metric::*` families does this computation have?"), not a has-results question. It reads the meta snapshot table while the surrounding schema work reads analytics — see [dual-datasource.md](dual-datasource.md) on keeping such a read out of the other datasource's transaction.

Export decides explicit-`computation` existence the same way, via `EvalSummaryRepository.existsByRunIdAndComputationId`.

## One system-wide same-millisecond tiebreak: `computed_at_ms DESC, computation_id ASC`

One `Clock` read per computation makes two computations of the same run share `computed_at_ms`, leaving `ORDER BY computed_at_ms DESC LIMIT 1` ambiguous. Three lookups resolve "latest" this way, and until this convergence they disagreed: `PostgresEvalSummaryRepository.findLatestComputationId` had **no tiebreak** at all (de-facto returning the *smallest* id when the index served it); `PostgresRunMetricSnapshotRepository.findLatestComputationId` and the inline latest-computation subquery inside the `test_suite_runs` query entity's `metric_names` field (see [`test_suite_runs` query entity](test-suite-runs-query-entity.md)) both took `computation_id DESC` — the *largest* id. The divergence was invisible while the two families never shared a response; the `enrich-test-suite-runs-overall-score` change puts a derived, eval-summary-based value next to `metric_names` (the snapshot path) in one `test_suite_runs` row, where a tied run would otherwise show one computation's value beside another computation's metric catalog.

All three — `PostgresEvalSummaryRepository.findLatestComputationId`, `PostgresRunMetricSnapshotRepository.findLatestComputationId`, and the `metric_names` subquery — now order `computed_at_ms DESC, computation_id ASC`: the **smallest** id wins a tie, everywhere. `computation_id` is a random UUID, so neither direction is semantically preferable; `ASC` was chosen because `idx_eval_summaries_run_computed_at` is `(test_suite_run_id, computed_at_ms DESC, computation_id)` — ascending on the third column — so the eval-summary path (the hottest of the three: list, count, aggregate, export, preview and the `"latest"` sentinel all route through it) gets the tiebreak for free in index order, while `DESC` would force an incremental sort over the whole latest-`computed_at_ms` group. The batch form of the eval-summary lookup, `EvalSummaryRepository.findLatestComputationIds(Collection<UUID>)` — `unnest(ids) CROSS JOIN LATERAL (… ORDER BY computed_at_ms DESC, computation_id ASC LIMIT 1)` in `PostgresEvalSummaryRepository`, exposed as `ComputationResolver.resolveLatest(Collection<UUID>)` — resolves a whole page's runs in one statement and follows this same order, for the same index-served reason.

**No index changed.** `idx_run_metric_snapshots_run_computed_at` stays `(test_suite_run_id, computed_at_ms DESC, computation_id DESC)`; a btree backward scan reverses every column, so the snapshot path's now-mixed `DESC, ASC` order is not index-served and takes a small incremental sort. That tie group is one run's TSMD catalog at one millisecond — single digits to low tens of rows — so the cost is accepted rather than re-pointing the index; see design D7 of `enrich-test-suite-runs-overall-score` if that stops holding.

This is a real behavior change, not a formalization of prior behavior, on every `ComputationResolver` call path (`EvalSummaryService`, `EvalSummaryExportService`, `MetricScoreLatestComputationDefaulter`, `RunComparisonService`) whenever the planner does not use the index.

## SQL-side "latest" in the Query DSL (`LatestComputationSupport`)

A fourth place resolves "latest", this time inside a SQL statement. `computation_id eq "latest"` on `test_case_eval_scores`, `metric_score_results` and `test_case_metric_scores` is rewritten (after the single-run literal path in `MetricScoreLatestComputationDefaulter`, which stays first and keeps a pinned run sargable) into `is_latest_computation eq true`, a binding-only BOOLEAN field defined as

```
<entity>.computation_id = (SELECT l.computation_id FROM test_case_eval_summaries l
                            WHERE l.test_suite_run_id = <entity>.test_suite_run_id
                            ORDER BY l.computed_at_ms DESC, l.computation_id ASC LIMIT 1)
```

This lets a run set produced by a subquery ("the last N runs of a suite") resolve each run's own latest computation, which a pre-SQL rewrite cannot see. The order is the same `computed_at_ms DESC, computation_id ASC` as the three lookups above, so the same-millisecond tie goes to the smallest id here too. `LatestComputationSupportTest` pins the rendered ordering and `TestCaseEvalScoresStructuredQueryFunctionalTests` pins the tie.

- **Correlated against `test_case_eval_summaries`, not the entity's own table**, to keep the definition "latest computation with readable rows". A run whose newest computation has no rows for the entity contributes no rows; it does not fall back to an older computation.
- **A query-time expression, not a stored flag.** An `is_latest` column would break the append-only rule above and need a migration plus backfill.
- **No `RunDeletionFilter` inside the lookup.** The entity's `baseCondition()` already hides a deleted run, and runs are deleted whole.
- **Not a table column.** The binding is absent from default projections and from the published schema; it is an unpublished internal alias.
- **Only `eq`** is rewritten; `ne` / `in` with `"latest"` still fail, and a `"latest"` inside a nested subquery's own filter is not rewritten (the pipeline rewrites the top-level query only).
- A pinned run with no eval summaries now returns no rows instead of a 400.

### EXPLAIN evidence

Dashboard shape on seeded Testcontainers Postgres: `test_case_eval_scores` filtered by `test_suite_run_id IN (last 5 runs of a suite from eval_summaries) AND computation_id = latest`, grouped by run/status/passed. Data: 60 runs, 10 in the target suite with 3 computations each, 120 test cases per computation (about 10k summary rows, 5.4k score rows). The SQL was written by hand to mirror the generated statement, so the plan shape is representative but not byte-identical.

- The correlated lookup is an **Index Only Scan on `idx_eval_summaries_run_computed_at`** with `Limit 1`: 1800 probes at 0.002 ms each, 5400 shared buffer hits.
- Probes run once per candidate score row of the selected runs (1800 = 5 runs x 360 rows), so the cost is bounded by the run predicate, not by table size. The subplan is a filter on the bitmap scan of `uq_test_case_eval_scores_natural_key`.
- The tombstone check is an index-only probe on `run_deletions_pkey`.
- Total execution 7.7 ms. Heap fetches were 1800 because the fresh test data has no visibility map yet; a vacuumed table would avoid them.

### `eval_summaries` is not opted in

Decision: `eval_summaries` does not use `LatestComputationSupport`. It is the largest table and the lookup would self-correlate against it. The dashboard needs only the score entity, and the eval-summary read paths already resolve "latest" through `ComputationResolver`. The probe is cheap (see above), so this can change on demand with the same three-line opt-in plus a functional test.
