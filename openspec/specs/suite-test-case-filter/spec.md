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
Status: **Implemented**

#### Scenario: Filter narrows the runnable set
- **WHEN** a suite's dataset has valid test cases `[tc-1, tc-2, tc-3]` with `data.category` values
  `["A", "B", "A"]`, `disabledTestCaseIds` is empty, and `testCaseFilter` is
  `category IN ('A')`
- **THEN** the runnable set SHALL be `[tc-1, tc-3]`

#### Scenario: Filter is AND-combined with disabled and validity
- **WHEN** the same suite additionally has `tc-1` in `disabledTestCaseIds` and `tc-3` is
  `is_valid = false`
- **THEN** the runnable set SHALL be empty (each of validity, exclusion, and the filter is applied)

#### Scenario: Null filter preserves prior behavior
- **WHEN** a suite has `testCaseFilter = null`
- **THEN** the runnable set SHALL be the valid, non-excluded test cases with no additional predicate

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

### Requirement: ALL-turns-match filtering for multi-turn cases
When a suite `testCaseFilter` is applied to runnable-case selection, filtering SHALL be scope-aware. A predicate on a **shared** (`perTurn=false`) field SHALL bind to the case's shared `data` map (a row-level predicate, constant across turns). A predicate on a **per-turn** (`perTurn=true`) field SHALL bind to the individual turn element and be evaluated under a universal quantifier: a multi-turn case matches the per-turn predicate if and only if **every** turn satisfies it. A single-turn case is the trivial one-turn case (identical to current behavior). A turn for which a per-turn predicate is unknown/null (e.g. a missing field) SHALL count as failing. A filter MAY combine shared and per-turn predicates; the shared parts are evaluated once at row level and the per-turn parts under the all-turns quantifier.
Status: **Implemented**

#### Scenario: Shared-field filter selects at case level
- **WHEN** a filter references a shared field (e.g. `tags co "a"`) and the case's shared `data` satisfies it
- **THEN** the case is selected regardless of turn count, and a case whose shared `data` fails the predicate is excluded

#### Scenario: All turns match a per-turn filter
- **WHEN** a filter references a per-turn field and every turn of a multi-turn case satisfies it
- **THEN** the case is selected as runnable

#### Scenario: One non-matching turn excludes the case
- **WHEN** at least one turn of a multi-turn case fails a per-turn predicate
- **THEN** the entire case is excluded from runnable selection

#### Scenario: Missing per-turn field fails
- **WHEN** a turn lacks the per-turn field referenced by the filter
- **THEN** that turn is treated as failing and the case is excluded

### Requirement: Filter compiles once over a coalesced turns array
The filter SHALL be compiled once using scope-aware bindings: shared-field bindings resolve against the outer row's `data`, and per-turn-field bindings resolve against a per-turn element. The per-turn portion SHALL be wrapped as a universal quantifier over `COALESCE(multi_turn_data, jsonb_build_array(data))` using a `NOT EXISTS (... WHERE (<filter>) IS NOT TRUE)` lateral, correlated to the outer row so shared-field references remain valid inside the lateral. The lateral is added only when a filter is present, so unfiltered selection is unchanged.
Status: **Implemented**

#### Scenario: No filter leaves selection unchanged
- **WHEN** a suite has no `testCaseFilter`
- **THEN** runnable selection issues today's query with no coalesce/lateral

#### Scenario: Shared reference resolves inside the lateral
- **WHEN** a filter mixes a shared-field predicate and a per-turn predicate
- **THEN** the compiled SQL evaluates the shared predicate against the outer row's `data` and the per-turn predicate per turn element, with the shared reference correlated correctly inside the `NOT EXISTS` lateral

## Implementation Notes
- New `service`-layer interface `service.domain.job.RunnableTestCaseSelector`; implementation in
  `experimental.query.service` (mirrors the `MetricScoreComputation` inversion), backed by
  `QueryDslRunnableTestCaseSelector`.
- Multi-turn ALL-turns-match: `TestCaseFieldBindingsBuilder` overload binds `data::<field>` against a
  per-turn JSONB element (`elem`); `QueryDslRunnableTestCaseSelector.compile()` compiles the filter
  against `elem` and wraps the `Condition` in the `NOT EXISTS` lateral over
  `COALESCE(multi_turn_data, jsonb_build_array(data))`, passed to `PostgresTestCaseRepository` as an
  opaque `extraCondition`.
- Translation reuse: `FilterTranslator`, `TestCaseFieldBindingsBuilder`; base predicate mirrors
  `PostgresTestCaseRepository.validNotExcludedCondition`.
- Run wiring: `RunnableTestCaseCounter`, `TestSuiteRunService.createRun` (guard #4),
  `TestSuiteEvaluationJob.attemptSnapshot`.
- Write-time filter validation: `RunnableTestCaseSelector.validateFilter(datasetId, filterJson)`,
  invoked from `TestSuiteService` on suite create/update (see `test-suites` spec).
- Related capabilities: `query-schema-discovery` (`test_cases` complex entity), `structured-query-model`
  (array-field `co`/`nc` JSONB containment), `suite-run-snapshot` (snapshot phase selection),
  `test-suite-runs` (zero-runnable guard), `test-suites` (`testCaseFilter` field on the suite API).
