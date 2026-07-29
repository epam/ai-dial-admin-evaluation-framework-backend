## Context

A test suite run executes in three phases (`TestSuiteEvaluationJob`): Phase 1 writes `test_case_run_results` (responses + `extracted_columns`), Phase 2 (`InProcessMetricEvaluationExecutor`) writes `run_metric_snapshots` + `test_case_eval_summaries`, Phase 3 computes `metric_score_results`. Phase 1 is metric-independent and already works. Phase 2 returns early when the run's enabled+valid TSMD list is empty (`InProcessMetricEvaluationExecutor.execute`, the `getAggregatedTsmds().isEmpty()` guard), so a metric-less run writes no eval summaries.

Fixing only the write path is not enough. Every eval-summary read resolves the run's computation through `ComputationResolver`, which asks `RunMetricSnapshotRepository.findLatestComputationId(runId)` — a table that stays legitimately empty for a metric-less run. Consequences today: `EvalSummaryService.listByFilter` short-circuits to an empty page, `countByFilter` to `0`, and `EvalSummaryExportService.resolveContext` throws `EntityNotFoundException` (HTTP 404). So the read path must stop treating "has metric snapshots" as a proxy for "has results".

Why it reads the snapshot table at all is documented in the archived `2026-03-12-metrics-storage` design (D3): purely because `run_metric_snapshots` is a tiny dimension table (~one row per TSMD per computation, ~1-5 rows scanned) while `test_case_eval_summaries` is the fact table, so the top-1 lookup was free without a dedicated index. It was never a semantic choice — the same document (`:131`) explicitly tolerated the two tables disagreeing: *"the 'latest computation' resolution may point to a computation with snapshots but no eval summary data — the list endpoint returns an empty page (acceptable)"*.

Constraint that shapes the whole design (per proposal): `test_case_eval_summaries` is the **single read surface** for run results. Clients never branch to `test_case_run_results`, so the duplicated `test_case_data` / `extracted_columns` in eval summaries is intentional and not to be optimized away.

Both tables live on the analytics datasource, so no new transaction manager or cross-datasource concern is introduced. Callers already open an `analyticsTransactionManager` scope before invoking `ComputationResolver`.

## Goals / Non-Goals

**Goals:**
- A run over a suite with zero enabled+valid TSMDs writes one eval summary per `TestCaseRunResult` row, with empty `metric_values` / `metric_infos` and all non-metric fields intact.
- `GET /api/v1/analytics/eval-summaries`, `/count`, `/aggregate`, `/export.csv`, `/export/preview` all return that run's data, for `computation` omitted, `latest`, and an explicit UUID.
- Zero behavior change for runs that do have metrics — same rows, same computation resolution, same columns.
- No schema-breaking or API-contract change; no new configuration property.

**Non-Goals:**
- Any "metric-less" flag on suite, run, or snapshot. The condition is emergent: zero enabled+valid TSMDs.
- Phase 3 metric scores (already skips cleanly when no numeric metric fields are discovered).
- Run-creation guards / suite validity (both already metric-independent).
- Deduplicating `test_case_data` / `extracted_columns` between the two analytics tables.
- Any change to `MetricEvaluationWorker`, `BindingResolver`, `MetricOutputMapper`, or `ConditionExpressionEvaluator`.

## Decisions

### D1. Delete the early return and let the existing per-result path degenerate — no separate metric-less branch

`InProcessMetricEvaluationExecutor.execute` drops the `getAggregatedTsmds().isEmpty()` early return (keeping an informational log line: metric-less run, N results). Everything downstream already behaves correctly with an empty TSMD list, verified against the current code:

- `writeRunMetricSnapshots` → `RunMetricSnapshotBatchWriteClient.batchWrite` already returns on an empty list, so no snapshot rows and no empty-batch DTO validation failure.
- `buildProviderSemaphores` → empty map, never indexed (nothing is dispatched).
- `evaluateAndBuild` per SUCCESS row → the condition loop and the dispatch loop iterate zero TSMDs (no JSONata evaluation), `CompletableFuture.allOf(new CompletableFuture[0]).get(timeout)` completes immediately, the timeout-reconciliation loop is empty, `checkForErrors` → `false`, `buildMetricValues`/`buildMetricInfos` → `{}` → a SUCCESS summary carrying `test_case_data`, `extracted_columns`, `extraction_warnings`, turn columns, timings.
- Non-SUCCESS rows keep taking `buildPropagatedItem`, so FAILED/ERROR statuses still propagate.
- Batching/flush, cursor pagination, and the cancellation check are unchanged.
- `EvalSummaryService.batchCreate`'s `validateMetricValues({})` passes (empty object is a valid object).

**Alternative considered:** a dedicated metric-less loop (`buildPropagatedItem` for every row, skipping `evaluateAndBuild`). Rejected: it duplicates row assembly and would silently drift from `buildItem` as columns are added — the exact class of bug that produced this gap. The degenerate path costs one empty `allOf` per row.

**Alternative considered:** keeping the early return and having Phase 1 write eval summaries directly. Rejected: it splits ownership of `test_case_eval_summaries` across two phases and would double-write on re-evaluation/import runs.

### D2. Resolve "latest computation" from `test_case_eval_summaries` only

`ComputationResolver.resolve(computation, runId)` keeps its signature, and its `latest` branch switches from `RunMetricSnapshotRepository.findLatestComputationId` to a new `EvalSummaryRepository.findLatestComputationId(runId)`. Not a fallback — a replacement. The snapshot dependency is dropped from the resolver entirely. `Optional.empty()` now means "this run has no eval summaries", which is exactly the question all six call sites are asking.

Rationale: every consumer of the resolver reads eval summaries (list, count, aggregate, export, export preview) or metric scores keyed on the same `computationId` (`MetricScoreLatestComputationDefaulter`) — none reads snapshots. Resolving against the table being read makes "latest" mean "latest computation that has readable rows", removes the two-source divergence the original design tolerated, and leaves one code path instead of a primary plus a fallback. When metrics exist the answer is unchanged, because Phase 2 writes snapshots and summaries under one `computationId` and Phase 3 reuses it.

The cost argument that motivated the original choice is neutralised by D3's index: both become a top-1 index probe.

**Behavior delta for existing metric-bearing runs** (accepted): a computation whose snapshot write landed but whose summary flush did not is no longer selectable as `latest` — the previous complete computation is returned instead of an empty page. Strictly better than the documented status quo, but it is a change and the `metrics-storage` delta spec states it.

**Alternative considered:** snapshots first, eval summaries as fallback. Rejected once the archived rationale was checked: it preserves a divergence that the original design called merely "acceptable", keeps two sources of truth for one question, and adds a branch whose second arm is only ever exercised by metric-less runs — i.e. the least-tested path would carry the new capability.

**Kept, deliberately:** `RunMetricSnapshotRepository.findLatestComputationId` stays. `EvalSummariesSchemaProvider.latestComputationMetricSnapshots` uses it to enumerate the run's metric column families for Query DSL schema discovery — a metric-catalog question where the snapshot table is the right source, and one that already returns `List.of()` for a metric-less run (so schema discovery correctly advertises `data:*` / `response:*` fields and no `metric:*` families). Unrelated pre-existing dead code (`RunMetricSnapshotService.findLatestComputationId`, no callers) is out of scope.

**Alternative considered:** insert a synthetic placeholder row into `run_metric_snapshots`. Rejected outright: `tsmd_id`, `tsmd_name`, `metric_declaration_id`, `metric_declaration_version_id` are all `NOT NULL` (V1.6), the unique index is `(computation_id, tsmd_id)`, and such a row would poison `EvalSummaryExportColumnPlanner` (a phantom `metric:*` column block) and Phase-3 metric-field discovery.

**Alternative considered:** widen `resolve` to take `runCreatedAtMs` for partition pruning. Rejected: `test_case_eval_summaries` is not actually partitioned (V1.5 declares only a `(created_at_ms, id)` primary key), and widening the signature would churn all six call sites for no benefit.

Side effect, accepted: `MetricScoreLatestComputationDefaulter` (Query DSL `metric_score_results`, `computation_id eq "latest"`) will now resolve a real UUID for a metric-less run instead of leaving the literal sentinel in the filter. Both yield zero rows — there are no metric scores — but the rewritten form is the more honest one.

### D3. New repository method on the analytics eval-summary repository, plus a covering index

`EvalSummaryRepository` gains `Optional<UUID> findLatestComputationId(UUID runId)`, implemented in `PostgresEvalSummaryRepository` with the typed jOOQ DSL (`select(COMPUTATION_ID) … where(TEST_SUITE_RUN_ID.eq(...)) … orderBy(COMPUTED_AT_MS.desc()).limit(1)`), mirroring `PostgresRunMetricSnapshotRepository.findLatestComputationId` line for line so the two read the same way.

This is now the sole resolution query on a request path, so it must not degrade into a scan. The existing `idx_eval_summaries_run_computation (test_suite_run_id, computation_id)` cannot serve `ORDER BY computed_at_ms DESC LIMIT 1`: Postgres would walk every row of the run and heap-fetch `computed_at_ms` for each, and a run's row count is `test cases × runs × turns × computations` — easily six figures. So this change adds a Flyway migration whose index both orders and covers:

```
src/main/resources/db/migration/analytics/POSTGRES/V1.15__AddEvalSummariesRunComputedAtIndex.sql
CREATE INDEX idx_eval_summaries_run_computed_at
    ON test_case_eval_summaries (test_suite_run_id, computed_at_ms DESC, computation_id);
```

`computation_id` is the third key column rather than an `INCLUDE` payload so the plan is an index-only top-1 read on every Postgres version the project supports, without depending on visibility-map state for an `INCLUDE` fetch. Ordering `(run, computed_at DESC)` also leaves the index usable for any future "computations of this run, newest first" listing.

Follow-on obligations (project rules): run `./gradlew generateJooq` and commit the regenerated sources under `src/main/java-generated/` (index changes surface in the generated `Indexes` class and `JooqSchemaDriftTest` guards the drift), and update `docs/database-schema.md`. No column change, no data backfill, no `docs/configuration.md` change.

**Alternative considered:** rely on the existing index plus a sort. Rejected on the read-amplification argument above — and more firmly than under the fallback design, since with D2 there is no cheap dimension-table path left to absorb the common case.

### D4. Export decides not-found by eval summaries, not by metric snapshots

`EvalSummaryExportService.resolveContext` currently throws `EntityNotFoundException` when an explicit `computation=<uuid>` yields no `RunMetricSnapshot` rows. That guard is re-pointed: for an explicit UUID, existence is established by the presence of eval summaries for `(runId, computationId)`; `metricSnapshots` being empty is then a legitimate metric-less export and simply produces a metric-free column manifest. `EvalSummaryExportColumnPlanner.plan(snapshot, [])` already handles this — it emits identity, timestamp, execution, `data:<field>` (from the suite snapshot's `testCaseSchema`) and `response:<column>` (from `responseColumns`) descriptors and skips the whole per-metric block, so no `metric:*` / `metricInfo:*` / `metricError:*` columns and no planner change.

Existence is checked with an existing count-style read on the analytics repository rather than a new bespoke query where one already serves; the specific method is an implementation detail for the tasks phase, but it must not fetch rows to decide presence.

Unchanged: the `MAX_EXPORT_COLUMNS` cap, the run-must-be-terminal guard, suite-snapshot resolution and version check, CSV streaming/pagination.

### D5. No new components, packages, or configuration

Every touched class already exists and already has the right layer and responsibility: the executor orchestrates (service.domain.job), `ComputationResolver` resolves (service.domain.analytics), the repository does SQL (data.db.analytics.repository). No parser/validator/converter is introduced, so the "specialized injectable component" rule has nothing to bite on here. No new property, so `docs/configuration.md` is untouched.

## Risks / Trade-offs

- **Consumers inferring "suite has no metrics" from an empty eval-summary list** → the proposal marks this BREAKING at spec level; the honest signals are an empty `run_metric_snapshots` for the computation or empty `metric_values` on the rows. Called out in the `metric-evaluation` delta spec.
- **Storage growth: metric-less runs now duplicate `test_case_data` / `extracted_columns` into a second analytics table** → accepted by explicit product decision (single read surface); no mitigation attempted, and JSONB TOAST compression plus the existing selective-column-projection tiers keep bulk reads off the large columns.
- **Resolution moves from a ~5-row dimension table to the fact table** → `V1.15` makes it an index-only top-1 probe, so the plan is a single index descent either way; `listByFilter` / `countByFilter` still short-circuit (empty page / `0`) when the run has no summaries at all. Regression guard: assert the resolution query's plan/latency does not depend on the run's row count in a functional test over a multi-computation run.
- **`latest` no longer selects a computation whose summaries failed to write** → intended (see D2); the effect is that such a run shows its last complete computation instead of an empty page. Anything that needs the metric-side view of a computation (schema discovery, export columns) still goes through `run_metric_snapshots`, unchanged.
- **Runs cancelled mid-Phase-2 with no metrics** → the cancellation check sits at the top of the page loop and inside the per-row loop and is untouched, so a cancelled metric-less run flushes what it buffered and stops, exactly like a metric-bearing one.
- **`generateJooq` regeneration drift** → the migration is index-only; if the regenerated diff touches anything beyond the `Indexes` class, that is a signal to stop and investigate rather than commit.
- **Ordering dependency inside Phase 2** → with `latest` anchored on summaries, a computation only becomes visible once its first batch flushes; the executor already writes snapshots before the first flush, so the metric-side view is never the lagging one. No new ordering constraint, but the tasks phase must not reorder those two writes.

## Migration Plan

1. Ship the Flyway migration (index-only, `CREATE INDEX` on an append-only table; brief write lock, no rewrite) + regenerated jOOQ sources. This must land **before or with** the resolver switch, since after D2 every `latest` resolution depends on that index.
2. Ship the executor and resolver changes together. Order does not matter functionally — the write fix alone is invisible to clients, the read fix alone is a no-op until summaries exist — but shipping both in one deployment avoids a window where the API still reports zero rows for a metric-less run.
3. Rollback: revert the code; the index can stay (harmless) or be dropped separately. Reverting the resolver restores snapshot-based resolution, under which metric-less runs go back to reporting no rows — the summaries written meanwhile stay valid, append-only, and reachable via an explicit `computation=<uuid>`. No data repair, no cleanup script.
4. No backfill: previously executed metric-less runs stay empty. Re-running (or re-importing results for) such a run produces summaries under a new `computation_id`.

## Open Questions

None blocking. Two implementation details are deliberately left to the tasks phase: the exact existence-check method used by D4's export guard, and whether the metric-less log line in the executor sits at `info` or `debug`.
