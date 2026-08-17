-- Per-request result rows: each request in a suite's chain is its own row (per turn too).
-- request_index is 0-based (matches run_index/turn_index); total_requests is the chain length.
-- NOT NULL DEFAULT backfills existing single-request rows to 0/1 in a single metadata-only
-- statement (PG 11+, no table rewrite).
ALTER TABLE test_case_run_results
    ADD COLUMN IF NOT EXISTS request_index  INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_requests INTEGER NOT NULL DEFAULT 1;

-- Extend the idempotency key to include request_index so each chain position is uniquely
-- keyed and ON CONFLICT DO NOTHING writes hold per request. Columns are NOT NULL, so a
-- plain unique constraint suffices (no NULLS NOT DISTINCT).
-- NOTE: on a large analytics deployment, replace the drop/re-add below with a
-- non-transactional migration using CREATE UNIQUE INDEX CONCURRENTLY + constraint swap
-- to avoid a long exclusive lock.
ALTER TABLE test_case_run_results
    DROP CONSTRAINT uq_results_run_case_index;

ALTER TABLE test_case_run_results
    ADD CONSTRAINT uq_results_run_case_index
        UNIQUE (test_suite_run_id, test_case_id, run_index, request_index, turn_index, created_at_ms);
