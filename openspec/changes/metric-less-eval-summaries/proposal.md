## Why

Users want to run an evaluation with **no metrics configured** — just to inspect the deployment's responses and extracted columns visually (and export them to CSV). Today such a run completes successfully but produces nothing readable: the metric-evaluation phase returns early when the suite has no enabled+valid TSMDs, so no `test_case_eval_summaries` rows are written, and the list/count/export endpoints resolve "latest computation" from `run_metric_snapshots` — also empty — so they return an empty page, a count of `0`, and HTTP 404 respectively.

`eval_summaries` is the single read surface for run results by design: clients must never branch between `test-case-results` and `eval-summaries` depending on whether the suite has metrics. Duplicating `testCaseData`/`extractedColumns` into eval summaries is the accepted cost of that contract.

## What Changes

- **Metric evaluation phase writes eval summaries for a metric-less run.** `InProcessMetricEvaluationExecutor` no longer returns early when the TSMD list is empty; it iterates the run's `TestCaseRunResult` rows and writes one eval summary per row with `metric_values = {}` and `metric_infos = {}`, preserving `execution_status`, `test_case_data`, `extracted_columns`, `extraction_warnings`, turn columns, and timings. `run_metric_snapshots` stays empty (correct — there are no metrics).
- **"Latest computation" is resolved from `test_case_eval_summaries` instead of `run_metric_snapshots`.** `ComputationResolver` reads the table its callers actually read; the snapshot-based lookup is dropped from the resolver (it stays in place for Query DSL schema discovery, which legitimately asks a metric-catalog question). Behavior is unchanged when metrics exist, because Phases 2 and 3 share one `computationId`. A supporting index makes the lookup an index-only top-1 probe.
- **A covering index backs that lookup** — `V1.15__AddEvalSummariesRunComputedAtIndex.sql` on `(test_suite_run_id, computed_at_ms DESC, computation_id)`, so resolution stays an index-only top-1 read on the fact table.
- **List / count / aggregate / export work for metric-less runs.** `GET /api/v1/analytics/eval-summaries` (and `/count`, `/aggregate`, `/export.csv`, `/export/preview`) return the run's rows; the export manifest contains identity, timestamp, execution, `data:<field>` and `response:<column>` columns and no `metric:*` / `metricInfo:*` / `metricError:*` columns.
- **Export stops 404-ing an explicit `computation=<uuid>` that has no metric snapshots** — existence is decided by eval summaries for that computation, not by snapshot rows.
- Applies equally to the CSV **eval-results import** path (`skipDeploymentPhase=true`), which reuses Phases 2–3 over imported `test_case_run_results`.
- **BREAKING** (spec-level, not API-shape), two items: (1) the documented behavior "the executor SHALL skip metric evaluation entirely and return without writing any records" for zero TSMDs is replaced — callers that relied on "no summaries ⇒ suite has no metrics" must instead read `run_metric_snapshots` / `metric_values` emptiness; (2) `latest` no longer selects a computation whose metric snapshots landed but whose eval summaries did not — such a run now returns its last complete computation instead of an empty page. No endpoint, DTO, status code, or column changes; no new configuration property. One index-only Flyway migration (`V1.15`), which requires `./gradlew generateJooq` and a `docs/database-schema.md` update.

Not in scope: metric-score statistics (Phase 3 already skips cleanly when no numeric metric fields are discovered — no `metric_score_results` rows, non-fatal), run-creation guards (already permit metric-less suites), suite validity (already metric-independent), and any new "metric-less" flag on suite or run — a metric-less run is emergent from having zero enabled+valid TSMDs, not a mode.

## Capabilities

### New Capabilities

None — this change modifies the requirements of existing capabilities only.

### Modified Capabilities

- `metric-evaluation`: the zero-TSMD and all-TSMDs-disabled/invalid scenarios change from "return without writing any records" to "write one eval summary per test case run result with empty `metric_values`"; `run_metric_snapshots` remains unwritten.
- `metrics-storage`: latest-computation resolution reads `test_case_eval_summaries` (previously `run_metric_snapshots`); the "no computation exists" scenario now means "the run has no eval summaries"; a new index backs the lookup.
- `eval-summary-export`: export of a run with zero metric snapshots is supported (metric-free column manifest); explicit-`computation` not-found is decided by eval summaries rather than metric snapshots.
- `test-suite-runs`: the Phase-2 orchestration statement that "the executor handles the 'no TSMDs' case by returning early without writing any records" is corrected.

## Impact

**Code (Implemented layer):**
- `service.domain.job.InProcessMetricEvaluationExecutor` — remove the empty-TSMD early return (the existing path degenerates correctly: empty provider-semaphore map is never indexed, no futures are dispatched, `allOf(empty)` completes immediately, `hasError=false`, `metricValues`/`metricInfos` are empty objects).
- `service.domain.analytics.ComputationResolver` — `latest` resolves against eval summaries; the `RunMetricSnapshotRepository` dependency is dropped from this class.
- `data.db.analytics.repository.EvalSummaryRepository` + `PostgresEvalSummaryRepository` — new `findLatestComputationId(UUID runId)` (typed jOOQ DSL over `test_case_eval_summaries`).
- `service.domain.analytics.EvalSummaryExportService` — relax the explicit-computation not-found guard.
- Unchanged on purpose: `RunMetricSnapshotRepository.findLatestComputationId` stays for `experimental.query.service.EvalSummariesSchemaProvider`, which asks the metric-catalog question ("which `metric:*` families does this run's latest computation have?"), not the has-results question.

**APIs:** no contract change; only the data returned for metric-less runs. OpenAPI examples unaffected (metric-free responses are the existing shape with `metricValues: {}`).

**Data:** one index-only Flyway migration, `V1.15__AddEvalSummariesRunComputedAtIndex.sql` on `test_case_eval_summaries (test_suite_run_id, computed_at_ms DESC, computation_id)` — no column change, no backfill; requires `./gradlew generateJooq` (committed) and a `docs/database-schema.md` update. Storage cost: a metric-less run duplicates `test_case_data` / `extracted_columns` into `test_case_eval_summaries` — accepted per the single-read-surface contract above.

**Risks:** (1) consumers inferring "metric-less" from the absence of summaries — addressed by the BREAKING note; (2) `latest` resolution moves from a ~5-row dimension table to the fact table — mitigated by the covering index, with a functional test asserting the lookup does not scale with the run's row count.

**Tests:** unit — executor with empty TSMDs writes one summary per result and no snapshots; resolver resolves via eval summaries and returns empty when the run has none. Functional — a run over a metric-less suite yields non-empty `GET /eval-summaries` and `/count`, a metric-free `export.csv`, and a working `computation=<uuid>` export; multi-computation run resolves the newest; regression — a run with metrics is unchanged.

**Docs/specs:** four delta specs (above); `docs/database-schema.md` for the new index; `openspec/specs/README.md` summaries only if a listed capability's one-liner becomes inaccurate; no `config.yaml` or `docs/configuration.md` change.
