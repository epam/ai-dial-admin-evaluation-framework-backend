## ADDED Requirements

### Requirement: Database schema for run deletions tombstone table
The analytics database SHALL contain a `run_deletions` table storing one append-only row per deleted test suite run, keyed by `test_suite_run_id`. No existing analytics row SHALL ever be updated to represent a run's deletion — deletion SHALL be represented exclusively by the presence of a tombstone row.
Status: **Implemented**

#### Scenario: Table structure
- **WHEN** the analytics Flyway migration creating `run_deletions` is applied
- **THEN** the `run_deletions` table SHALL have columns: `test_suite_run_id` (VARCHAR(36), NOT NULL, PRIMARY KEY), `deleted_at_ms` (BIGINT, NOT NULL)

#### Scenario: No foreign keys
- **WHEN** the migration is applied
- **THEN** no foreign key constraints SHALL exist on `run_deletions` (no foreign key can span the meta and analytics databases)

### Requirement: Tombstone write on run deletion is idempotent
The system SHALL write a `run_deletions` row for a run before that run's meta-DB row is hard-deleted. The write SHALL be idempotent — inserting a tombstone for a `test_suite_run_id` that already has one SHALL succeed without error and leave the existing row unchanged.
Status: **Implemented**

#### Scenario: First deletion writes a tombstone
- **WHEN** a terminal test suite run is deleted for the first time
- **THEN** a `run_deletions` row SHALL be written for that run's id before the meta-DB row is removed

#### Scenario: A retried deletion is idempotent
- **WHEN** a client retries `DELETE /api/v1/test-suite-runs/{id}` for a run whose tombstone was already written in a prior, partially-failed attempt
- **THEN** the tombstone write SHALL succeed without error (no duplicate row, no exception), and the meta-DB row deletion SHALL proceed as normal

### Requirement: Analytics reads exclude rows of deleted runs
For every analytics table carrying a run-scoping column (`test_case_run_results`, `test_case_eval_summaries`, `test_case_eval_scores`, `test_case_metric_scores_aggregated`, `metric_score_result`), every read of the table — Query DSL entities and direct repository reads alike — SHALL exclude rows whose run has a matching `run_deletions` tombstone, by anti-joining `run_deletions` on the run-scoping column in the read query itself. Entity names and column sets exposed through the Query DSL SHALL be unchanged. Query DSL entities SHALL apply the exclusion through a resolver-level base condition that the query builder ANDs onto every select of that entity, subqueries included; writes SHALL NOT be filtered.
Status: **Implemented**

#### Scenario: A deleted run's rows are excluded from a Query DSL read
- **WHEN** a structured query is executed against an entity backed by one of the five run-scoped analytics tables, and the query would otherwise match rows belonging to a run that has a `run_deletions` tombstone
- **THEN** those rows SHALL NOT appear in the query result

#### Scenario: A deleted run's rows are excluded from a direct repository read
- **WHEN** a repository method reads one of the five run-scoped analytics tables directly (not via the Query DSL) for a run that has a `run_deletions` tombstone
- **THEN** no row belonging to that run SHALL be returned

#### Scenario: An active run's rows are unaffected
- **WHEN** a read targets a run that has no `run_deletions` tombstone
- **THEN** all of that run's matching rows SHALL be returned exactly as if the exclusion did not exist

### Requirement: Registry test prevents future drift
The system SHALL include a test that queries the analytics database's schema metadata for every base table carrying a run-scoping column and asserts each is registered as tombstone-filtered (or explicitly frozen), so a new run-scoped table cannot be added without its read paths being filtered and covered by the deletion functional tests.
Status: **Implemented**

#### Scenario: A new run-scoped table that is not registered fails the build
- **WHEN** a new analytics table carrying a run-scoping column is added without being registered as tombstone-filtered
- **THEN** the registry test SHALL fail, naming the unregistered table

#### Scenario: All current run-scoped tables are registered
- **WHEN** the registry test runs against the current schema
- **THEN** it SHALL pass for all five run-scoped analytics tables

#### Scenario: Registered tables are verified behaviourally
- **WHEN** the deletion functional tests delete a run and a suite
- **THEN** every direct repository read and every Query DSL entity over the five tables SHALL return no row of the deleted run and SHALL still return the rows of a live run
