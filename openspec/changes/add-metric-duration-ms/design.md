## Context

`test_case_eval_summaries` records `exec_duration_ms` — the elapsed time of the deployment call that produced the row — but nothing about Phase 2, where each row is scored by one or more remote metric providers. Phase 2 is the dominant cost of most runs: `InProcessMetricEvaluationExecutor` iterates result rows via cursor pagination and, per row, dispatches every applicable TSMD onto a virtual-thread executor bounded by a per-provider `Semaphore`, then joins with `perResultTimeoutMs`. All of that time is currently invisible outside application logs.

Constraints shaping the design:

- Analytics writes are append-only, batched, and go through `EvalSummaryService.batchCreate` under `@Transactional("analyticsTransactionManager")`. The row is written **after** evaluation completes, so a duration measured during evaluation can simply ride along on the batch-write item — no second write, no schema for partial state.
- Production code may not call `System.currentTimeMillis()`/`Instant.now()`; time comes from an injected `java.time.Clock`. `InProcessMetricEvaluationExecutor` does not currently have one.
- The eval-summaries table is read through four distinct jOOQ projections (list, `findById`, export, export-with-bodies) plus a `RecordMapper` with three map methods — the column has to be added to each to be visible, by design of the selective-column-projection pattern.
- The experimental query DSL derives both its schema and its field bindings from the generated jOOQ table, so a new column becomes queryable with no DSL-side code.

## Goals / Non-Goals

**Goals:**

- Persist, per eval summary row, the wall-clock milliseconds spent evaluating that row's metrics, comparable to `exec_duration_ms` as an elapsed-time figure.
- Surface it where users already read execution timing: list and detail responses, the CSV export, and the query DSL.
- Keep "evaluation never ran" distinguishable from "evaluation took ~0 ms".
- Zero behavioural change to metric evaluation itself: no extra calls, no altered concurrency, no new failure mode.

**Non-Goals:**

- **Per-metric attribution.** No `metric_durations` JSONB, no `metricDuration::<name>` export family. Which provider is slow is answerable by comparing runs/suites, and adding it later is additive.
- **Run-level aggregates.** No stored `avgMetricDurationMs` on the run or in `run_metric_snapshots`; `avg`/percentiles over the column via the query DSL cover it.
- **Legacy list filtering.** No `FilterWhitelists.EVAL_SUMMARIES` entry (explicit product decision — the experimental DSL is the filtering surface for this field).
- **Timing the same thing on `test_case_run_results`.** Metric timing belongs to the summary row.
- Backfilling historical rows.

## Decisions

### 1. One nullable BIGINT column, `metric_duration_ms`, on `test_case_eval_summaries`

Added by `V1.15__AddMetricDurationToEvalSummaries.sql` (analytics; current head is `V1.14`), with no `DEFAULT` and no index, followed by `./gradlew generateJooq` and a committed generated diff (guarded by `JooqSchemaDriftTest`).

*Nullable, not `NOT NULL DEFAULT 0`* — unlike its neighbour `exec_duration_ms`. Rows that never entered metric evaluation (non-SUCCESS rows propagated by `buildPropagatedItem`) and every row written before the migration must not read as "scored in 0 ms", or `avg(metric_duration_ms)` over a run silently deflates by the error rate. `0` stays meaningful: evaluation ran and finished within a millisecond (e.g. every metric condition-skipped).

*Alternative considered — reuse `metric_infos` JSONB.* Avoids a migration, but a duration is a scalar dimension, not per-metric detail: it would be unsortable, un-aggregatable without JSONB extraction, and would pollute a structure whose shape is contractual for the `metricInfo::` export family.

*Alternative considered — no index.* Deliberate: analytics reads are always already constrained by `computation_id` + `created_at_ms`, so a standalone index on a duration column would only pay for itself for cross-run scans we don't offer.

### 2. Measure per-row wall-clock inside `evaluateAndBuild`, not per provider call

`clock.millis()` is taken as the first statement of `InProcessMetricEvaluationExecutor.evaluateAndBuild` and again after the `CompletableFuture.allOf(...).get(perResultTimeoutMs)` join **and** the timeout/missing-TSMD reconciliation loop. The window therefore includes JSONata condition evaluation, async dispatch, provider-semaphore waiting, and — on timeout — the full `perResultTimeoutMs`, which is the honest cost that row imposed on the run.

*Alternative considered — sum of per-TSMD `worker.evaluate(...)` durations.* Rejected: metrics for a row run concurrently, so the sum exceeds elapsed time and is not comparable to `exec_duration_ms`; it also requires threading a duration through `TsmdEvaluationResult`'s three variants and every `MetricOutputMapper` branch, for a number that answers a per-metric question we explicitly deferred.

*Alternative considered — first-call-start to last-call-end, excluding semaphore wait.* Rejected: it hides exactly the queueing that `default-concurrency-per-provider` tuning is about, and has no defined value when a metric is never dispatched.

*Consequence to document, not fix:* `exec_duration_ms` on a single-request row covers only the **final** HTTP attempt (`EvaluationWorker.executeWithRetry` propagates the last attempt's duration; earlier attempts live in `log_details`), whereas `metric_duration_ms` covers the whole per-row evaluation including provider retries. `exec + metric` is therefore not a row total, and `docs/database-schema.md` says so.

### 3. `Clock` injected into the executor; no new component

Measurement is two `clock.millis()` calls and a subtraction at the point where the row's evaluation is already orchestrated. Extracting a "timer" component would add an indirection with no independent behaviour to test — the project's rule about injectable components targets conversion/validation logic, of which there is none here. The change to the executor is: one constructor dependency, two locals, and a `Long metricDurationMs` parameter on `buildItem`; `buildPropagatedItem` passes `null`.

*Cancellation* needs no handling: the executor already `break`s out of the row loop before `buildItem`, so a cancelled row produces no summary item at all.

*Error handling* is unchanged. The duration is computed from the clock after the join returns, on every path — timeout, `ExecutionException`, interruption — so no `catch` block can leave it unset, and it can never itself throw or fail a row. It is metadata about a completed evaluation, so the fail-fast-vs-degrade question does not arise.

### 4. Optional on the write contract, symmetric on the read contract

`EvalSummaryBatchWriteItemDto` gains `Long metricDurationMs` **without** `@NotNull` (`execDurationMs` has one). The batch-write endpoint is public; making the field required would break existing callers, and a client that legitimately has no metric-timing to report should be able to omit it and land a `NULL`. `EvalSummaryMapper.toEntity` is a multi-source method, so it needs an explicit `@Mapping(source = "item.metricDurationMs", target = "metricDurationMs")`; `toDto`/`toDetailDto` are single-source and map by name with no edit.

On the read side the field appears in both response DTOs, in all four repository projections and the insert, and as a CSV column directly after `execDurationMs` — placed by parity so the two durations read together, accepting that `responseStatusCode` shifts one column right. Header-name-based consumers are unaffected, and the re-import path cannot break: `EvalResultsCsvParser` parses a different, run-result-shaped CSV (reserved columns `startedAt`/`completedAt`, no `execDurationMs`) and routes unknown headers to a no-op `default -> {}`.

### 5. Query DSL access comes for free; legacy filtering is deliberately absent

`EvalSummariesSchemaProvider.baseSchema` and `PostgresEvalSummaryEntityResolver.bindings` are both built from `JooqTableSchemaResolver.…(TEST_CASE_EVAL_SUMMARIES)`, so after regeneration `metric_duration_ms` is selectable, filterable and aggregatable in `POST /api/v1/queries/execute` with no code change. The legacy `filter=metricDurationMs,…` list param stays a 400 unknown-field error, so the two surfaces diverge intentionally — the spec states it as a requirement so it is not "fixed" later by accident.

## Risks / Trade-offs

- **Wall-clock absorbs provider queueing** → a row can read slow because the provider semaphore was saturated, not because its metrics are slow. Mitigation: name and document the semantics in the spec and `docs/database-schema.md`; per-metric attribution remains an additive follow-up.
- **Two duration columns with different retry semantics invite `exec + metric = total` arithmetic** → Mitigation: documented explicitly in `docs/database-schema.md` next to both columns.
- **CSV column insertion shifts `responseStatusCode`** → Mitigation: verified no positional consumer exists in-repo (import binds by header name and ignores unknown headers); the export spec fixes the column order, so the change is stated there rather than discovered.
- **Nullable column means every read surface must tolerate `NULL`** → Mitigation: `Long` (not `long`) throughout model, DTOs and export accessor — the export planner's `plain(...)` descriptors already render `null` as an empty cell.
- **New `Clock` dependency changes the executor's constructor** → Mitigation: `ClockConfiguration` already exposes the bean; a functional test that boots the context covers the wiring, and `InProcessMetricEvaluationExecutorTest` needs a ticking clock stub because `Clock.fixed` would make every measured duration `0`.
