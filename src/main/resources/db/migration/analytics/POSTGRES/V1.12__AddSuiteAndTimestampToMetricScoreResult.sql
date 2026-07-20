-- Add suite scope and compute timestamp to metric_score_result so the Query DSL can retrieve the
-- latest N score aggregations for a suite (filter test_suite_id, ORDER BY computed_at_ms DESC LIMIT N).
ALTER TABLE metric_score_result ADD COLUMN test_suite_id VARCHAR(36);
ALTER TABLE metric_score_result ADD COLUMN computed_at_ms BIGINT;

-- Backfill computed_at_ms from the run's metric snapshots. All snapshot rows of a (run, computation)
-- share one timestamp; MIN() collapses them to a single value and avoids fan-out on the join.
UPDATE metric_score_result m
SET computed_at_ms = s.computed_at_ms
FROM (
    SELECT test_suite_run_id, computation_id, MIN(computed_at_ms) AS computed_at_ms
    FROM run_metric_snapshots
    GROUP BY test_suite_run_id, computation_id
) s
WHERE s.test_suite_run_id = m.test_suite_run_id
  AND s.computation_id = m.computation_id;

-- Backfill test_suite_id from the run's eval summaries (one suite per run).
UPDATE metric_score_result m
SET test_suite_id = e.test_suite_id
FROM (
    SELECT DISTINCT test_suite_run_id, test_suite_id
    FROM test_case_eval_summaries
) e
WHERE e.test_suite_run_id = m.test_suite_run_id;

-- Remove any rows that could not be backfilled (should be none: a score row implies a completed
-- computation that also wrote snapshots and eval summaries). Scores are append-only and regenerable.
DELETE FROM metric_score_result
WHERE test_suite_id IS NULL OR computed_at_ms IS NULL;

ALTER TABLE metric_score_result ALTER COLUMN test_suite_id SET NOT NULL;
ALTER TABLE metric_score_result ALTER COLUMN computed_at_ms SET NOT NULL;

-- Support suite-scoped, time-ordered latest-N retrieval.
CREATE INDEX idx_metric_score_result_suite_computed
    ON metric_score_result (test_suite_id, computed_at_ms);
