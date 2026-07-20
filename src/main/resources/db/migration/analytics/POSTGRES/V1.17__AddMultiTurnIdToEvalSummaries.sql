-- multi_turn_id = the id of the multi-turn a summary row belongs to (copied verbatim from the source
-- TestCaseRunResult). NULL means single-turn. The eval-summary table carries no trace_id, so multi_turn_id
-- is its only multi-turn grouping key — it lets the frontend regroup per-turn summary rows into one
-- multi-turn. Nullable, no default: pre-existing rows backfill to NULL (single-turn semantics) in a single
-- metadata-only statement (PG 11+, no rewrite).
-- NOT part of the UNIQUE natural key (test_suite_run_id, test_case_id, run_index, turn_index,
-- computation_id, created_at_ms) or the primary key — it is redundant grouping metadata.
ALTER TABLE test_case_eval_summaries
    ADD COLUMN IF NOT EXISTS multi_turn_id VARCHAR(36);

-- Non-unique grouping index: equality/grouping columns lead (test_suite_run_id, multi_turn_id);
-- created_at_ms trails to align with the (created_at_ms, id) keyset spine and stay time-partition-ready.
CREATE INDEX IF NOT EXISTS idx_eval_summaries_run_multiturn
    ON test_case_eval_summaries (test_suite_run_id, multi_turn_id, created_at_ms);
