## MODIFIED Requirements

### Requirement: Analytics reads exclude rows of deleted runs
For every analytics table carrying a run-scoping column (`test_case_run_results`, `test_case_eval_summaries`, `test_case_eval_scores`, `test_case_metric_scores_aggregated`, `metric_score_result`, `run_pass_counts`), every read of the table — Query DSL entities and direct repository reads alike — SHALL exclude rows whose run has a matching `run_deletions` tombstone, by anti-joining `run_deletions` on the run-scoping column in the read query itself. Entity names and column sets exposed through the Query DSL SHALL be unchanged. Query DSL entities SHALL apply the exclusion through a resolver-level base condition that the query builder ANDs onto every select of that entity, subqueries included. Writes SHALL NOT be filtered, and neither SHALL the reads that serve a writer: computing values for a run that is being written, and checking whether a row already exists before writing it.
Status: **Planned**

#### Scenario: A deleted run's rows are excluded from a Query DSL read
- **WHEN** a structured query is executed against an entity backed by one of the run-scoped analytics tables, and the query would otherwise match rows belonging to a run that has a `run_deletions` tombstone
- **THEN** those rows SHALL NOT appear in the query result

#### Scenario: A deleted run's rows are excluded from a direct repository read
- **WHEN** a repository method reads one of the six run-scoped analytics tables directly (not via the Query DSL) for a run that has a `run_deletions` tombstone
- **THEN** no row belonging to that run SHALL be returned

#### Scenario: An active run's rows are unaffected
- **WHEN** a read targets a run that has no `run_deletions` tombstone
- **THEN** all of that run's matching rows SHALL be returned exactly as if the exclusion did not exist

#### Scenario: A writer-side read is not filtered
- **WHEN** a writer checks whether a row already exists for a run before writing it, or computes values for the run it is writing
- **THEN** the read SHALL NOT apply the tombstone exclusion

### Requirement: Registry test prevents future drift
The system SHALL include a test that queries the analytics database's schema metadata for every base table carrying a run-scoping column and asserts each is registered as tombstone-filtered (or explicitly frozen), so a new run-scoped table cannot be added without its read paths being filtered and covered by the deletion functional tests.
Status: **Planned**

#### Scenario: A new run-scoped table that is not registered fails the build
- **WHEN** a new analytics table carrying a run-scoping column is added without being registered as tombstone-filtered
- **THEN** the registry test SHALL fail, naming the unregistered table

#### Scenario: All current run-scoped tables are registered
- **WHEN** the registry test runs against the current schema
- **THEN** it SHALL pass for all six run-scoped analytics tables

#### Scenario: Registered tables are verified behaviourally
- **WHEN** the deletion functional tests delete a run and a suite
- **THEN** every direct repository read and every Query DSL entity over the six tables SHALL return no row of the deleted run and SHALL still return the rows of a live run
