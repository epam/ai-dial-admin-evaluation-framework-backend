## MODIFIED Requirements

### Requirement: Database schema for eval summary scores
The analytics database SHALL contain a `test_case_eval_scores` table storing one row per `(test_suite_run_id, test_case_id, computation_id)`, computed via SQL and joined into the eval-summary read surface (not native columns on `test_case_eval_summaries`). The table SHALL carry `test_suite_run_id`, `test_case_id`, `test_case_name`, `computation_id`, and `execution_status`, denormalized/aggregated at write time, so the table can also be read directly (deduplicated — see the `test_case_eval_scores` Query DSL entity requirement below) without a join. The primary key SHALL be a surrogate `id` column, not `eval_summary_id`. `eval_summary_id` SHALL be a nullable old/new-format discriminator: `NULL` for every row written after this requirement's migration, populated with a real value only on rows written before it. A **partial unique index** on `(test_suite_run_id, test_case_id, computation_id) WHERE eval_summary_id IS NULL` SHALL enforce that at most one new-format row exists per test case per computation; no uniqueness constraint SHALL apply across legacy rows.
Status: **Implemented**

#### Scenario: Table structure
- **WHEN** the analytics Flyway migration V1.21 is applied
- **THEN** the `test_case_eval_scores` table SHALL have the columns: `id` (VARCHAR(36), NOT NULL, PK), `eval_summary_id` (VARCHAR(36), nullable), `test_suite_run_id` (VARCHAR(36), NOT NULL), `test_case_id` (VARCHAR(36), NOT NULL), `test_case_name` (VARCHAR(255), NOT NULL), `computation_id` (VARCHAR(36), NOT NULL), `execution_status` (VARCHAR(20), NOT NULL), `score` (DOUBLE PRECISION, nullable), `passed` (BOOLEAN, nullable), `computed_at_ms` (BIGINT, NOT NULL), a unique index `uq_test_case_eval_scores_new_format_key` on `(test_suite_run_id, test_case_id, computation_id) WHERE eval_summary_id IS NULL`, and an index `idx_test_case_eval_scores_natural_key` on `(test_suite_run_id, test_case_id, computation_id, (eval_summary_id IS NULL) DESC, computed_at_ms DESC)`

#### Scenario: Primary key is a surrogate id, not eval_summary_id
- **WHEN** the migration is applied
- **THEN** `id` SHALL be the primary key. `eval_summary_id` SHALL NOT be part of any primary key or table-wide unique constraint — it is a discriminator column only

#### Scenario: eval_summary_id is null for every row written after this migration
- **WHEN** a `test_case_eval_scores` row is written by the in-process metric evaluation engine after this migration
- **THEN** that row's `eval_summary_id` SHALL be `NULL`, and it SHALL correspond 1:1 to the `test_case_metric_scores_aggregated` row it was computed from

#### Scenario: A row's absence and a present-but-null row read identically
- **WHEN** a test case has no matching `test_case_eval_scores` row (no `overallScore` configured, or a rejected query shape), versus a matching row whose `score` is itself SQL NULL (e.g. a population-dependent CustomFunction, or an `execution_status = FAILED` aggregate)
- **THEN** both SHALL read back as `score = null, passed = null` via the LEFT JOIN — a client cannot and need not distinguish the two cases

#### Scenario: Existing rows are never deleted or reconciled
- **WHEN** this migration runs against a database with pre-existing `test_case_eval_scores` rows written before this requirement (real `eval_summary_id`, potentially several rows per test case, possibly disagreeing on `score`)
- **THEN** every existing row SHALL be preserved unchanged: its `eval_summary_id` value is untouched, its `id` is backfilled from that same value, its identity columns (`test_suite_run_id`/`test_case_id`/`test_case_name`/`computation_id`) are backfilled via a join to `test_case_eval_summaries` on `eval_summary_id`, and its `execution_status` is backfilled as a fresh `bool_or(execution_status <> 'SUCCESS')` aggregate over `test_case_eval_summaries` grouped by `(test_suite_run_id, test_case_id, computation_id)` — never copied from any single row's own status and never picked from among disagreeing legacy rows

### Requirement: Batch write eval summary scores (internal only)
The in-process metric evaluation engine SHALL write `test_case_eval_scores` rows via `TestCaseEvalScoreService.batchUpsert()`, one batch per Phase-2 flush, immediately after that flush's `test_case_metric_scores_aggregated` batch write succeeds, with exactly one item per distinct test case in the flush batch (not one per raw `test_case_eval_summaries` row). There SHALL be no external REST endpoint for this table — it is populated only by the internal engine and read directly via the `test_case_eval_scores` Query DSL entity. The `eval_summaries` read surface SHALL NOT join to this table (see the "Overall score and pass/fail exposed in eval summary responses" requirement, REMOVED).
Status: **Implemented**

#### Scenario: One score batch write per flush, one item per test case
- **WHEN** a Phase-2 flush writes N `test_case_eval_summaries` rows spanning M distinct test cases and the suite has an `overallScore` definition configured
- **THEN** at most one `test_case_eval_scores` batch write SHALL follow, containing at most one item per test case (never one per raw row), each carrying that test case's `test_suite_run_id`/`test_case_id`/`test_case_name`/`computation_id`/`execution_status`, with `eval_summary_id` omitted (left `NULL`)

#### Scenario: A later write corrects an earlier one for the same test case
- **WHEN** a score batch write is retried, or a later flush recomputes a score, for a `(test_suite_run_id, test_case_id, computation_id)` already present as a new-format row
- **THEN** the insert SHALL use `ON CONFLICT (test_suite_run_id, test_case_id, computation_id) WHERE eval_summary_id IS NULL DO UPDATE SET test_case_name, execution_status, score, passed, computed_at_ms`, so a stale value from an earlier, partial computation is corrected rather than permanently frozen — the row's surrogate `id`, minted on first insert, is preserved across corrections

#### Scenario: A failed score write does not fail the run
- **WHEN** the score computation or batch write throws an unexpected error
- **THEN** the error SHALL be logged and the run SHALL continue — `score`/`passed`/`execution_status` are regenerable derived data, unlike the eval summaries themselves

### Requirement: `test_case_eval_scores` Query DSL entity
The system SHALL expose `test_case_eval_scores` as a Query DSL entity of the same name, presenting exactly one row per test case per computation. For a computation whose rows were all written after the re-key, this is inherent (at most one new-format row per key, enforced by the partial unique index) — no dedup work is needed. For a computation with legacy rows (written before the re-key), the entity SHALL still present one row via `SELECT DISTINCT ON (test_suite_run_id, test_case_id, computation_id) ... ORDER BY test_suite_run_id, test_case_id, computation_id, (eval_summary_id IS NULL) DESC, computed_at_ms DESC` — a new-format row always wins over any number of legacy rows for the same key, and only among legacy rows does the freshest `computed_at_ms` apply. `eval_summary_id` SHALL be excluded from the entity's projection — which row's id "wins" is an implementation detail, not meaningful to a client of this entity. The entity SHALL support the `computation_id eq "latest"` sentinel, resolved the same way as `metric_score_results`. This entity SHALL be reachable through the existing generic query endpoint (`POST /api/v1/queries/execute`), and is the sole read surface for a test case's `score`/`passed` — the `eval_summaries` entity and REST endpoints no longer expose either field (see "Overall score and pass/fail exposed in eval summary responses", REMOVED).
Status: **Implemented**

#### Scenario: A multi-row legacy test case collapses to one row per test case
- **WHEN** a structured query against `test_case_eval_scores` filters by `test_suite_run_id` for a run containing a test case with multiple legacy rows (written before the re-key; e.g. a multi-turn/multi-rerun test case)
- **THEN** the result SHALL contain exactly one row for that test case, not one per underlying legacy row

#### Scenario: A new-format row always wins over legacy rows for the same key
- **WHEN** a test case has both legacy rows (real `eval_summary_id`) and a new-format row (`eval_summary_id IS NULL`) for the same `(test_suite_run_id, test_case_id, computation_id)`
- **THEN** the entity SHALL surface the new-format row's `score`/`passed`/`execution_status`, regardless of `computed_at_ms` ordering

#### Scenario: The freshest legacy row wins when only legacy rows disagree
- **WHEN** a test case has only legacy rows for a key, and they disagree on `score` (different `computed_at_ms` values), with no new-format row present
- **THEN** the entity SHALL surface the value from the legacy row with the greatest `computed_at_ms`

#### Scenario: `computation_id eq "latest"` resolves to the run's latest computation
- **WHEN** a structured query against `test_case_eval_scores` filters by a single `test_suite_run_id` and `computation_id eq "latest"`
- **THEN** the sentinel SHALL resolve to that run's most recently computed computation before translation, the same way it does for `metric_score_results`

## REMOVED Requirements

### Requirement: Overall score and pass/fail exposed in eval summary responses
**Reason**: Superseded by Decision 11 (see `design.md`) — mid-implementation of the LATERAL-join repointing this requirement originally described, the user decided `eval_summaries` should stop exposing `score`/`passed` altogether rather than keep it in sync with `test_case_eval_scores` via any join shape. `test_case_eval_scores` (its own deduplicated Query DSL entity, see the requirement above) is now the sole read surface for a test case's score/pass-fail.
**Migration**: `EvalSummaryResponseDto`/`EvalSummaryDetailResponseDto` no longer return `score`/`passed` — this is an API-breaking change. Any client reading `score`/`passed` off `GET /api/v1/analytics/eval-summaries` (list/detail/export) or the `eval_summaries` Query DSL entity must switch to querying the `test_case_eval_scores` Query DSL entity directly, filtering by `test_suite_run_id`/`test_case_id`/`computation_id` as needed. No data is lost — `test_case_eval_scores` already carries the same values, at test-case grain rather than broadcast to every raw row.

