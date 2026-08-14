-- Average latency (ms) of the TSMD provider /evaluate calls dispatched for this row's
-- computation, excluding TSMDs whose condition produced a ConditionError (no call made).
-- NOT NULL DEFAULT backfills existing rows to 0 (metadata-only, no rewrite).
ALTER TABLE test_case_eval_summaries
    ADD COLUMN IF NOT EXISTS avg_metric_eval_duration_ms BIGINT NOT NULL DEFAULT 0;
