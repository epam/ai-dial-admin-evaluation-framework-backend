## Context

Phase 3 of a test suite run (`MetricScoreComputationExecutor`) computes AVG/P10/P90/MIN/MAX per numeric
metric field plus a run-level `overall`, and persists them to `metric_score_results` keyed by
`(test_suite_run_id, computation_id, metric_score_name, metric_name)`. Those values are computed over the
run's **entire** eval-summary population.

The FE's run-comparison view places two runs' values side by side. Because the runs' test-case sets
differ, the two populations differ, so the comparison is invalid. The FE also builds a score-distribution
histogram via `POST /api/v1/queries/execute` over `eval_summaries`, and needs that query filtered to the
same matched population.

The FE cannot compute this itself. `overall` is composed by `OverallScoreDefinitionResolver` from the
run's snapshot `OverallScoreDefinition` (`Mean` / `WeightedMean` / `CustomFunction`) against the run's
**runtime-discovered** metric fields — a server-side composition that is deliberately not exposed as a
DSL function. The five per-metric statistics alone would not justify an endpoint.

Constraints: JDBC-only via typed jOOQ; dual datasource (meta = suites/runs, analytics = eval summaries and
metric scores) with no cross-database joins; `LayeredArchitectureTest` (ArchUnit) forbids `web` from
reaching `experimental` code; the Query DSL is the only sanctioned way to run these aggregates.

## Goals / Non-Goals

**Goals:**
- Recompute the five per-metric statistics and `overall` over only the rows present in both runs.
- Recompute the run's **average execution duration** over the same population, since the FE's summary page
  shows it beside the scores and it is incomparable for exactly the same reason.
- Return the **non-matching** `test_case_eval_summaries.id` values so the FE's histogram query can exclude
  them and reproduce the recomputed population.
- Keep the numbers trustworthy: values must agree with the persisted ones when the matched set happens to
  be the full set.
- Leave Phase 3's computation and stored results untouched.

**Non-Goals:**
- More than two runs, or runs from different suites.
- Persisting the filtered values.
- Signalling that the two runs' metric sets or `overallScore` definitions differ — the two `scores`
  arrays may legitimately be asymmetric and the API exposes no mismatch flag.
- Gating on run status, or on Phase 3 having succeeded.
- A `request_number` component in the match key (a known future fourth component; out of scope here).

## Decisions

### 1. Match by name, not by test-case id; return eval-summary ids

Match key is `lower(test_case_name)` + `run_index` + `turn_index`. A test case that is accidentally
deleted and restored keeps its human-chosen name but receives a new id, so id-matching would drop exactly
the rows a user most expects to compare. `run_index` is the `numberOfRuns` repetition index; `turn_index`
is the multi-turn turn index (V1.14 writes one eval-summary row per turn) — both must participate because
both are part of a row's identity, not merely to keep a join narrow.

The response carries `test_case_eval_summaries.id`, not test-case ids: the histogram the FE builds queries
`eval_summaries`, so row identity is what it needs. The grafted exclusion predicate is evaluated as a
filter on top of the run/computation index scan (`idx_eval_summaries_run_computation`) — a `NOT IN` cannot
use `idx_eval_summaries_id` (`V1.5:31-32`) — which is acceptable because that scan is already the driving
access path for every query here.

*Alternative rejected:* id-first with name fallback (what the FE does today). It reproduces FE logic
server-side for no gain, since name-matching is a superset of the cases id-matching handles correctly.

### 2. No collapse: a per-side anti-join against the other run's distinct key set

**A row matches if and only if its match key occurs in the other run's population.** Where a run holds
several rows for one key, **all** of them match. There is no de-duplication of the reported side.

The implementation is, per direction, a derived table `k` holding the *other* run's `DISTINCT` key set,
left-joined to the reported side:

```sql
WITH k AS (SELECT DISTINCT lower(test_case_name) AS name_lower, run_index, turn_index
             FROM test_case_eval_summaries
            WHERE test_suite_run_id = :runB AND computation_id = :compB)
SELECT … FROM test_case_eval_summaries s
  LEFT JOIN k ON k.name_lower = lower(s.test_case_name)
             AND k.run_index = s.run_index AND k.turn_index = s.turn_index
 WHERE s.test_suite_run_id = :runA AND s.computation_id = :compA
```

`k.name_lower IS NOT NULL` is the matched probe, `IS NULL` the unmatched one. The `DISTINCT` here
de-duplicates the **probe key set** — a fundamentally different operation from de-duplicating the rows
being reported on, and one that can never drop a row from an aggregate. Because `k` is unique on the join
key, **fan-out is structurally impossible**.

*Alternative rejected: `DISTINCT ON (key) … ORDER BY key, id` per side, inner-joined.* This was the
original design, adopted to make the join strictly 1:1 so that both sides' *matched* id lists were
equal-length — a requirement of the **inclusion** polarity that decision 13 has since replaced. Under
exclusion polarity nothing requires 1:1, and the collapse actively harms correctness: it silently omits a
real row from the aggregate population, choosing the survivor by UUID ordering.

Critically, **that omission is reachable through a supported feature, not just direct analytics writes**.
`EvalResultsImportService.validateBatch` (`:75-82`) rejects in-batch duplicates using
`testCaseIdentity(item) + "#" + runIndex` in a **case-sensitive** `HashSet`, where `testCaseIdentity`
prefers `testCaseId` and falls back to `testCaseName`, and `turnIndex` is absent from the key entirely.
`EvalResultsCsvParser:252` assigns `testCaseId != null ? testCaseId : UUID.randomUUID()`. So two CSV rows
named `Foo` and `foo` at the same `runIndex` — or one name carrying two explicit `testCaseId`s — both pass
validation, receive distinct `test_case_id`s, and persist as two eval-summary rows sharing
`(lower(test_case_name), run_index, turn_index)`; the analytics natural key is on `test_case_id`
(`V1.5:22-23` + `V1.14:14-15`) so it does not collide. That is the eval-results **import** path — exactly
how a user gets external results in so they can compare them. A collapse would drop one of their
measurements from the average on the one feature whose purpose is comparison.

The meta DB's `uq_test_cases_dataset_name (dataset_id, LOWER(test_case_name))` (`V1.22:204-205`) does make
the population duplicate-free for suites produced by the normal snapshot→execute pipeline — so for those,
this decision and the collapse behave identically. It does **not** cover the import path.

Two further benefits: `matchedRowCount + size(unmatchedEvalSummaryIds) == totalRowCount` becomes a
tautology rather than a rule someone must uphold; and the only Postgres-only construct the design
introduced (`DISTINCT ON`) disappears.

An earlier draft of this paragraph also claimed the per-side form removes the sorts `DISTINCT ON` forced.
Measurement (see decision 3) shows that holds for the counts query only: the ids query sorts regardless,
because its deterministic `ORDER BY` requires ordered output whatever the join shape.

### 3. Per-side anti-joins now; a group-by if the analytics store becomes ClickHouse

Nothing downstream consumes the *pairing* of matched rows — only each side's own matched/unmatched row sets
and counts. So there is no self-join at all: two independent per-side queries, each index-scanning
`idx_eval_summaries_run_computation` (`V1.5:25-26`). On ClickHouse the same semantics are expressed as
`GROUP BY lower(name), run_index, turn_index HAVING count(DISTINCT test_suite_run_id) = 2` with
`array_agg(id)`. Note that formulation is **already collapse-free**, which corroborates decision 2 rather
than conflicting with it. Recorded so the port is a rewrite of one repository method, not a rediscovery.

*Alternative rejected: a plain equi-join between the two sides* (`a.name_lower = b.name_lower AND
a.run_index = b.run_index AND a.turn_index = b.turn_index`), which is the obvious formulation and would
answer both sides in one statement — 2 queries instead of 4. Two reasons it loses:

First, an equi-join multiplies each reported row by its number of counterparts, and that multiplier varies
per key — so **the duration average of decision 14 comes out weighted**:

```
A: a1(key K, 100ms)  a2(key L, 200ms)        join → a1 appears 3×, a2 appears 1×
B: 3 rows key K,     1 row key L             avg = (100·3 + 200)/4 = 125, not 150
```

`count(DISTINCT a.id)` recovers the counts, but nothing recovers the mean: `AVG(DISTINCT exec_duration_ms)`
de-duplicates equal *values*, collapsing two distinct rows that both took 100 ms into one sample — a
different wrong answer. Correcting it requires de-duplicating the reported side in a derived table before
aggregating, which is exactly the shape the per-side query already has.

Second, nothing downstream consumes the pairing at all (see above), so the pairs are wasted work in the
wrong shape. The saved two queries are invisible against the ~200 aggregate queries of decision 5.

A semi-join (`WHERE EXISTS (…)`) is equally correct; `LEFT JOIN … IS NULL` was chosen only to avoid
depending on sublink pull-up.

**Measured** (`EXPLAIN (ANALYZE, BUFFERS)`, Postgres 17.4 in Testcontainers, 6 200 rows per run, ~80 %
overlap, after `ANALYZE`; the statements were captured from jOOQ's own execute log, so they are the ones
the repository issues):

| Query | Plan | Time |
|---|---|---|
| `countMatches` | `Aggregate` ← **`Hash Left Join`** ← two `Bitmap Index Scan`s on `idx_eval_summaries_run_computation`, probe side `HashAggregate`d for the `DISTINCT` | 9.4 ms |
| `findUnmatchedIds` | `Incremental Sort` ← **`Merge Anti Join`** ← two `Sort`s over the same bitmap index scans | 28.7 ms |

Both confirm the index is used and neither degenerates to a sequential scan or a nested loop, which was the
claim worth checking. Two details differ from what the design predicted:

- The planner rewrites `LEFT JOIN … WHERE k.name_lower IS NULL` into a genuine **anti-join** rather than
  materialising matched rows and discarding them. So the `NOT EXISTS` formulation this decision declined
  would very likely have produced the same plan — the sublink-pull-up worry was unfounded, though the
  choice costs nothing.
- The ids query is **not** hash-joined and does sort twice. That is not a defect of the join shape: its
  `ORDER BY lower(test_case_name), run_index, turn_index, id` contract needs ordered output, and choosing a
  merge anti-join is how the planner gets three of those four keys for free — `Incremental Sort` then sorts
  only within each presorted group (38 groups, 27 kB peak). A hash join would have to sort everything
  afterwards.

At this size both are far below the ~200 aggregate queries that dominate the request, so the join is not
the thing to optimise if the endpoint proves slow.

Both queries pin `computation_id` as well as the run id. Re-evaluations mint a new `computation_id`
and all of them coexist in the table, so omitting it would match across computations. The invariant
that makes `(run, computation)` a coherent slice is `created_at_ms == run.getCreatedAt()`
(`EvalSummaryService.java:79`). It is specifically **not** "one batch write per computation" — writes are
chunked at `analytics.eval-summaries.batch.max-items`, so a computation spans many batches.

### 4. Never persist the filtered values

`metric_score_result`'s unique key `(run, computation, score_name, metric_name)`
(`PostgresMetricScoreResultRepository.java:52-57`) has no room for "which subset" — writing filtered
values would collide with and overwrite the real full-population ones. The endpoint is therefore a pure
computation with no write path, which also means it needs no `computed_at_ms` and no versioning story.

### 5. One query per (statistic, field), reusing Phase 3's queries verbatim

The aggregator's entire job is: take the query Phase 3 would run, AND one `NOT (id IN (…))` exclusion
predicate onto its filter (decision 13; nothing is grafted when the exclusion list is empty), execute it,
read the value. A single helper does the grafting:

```java
StructuredQuery withIdPredicate(StructuredQuery q, FilterNode idPredicate)
// filter == null ? idPredicate : new LogicalNode(AND, List.of(q.filter(), idPredicate))
```

Because the predicate is grafted **before** parameter resolution and contains only `ValueExpr`s,
`QueryParameterResolver` passes it through untouched — so the aggregator never calls the resolver itself,
and `BuiltInMetricStatistics` needs no change.

Per decision 13 the grafted predicate is `NOT (id IN (unmatched))`, and when the unmatched list is empty
**nothing is grafted at all** — Phase 3's query runs verbatim, which is both the cheapest path and the one
where parity is a tautology.

This buys the property that matters most: parity with the persisted values is *structural*. The endpoint
runs the same SQL over a narrower row set, so there is no second implementation that could drift.

*Alternative rejected:* batching all 5×N statistics into one query with positionally-aliased columns —
two queries per run instead of ~200. It requires `QueryParameterResolver` to lift and re-alias each
resolved select column (metric names contain dots and spaces and are not safe SQL identifiers, while
`StructuredQueryBuilder.requireAlias` demands a non-blank alias on every computed aggregate); it assumes
"all resolved filters are identical, take any one", which a future filter-side parameter would silently
break; and it hinges on executing N>2 `percentile_cont … WITHIN GROUP` aggregates in a single statement,
which is proven in this codebase only at the rendering level (`EvalSummaryQueryRenderTest.java:288-315`)
and never against a real Postgres. Revisit **only** on a measurement; if adopted, assert
`resolved.filter().equals(first)` rather than trusting it.

### 6. `overall` is its own query, and the resolver receives the un-filtered field list

`OverallScoreDefinitionResolver.resolve` returns a complete `StructuredQuery`, not a liftable `Expr` —
`CustomFunction` is a stored raw query — so `overall` cannot join the per-metric batch and the resolver
needs no changes at all.

All four variants (`defaultOverall`, `Mean`, `WeightedMean`, `CustomFunction`) go through the same
`withIdPredicate`; this is not a `CustomFunction` quirk. Only `CustomFunction` can present a null incoming
filter, because `Mean` and `WeightedMean` both route through `BuiltInMetricStatistics.aggregateSelecting`
(`OverallScoreDefinitionResolver:60-61`), which always attaches `runScopedFilter()`.

The resolver must be handed the **full discovered field list**. `meanExpr` divides by
`metricFieldNames.size()` (`:66-73`) and Phase 3 passes the discovered list
(`MetricScoreComputationExecutor:145`); passing any filtered subset would silently change the divisor and
produce a number that is not the suite's mean.

`CustomFunction` is stored opaquely and never validated as a runnable query, so it is guarded before
execution: entity must equal `MetricScoreConstants.ENTITY_EVAL_SUMMARIES` (constant first, for
null-safety), `mode == AGGREGATE`, exactly one select column, read by that column's own alias (the
built-in paths all use `VALUE_ALIAS`, a `CustomFunction` need not). Anything else, or an unparseable
expression (`resolve` returns null), skips `overall` and logs. Phase 3's unfiltered value is unaffected
either way.

`includeOverall` follows Phase 3's own rule (`:132-136`): a non-null definition is always computed; a null
definition is computed only when the run has exactly one discovered field.

### 7. Extract `MetricFieldDiscoverer`; never split `metric_name` on `.`

Discovery is moved out of `MetricScoreComputationExecutor.discoverMetricFields` (`:207-218`) into a shared
`MetricFieldDiscoverer` component, with `MetricField` promoted from a private record (`:244`) to top-level.
Phase 3 delegates to it.

The alternative — re-implementing discovery in the new service — was rejected because it puts a second
copy of the `METRIC_COLUMN_PREFIX + tsmd + COLUMN_SEPARATOR + field` flattening rule in the codebase, and
that is the one divergence an output-parity test cannot catch: two discoverers could find different fields
and still agree on every value they both produced.

Discovery reads the run's `run_metric_snapshots` plus `OutputSchemaFieldExtractor`, and takes **no**
intersection with persisted `metric_score_result` rows — a run may legitimately have snapshots and no
persisted scores (Phase 3 skipped or failed), and the endpoint should still return full aggregates.

`MetricFieldDiscoverer.discover(List<RunMetricSnapshot>)` takes the snapshots as a **parameter** and loads
nothing itself, mirroring `MetricScoreComputationExecutor.discoverMetricFields`'s existing signature.
`RunComparisonService` obtains them via `RunMetricSnapshotRepository.findByRunIdAndComputationId(runId,
computationId)` — an existing method — with the repository injected **directly**, the same dependency
`MetricScoreComputationExecutor` already has in this package and legal because `experimentalService` may
access `data`. `RunMetricSnapshotService` is not the source: it exposes only DTO-returning reads plus
`findLatestComputationId`.

The persisted `metric_name` (`<tsmd>.<field>`) must never be split on `.` to recover its parts: tsmd names
may themselves contain dots. The snapshot yields both halves unambiguously.

### 8. Placement: invert through a `service`-layer interface

`RunComparisonProvider` (interface) and the response DTOs live in `service.domain.analytics` /
`service.domain.dto.analytics`; `RunComparisonService` (the implementation) lives in
`experimental.query.service.metricscore` because it depends on `StructuredQueryService`;
`RunComparisonController` lives in `web.controller` and injects the interface. This is the inversion
already used twice — `MetricScoreComputation` and `RunnableTestCaseSelector` — and
`LayeredArchitectureTest` passes unmodified.

*Alternative rejected:* putting the whole feature inside `experimental.query`, including the controller.
It appeared simpler, but inversion also requires no ArchUnit change, so the comparison came down to cost:
`LayeredArchitectureTest` (`:45-50`) declares `service` as
`mayOnlyBeAccessedByLayers("web", "configuration", "experimentalService")` and `data` as
`mayOnlyBeAccessedByLayers("service", "configuration", "experimentalService")` — `experimentalWeb` is
absent from both (and `web` may not access `data` at all) — so an experimental controller could import
nothing from `service..`/`data..`. It could not reference
`ValidationException` / `EntityNotFoundException` / `InvalidOperationException`, forcing every guard into
the service for a layering reason rather than a design one, and it would park a stable public URL's
response DTOs in `experimental.query.service.dto`. One interface file is the whole price of avoiding that.

The URL is a stable contract even though the implementation is experimental; that asymmetry is the reason
the interface and DTOs sit in the stable layer.

**Repository access is one pattern, not two.** The meta reads go through the owning `TestSuiteRunService`
(`getRun`), which already raises `EntityNotFoundException` for an unknown run. The two analytics reads inject
their repositories — `EvalSummaryRepository` and `RunMetricSnapshotRepository` — **directly**, which is the
established pattern in this area (`ComputationResolver:42` and `MetricScoreComputationExecutor` both do
exactly this) and is legal because `LayeredArchitectureTest:48-49` lets `experimentalService` access `data`.
AGENTS.md's "don't inject a foreign domain's repository into a service" rule targets `service.domain` domain
services, not an `experimental.query.service` executor. Routing these reads through `EvalSummaryService` was
rejected: every one of its public reads returns a DTO, so a delegate returning the raw
`data.db.analytics.model.EvalSummaryMatchStats` would be its first domain-model return — a new shape for
that service to carry, for one caller, buying only a proxy hop.

### 9. Transaction boundaries

Meta reads (run lookup, suite gate, snapshot) happen first, outside any analytics transaction. Everything
after runs inside a single read-only analytics transaction: `ComputationResolver`'s own contract requires
an ambient analytics transaction, `@Transactional` on a self-invoked helper would silently open none
(proxy bypass), and one transaction gives all the aggregate queries a consistent snapshot.

**There is no `TransactionTemplate` bean in this application.** All existing users inject a
`PlatformTransactionManager` and construct the template themselves — e.g.
`EvalSummaryExportService:105-108`, where `analyticsTransactionTemplate` is a private final field
(`:79-80`), not a bean name. So this service injects
`@Qualifier("analyticsTransactionManager") PlatformTransactionManager`
(`AnalyticsJdbcConfiguration:35-37`) and builds a read-only template in an explicit constructor.
`@Qualifier("analyticsTransactionTemplate")` would compile cleanly and fail context startup with
`NoSuchBeanDefinitionException` — a mistake an earlier draft of this design actually made, which is why
the task list boots the context before asserting behaviour.

### 10. Snapshot handling: null rejected, version deliberately not gated

A null/blank `suite_snapshot` (a run predating `V1.17`) → 422 `SNAPSHOT_SUITE_MISSING`, matching
`EvalSummaryExportService.resolveSnapshot`'s treatment of legacy runs. The snapshot **version**, however,
is deliberately not gated, unlike that method's `UNSUPPORTED_SNAPSHOT_VERSION` check (`:267-272`): only
`overallScore` is read here, and a legacy snapshot that lacks the field deserializes to `null`, which the
default-overall rule already handles correctly. Adding a version gate would reject runs this endpoint can
serve perfectly well.

(Malformed snapshot JSON throws `IllegalStateException` → 500 inside `TestSuiteRunMapper:75`. Because this
endpoint reads runs through `TestSuiteRunService.getRun`, it inherits that path rather than avoiding it.
Pre-existing behaviour, deliberately not worked around here — a run whose stored snapshot will not
deserialize is equally broken for `GET /runs/{id}`.)

### 11. A configurable cap on non-matching rows, enforced as 409

`analytics.comparison.max-unmatched-rows` (default `5000`, `@Min(1)`, default in `application.yml` only).
The cap exists because of a concrete failure chain, not tidiness: the DSL renders `id IN (…)` as one bind
per value (`FilterTranslator.java:111-116` / `:138-154`), Postgres' 65535-parameter ceiling is reachable at
documented scale (`MAX_DISABLED_TC_IDS = 10000` × `max-number-of-runs: 64` × `max-turns: 10`), and an
overflow surfaces as an `UncategorizedSQLException` that `StructuredQueryExecutor:56-64` does not catch —
so the user gets a **500**, not a 4xx. Exceeding the cap is therefore turned into an explicit 409 naming
both the count and the limit.

`5000` rather than `10000` keeps the worst-case response near 0.35 MB (2 × 5000 UUIDs) and halves
bind-serialization work, which matters because the id list is re-bound once per (statistic, field).

**The cap counts `unmatchedEvalSummaryIds`**, following decision 13's polarity — that list is both what is
bound and what is shipped. This inverts which comparison is expensive: a full match is now trivially
cheap, while *zero* overlap ships the whole run. Two consequences worth stating plainly:

- A comparison with 50,000 matched rows and 3 unmatched now succeeds, where inclusion polarity would have
  rejected it. Since two runs of one suite usually overlap heavily, the cap binds far less often.
- The 409 message names the non-matching count, the configured limit and the
  `analytics.comparison.max-unmatched-rows` property, and the exact count is available as
  `total_rows − matched_rows` from the counts query (decision 13) without materialising anything.
- The check runs **before** any id is fetched. This is what makes the cap actually bound work; see
  decision 13 for the earlier design in which it did not.

*Alternative rejected:* **adaptive** polarity — choosing include or exclude per request, whichever list is
shorter. Two filter shapes, two response fields and a discriminator, to save bytes in a case fixed
exclusion polarity already handles. (An earlier draft rejected adaptive polarity on the grounds that "the
response ships the matched list regardless, so shrinking only the bind list saves nothing." That reasoning
applied to *adaptive* polarity layered on a matched-id response; decision 13 changes what is shipped, which
is what makes fixed exclusion polarity worthwhile.)

### 12. Response shape

`runs` is a JSON **array** ordered as requested, not a map keyed by run UUID: a UUID-keyed map degrades to
`additionalProperties` in OpenAPI, losing the schema for what is actually a fixed, well-typed object.

An entry whose aggregate is SQL NULL is **omitted** from `scores` rather than emitted with
`value: null`, exactly as Phase 3 omits it (`MetricScoreComputationExecutor:119-122`). So `value` is never
null.

Execution status does not participate in matching — a FAILED row still matches and is still counted in
`matchedRowCount`. `matchedSuccessRowCount` is reported separately, and exists for one concrete purpose: the
FE renders "28/29" and "27/29" per run, i.e. how many of the **compared** rows succeeded. Its denominator is
`matchedRowCount`.

**It is emphatically not any statistic's denominator, and not even an upper bound on one.** Two verified
facts make the two numbers independent:

1. A row's `execution_status` is SUCCESS only if the endpoint call succeeded **and every metric evaluated
   cleanly**. `InProcessMetricEvaluationExecutor:281` writes `hasError ? FAILED : SUCCESS`, where
   `checkForErrors` (`:286-301`) trips on any TSMD `Failure` *or* any output field of type `"error"`. One bad
   metric out of ten stamps the whole row FAILED.
2. The statistics do **not** filter on status at all — `BuiltInMetricStatistics.runScopedFilter()` (`:99-105`)
   is run + computation only. And a sibling metric's failure does not erase the healthy metrics' values:
   `MetricOutputMapper.buildMetricValues` (`:53-63`) maps every `Success` TSMD's values and writes explicit
   nulls only for the `Failure` ones.

So with 10 matched rows carrying metrics A and B, where B errors on 4: `matchedSuccessRowCount` is 6 while
metric A's AVG denominator is 10 — the success count is *lower* than the denominator. Each metric has its own
denominator, unknowable from any single row count, which is precisely why none is offered.

(An earlier draft of this document claimed `matchedSuccessRowCount` was "an upper bound on the aggregate
denominator", reasoning from conditional metrics being absent on a SUCCESS row. That reasoning ignored
direction 1 above and was wrong in both directions; the field's justification is the FE's success ratio, not
denominator approximation.)

*Alternative rejected:* counting success from `test_case_run_results.execution_status` (joined via the
`NOT NULL` `test_case_run_result_id`) instead, which would mean "the test case executed" independent of metric
health. It is equally cheap, and arguably the more literal reading of "successful test cases" — but
`FilterWhitelists:204-206` exposes the **eval-summary** `executionStatus` as the status clients filter and
count on for this entity, so a comparison view using the run-result column would report a different numerator
than the run view does for the very same rows. Consistency with the existing surface decides it. Revisit only
if the FE wants "executed OK despite an unusable metric" as a distinct number.

A `::numeric` cast on a metric value cannot fail, so no defensive handling is needed: values are
number-or-null by construction (`MetricOutputFieldDto.value` is `BigDecimal`, and
`EvalSummaryService.java:258-263` rejects a non-numeric, non-null value at write time). A field that a
metric's output schema declares as a string is simply absent from the JSONB, so it aggregates to NULL and
is omitted. An earlier draft of this design built an elaborate `metric_score_result` intersection scheme on
the false opposite premise; that scheme is gone.

*Alternative rejected:* extending `PostgresEvalSummaryRepository.aggregate(...)` (`:161-205`) instead of
using the Query DSL. It is built on the legacy `FilterCondition` framework, has no percentile support,
cannot host `overall` at all, and would fork the very code path whose parity we depend on.

### 13. Exclusion polarity: ship `unmatchedEvalSummaryIds`, not matched ones

Two runs of one suite over the same dataset match *everything* — the common case, not an edge case.
Shipping the **matched** ids means serialising up to the cap in UUIDs so the FE can send them all back to
express a filter that selects the entire run.

The response therefore carries `unmatchedEvalSummaryIds` — the rows of this run that did **not** match —
and the FE filters with `NOT (id IN […])`. This needs no discriminator flag, because the polarity makes the
degenerate cases self-consistent:

| Overlap | `unmatchedEvalSummaryIds` | FE filter | Meaning |
|---|---|---|---|
| Everything matched | `[]` | none needed | exclude nothing = whole run |
| Partial | the non-matching rows | `NOT (id IN […])` | the intersection |
| Nothing matched | every row | `NOT (id IN [all])` | empty population |

An empty list unambiguously means "exclude nothing", so there is no `null`-versus-`[]` convention, no
`allRowsMatched` boolean, and no zero-match guard — a previous draft needed all three, plus a rule that a
client ignoring the flag would silently build an unfiltered histogram. Zero overlap needs no special case
either: excluding every row yields an empty population, NULL aggregates, and omitted `scores`, which is
exactly right.

**No DSL change is required, but there is no `not_in` operator.** `ComparisonOp` offers only
`eq/ne/co/nc/lt/gt/le/ge/in`. Exclusion is expressed as `LogicalNode(NOT, [ComparisonNode(IN, …)])`,
translated at `FilterTranslator:68-72` to `DSL.not(...)` (arity-1, validated). OpenAPI examples must show
the nested `not` wrapper, not a flat operator. `NOT IN` is safe from the classic NULL trap here because
`id` is `NOT NULL` (a primary-key component) and no element of the list is null.

**The server applies the same polarity**, grafting `NOT (id IN (unmatched))` instead of `id IN (matched)`,
and grafting **nothing** when the unmatched list is empty — Phase 3's query then runs verbatim, which is
both the cheapest path and the one where parity is a tautology.

**The unmatched set comes straight from the anti-join** (decision 2) as `k.name_lower IS NULL`. With no
collapse there is no discrepancy between "not in the matched set" and "key absent from the other run", so
the key-based anti-join is exactly right — a fact worth stating because it is false under a collapsing
design, and an earlier draft of this document warned against it for that reason.

**Two queries per side, counts before ids** (four total). The counts query materialises nothing:

```sql
SELECT count(*)                                            AS total_rows,
       count(k.name_lower)                                 AS matched_rows,
       count(*) FILTER (WHERE k.name_lower IS NOT NULL
                          AND s.execution_status = 'SUCCESS') AS matched_success_rows,
       avg(s.exec_duration_ms) FILTER (WHERE k.name_lower IS NOT NULL)
                                                           AS avg_exec_duration_ms
```

`count(k.name_lower)` is the matched count because `lower(test_case_name)` is `NOT NULL` and `k` cannot
fan out. The ids query runs **only after the cap check passes**, so the exact unmatched count is known
(`total − matched`) before a single id leaves the database.

This ordering is not incidental — it fixes a real defect in the earlier design, which fetched every row of
both sides into Java and *then* checked the cap. At the scale decision 11 itself cites (10 000 cases × 64
runs × 10 turns), a fully-matching comparison would have materialised ~12.8 M tuples and then passed the
cap with `unmatched == 0`. The cap bounded nothing. That is the same objection this document raises against
computing the match in Java (below), so it applied to our own design.

`DSL.count().filterWhere(...)` renders `FILTER (WHERE …)`; if it proves awkward,
`DSL.count(DSL.when(matched.and(status), 1))` is an equivalent fallback with no semantic change.

`matchedRowCount`, `matchedSuccessRowCount` and `totalRowCount` all remain in the response: the FE still
wants to display "350 of 400 matched" without deriving it from a list length. All three are per-side, and
per decision 2 the two runs' `matchedRowCount` values may differ when one holds duplicate keys — the
response is per-run-shaped throughout, so a per-run "X of Y" label is unaffected.

*Alternative rejected:* shipping matched ids plus an `allRowsMatched` boolean. It works, but needs the
flag, the `null`-vs-`[]` convention and the zero-match guard, and keeps a large payload in precisely the
case where it carries no information.

*Alternative rejected:* fetching both runs' full `(id, key)` populations and computing the match and
subtraction entirely in Java. It moves set logic out of the database, requires materialising both full
populations even when only the intersection is wanted, and defeats the cap, which is meant to bound work
*before* it is done. Note this objection also applied to an earlier draft of *this* design, which fetched
every row per side to derive the unmatched set — see decision 13 for how counts-then-ids resolves it.

### 14. Average execution duration: all matched rows, one sample per row, free on the existing join

The FE's summary page shows the run's average test-case duration next to the scores, and it is incomparable
for exactly the reason the scores are. `avgExecDurationMs` therefore recomputes it over the matched
population.

It costs **nothing**: `exec_duration_ms` is a plain `BIGINT NOT NULL` column (`V1.5:13`) on the table the
anti-join already scans, so it is a **fourth aggregate on the same statement** — no extra query, no extra
index, no change to `FilteredMetricScoreAggregator`, and the whole feature adds zero query load. This is
also why it is not expressed as a sixth built-in statistic: routing it through the Query DSL would add a
per-run query to compute something the match query can return for free, and duration is not a metric score
— it has no `metricName`, so it does not fit the `scores` array's shape.

`DSL.avg` over a `BIGINT` yields `Field<BigDecimal>`, so the carrier holds `BigDecimal` and the DTO exposes
`Double`. Since the column is `NOT NULL`, the average is NULL only for an empty matched set, where the field
is simply absent from the response (global `NON_NULL` inclusion) — the same "never null, just absent" shape
as a `scores` entry. It is computed **before** the cap check, so it is still returned when the comparison
later short-circuits to `scores: []` on zero overlap; there it is absent, which is the correct answer.

**Population: all matched rows, not SUCCESS-only.** The denominator is therefore exactly
`matchedRowCount` — this is the one aggregate in the response whose denominator a client can verify from the
response itself, which is what decided it. The cost is a known bias, recorded here rather than left to be
discovered: failure rows are included, and synthetic ERROR rows are stamped `execDurationMs = 0`
(`TestCaseRunResultFactory:49`) rather than a measurement, so a run with crashed workers reports a *lower*
average — "faster" when it means "more broken". This is acceptable because the FE can isolate the clean
population itself: `execution_status` and `exec_duration_ms` are both queryable DSL fields on
`eval_summaries` (bindings come from `JooqTableSchemaResolver.bindings(TEST_CASE_EVAL_SUMMARIES)`, which
binds every column automatically), so a SUCCESS-only mean is expressible with the same `NOT (id IN …)`
exclusion filter and needs no second server-side field.

*Alternative rejected:* SUCCESS-only (`… FILTER (WHERE matched AND execution_status = 'SUCCESS')`,
denominator `matchedSuccessRowCount`). It avoids the ERROR-zero bias, but decision 12 establishes that a row
is non-SUCCESS when merely **one metric** errored — its endpoint call, and therefore its duration, is a
perfectly valid measurement. A SUCCESS-only average would silently discard real timings because an unrelated
metric misbehaved, which is a worse distortion than the ERROR-zero bias it set out to avoid.

**Granularity: one sample per eval-summary row** — per turn, per repetition. `turn_index` defaults to `0`
and `total_turns` to `1` (`V1.14:5-6`), so for every single-turn suite this is identical to one sample per
test-case execution; the two diverge only for multi-turn cases, where this reports mean **turn** latency.

*Alternative rejected:* per test-case execution, summing a case's turns before averaging. It is closer to
the literal phrase "test case run time" and to perceived conversation cost, but it needs a
`GROUP BY (name, run_index)` pre-aggregation — so it no longer folds into the counts statement — and its
denominator is a count the response does not otherwise carry, meaning a fourth count field would have to be
added purely to make the number interpretable. Revisit if the FE asks for conversation-level latency, which
is a different question from this one.

## Risks / Trade-offs

- **~200 sequential aggregate queries for a 20-metric two-run comparison** → this is Phase 3's existing
  5×N per-run cost moved onto the request path, and the price of structural parity. Mitigation: the
  exclusion polarity of decision 13 removes the per-query UUID binding entirely in the common
  same-dataset case, leaving only the query count; measure on a realistic suite; the batching alternative
  in decision 5 is the documented lever for the count, and it is a mechanical change confined to one
  component.
- **Duplicate match keys within one run** → **no longer a risk**; decision 2 removed the collapse rather
  than mitigating it, so no row is dropped from any aggregate and no diagnostic is needed. The residual
  consequence is that such a run's `matchedRowCount` may exceed the other's, which is accurate rather than
  wrong, and is why the equality guarantee was dropped from the spec.
- **A pathological duplicate count no longer blows up** → the old self-join emitted the cross product (50
  duplicate rows on each side ⇒ 2500 pair rows); the `DISTINCT` probe set makes fan-out impossible.
- **The `MetricFieldDiscoverer` extraction touches a Phase-3 class** → `MetricScoreComputationExecutorTest`
  is the regression guard; it already asserts a discovery-derived `metricName`. It needs exactly one line
  changed (a real `MetricFieldDiscoverer` wrapping the existing mocked `OutputSchemaFieldExtractor`) and
  every stub and assertion must survive verbatim. If an assertion has to change, the extraction was not
  behaviour-neutral.
- **New `@Qualifier` injection, a new `@ConfigurationProperties` class and four new beans** → a
  compile-clean build does not prove the context starts. Mitigation: boot a functional test before writing
  behavioural assertions.
- **The two runs' `scores` arrays may be asymmetric** (different metric sets, different `overallScore`
  definitions, different `datasetRef`s are all legal for two runs of one suite) → accepted; the API
  exposes no mismatch signal and the FE must tolerate a metric present on one side only.
- **`avgExecDurationMs` is biased low by failure rows**, because synthetic ERROR rows carry a fabricated
  `execDurationMs = 0` (`TestCaseRunResultFactory:49`) and are included in the population (decision 14).
  Accepted in exchange for a denominator the client can verify (`matchedRowCount`); the FE can re-derive a
  SUCCESS-only mean over the same population through the Query DSL, since both `execution_status` and
  `exec_duration_ms` are queryable fields. Documented in the spec as behaviour, not left implicit.
- **Response size** grows linearly with the number of **non-matching** rows: ≈0.35 MB at the default cap,
  and zero for a full match (decision 13). Accepted and documented in `docs/configuration.md` as a stated
  property rather than a surprise.

## Migration Plan

Purely additive: no Flyway migration, no schema change, no data backfill, no feature flag. One new
configuration property with a default, so no deployment-time configuration is required.

Rollback is a plain revert. The only non-additive edit is the `MetricFieldDiscoverer` extraction, which is
behaviour-neutral and self-contained; reverting it restores the private method.

## Open Questions

- At what suite size does the per-(statistic, field) query count actually regress? Needs a measurement
  before the batching alternative in decision 5 is worth building.
- `request_number` is a known future fourth component of the match key. Adding it later widens the join
  predicate and the probe key set; no API change is implied, but the id lists of existing callers
  would shift.
- **Follow-up, out of scope:** `EvalSummaryExportService.resolveContext` performs the same
  meta-then-analytics `(run, computation)` resolution this service needs. Extract a shared helper when a
  third call site appears.
