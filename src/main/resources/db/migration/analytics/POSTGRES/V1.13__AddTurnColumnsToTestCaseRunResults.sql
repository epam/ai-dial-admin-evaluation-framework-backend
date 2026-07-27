-- Per-turn result rows: each turn of a multi-turn is its own row.
-- turn_index is 0-based (matches run_index); total_turns is the planned turn count.
-- NOT NULL DEFAULT backfills existing single-turn rows to 0/1 in a single metadata-only
-- statement (PG 11+, no table rewrite).
ALTER TABLE test_case_run_results
    ADD COLUMN IF NOT EXISTS turn_index  INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_turns INTEGER NOT NULL DEFAULT 1;

-- Extend the idempotency key to include turn_index so each turn is uniquely keyed and
-- ON CONFLICT DO NOTHING writes hold per turn. Columns are NOT NULL, so a plain unique
-- constraint suffices (no NULLS NOT DISTINCT).
-- NOTE: on a large analytics deployment, replace the drop/re-add below with a
-- non-transactional migration using CREATE UNIQUE INDEX CONCURRENTLY + constraint swap
-- to avoid a long exclusive lock.
ALTER TABLE test_case_run_results
    DROP CONSTRAINT uq_results_run_case_index;

ALTER TABLE test_case_run_results
    ADD CONSTRAINT uq_results_run_case_index
        UNIQUE (test_suite_run_id, test_case_id, run_index, turn_index, created_at_ms);
