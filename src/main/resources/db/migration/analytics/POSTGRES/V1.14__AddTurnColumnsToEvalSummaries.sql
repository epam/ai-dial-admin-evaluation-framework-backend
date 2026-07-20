-- Per-turn eval summaries: each turn produces its own summary row.
-- turn_index is 0-based (matches run_index); total_turns is the planned turn count.
-- NOT NULL DEFAULT backfills existing single-turn rows to 0/1 (metadata-only, no rewrite).
ALTER TABLE test_case_eval_summaries
    ADD COLUMN IF NOT EXISTS turn_index  INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_turns INTEGER NOT NULL DEFAULT 1;

-- Extend the natural key to include turn_index so each turn's summary is uniquely keyed
-- per computation. Columns are NOT NULL, so a plain unique index suffices.
-- NOTE: on a large analytics deployment, use CREATE UNIQUE INDEX CONCURRENTLY (in a
-- non-transactional migration) then DROP the old index, to avoid a long exclusive lock.
DROP INDEX uq_eval_summaries_natural_key;

CREATE UNIQUE INDEX uq_eval_summaries_natural_key
    ON test_case_eval_summaries (test_suite_run_id, test_case_id, run_index, turn_index, computation_id, created_at_ms);
