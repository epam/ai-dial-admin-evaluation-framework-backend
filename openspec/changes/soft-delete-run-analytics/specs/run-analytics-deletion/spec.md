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

### Requirement: Analytics reads exclude rows of deleted runs via database views
For every analytics table carrying a run-scoping column (`test_case_run_results`, `test_case_eval_summaries`, `test_case_eval_scores`, `test_case_metric_scores_aggregated`, `metric_score_result`), the system SHALL provide a companion `<table>_active` database view that excludes rows whose run has a matching `run_deletions` tombstone. Every read of these tables — Query DSL entity resolvers and direct repository reads alike — SHALL read through the `<table>_active` view rather than the base table.
Status: **Implemented**

#### Scenario: A deleted run's rows are excluded from a Query DSL read
- **WHEN** a structured query is executed against an entity backed by one of the five run-scoped analytics tables, and the query would otherwise match rows belonging to a run that has a `run_deletions` tombstone
- **THEN** those rows SHALL NOT appear in the query result

#### Scenario: A deleted run's rows are excluded from a direct repository read
- **WHEN** a repository method reads one of the five run-scoped analytics tables directly (not via the Query DSL) for a run that has a `run_deletions` tombstone
- **THEN** no row belonging to that run SHALL be returned

#### Scenario: An active run's rows are unaffected
- **WHEN** a read targets a run that has no `run_deletions` tombstone
- **THEN** all of that run's matching rows SHALL be returned exactly as if the view did not exist

### Requirement: Schema-introspection test prevents future drift
The system SHALL include a test that queries the analytics database's schema metadata for every table carrying a run-scoping column and asserts a corresponding `<table>_active` view exists, independent of which application code references that view.
Status: **Implemented**

#### Scenario: A new run-scoped table without its active view fails the build
- **WHEN** a new analytics table carrying a run-scoping column is added without a corresponding `<table>_active` view
- **THEN** the schema-introspection test SHALL fail, naming the missing view

#### Scenario: All current run-scoped tables have active views
- **WHEN** the schema-introspection test runs against the current schema
- **THEN** it SHALL pass for all five run-scoped analytics tables
