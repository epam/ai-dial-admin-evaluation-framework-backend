## MODIFIED Requirements

### Requirement: Matched-row metric-score comparison endpoint
The system SHALL expose `GET /api/v1/analytics/metric-scores/comparison?runIds=<uuidA>,<uuidB>`, which accepts exactly two distinct test suite run ids and returns, for each run: its `runId`, its resolved `computationId`, `totalRowCount`, `matchedRowCount`, `matchedSuccessRowCount`, `avgExecDurationMs`, `unmatchedEvalSummaryIds`, `unmatchedEvalTestCaseIds`, and a `scores` array of `{metricScoreName, metricName, value}` entries.

The two runs SHALL be comparable when they belong to the same test suite, or when their stored suite snapshots reference the same dataset id. The dataset SHALL be taken from each run's suite snapshot — the dataset the run executed against — not from the suite's current binding, and the dataset version SHALL NOT be compared. A run whose snapshot carries no dataset reference SHALL be comparable only with a run of its own suite. The response SHALL NOT signal which of the two conditions held.

The `runs` array SHALL be ordered as the ids were requested, and SHALL be a JSON array rather than an object keyed by run id. All counts SHALL be per-run; the system SHALL NOT guarantee that the two runs report an equal `matchedRowCount`, because a run holding duplicate match keys legitimately matches more rows than its counterpart.

The two runs MAY legitimately carry different metric sets and different `overallScore` definitions, so their `scores` arrays MAY be asymmetric; the response SHALL NOT expose a mismatch signal.
Status: **Implemented**

#### Scenario: Two runs of one suite are compared
- **WHEN** a client requests the comparison of two distinct runs of the same suite, each having eval summaries and a resolvable computation
- **THEN** the response contains one entry per run, each with its `computationId`, the three row counts, its `avgExecDurationMs`, its `unmatchedEvalSummaryIds`, its `unmatchedEvalTestCaseIds`, and its recomputed `scores`

#### Scenario: Runs of different suites sharing a dataset are compared
- **WHEN** a client requests the comparison of two runs of different suites whose suite snapshots reference the same dataset, each having eval summaries and a resolvable computation
- **THEN** the request succeeds, rows match on the same key as for two runs of one suite, and each run's `scores` are recomputed over its matched test cases

#### Scenario: Runs of different suites over unrelated datasets are rejected
- **WHEN** a client requests the comparison of two runs of different suites whose suite snapshots reference different datasets
- **THEN** the request fails with 409 `INVALID_OPERATION` and no comparison is computed

#### Scenario: Runs of one suite over different datasets are compared
- **WHEN** two runs of the same suite were taken while the suite was bound to different datasets
- **THEN** the request is not rejected on account of the differing datasets

#### Scenario: A different-suite run without a dataset reference is rejected
- **WHEN** two runs of different suites are compared and either run's suite snapshot carries no dataset reference
- **THEN** the request fails with 409 `INVALID_OPERATION`

#### Scenario: Response order follows request order
- **WHEN** a client requests `runIds=<uuidB>,<uuidA>`
- **THEN** the first element of `runs` has `runId` equal to `<uuidB>` and the second has `<uuidA>`

#### Scenario: Counts are reported per run
- **WHEN** a comparison succeeds over runs whose populations partially overlap and neither run contains duplicate match keys
- **THEN** each run entry reports its own `totalRowCount`, `matchedRowCount` and `matchedSuccessRowCount`, and `matchedRowCount` is the same in both because each matched row has exactly one counterpart

#### Scenario: Runs with differing metric sets produce asymmetric scores
- **WHEN** the two runs' computations discovered different numeric metric fields
- **THEN** each run's `scores` array reflects only its own discovered fields, and no mismatch indicator is returned

### Requirement: Comparison request validation and error semantics
The system SHALL reject a malformed or unsatisfiable comparison request with a specific status: 400 `VALIDATION_ERROR` when `runIds` is absent, does not contain exactly two values, contains a non-UUID, or contains the same id twice; 404 `NOT_FOUND` when either run does not exist; 422 `SNAPSHOT_SUITE_MISSING` when either run has no stored suite snapshot; and 409 `INVALID_OPERATION` when the two runs share neither a test suite nor a snapshot dataset.

The endpoint SHALL NOT be gated on run status — a run in any terminal or non-terminal state whose rows exist is comparable. The stored snapshot's schema **version** SHALL NOT be validated, because only the `overallScore` definition and the dataset reference are read from it.

Guards SHALL be evaluated in this order: (1) 400 request shape, (2) 404 unknown run, (3) 422 missing suite snapshot, (4) 409 neither suite nor dataset shared, (5) 409 no resolvable computation, (6) 409 exclusion-list cap.
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
- **WHEN** the two runs belong to different test suites and their suite snapshots reference different datasets
- **THEN** the request fails with 409 `INVALID_OPERATION`

#### Scenario: Legacy run without a suite snapshot
- **WHEN** either run has no stored suite snapshot
- **THEN** the request fails with 422 `SNAPSHOT_SUITE_MISSING`, even when the runs also belong to different suites

#### Scenario: A cancelled run is still comparable
- **WHEN** one of the runs has status CANCELLED but has eval summaries and a resolvable computation
- **THEN** the comparison succeeds over the rows that exist
