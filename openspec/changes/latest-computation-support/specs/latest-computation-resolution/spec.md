## ADDED Requirements

### Requirement: `computation_id eq "latest"` resolves each run's own latest computation
The system SHALL resolve the sentinel `computation_id eq "latest"` (case-insensitive) on every opted-in Query DSL entity — `test_case_eval_scores`, `metric_score_results` and `test_case_metric_scores` — whichever way the filter selects its runs: a single pinned run, a literal list (`test_suite_run_id in [...]`), a subquery-valued `in`, or no run predicate at all. Each run in the result set SHALL contribute only the rows of **its own** latest computation. The sentinel SHALL never be parsed as a UUID.
Status: **Implemented**

#### Scenario: Multiple runs selected by a literal list
- **WHEN** a structured query on `test_case_eval_scores` filters `test_suite_run_id in [A, B] and computation_id eq "latest"`
- **THEN** the result contains only run A's rows from A's latest computation and run B's rows from B's latest computation

#### Scenario: Runs selected by an `in` subquery
- **WHEN** a structured query filters `test_suite_run_id in (<subquery on eval_summaries returning the last N runs of a suite>) and computation_id eq "latest"`
- **THEN** each of those runs contributes only the rows of its own latest computation

#### Scenario: A recomputed run is counted once
- **WHEN** run A has two computations and a query aggregates `test_case_eval_scores` grouped by `test_suite_run_id, execution_status, passed` with `computation_id eq "latest"`
- **THEN** the counts reflect only A's newest computation, not the sum of both

#### Scenario: No run predicate
- **WHEN** a structured query filters only `computation_id eq "latest"`
- **THEN** every non-deleted run contributes its own latest computation's rows, and the sentinel is not rejected as an invalid UUID

#### Scenario: Sentinel is case-insensitive
- **WHEN** the sentinel value is written `LATEST` or `Latest`
- **THEN** it resolves exactly as `latest` does

### Requirement: "Latest" is the canonical latest computation read from eval summaries
For every opted-in entity, the latest computation of a run SHALL be the one with the greatest `computed_at_ms` among the run's rows in `test_case_eval_summaries`, ties broken by the smallest `computation_id`. This SHALL be the same computation `ComputationResolver` resolves for that run. A run whose latest computation has no rows in the queried entity SHALL contribute no rows; the query SHALL NOT fall back to an earlier computation.
Status: **Implemented**

#### Scenario: Same-millisecond computations
- **WHEN** a run has two computations whose eval summaries share the same `computed_at_ms`
- **THEN** the computation with the smaller `computation_id` is treated as latest, matching `ComputationResolver`

#### Scenario: Latest computation without rows in the queried entity
- **WHEN** a run's newest computation (by eval summaries) has no `test_case_eval_scores` rows but an older computation does
- **THEN** a `computation_id eq "latest"` query on `test_case_eval_scores` returns no rows for that run

#### Scenario: Agreement with the single-run resolver
- **WHEN** the same run is queried once with `test_suite_run_id eq X and computation_id eq "latest"` and once as part of a multi-run filter
- **THEN** both queries read the same computation for run X

### Requirement: A single pinned run keeps its literal resolution
For a query that pins exactly one run (`test_suite_run_id eq X`), the system SHALL continue to resolve `computation_id eq "latest"` to the run's latest computation id before translation, exactly as before. The multi-run resolution SHALL apply only to filters the literal path does not resolve.
Status: **Implemented**

#### Scenario: Single pinned run unchanged
- **WHEN** a query filters `test_suite_run_id eq X and computation_id eq "latest"` and X has eval summaries
- **THEN** the query is scoped to X's latest computation id, and the result equals the previous behaviour

### Requirement: A run without eval summaries contributes no rows
A run that has no rows in `test_case_eval_summaries` has no latest computation. For a query with `computation_id eq "latest"` such a run SHALL contribute no rows, and the query SHALL NOT fail because of it.
Status: **Implemented**

#### Scenario: One run of several has no eval summaries
- **WHEN** a multi-run `computation_id eq "latest"` query includes a run with no eval summaries
- **THEN** the other runs return their latest computation's rows and the run without eval summaries returns none

#### Scenario: Single pinned run with no eval summaries
- **WHEN** a query filters `test_suite_run_id eq X and computation_id eq "latest"` and X has no eval summaries
- **THEN** the result is empty, not a validation error for the sentinel

### Requirement: Scope of the sentinel rewrite
Only the `eq` form of `computation_id eq "latest"` SHALL be resolved. It SHALL be resolved wherever it appears in the top-level filter tree (including inside `and`, `or` and `not`) with the same meaning as at the root. The `ne` and `in` forms with `"latest"`, and a `"latest"` inside a nested subquery's own filter, SHALL be rejected as before. An explicit `computation_id` value (`eq <uuid>`, `in [...]`) SHALL be honoured verbatim, and an omitted `computation_id` SHALL span all computations.
Status: **Implemented**

#### Scenario: Sentinel nested in a logical node
- **WHEN** the filter is `or(and(test_suite_run_id eq A, computation_id eq "latest"), test_suite_run_id eq B)`
- **THEN** run A is limited to its latest computation and run B is unaffected

#### Scenario: Negated sentinel
- **WHEN** the filter is `not(computation_id eq "latest")` for a run with several computations
- **THEN** the run's non-latest computations are returned, following the Query DSL null-polarity rule for `not`

#### Scenario: Unsupported forms are still rejected
- **WHEN** a query uses `computation_id in ["latest"]` or `computation_id ne "latest"`
- **THEN** the sentinel is not resolved and the query is rejected as before

#### Scenario: Explicit and omitted computation unchanged
- **WHEN** a query filters `computation_id eq <uuid>`, or omits `computation_id`
- **THEN** the explicit value is honoured verbatim, and omission spans all of the run's computations with no implicit latest-defaulting

### Requirement: The resolution mechanism is not part of the public query surface
The internal field used to resolve the sentinel SHALL NOT appear in the default (empty `select`) row projection of any entity, and SHALL NOT be published by `GET /api/v1/queries/entities/schema/{name}`. Clients express the behaviour only through `computation_id eq "latest"`.
Status: **Implemented**

#### Scenario: Default projection unchanged
- **WHEN** a row-mode query with an empty `select` runs on `test_case_eval_scores`
- **THEN** the returned rows contain the entity's published fields only, with no resolution field

#### Scenario: Schema discovery unchanged
- **WHEN** a client requests the schema of `test_case_eval_scores`, `metric_score_results` or `test_case_metric_scores`
- **THEN** the field list contains no resolution field

### Requirement: Latest resolution does not expose deleted runs
Rows of a run that has an analytics tombstone SHALL remain excluded from a `computation_id eq "latest"` query, as for any other read of the entity (see `run-analytics-deletion`).
Status: **Implemented**

#### Scenario: Deleted run with the sentinel
- **WHEN** a multi-run `computation_id eq "latest"` query would otherwise match rows of a run with an analytics tombstone
- **THEN** no row of that run appears in the result

### Requirement: Entities that are not opted in keep their behaviour
An entity that is not opted in — currently `eval_summaries` — SHALL keep its current handling of `computation_id`, and a `"latest"` sentinel on it SHALL NOT be resolved by this capability.
Status: **Implemented**

#### Scenario: `eval_summaries` is unchanged
- **WHEN** a query on `eval_summaries` filters `computation_id eq "latest"`
- **THEN** it behaves exactly as before this change

## Implementation Notes
- New component `LatestComputationSupport` (`com.epam.aidial.evaluation.query.service.repository`): builds the internal BOOLEAN binding from an entity's run-id and computation-id fields, merges it into a copy of the entity's bindings, and rewrites the sentinel in the top-level filter.
- The binding is a correlated lookup in `test_case_eval_summaries` ordered `computed_at_ms DESC, computation_id ASC LIMIT 1`, served by `idx_eval_summaries_run_computed_at (test_suite_run_id, computed_at_ms DESC, computation_id)`; the same ordering as `PostgresEvalSummaryRepository.findLatestComputationIds` and `ComputationResolver`.
- Opt-in: `PostgresTestCaseEvalScoreEntityResolver` (via the derived `SCORES` table's fields), `PostgresMetricScoreResultEntityResolver`, `PostgresTestCaseMetricScoreAggregatedEntityResolver` (gains a `rewrite` hook). Each runs `MetricScoreLatestComputationDefaulter.resolveLatestComputation` first, then the SQL rewrite.
- The field is a binding only, not a column of `table()`, so default projections and `*SchemaProvider` output are unchanged. Deleted runs stay hidden by each entity's `baseCondition()` (`RunDeletionFilter.notDeleted`).
- No Flyway migration, no configuration property, no new endpoint.
