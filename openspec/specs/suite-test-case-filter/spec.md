# Suite Test Case Filter

## Purpose
This spec defines the per-suite `testCaseFilter`: an optional Structured Query DSL filter, authored
over a suite's bound dataset's test-case fields, that narrows the set of test cases a suite run
executes. It covers the runnable-subset semantics, its consistent application at run-creation count
and snapshot time, and the wiring that lets the run pipeline reuse the Structured Query DSL
translation layer.

Status: **Implemented**

## Requirements

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

### Requirement: Filter is applied consistently at run-creation count and snapshot
The same runnable definition (validity + `testCaseFilter`) SHALL be applied both by the run-creation
zero-runnable guard and by the snapshot phase that materializes `test_case_run_inputs`, so that a run is
only created when at least one test case is runnable and the snapshot contains exactly the runnable test
cases. Both SHALL reach that definition through `RunnableTestCaseSelector`, so they cannot diverge. A client
MAY preview the runnable subset through the `test_cases` query entity by AND-combining the suite's
`testCaseFilter` with `valid = true`; for a single-turn dataset that preview count SHALL equal the executed
run's count. The query entity SHALL NOT be assumed to reproduce the runnable definition on its own: it
applies neither `is_valid` nor the ALL-turns-match quantifier (see "ALL-turns-match filtering for multi-turn
cases"), so for a multi-turn dataset a raw preview MAY over-count and parity is NOT guaranteed. A suite whose
filter matches no runnable test case
SHALL be rejected at run creation with HTTP 409 `INVALID_OPERATION` and message
"Suite has no valid and enabled test cases"; no run record SHALL be persisted.
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

### Requirement: Run selection reuses the DSL translation, not the public execute endpoint
The run pipeline SHALL apply the filter by translating the stored `testCaseFilter` into a SQL
predicate (via the Structured Query DSL translation layer) and running a purpose-built, paginated
query returning full test-case rows in deterministic snapshot order
(`created_at_ms ASC, id ASC`), inside the snapshot's `REPEATABLE READ` transaction. The run pipeline
SHALL NOT route selection through the public `POST /api/v1/queries/execute` endpoint (which returns
untyped rows and caps result size). This behavior SHALL be exposed to the run/suite services through a
direct dependency on the concrete `query.service.QueryDslRunnableTestCaseSelector` class, which lives
alongside the rest of the Query DSL classes it drives; `LayeredArchitectureTest` folds `query.service`
(and `query.web`/`query.model`) into the standard `web`/`service` architectural layers, so this is an
ordinary `service` → `service` edge rather than a cross-layer dependency requiring interface inversion.
Status: **Implemented**

#### Scenario: Selection paginates in snapshot order
- **WHEN** the snapshot phase selects filter-matching test cases for a large dataset
- **THEN** it SHALL page through them ordered by `created_at_ms ASC, id ASC` with the configured page
  size, assigning `test_case_run_inputs.position` in that stable order

#### Scenario: No layering violation is introduced
- **WHEN** the project's architecture test runs
- **THEN** the direct compile-time dependency from `service`-layer classes (`TestSuiteService`,
  `TestSuiteRunService`, `TestSuiteEvaluationJob`) to `query.service.QueryDslRunnableTestCaseSelector`
  SHALL NOT be flagged as a layering violation, because `LayeredArchitectureTest` defines the `service`
  layer as `SERVICE_PACKAGE` union `QUERY_SERVICE_PACKAGE`/`QUERY_MODEL_PACKAGE`

### Requirement: ALL-turns-match filtering for multi-turn cases
When a suite `testCaseFilter` is applied to runnable-case selection, filtering SHALL be scope-aware. A predicate on a **shared** (`perTurn=false`) field SHALL bind to the case's shared `data` map (a row-level predicate, constant across turns). A predicate on a **per-turn** (`perTurn=true`) field SHALL bind to the individual turn element and be evaluated under a universal quantifier: a multi-turn case matches the per-turn predicate if and only if **every** turn satisfies it. A single-turn case is the trivial one-turn case (identical to current behavior). A turn whose per-turn predicate is not satisfied SHALL count as failing, and per-operator null semantics decide satisfaction for a missing or null field: a **positive** predicate (`co`, `eq`, `lt`, `gt`, `le`, `ge`, `in`) SHALL NOT be satisfied by a missing/null field, while a **negated** predicate (`nc`, `ne` with a non-null operand, `not(...)`) SHALL be satisfied by it. A filter MAY combine shared and per-turn predicates; the shared parts are evaluated once at row level and the per-turn parts under the all-turns quantifier.
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
- **WHEN** a turn lacks the per-turn field referenced by a positive filter predicate (e.g. `category eq "A"`)
- **THEN** that turn is treated as failing and the case is excluded

#### Scenario: Missing per-turn field satisfies a negated predicate
- **WHEN** a filter is a negated predicate on a per-turn field (e.g. `expected nc "London"`) and some turns of a multi-turn case have a missing or null value for that field while no turn holds a value that violates the predicate
- **THEN** every turn is treated as satisfying the predicate and the case is selected as runnable

#### Scenario: A violating turn still excludes the case under a negated predicate
- **WHEN** a filter is `expected nc "London"` and one turn of a multi-turn case has `expected = "London"`
- **THEN** that turn fails and the entire case is excluded, regardless of the other turns' null values

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
- `query.service.QueryDslRunnableTestCaseSelector`, injected directly into `TestSuiteService`,
  `TestSuiteRunService`, and `TestSuiteEvaluationJob`.
- Multi-turn ALL-turns-match: `TestCaseFieldBindingsBuilder` overload binds `data::<field>` against a
  per-turn JSONB element (`elem`); `QueryDslRunnableTestCaseSelector.compile()` compiles the filter
  against `elem` and wraps the `Condition` in the `NOT EXISTS` lateral over
  `COALESCE(multi_turn_data, jsonb_build_array(data))`, passed to `PostgresTestCaseRepository` as an
  opaque `extraCondition`.
- Per-operator null polarity lives entirely in `FilterTranslator` (see the `structured-query-model` spec's
  null-handling requirement): negated comparisons render as `(<pred>) IS NOT FALSE`, so a total leaf never
  reaches the lateral's `IS NOT TRUE` as UNKNOWN and `QueryDslRunnableTestCaseSelector` needs no knowledge
  of operator polarity. End-to-end proof:
  `MultiTurnFilterFunctionalTests.negatedFilterTreatsMissingPerTurnValueAsMatching` (GH #141).
- Translation reuse: `FilterTranslator`, `TestCaseFieldBindingsBuilder`; base predicate mirrors
  `PostgresTestCaseRepository.validCondition` (`dataset_id` + `is_valid`).
- `RunnableTestCaseSelector` exposes `countRunnable(datasetId, filterJson)` and
  `loadRunnablePage(datasetId, filterJson, offset, limit)`; `QueryDslRunnableTestCaseSelector` delegates to
  `TestCaseRepository.countValidByDatasetId{,Matching}` / `findValidByDatasetId{,Matching}`.
- Run wiring: `TestSuiteRunService.createRun` (guard #4) calls the selector directly (the former
  `RunnableTestCaseCounter` pass-through is deleted); `TestSuiteEvaluationJob.attemptSnapshot`.
- Write-time filter validation: `RunnableTestCaseSelector.validateFilter(datasetId, filterJson)`,
  invoked from `TestSuiteService` on suite create/update (see `test-suites` spec).
- Related capabilities: `query-schema-discovery` (`test_cases` complex entity), `structured-query-model`
  (array-field `co`/`nc` JSONB containment), `suite-run-snapshot` (snapshot phase selection),
  `test-suite-runs` (zero-runnable guard), `test-suites` (`testCaseFilter` field on the suite API).
