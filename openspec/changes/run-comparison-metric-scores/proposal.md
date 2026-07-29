## Why

The frontend's run-comparison view matches test cases across two test suite runs and shows aggregated
metric scores plus a user-defined overall score. Those aggregates are the run-level values computed by
Phase 3 over each run's **entire** population — but the two runs' test-case sets differ, so the numbers
sit side by side while describing different populations. They are not comparable, and the FE cannot fix
this itself: recomputing `overall` requires the run's snapshot `OverallScoreDefinition` composed against
the run's runtime-discovered metric fields, which is not expressible in the Query DSL by a client.

This change adds a read-only endpoint that recomputes both the five per-metric statistics and `overall`
over **only the rows present in both runs**, and returns each run's **non-matching** eval-summary ids so the
FE can build a correctly-filtered follow-up histogram query — `NOT (id IN […])`, or no filter at all when the
list is empty — over the Experimental Query API.

## What Changes

- **New** `GET /api/v1/analytics/metric-scores/comparison?runIds=<uuidA>,<uuidB>` — exactly two distinct
  runs of the same suite. Returns, per run: `runId`, `computationId`, `totalRowCount`, `matchedRowCount`,
  `matchedSuccessRowCount`, `avgExecDurationMs`, `unmatchedEvalSummaryIds`, and a `scores` array of
  `{metricScoreName, metricName, value}`.
- **Average execution duration over the matched rows.** The summary page shows the run's average test-case
  duration beside the scores, and it suffers the same incomparability, so it is recomputed over the same
  population: `avg(exec_duration_ms)` over **all** matched rows — denominator `matchedRowCount`, one sample
  per eval-summary row (per turn, per repetition). Because `exec_duration_ms` is a plain `NOT NULL` column
  on the table the match query already scans, this is a **fourth aggregate on that one statement**: no extra
  query, no extra index, and the aggregation component is untouched.
- **Exclusion polarity for row identity.** The response names the rows that did **not** match, and the FE
  filters its histogram with `NOT (id IN […])`. Because two runs of one suite over the same dataset match
  every row — the common case — this list is usually empty, and an empty exclusion list needs no
  interpretation: exclude nothing = the whole run. Zero overlap is equally self-consistent (exclude
  everything = empty population). No discriminator flag, no `null`-versus-empty convention. Expressed as a
  `not`-wrapped `in` node; the DSL has no `not_in` operator and needs no new one.
- **Row matching** on `lower(test_case_name)` + `run_index` + `turn_index`: a row matches iff its key
  occurs in the other run's population, so where a run holds duplicate keys **all** of those rows match and
  none is dropped from the aggregate. Implemented as a per-side anti-join against the other run's `DISTINCT`
  key set — no self-join, no de-duplication of the reported side, no possible fan-out. Matching is
  name-based (not test-case-id-based) because a test case that is deleted and restored keeps its
  human-chosen name but not its id.
- **Aggregation reuses Phase 3's own queries verbatim**, with a single `NOT (id IN (…))` exclusion predicate
  ANDed onto each — and nothing grafted at all when a run has no non-matching rows.
  `BuiltInMetricStatistics` and `OverallScoreDefinitionResolver` are unchanged, which makes parity
  with the persisted full-population values structural rather than merely tested.
- **Computed values are never persisted.** `metric_score_result`'s unique key
  `(run, computation, score_name, metric_name)` would collide with the real full-set values.
- **Refactor (behaviour-neutral):** metric-field discovery is extracted from
  `MetricScoreComputationExecutor`'s private `discoverMetricFields` into a shared
  `MetricFieldDiscoverer` component, with `MetricField` promoted to a top-level record. Phase 3
  delegates to it. This removes a second hand-written copy of the `metric::<tsmd>::<field>` flattening
  rule — the one divergence an output-parity test cannot detect.
- **New configuration property** `analytics.comparison.max-unmatched-rows` (default `5000`), which bounds
  the returned exclusion id list and the `IN` bind count. `docs/configuration.md` must gain a row.
- No breaking changes. No new or modified DB schema.

## Capabilities

### New Capabilities
- `run-comparison-metric-scores`: matched-row identification across two runs of one suite, and
  recomputation of per-metric statistics plus the suite's `overall` score over only the matched rows,
  exposed as a read-only REST endpoint returning both the scores and the non-matching eval-summary ids.

### Modified Capabilities
- `metric-score-statistics`: the requirement *"Metric-score results read exclusively via the unified
  Query API"* (`spec.md:144`) currently states "there is no dedicated metric-score-results REST
  endpoint". This is a **clarification, not a behaviour change** — the new endpoint reads no persisted
  `metric_score_result` rows at all. Narrow the prohibition to name *persisted* results explicitly, so
  it continues to forbid a CRUD surface over the stored table while permitting a derived,
  never-persisted computation.

## Impact

**Goals** — give the FE comparable aggregates over the matched population; return the non-matching row ids
so its histogram query can exclude them identically; keep Phase 3's persisted values authoritative and
untouched.

**Non-goals** — comparing more than two runs; comparing across suites; persisting filtered values;
exposing a mismatch signal when the two runs carry different metric sets or `overallScore` definitions
(the two `scores` arrays may legitimately be asymmetric); any change to how Phase 3 computes or stores
results.

**Current state (all Implemented)** — Phase 3 (`MetricScoreComputationExecutor`) computes AVG/P10/P90/
MIN/MAX per numeric metric field plus `overall`, and persists them per `(run, computation)`.
`metric_score_results` is readable only through `POST /api/v1/queries/execute`. Nothing recomputes over a
row subset.

**API** — one new read-only endpoint. `400` (missing/non-UUID/wrong-arity/non-distinct `runIds`), `404`
(unknown run), `409` (different suites, no resolvable computation, or non-matching rows over the cap), `422`
(`SNAPSHOT_SUITE_MISSING` for a legacy run with no `suite_snapshot`). Deliberately not gated on run
status — a CANCELLED or partially-completed run still has rows worth comparing.

**Data** — analytics DB reads only; no migration, so `docs/database-schema.md` is unaffected. Two new
read methods on `EvalSummaryRepository`: a per-side statistics query
(`total`/`matched`/`matchedSuccess`/`avgExecDuration`, which materialises no rows so the cap can be
enforced first) and a per-side unmatched-id query.

**Security** — inherits the standard OIDC/JWT filter chain; no new roles, no new external calls.

**New classes** — `RunComparisonProvider` (interface, `service.domain.analytics`) with response DTOs in
`service.domain.dto.analytics`; `RunComparisonService`, `FilteredMetricScoreAggregator`,
`MetricFieldDiscoverer`, `MetricField` (`experimental.query.service.metricscore`);
`RunComparisonController` (`web.controller`); `RunComparisonProperties`
(`configuration.properties.analytics`); and one small `EvalSummaryMatchStats` carrier (three counts plus
the duration average) in `data.db.analytics`. No new
packages, and — because the anti-join returns scalars and bare ids — no new domain model or `RecordMapper`
for matched row pairs. The implementation lives in `experimental.query` because it
depends on `StructuredQueryService`, and is reached from the stable layers through the
`service`-layer interface — the same inversion already used by `MetricScoreComputation` and
`RunnableTestCaseSelector`, so `LayeredArchitectureTest` needs no change.

**Risks** — (1) the endpoint issues 5×N sequential aggregate queries per run, so a 20-metric comparison
issues ~200; this is Phase 3's existing per-run cost moved onto the request path, and batching into one
multi-column statement is a measured follow-up rather than a precondition. (2) A run holding duplicate
match keys reports a higher `matchedRowCount` than its counterpart; accurate rather than wrong, and the
reason no cross-run equality guarantee is offered. (3) The `MetricFieldDiscoverer` extraction touches a
Phase-3 class;
`MetricScoreComputationExecutorTest` is the regression guard and must keep every existing assertion.

**Rollout** — purely additive; no flag, no migration, no data backfill. The URL is a stable contract even
though the implementation sits in the experimental package.

**Test plan** — unit tests for the service guards, the id-predicate grafting, every `overall` variant and
the discoverer; functional tests (`@PostgresFunctionalTests`) for anti-divergence against the persisted
values, zero overlap, `turn_index`/`run_index` participation, duplicate keys all matching, the cap, and each
error status. Because this adds `@Qualifier`-injected beans and a new `@ConfigurationProperties` class, a
context-booting functional test runs before any behavioural assertions.
