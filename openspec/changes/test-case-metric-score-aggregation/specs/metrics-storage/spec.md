## MODIFIED Requirements

### Requirement: Database schema for eval summary scores
The analytics database SHALL contain a `test_case_eval_scores` table storing per-row overall score/pass-fail, computed via SQL and joined into the eval-summary read surface (not native columns on `test_case_eval_summaries`). The table SHALL additionally carry `test_suite_run_id`, `test_case_id`, `test_case_name`, and `computation_id`, denormalized from the corresponding `test_case_eval_summaries` row at write time, so the table can also be read directly (deduplicated — see the `test_case_eval_scores` Query DSL entity requirement below) without a join. `eval_summary_id` SHALL remain the primary key and the write grain SHALL remain one row per raw `test_case_eval_summaries` row, unchanged.
Status: **Implemented**

#### Scenario: Table structure
- **WHEN** the analytics Flyway migration V1.21 is applied
- **THEN** the `test_case_eval_scores` table SHALL have the columns: `eval_summary_id` (VARCHAR(36), NOT NULL, PK), `test_suite_run_id` (VARCHAR(36), NOT NULL), `test_case_id` (VARCHAR(36), NOT NULL), `test_case_name` (VARCHAR(255), NOT NULL), `computation_id` (VARCHAR(36), NOT NULL), `score` (DOUBLE PRECISION, nullable), `passed` (BOOLEAN, nullable), `computed_at_ms` (BIGINT, NOT NULL), and an index `idx_test_case_eval_scores_natural_key` on `(test_suite_run_id, test_case_id, computation_id, computed_at_ms DESC)`

#### Scenario: Primary key is the scored row's own id
- **WHEN** the migration is applied
- **THEN** `eval_summary_id` SHALL remain the primary key — a 1:1 (or 0:1, since a row without a computable score is simply never inserted) relationship with `test_case_eval_summaries.id`, needing no surrogate PK

#### Scenario: A row's absence and a present-but-null row read identically
- **WHEN** a `test_case_eval_summaries` row has no matching `test_case_eval_scores` row (no `overallScore` configured, or a rejected query shape), versus a matching row whose `score` is itself SQL NULL (e.g. a population-dependent CustomFunction)
- **THEN** both SHALL read back as `score = null, passed = null` via the LEFT JOIN — a client cannot and need not distinguish the two cases

#### Scenario: Existing rows are backfilled in place, not dropped
- **WHEN** migration V1.21 runs against a database with pre-existing `test_case_eval_scores` rows (from before this column set existed)
- **THEN** every existing row's new `test_suite_run_id`/`test_case_id`/`test_case_name`/`computation_id` columns SHALL be populated from a join to `test_case_eval_summaries` on `eval_summary_id`, with no data loss and no change to `eval_summary_id`, `score`, `passed`, or `computed_at_ms`

### Requirement: Batch write eval summary scores (internal only)
The in-process metric evaluation engine SHALL write `test_case_eval_scores` rows via `TestCaseEvalScoreService.batchUpsert()`, one batch per Phase-2 flush, immediately after that flush's `test_case_eval_summaries` batch write succeeds. There SHALL be no external REST endpoint for this table — it is populated only by the internal engine and read via the LEFT JOIN into the existing eval-summary endpoints, or directly via the `test_case_eval_scores` Query DSL entity.
Status: **Implemented**

#### Scenario: One score batch write per flush
- **WHEN** a Phase-2 flush writes N `test_case_eval_summaries` rows and the suite has an `overallScore` definition configured
- **THEN** at most one `test_case_eval_scores` batch write SHALL follow, containing an entry for every row id the score computation returned a result for, each carrying that row's `test_suite_run_id`/`test_case_id`/`test_case_name`/`computation_id`

#### Scenario: A later write corrects an earlier one for the same row
- **WHEN** a score batch write is retried, or a later flush recomputes a score, for an `eval_summary_id` already present
- **THEN** the insert SHALL use `ON CONFLICT (eval_summary_id) DO UPDATE SET score, passed, computed_at_ms` (not `DO NOTHING`), so a stale value from an earlier, partial computation is corrected rather than permanently frozen

#### Scenario: A failed score write does not fail the run
- **WHEN** the score computation or batch write throws an unexpected error
- **THEN** the error SHALL be logged and the run SHALL continue — `score`/`passed` are regenerable derived data, unlike the eval summaries themselves

## ADDED Requirements

### Requirement: `test_case_eval_scores` Query DSL entity
The system SHALL expose `test_case_eval_scores` as a Query DSL entity of the same name, presenting exactly one row per test case per computation even though the underlying table stores one row per raw `test_case_eval_summaries` row — via `SELECT DISTINCT ON (test_suite_run_id, test_case_id, computation_id) ... ORDER BY test_suite_run_id, test_case_id, computation_id, computed_at_ms DESC`, so the row with the freshest `computed_at_ms` per group wins. `eval_summary_id` SHALL be excluded from the entity's projection — which raw row's id "wins" the dedup is an implementation detail, not meaningful to a client of this entity. The entity SHALL support the `computation_id eq "latest"` sentinel, resolved the same way as `metric_score_results`. This entity SHALL be reachable through the existing generic query endpoint (`POST /api/v1/queries/execute`), and exists alongside (not instead of) `score`/`passed` remaining queryable via the `eval_summaries` entity's join — both surfaces read the same underlying table.
Status: **Implemented**

#### Scenario: A multi-row test case collapses to one row per test case
- **WHEN** a structured query against `test_case_eval_scores` filters by `test_suite_run_id` for a run containing a test case with multiple raw rows (e.g. a multi-turn/multi-rerun test case), all sharing the same, already-correct `score`/`passed`
- **THEN** the result SHALL contain exactly one row for that test case, not one per underlying raw row

#### Scenario: The freshest row wins when a test case's rows disagree
- **WHEN** a test case's `test_case_eval_scores` rows disagree on `score` (e.g. from the pre-existing staleness this change's upsert fixes going forward — see the batch-write requirement above), with different `computed_at_ms` values
- **THEN** the entity SHALL surface the value from the row with the greatest `computed_at_ms`

#### Scenario: `computation_id eq "latest"` resolves to the run's latest computation
- **WHEN** a structured query against `test_case_eval_scores` filters by a single `test_suite_run_id` and `computation_id eq "latest"`
- **THEN** the sentinel SHALL resolve to that run's most recently computed computation before translation, the same way it does for `metric_score_results`
