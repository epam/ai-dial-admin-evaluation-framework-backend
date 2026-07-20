# Suite Test Case Filter

## Purpose
This spec defines the per-suite `testCaseFilter`: an optional Structured Query DSL filter, authored
over a suite's bound dataset's test-case fields, that narrows the set of test cases a suite run
executes. It covers the runnable-subset semantics, its consistent application at run-creation count
and snapshot time, and the interface-inversion wiring that lets the run pipeline reuse the Structured
Query DSL translation layer without introducing a `service` → `experimental.query` dependency.

Status: **Implemented**

## Requirements

### Requirement: Suite-level test-case filter defines the runnable subset
A test suite's optional `testCaseFilter` SHALL define the runnable subset of its dataset's test cases.
The `testCaseFilter` is a Structured Query DSL `filter` subtree authored over the bound dataset's
test-case fields (base columns and flattened `data::<field>` fields). When set, the runnable set SHALL
be exactly the test cases that are `is_valid = true`, NOT in the suite's `disabledTestCaseIds`, AND
match the filter. The filter is AND-combined with the existing validity
and exclusion predicates; it never widens the set. When `testCaseFilter` is null or absent, selection
behaves exactly as before (validity + `disabledTestCaseIds` only).

Filter application SHALL be **row-level**, applied to individual test-case rows exactly like the
`disabledTestCaseIds` exclusion. A single-turn test case (`multi_turn_id IS NULL`) is filtered per
row. For a multi-turn (rows sharing a non-null `multi_turn_id`) the filter selects
individual turns: a non-matching turn is simply not a survivor (identical to a disabled turn). There
is NO multi-turn-level aggregation in the counting or selection path (no `GROUP BY multi_turn_id
HAVING …`); multi-turn integrity over the surviving (valid + enabled + filter-matching) turns —
contiguity from turn 0, tail-only truncation, broken on a middle hole — is resolved **only at snapshot
time** by `MultiTurnAssembler` (see the suite-run-snapshot spec). Consequently a non-matching
**tail** turn truncates the multi-turn's runnable prefix, while a non-matching **middle** turn leaves
a hole and breaks the multi-turn.
Status: **Implemented**

#### Scenario: Filter narrows the runnable set
- **WHEN** a suite's dataset has valid single-turn test cases `[tc-1, tc-2, tc-3]` with `data.category`
  values `["A", "B", "A"]`, `disabledTestCaseIds` is empty, and `testCaseFilter` is
  `category IN ('A')`
- **THEN** the runnable set SHALL be `[tc-1, tc-3]`

#### Scenario: Filter is AND-combined with disabled and validity
- **WHEN** the same suite additionally has `tc-1` in `disabledTestCaseIds` and `tc-3` is
  `is_valid = false`
- **THEN** the runnable set SHALL be empty (each of validity, exclusion, and the filter is applied)

#### Scenario: Null filter preserves prior behavior
- **WHEN** a suite has `testCaseFilter = null`
- **THEN** the runnable set SHALL be the valid, non-excluded test cases with no additional predicate

#### Scenario: Multi-turn included only when all turns match
- **WHEN** a multi-turn `conv-1` has turns `[turn-0, turn-1, turn-2]` with `data.category` values
  `["A", "A", "A"]` and `testCaseFilter` is `category IN ('A')`
- **THEN** the whole multi-turn `conv-1` SHALL be included as one runnable unit

#### Scenario: Multi-turn excluded when any turn fails the filter
- **WHEN** a multi-turn `conv-1` has turns `[turn-0, turn-1, turn-2]` with `data.category` values
  `["A", "B", "A"]` and `testCaseFilter` is `category IN ('A')`
- **THEN** the whole multi-turn `conv-1` SHALL be excluded (no partial/per-turn inclusion), leaving
  no `conv-1` turn in the runnable set

### Requirement: Filter is applied consistently at run-creation count and snapshot
The same runnable definition (validity + `disabledTestCaseIds` + `testCaseFilter`) SHALL be applied
both by the run-creation zero-runnable guard and by the snapshot phase that materializes
`test_case_run_inputs`, so that a run is only created when at least one test case is runnable and the
snapshot contains exactly the runnable test cases. A suite whose filter matches no runnable test case
SHALL be rejected at run creation with HTTP 409 `INVALID_OPERATION` and message
"Suite has no valid and enabled test cases"; no run record SHALL be persisted.
Status: **Implemented**

#### Scenario: Zero-match filter rejected at run creation
- **WHEN** a client triggers a run for a config-valid, bound suite whose `testCaseFilter` matches no
  valid, non-excluded test case
- **THEN** the system SHALL respond HTTP 409 `INVALID_OPERATION` with message
  "Suite has no valid and enabled test cases", and SHALL NOT persist a run or dispatch a job

#### Scenario: Snapshot materializes only filter-matching cases
- **WHEN** a run is created for a suite with a matching `testCaseFilter` and the snapshot phase runs
- **THEN** `test_case_run_inputs` SHALL contain rows for exactly the valid, non-excluded,
  filter-matching test cases, and `numberOfTestCases` SHALL equal that count

### Requirement: Run selection reuses the DSL translation, not the public execute endpoint
The run pipeline SHALL apply the filter by translating the stored `testCaseFilter` into a SQL
predicate (via the Structured Query DSL translation layer) and running a purpose-built, paginated
query returning full test-case rows in deterministic snapshot order
(`created_at_ms ASC, id ASC`), inside the snapshot's `REPEATABLE READ` transaction. The run pipeline
SHALL NOT route selection through the public `POST /api/v1/queries/execute` endpoint (which returns
untyped rows and caps result size). To keep the run pipeline free of a dependency on the experimental
query layer, this behavior SHALL be exposed to the run/suite services through a stable service-layer
interface implemented in the query layer (interface inversion).
Status: **Implemented**

#### Scenario: Selection paginates in snapshot order
- **WHEN** the snapshot phase selects filter-matching test cases for a large dataset
- **THEN** it SHALL page through them ordered by `created_at_ms ASC, id ASC` with the configured page
  size, assigning `test_case_run_inputs.position` in that stable order

#### Scenario: No layering violation is introduced
- **WHEN** the project's architecture test runs
- **THEN** there SHALL be no compile-time dependency from the `service` layer to the
  `experimental.query` layer (the selector is a `service`-layer interface implemented in the query
  layer)

## Implementation Notes
- New `service`-layer interface `service.domain.job.RunnableTestCaseSelector`; implementation in
  `experimental.query.service` (mirrors the `MetricScoreComputation` inversion), backed by
  `QueryDslRunnableTestCaseSelector`. The selector aggregates per `multi_turn_id` so a multi-turn
  multi-turn is included only when all of its turns match; single-turn rows
  (`multi_turn_id IS NULL`) are still evaluated per row.
- Translation reuse: `FilterTranslator`, `TestCaseFieldBindingsBuilder`; base predicate mirrors
  `PostgresTestCaseRepository.validNotExcludedCondition`, AND-combined with the translated filter
  before the per-multi-turn aggregation.
- Run wiring: `RunnableTestCaseCounter`, `TestSuiteRunService.createRun` (guard #4),
  `TestSuiteEvaluationJob.attemptSnapshot`.
- Write-time filter validation: `RunnableTestCaseSelector.validateFilter(datasetId, filterJson)`,
  invoked from `TestSuiteService` on suite create/update (see `test-suites` spec).
- Related capabilities: `query-schema-discovery` (`test_cases` complex entity), `structured-query-model`
  (array-field `co`/`nc` JSONB containment), `suite-run-snapshot` (snapshot phase selection),
  `test-suite-runs` (zero-runnable guard), `test-suites` (`testCaseFilter` field on the suite API).
