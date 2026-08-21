# Run Comparison Metric Scores

## Purpose
Compare two runs of one test suite over only the eval-summary rows they have in common, so that
run-level metric statistics, `overall` and average execution duration describe a single population and are
therefore comparable. Also returns the identity of each run's non-matching rows, so a client can reproduce
the same population in a follow-up structured query.

Status: **Implemented**

## Requirements


### Requirement: Matched-row metric-score comparison endpoint
The system SHALL expose `GET /api/v1/analytics/metric-scores/comparison?runIds=<uuidA>,<uuidB>`, which accepts exactly two distinct test suite run ids belonging to the **same** test suite and returns, for each run: its `runId`, its resolved `computationId`, `totalRowCount`, `matchedRowCount`, `matchedSuccessRowCount`, `avgExecDurationMs`, `unmatchedEvalSummaryIds`, and a `scores` array of `{metricScoreName, metricName, value}` entries.

The `runs` array SHALL be ordered as the ids were requested, and SHALL be a JSON array rather than an object keyed by run id. All counts SHALL be per-run; the system SHALL NOT guarantee that the two runs report an equal `matchedRowCount`, because a run holding duplicate match keys legitimately matches more rows than its counterpart.

The two runs MAY legitimately carry different metric sets and different `overallScore` definitions, so their `scores` arrays MAY be asymmetric; the response SHALL NOT expose a mismatch signal.
Status: **Implemented**

#### Scenario: Two runs of one suite are compared
- **WHEN** a client requests the comparison of two distinct runs of the same suite, each having eval summaries and a resolvable computation
- **THEN** the response contains one entry per run, each with its `computationId`, the three row counts, its `avgExecDurationMs`, its `unmatchedEvalSummaryIds`, and its recomputed `scores`

#### Scenario: Response order follows request order
- **WHEN** a client requests `runIds=<uuidB>,<uuidA>`
- **THEN** the first element of `runs` has `runId` equal to `<uuidB>` and the second has `<uuidA>`

#### Scenario: Counts are reported per run
- **WHEN** a comparison succeeds over runs whose populations partially overlap and neither run contains duplicate match keys
- **THEN** each run entry reports its own `totalRowCount`, `matchedRowCount` and `matchedSuccessRowCount`, and `matchedRowCount` is the same in both because each matched row has exactly one counterpart

#### Scenario: Runs with differing metric sets produce asymmetric scores
- **WHEN** the two runs' computations discovered different numeric metric fields
- **THEN** each run's `scores` array reflects only its own discovered fields, and no mismatch indicator is returned

### Requirement: Row matching by name, repetition index, request index and turn index
The system SHALL match eval-summary rows across the two runs on the composite key `lower(test_case_name)` + `run_index` + `request_index` + `turn_index`, scoped per run to that run's resolved `computation_id`.

Matching SHALL be name-based rather than test-case-id-based, so that a test case deleted and later restored under the same name still matches.

`request_index` SHALL participate in the key so that two runs of a multi-request suite compare like-for-like chain positions: a row produced by the chain's setup request SHALL never match a row produced by its test request. Because `request_index` defaults to `0` on every row of a single-request suite, adding it to the key SHALL NOT change matching for runs of suites without a chain, nor for a comparison between a run taken before the column existed and a run taken after.

A row SHALL match if and only if its key occurs in the other run's population. Where a run contains more than one row for a single key, **all** of those rows SHALL match — no row SHALL be excluded from the matched population merely because another row shares its key. Consequently `matchedRowCount + size(unmatchedEvalSummaryIds)` SHALL equal `totalRowCount` for every run, and `unmatchedEvalSummaryIds` ordering SHALL be stable across identical requests.

Execution status SHALL NOT participate in the match key.

`matchedSuccessRowCount` SHALL report how many of the run's matched rows have a SUCCESS execution status, so that a client can present a per-run success ratio over the compared population (e.g. "28/29" for one run beside "27/29" for the other). Its denominator SHALL be `matchedRowCount`.

A row SHALL be counted as successful only when its stored execution status is SUCCESS, which for a row produced by the evaluation pipeline means the test case executed **and** every metric evaluated without error. Consequently a row carrying usable values for most of its metrics and an error for one SHALL NOT be counted as successful. `matchedSuccessRowCount` SHALL NOT be interpreted as any statistic's sample size: statistics are computed over matched rows irrespective of execution status, and a non-successful row still contributes its healthy metrics' values, so a metric's denominator MAY exceed `matchedSuccessRowCount`.
Status: **Implemented**

#### Scenario: Names match case-insensitively
- **WHEN** one run has a test case named `Foo` and the other has `foo`, with equal `run_index`, `request_index` and `turn_index`
- **THEN** the two rows match and are counted in `matchedRowCount`

#### Scenario: Turn index participates in the key
- **WHEN** a same-named multi-turn test case has 3 turns in one run and 2 turns in the other
- **THEN** exactly 2 rows match — one per shared turn index

#### Scenario: Repetition index participates in the key
- **WHEN** a same-named test case was executed with `numberOfRuns` 3 in one run and 2 in the other
- **THEN** exactly 2 rows match — one per shared repetition index

#### Scenario: Request index participates in the key
- **WHEN** a same-named test case ran a 3-request chain in one run and a 2-request chain in the other
- **THEN** exactly 2 rows match — one per shared request index — and the third request's row is unmatched

#### Scenario: Single-request runs are unaffected by the request index
- **WHEN** two runs of a suite with no `additionalRequests` are compared
- **THEN** every row carries `request_index = 0`, so the matched population is identical to the pre-change result

#### Scenario: Duplicate keys within one run all match
- **WHEN** one run contains two eval-summary rows sharing the same `lower(test_case_name)`, `run_index`, `request_index` and `turn_index`, and the other run contains one row with that key
- **THEN** both rows match, that run's `matchedRowCount` exceeds the other run's, its `unmatchedEvalSummaryIds` is empty, and both rows contribute to its recomputed statistics

#### Scenario: Identical requests return identical ordering
- **WHEN** the same comparison is requested twice
- **THEN** each run's `unmatchedEvalSummaryIds` is returned in the same order both times

#### Scenario: A failed row still matches
- **WHEN** a matched row has execution status FAILED
- **THEN** it is included in `matchedRowCount` and excluded from `matchedSuccessRowCount`

#### Scenario: Per-run success ratios are reported over the compared population
- **WHEN** two runs overlap on 29 rows, of which one run has 28 successful and the other 27
- **THEN** the first run reports `matchedRowCount` 29 with `matchedSuccessRowCount` 28, and the second reports 29 with 27

#### Scenario: A row with one errored metric is not counted as successful
- **WHEN** a matched row's test case executed successfully but one of its metrics returned an error, leaving its other metrics with usable values
- **THEN** the row is excluded from `matchedSuccessRowCount` while its healthy metrics' values still contribute to those metrics' statistics, so a metric's denominator exceeds `matchedSuccessRowCount`

### Requirement: Statistics and overall score recomputed over matched rows only
The system SHALL recompute, per run and over only that run's matched rows, each built-in per-metric statistic for every numeric metric field discovered from the run's metric snapshots, plus the run-level `overall` score derived from the run's suite-snapshot `overallScore` definition.

`overall` SHALL follow the same inclusion rule as the persisted computation: a non-null definition is always computed; a null definition is computed only when the run has exactly one discovered numeric metric field. The definition SHALL be resolved against the run's **full** discovered field list, never a subset, so that a mean's divisor is unchanged.

An entry whose aggregate evaluates to SQL NULL SHALL be omitted from `scores`, so a returned `value` is never null.

Each entry's `metricScoreName` SHALL be the built-in statistic name (`AVG`, `P10`, `P90`, `MIN`, `MAX`) or `overall`, and `metricName` SHALL use the persisted `<tsmdName>.<outputField>` form — byte-identical to the corresponding `metric_score_results.metric_name`, with dots inside a tsmd name preserved. `metricName` SHALL NOT be derived by splitting a persisted value on `.`.

Recomputed values SHALL NOT be persisted; the endpoint SHALL NOT write to or read from stored metric-score results.
Status: **Implemented**

#### Scenario: Full overlap reproduces the persisted values
- **WHEN** two runs have identical test-case populations, so every row matches
- **THEN** each returned score equals the corresponding persisted metric-score result for that run and computation

#### Scenario: A null aggregate is omitted
- **WHEN** a discovered metric field has no numeric values among the matched rows
- **THEN** that field's entries are absent from `scores` rather than present with a null `value`

#### Scenario: Default overall is computed for a single metric field
- **WHEN** a run's suite snapshot has no `overallScore` definition and the run discovered exactly one numeric metric field
- **THEN** `overall` is returned as that field's average over the matched rows

#### Scenario: Default overall is skipped for multiple metric fields
- **WHEN** a run's suite snapshot has no `overallScore` definition and the run discovered more than one numeric metric field
- **THEN** no `overall` entry is returned for that run

#### Scenario: A mean definition divides by the full discovered field count
- **WHEN** a run's `overallScore` is a mean and one of its discovered metric fields has no values among the matched rows
- **THEN** `overall` is still divided by the full discovered field count, with the missing field contributing zero

#### Scenario: Nothing is persisted
- **WHEN** a comparison is requested
- **THEN** no metric-score result row is created, updated or deleted, and the persisted full-population values remain unchanged

### Requirement: Average execution duration over matched rows
The system SHALL report, per run, `avgExecDurationMs` — the mean `exec_duration_ms` of that run's matched eval-summary rows.

The population SHALL be **all** matched rows regardless of execution status, so the denominator SHALL be exactly `matchedRowCount`. Each matched row SHALL contribute exactly one sample, meaning one sample per turn per repetition; for a single-turn test case that is one sample per execution.

Rows whose execution failed SHALL be included. The system MAY therefore report a lower average for a run containing failure rows, because a row synthesised for a crashed execution carries a zero duration rather than a measurement; clients needing a success-only mean SHALL derive it themselves over the same population via the structured Query API, where `execution_status` and `exec_duration_ms` are both queryable fields.

`avgExecDurationMs` SHALL be absent from a run's entry when that run has no matched rows. It SHALL NOT be reported as zero in that case, because zero is a valid average.
Status: **Implemented**

#### Scenario: The average is scoped to the matched rows
- **WHEN** a run's matched rows have durations 100 ms and 300 ms and it also has a non-matching row of 9000 ms
- **THEN** `avgExecDurationMs` is 200, not the run-wide average

#### Scenario: A failed row participates in the average
- **WHEN** a matched row has a non-SUCCESS execution status
- **THEN** its duration is included in `avgExecDurationMs`, whose denominator remains `matchedRowCount`

#### Scenario: Duplicate match keys contribute every row
- **WHEN** a run has two matched rows sharing one match key, with different durations
- **THEN** both durations are averaged, consistent with both rows counting toward `matchedRowCount`

#### Scenario: No matched rows yields no average
- **WHEN** a run has no matched rows
- **THEN** `avgExecDurationMs` is absent from that run's entry rather than zero

#### Scenario: Multi-turn rows are averaged per turn
- **WHEN** a matched multi-turn test case produced one eval-summary row per turn
- **THEN** each turn contributes one sample, so the average is a mean turn duration rather than a mean conversation duration

### Requirement: Excluded-row identity for follow-up queries
The system SHALL return, per run, the identifiers of that run's eval-summary rows that did **not** match — `unmatchedEvalSummaryIds` — so that a client can reproduce the matched population in a follow-up structured query by excluding them.

An empty list SHALL mean that every row of the run matched, and therefore that no row filter is required. A list containing every row of the run SHALL mean that nothing matched. No additional discriminator field is required to distinguish these cases.
Status: **Implemented**

#### Scenario: Full overlap returns an empty exclusion list
- **WHEN** every row of a run matched
- **THEN** that run's `unmatchedEvalSummaryIds` is an empty array and `matchedRowCount` equals `totalRowCount`

#### Scenario: Partial overlap returns the non-matching rows
- **WHEN** a run has rows that did not match
- **THEN** that run's `unmatchedEvalSummaryIds` contains exactly those rows' identifiers, and `matchedRowCount + size(unmatchedEvalSummaryIds)` equals `totalRowCount`

#### Scenario: One run may match fully while the other does not
- **WHEN** one run's population is a strict subset of the other's
- **THEN** the subset run returns an empty `unmatchedEvalSummaryIds` and the other returns a populated one, in the same response

#### Scenario: Zero overlap returns no scores
- **WHEN** the two runs share no matching rows
- **THEN** `matchedRowCount` is 0 and `scores` is empty for both runs

#### Scenario: A run with no eval summaries
- **WHEN** one run has a resolvable computation but no eval-summary rows
- **THEN** that run reports `totalRowCount` 0, `matchedRowCount` 0, an empty `unmatchedEvalSummaryIds` and an empty `scores`, and the request still succeeds

### Requirement: Comparison always uses each run's latest computation
The system SHALL resolve each run's computation to that run's most recent one, and SHALL NOT accept a client-supplied computation override. The resolved computation SHALL be used both for row matching and for aggregation, and SHALL be reported as `computationId` in the response.
Status: **Implemented**

#### Scenario: The latest computation is used
- **WHEN** a run has been evaluated more than once, producing multiple computations
- **THEN** only the most recent computation's rows participate, and its id is returned as `computationId`

#### Scenario: A run without any computation is rejected
- **WHEN** a run has no metric snapshots, so no computation can be resolved
- **THEN** the request fails with 409 `INVALID_OPERATION`

#### Scenario: Missing persisted scores do not prevent comparison
- **WHEN** a run has metric snapshots for its latest computation but no persisted metric-score results
- **THEN** the comparison still returns the full set of recomputed statistics

### Requirement: Comparison request validation and error semantics
The system SHALL reject a malformed or unsatisfiable comparison request with a specific status: 400 `VALIDATION_ERROR` when `runIds` is absent, does not contain exactly two values, contains a non-UUID, or contains the same id twice; 404 `NOT_FOUND` when either run does not exist; 409 `INVALID_OPERATION` when the two runs belong to different test suites; and 422 `SNAPSHOT_SUITE_MISSING` when either run has no stored suite snapshot.

The endpoint SHALL NOT be gated on run status — a run in any terminal or non-terminal state whose rows exist is comparable. The stored snapshot's schema **version** SHALL NOT be validated, because only the `overallScore` definition is read from it.

Guards SHALL be evaluated in this order: (1) 400 request shape, (2) 404 unknown run, (3) 409 different test suites, (4) 422 missing suite snapshot, (5) 409 no resolvable computation, (6) 409 exclusion-list cap.
Status: **Implemented**

#### Scenario: Wrong number of run ids
- **WHEN** `runIds` contains one id, or three
- **THEN** the request fails with 400 `VALIDATION_ERROR`

#### Scenario: The same run twice
- **WHEN** `runIds` names the same run id twice
- **THEN** the request fails with 400 `VALIDATION_ERROR`

#### Scenario: Unknown run
- **WHEN** either id does not correspond to an existing run
- **THEN** the request fails with 404 `NOT_FOUND`

#### Scenario: Runs from different suites
- **WHEN** the two runs belong to different test suites
- **THEN** the request fails with 409 `INVALID_OPERATION`

#### Scenario: Legacy run without a suite snapshot
- **WHEN** either run has no stored suite snapshot
- **THEN** the request fails with 422 `SNAPSHOT_SUITE_MISSING`

#### Scenario: A cancelled run is still comparable
- **WHEN** one of the runs has status CANCELLED but has eval summaries and a resolvable computation
- **THEN** the comparison succeeds over the rows that exist

### Requirement: Bounded exclusion list
The system SHALL bound the number of non-matching rows a single comparison may report via a configurable maximum, and SHALL reject a comparison that exceeds it with 409 `INVALID_OPERATION`, naming both the actual count and the configured limit.
Status: **Implemented**

#### Scenario: Exclusion list within the limit
- **WHEN** the number of non-matching rows for both runs is at or below the configured maximum
- **THEN** the comparison succeeds and returns the full exclusion lists

#### Scenario: Exclusion list exceeds the limit
- **WHEN** either run's number of non-matching rows exceeds the configured maximum
- **THEN** the request fails with 409 `INVALID_OPERATION` and the message names the count and the limit

## Implementation notes

- Endpoint: `web/controller/RunComparisonController` (stable `web` layer), depending on
  `service/domain/analytics/RunComparisonProvider`.
- Orchestration: `experimental/query/service/metricscore/RunComparisonService` — it must live in the
  experimental package because it drives `StructuredQueryService`, and is reached from `web` through the
  `service`-layer interface, so `LayeredArchitectureTest` needs no exception.
- Matching: `PostgresEvalSummaryRepository.countMatches` / `findUnmatchedIds` — a per-side left join against
  the other run's `DISTINCT` key set, carrying the three counts and the duration average as four aggregates
  on one statement. Measured plans: hash left join for the counts, merge anti join for the ids (see the
  change's `design.md`).
- Recomputation: `FilteredMetricScoreAggregator` runs Phase 3's own query definitions
  (`BuiltInMetricStatistics`, `OverallScoreDefinitionResolver`) with one ANDed `not(id in [...])` predicate,
  which is what makes full-overlap parity with the persisted values structural rather than coincidental.
  Field discovery is shared with Phase 3 via `MetricFieldDiscoverer`.
- Cap: `analytics.comparison.max-unmatched-rows` (`RunComparisonProperties`).
- Rationale and rejected alternatives: `openspec/changes/run-comparison-metric-scores/design.md`.
