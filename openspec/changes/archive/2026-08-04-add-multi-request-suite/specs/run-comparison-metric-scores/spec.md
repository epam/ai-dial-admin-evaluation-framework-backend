## RENAMED Requirements

- FROM: `### Requirement: Row matching by name, repetition index and turn index`
- TO: `### Requirement: Row matching by name, repetition index, request index and turn index`

## MODIFIED Requirements

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
