## ADDED Requirements

### Requirement: `test_case_eval_scores` Query DSL entity resolves the latest computation per run
The `test_case_eval_scores` Query DSL entity SHALL accept the sentinel `computation_id eq "latest"` for any filter shape — a single pinned run, several runs selected by a literal list or an `in` subquery, or no run predicate — and resolve each selected run's own latest computation, per `latest-computation-resolution`. This SHALL allow per-run outcome counts (for example `count(*)` grouped by `test_suite_run_id, execution_status, passed`) over the last N runs of a suite to be computed against each run's latest computation in a single request, without a recomputed run being counted once per computation.

The entity SHALL expose only rows that exist: a test case without a `test_case_eval_scores` row (a suite with no effective per-row score definition, or a `SUCCESS` test case with no computed score) SHALL NOT be synthesised by this resolution.
Status: **Implemented**

#### Scenario: Per-run outcome counts against the latest computation
- **WHEN** a structured query on `test_case_eval_scores` filters `test_suite_run_id in (<subquery returning the last N runs of a suite>) and computation_id eq "latest"` and aggregates `count(*)` grouped by `test_suite_run_id, execution_status, passed`
- **THEN** each of the N runs returns counts only from its latest computation, one row per `(execution_status, passed)` combination that exists for it

#### Scenario: Recomputed run is not double counted
- **WHEN** a run has two computations, each with 10 `test_case_eval_scores` rows, and the query above is executed
- **THEN** the run's counts total 10, not 20

#### Scenario: No row is synthesised for a test case without a score row
- **WHEN** a run's latest computation has a `SUCCESS` test case with no computed score and therefore no `test_case_eval_scores` row
- **THEN** that test case does not appear in any count
