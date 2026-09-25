-- Denormalizes run/test-case context onto test_case_eval_scores so the table can also be read
-- directly (deduplicated) as its own test_case_eval_scores Query DSL entity, without joining
-- test_case_eval_summaries. eval_summary_id remains the primary key and the write grain is
-- unchanged (still one row per raw test_case_eval_summaries row) -- see
-- test-case-metric-score-aggregation's design.md for why this was extended in place rather than
-- re-keyed. Backfilled in place for existing rows; new rows are populated at write time going
-- forward (InProcessMetricEvaluationExecutor).
-- execution_status is a per-test-case aggregate (collapsed to SUCCESS/FAILED, OR-ed across every
-- run_index/request_index/turn_index row of that test case for the computation -- see
-- TestCaseExecutionStatusAggregator), not a copy of this row's own execution_status. The backfill
-- below approximates it from each row's own status only (a reasonable stand-in for pre-existing
-- local data); the app overwrites it with the true per-test-case aggregate on the next recompute.
ALTER TABLE test_case_eval_scores
    ADD COLUMN test_suite_run_id VARCHAR(36),
    ADD COLUMN test_case_id      VARCHAR(36),
    ADD COLUMN test_case_name    VARCHAR(255),
    ADD COLUMN computation_id    VARCHAR(36),
    ADD COLUMN execution_status  VARCHAR(20);

UPDATE test_case_eval_scores tces
SET test_suite_run_id = tes.test_suite_run_id,
    test_case_id      = tes.test_case_id,
    test_case_name    = tes.test_case_name,
    computation_id    = tes.computation_id,
    execution_status  = CASE WHEN tes.execution_status = 'SUCCESS' THEN 'SUCCESS' ELSE 'FAILED' END
FROM test_case_eval_summaries tes
WHERE tes.id = tces.eval_summary_id;

ALTER TABLE test_case_eval_scores
    ALTER COLUMN test_suite_run_id SET NOT NULL,
    ALTER COLUMN test_case_id SET NOT NULL,
    ALTER COLUMN test_case_name SET NOT NULL,
    ALTER COLUMN computation_id SET NOT NULL,
    ALTER COLUMN execution_status SET NOT NULL;

-- Supports the test_case_eval_scores entity's SELECT DISTINCT ON (test_suite_run_id, test_case_id,
-- computation_id) ... ORDER BY ..., computed_at_ms DESC access pattern (one row per test case per
-- computation, freshest wins).
CREATE INDEX idx_test_case_eval_scores_natural_key
    ON test_case_eval_scores (test_suite_run_id, test_case_id, computation_id, computed_at_ms DESC);
