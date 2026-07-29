## Why

A run result row records how long the *deployment call* took (`exec_duration_ms`) but nothing about how long *metric evaluation* took for that row. Metric providers are remote calls that dominate the wall-clock of Phase 2, and today the only way to reason about them is application logs — they are invisible in the eval summaries grid, the CSV export, and the query DSL. Users tuning a suite (dropping a slow judge metric, sizing `default-concurrency-per-provider`, or explaining why a run took 40 minutes) cannot attribute time to scoring versus execution.

## What Changes

- **New analytics column** `test_case_eval_summaries.metric_duration_ms` (BIGINT, **nullable**) holding the wall-clock milliseconds spent evaluating all of that row's metrics. Flyway migration `V1.15__AddMetricDurationToEvalSummaries.sql` (analytics DB; `V1.14` is current head), followed by `./gradlew generateJooq` and a committed generated-source diff. No index, no default — pre-existing rows read as `NULL`.
- **Measured as per-row wall-clock** in `InProcessMetricEvaluationExecutor.evaluateAndBuild`: from before condition evaluation through the `CompletableFuture.allOf(...).get(perResultTimeoutMs)` join and the timeout/missing-TSMD reconciliation. It therefore includes JSONata condition evaluation, provider-semaphore waiting, and the timeout itself, and is **not** the sum of provider call durations (metrics run concurrently). This makes it directly comparable to `exec_duration_ms` as elapsed time.
- **Null semantics**: `NULL` = metric evaluation never ran for this row — rows propagated by `buildPropagatedItem` (non-SUCCESS execution status) and rows written before the migration. `0` is reserved for "evaluation ran and finished within a millisecond" (e.g. every metric condition-skipped). Cancelled rows are unaffected: the executor already `break`s before building an item, so no row is written.
- **Row granularity is unchanged**: one value per eval summary row, i.e. per turn for multi-turn cases and per request for multi-request chains. There is no per-metric breakdown and no run-level aggregate — per-metric attribution and run averages are deliberately left to the query DSL (`avg`, percentiles) rather than new stored fields.
- **Read surfaces**: `EvalSummaryResponseDto` and `EvalSummaryDetailResponseDto` gain `metricDurationMs`; the CSV export gains a `metricDurationMs` column immediately after `execDurationMs`; `EvalSummaryBatchWriteItemDto` gains an **optional** `Long metricDurationMs` (no `@NotNull`, unlike `execDurationMs`) — a backward-compatible addition to `POST /api/v1/analytics/eval-summaries/batch`.
- **Deliberately NOT filterable via the legacy list filter**: no `FilterWhitelists.EVAL_SUMMARIES` entry, so `filter=metricDurationMs,…` stays a 400 unknown-field error. Slow-row hunting goes through the experimental query DSL, which picks the column up for free — both `EvalSummariesSchemaProvider.baseSchema` and `PostgresEvalSummaryEntityResolver.bindings` are derived from `JooqTableSchemaResolver.…(TEST_CASE_EVAL_SUMMARIES)`.
- No new configuration properties, so `docs/configuration.md` is untouched. `docs/database-schema.md` must be updated with the new column.
- No breaking changes. All new fields are additive and nullable.

## Capabilities

### New Capabilities

None. This extends existing capabilities rather than introducing a new one.

### Modified Capabilities

- `metrics-storage`: the `test_case_eval_summaries` column list gains `metric_duration_ms` (BIGINT, nullable); the batch-write contract gains an optional per-item `metricDurationMs`; the eval-summaries filter-field set is explicitly stated to **exclude** it.
- `metric-evaluation`: a new requirement that the executor measures per-row metric-evaluation wall-clock using the injected `Clock` and carries it on each batch-write item, including the `NULL`-for-propagated-rows and timeout-value rules.
- `eval-summary-export`: the fixed identity/execution column order (currently `… executionStatus, execDurationMs, responseStatusCode`) gains `metricDurationMs` after `execDurationMs`; it is a camelCase, non-family-separated column like its neighbours.

## Impact

**Schema / generated code**
- `src/main/resources/db/migration/analytics/POSTGRES/V1.15__AddMetricDurationToEvalSummaries.sql`
- `src/main/java-generated/…/data/db/jooq/analytics/**` (regenerated; `JooqSchemaDriftTest` guards this)
- `docs/database-schema.md`

**Write path**
- `service/domain/job/InProcessMetricEvaluationExecutor.java` — new `java.time.Clock` constructor dependency (per project rule: no `System.currentTimeMillis()`); `buildItem` gains a `Long metricDurationMs` parameter; `buildPropagatedItem` passes `null`.
- `service/domain/dto/analytics/EvalSummaryBatchWriteItemDto.java` — optional field + `@Schema` example; `src/main/resources/openapi/examples/eval-summary-batch-write-request.json`.

**Persistence / read path**
- `data/db/analytics/model/EvalSummary.java`, `data/db/analytics/mapper/EvalSummaryRecordMapper.java` (all three map methods)
- `data/db/analytics/repository/PostgresEvalSummaryRepository.java` — the insert plus the four select column lists (list, `findById`, export, export-with-bodies)
- `service/domain/dto/analytics/EvalSummaryResponseDto.java`, `EvalSummaryDetailResponseDto.java` (MapStruct maps `Long → Long` by name; `EvalSummaryMapper` needs no edit)
- `service/domain/analytics/EvalSummaryExportColumnPlanner.java`

**Explicitly unaffected**
- `FilterWhitelists` / `SortWhitelists` — no entry by design.
- CSV *import*: `EvalResultsCsvParser` reads a different, run-result-shaped CSV whose reserved columns are `startedAt`/`completedAt` (not `execDurationMs`), and binds unknown headers to a no-op `default -> {}` branch. The wider export header cannot break re-import.
- `test_case_run_results` — metric timing belongs to the summary row, which is 1:1 with a result row.

**Risks**
- *Wall-clock is not provider time.* Under provider-concurrency saturation the value absorbs semaphore waiting, so it reads high for reasons unrelated to a metric being slow. Accepted: it is the honest "how long did this row take to score" number, and the alternative (per-TSMD sums) hides queueing entirely. Documented in the spec so consumers do not misread it.
- *Two duration columns with different retry semantics.* `exec_duration_ms` on a single-request row covers only the final HTTP attempt, whereas `metric_duration_ms` covers the whole per-row evaluation including internal retries. Noted in `docs/database-schema.md` to prevent naive `exec + metric = total` arithmetic.

**Test plan**
- Unit (`InProcessMetricEvaluationExecutorTest`): the existing `Clock.fixed` cannot produce a non-zero elapsed value, so add a ticking clock stub; assert a scored row records the elapsed value, a propagated non-SUCCESS row records `NULL`, and a timed-out row records at least `perResultTimeoutMs`.
- Functional (`PostgresFunctionalTests`): batch-write → read round-trip preserves the value; an omitted field persists as `NULL`; the export CSV header contains `metricDurationMs` in the specified position; `filter=metricDurationMs,gt,1` returns 400; a query-DSL `avg(metric_duration_ms)` over `eval_summaries` succeeds.
