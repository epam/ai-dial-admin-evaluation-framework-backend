-- multi_turn_id = the id of the multi-turn a result row belongs to (the client-supplied multi_turn_id
-- of the originating test_cases rows). NULL means single-turn. Lets the frontend regroup per-turn result
-- rows into one multi-turn without relying on trace_id. Nullable, no default: pre-existing rows backfill
-- to NULL (single-turn semantics) in a single metadata-only statement (PG 11+, no rewrite).
-- NOT part of any unique/idempotency key — the natural key (test_suite_run_id, test_case_id, run_index,
-- turn_index, created_at_ms) already identifies a row; multi_turn_id is redundant grouping metadata.
ALTER TABLE test_case_run_results
    ADD COLUMN IF NOT EXISTS multi_turn_id VARCHAR(36);

-- Non-unique grouping index: equality/grouping columns lead (test_suite_run_id, multi_turn_id);
-- created_at_ms trails to align with the (created_at_ms, id) keyset spine and stay time-partition-ready
-- (mirrors the created_at_ms-in-keys design already used by the PK and the UNIQUE natural key). Leading
-- created_at_ms would defeat the equality-grouping this index exists for, so it goes last.
CREATE INDEX IF NOT EXISTS idx_results_run_multiturn
    ON test_case_run_results (test_suite_run_id, multi_turn_id, created_at_ms);
