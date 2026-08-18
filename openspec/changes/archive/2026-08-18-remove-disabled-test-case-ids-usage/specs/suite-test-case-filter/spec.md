## ADDED Requirements

### Requirement: Runnable subset is defined by validity and the suite test-case filter
A test suite's optional `testCaseFilter` SHALL be the single mechanism for narrowing which of its dataset's
test cases run. The `testCaseFilter` is a Structured Query DSL `filter` subtree authored over the bound
dataset's test-case fields (base columns and flattened `data::<field>` fields). When set, the runnable set
SHALL be exactly the test cases that are `is_valid = true` AND match the filter; the filter is AND-combined
with the validity predicate only and never widens the set. When `testCaseFilter` is null or absent, the
runnable set SHALL be exactly the `is_valid = true` test cases of the bound dataset. No other exclusion
source SHALL participate — in particular, a value stored in `test_suites.disabled_test_case_ids` by an
earlier version of the product SHALL NOT be read.
Status: **Implemented**

#### Scenario: Filter narrows the runnable set
- **WHEN** a suite's dataset has valid test cases `[tc-1, tc-2, tc-3]` with `data.category` values
  `["A", "B", "A"]` and `testCaseFilter` is `category IN ('A')`
- **THEN** the runnable set SHALL be `[tc-1, tc-3]`

#### Scenario: Filter is AND-combined with validity only
- **WHEN** the same suite's `tc-3` is `is_valid = false`
- **THEN** the runnable set SHALL be `[tc-1]` — validity and the filter both apply, and nothing else does

#### Scenario: Null filter selects every valid test case
- **WHEN** a suite has `testCaseFilter = null`
- **THEN** the runnable set SHALL be all `is_valid = true` test cases of the bound dataset, with no
  additional predicate

#### Scenario: Legacy stored exclusions do not narrow the runnable set
- **WHEN** a suite carries a non-empty `test_suites.disabled_test_case_ids` value stored by an earlier
  version of the product
- **THEN** the runnable set SHALL be computed as if that value were empty, so a filter matching only test
  cases named in it still yields a non-empty runnable set

## MODIFIED Requirements

### Requirement: Filter is applied consistently at run-creation count and snapshot
The same runnable definition (validity + `testCaseFilter`) SHALL be applied both by the run-creation
zero-runnable guard and by the snapshot phase that materializes `test_case_run_inputs`, so that a run is
only created when at least one test case is runnable and the snapshot contains exactly the runnable test
cases. Both SHALL reach that definition through `RunnableTestCaseSelector`, so they cannot diverge. A client
MAY preview the runnable subset through the `test_cases` query entity by AND-combining the suite's
`testCaseFilter` with `valid = true`; for a single-turn dataset that preview count SHALL equal the executed
run's count. The query entity SHALL NOT be assumed to reproduce the runnable definition on its own: it
applies neither `is_valid` nor the ALL-turns-match quantifier, so for a multi-turn dataset a raw preview MAY
over-count and parity is NOT guaranteed. A suite whose filter
matches no runnable test case SHALL be rejected at run creation with HTTP 409 `INVALID_OPERATION` and
message "Suite has no valid and enabled test cases"; no run record SHALL be persisted.
Status: **Implemented**

#### Scenario: Zero-match filter rejected at run creation
- **WHEN** a client triggers a run for a config-valid, bound suite whose `testCaseFilter` matches no
  valid test case
- **THEN** the system SHALL respond HTTP 409 `INVALID_OPERATION` with message
  "Suite has no valid and enabled test cases", and SHALL NOT persist a run or dispatch a job

#### Scenario: Snapshot materializes only filter-matching cases
- **WHEN** a run is created for a suite with a matching `testCaseFilter` and the snapshot phase runs
- **THEN** `test_case_run_inputs` SHALL contain rows for exactly the valid, filter-matching test cases,
  and `numberOfTestCases` SHALL equal that count

#### Scenario: Preview count matches the executed run for a single-turn dataset
- **WHEN** a client counts the suite's runnable test cases by applying the suite's `testCaseFilter`
  AND-combined with `valid = true` to the `test_cases` query entity for a bound single-turn dataset, then
  triggers a run for that suite
- **THEN** the persisted `numberOfTestCases` and the number of materialized `test_case_run_inputs` rows
  SHALL equal the previewed count

#### Scenario: Multi-turn preview is not guaranteed to match
- **WHEN** the same preview is taken over a multi-turn dataset with a per-turn predicate
- **THEN** the preview MAY include cases that the run excludes because at least one of their turns fails the
  predicate, so clients SHALL NOT treat the raw preview count as the runnable count

## REMOVED Requirements

### Requirement: Suite-level test-case filter defines the runnable subset
**Reason**: Restated as "Runnable subset is defined by validity and the suite test-case filter". The
definition changed from three terms (validity + `disabledTestCaseIds` + filter) to two (validity + filter),
and two of its scenarios ("Filter is AND-combined with disabled and validity", "Null filter preserves prior
behavior") describe the removed exclusion term — a MODIFIED block cannot drop scenarios, so the requirement
is restated instead.

**Migration**: None for clients. The endpoint contract is unchanged; only the removed
`disabledTestCaseIds` term leaves the runnable definition. See the ADDED requirement above.

## Implementation notes
- `service/domain/job/RunnableTestCaseSelector` exposes `countRunnable(datasetId, filterJson)` and
  `loadRunnablePage(datasetId, filterJson, offset, limit)`; the excluded-ids parameter is removed from both,
  and `experimental/query/service/QueryDslRunnableTestCaseSelector` delegates to
  `TestCaseRepository.countValidByDatasetId{,Matching}` / `findValidByDatasetId{,Matching}`.
- Removing the exclusion term also removes the `NOT (test_cases.id = ANY(?::text[]))` array-bound predicate
  from the repository's snapshot-phase condition builder.
