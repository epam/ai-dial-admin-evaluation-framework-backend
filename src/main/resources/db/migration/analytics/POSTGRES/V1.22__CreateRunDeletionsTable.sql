-- Soft-deletion tombstone table for run analytics exclusion.
-- When a test suite run is deleted, a row is inserted here to mark it as deleted.
-- Run-scoped analytics tables have companion _active views that exclude tombstoned runs.
-- This approach preserves the append-only invariant (no mutations to analytics rows).
CREATE TABLE run_deletions (
    test_suite_run_id VARCHAR(36) NOT NULL PRIMARY KEY,
    deleted_at_ms BIGINT NOT NULL
);

-- Active views: exclude tombstoned runs via anti-join against run_deletions.
-- Each view filters out rows belonging to runs marked as deleted in run_deletions.

CREATE VIEW test_case_run_results_active AS
SELECT *
FROM test_case_run_results
WHERE NOT EXISTS (
    SELECT 1
    FROM run_deletions
    WHERE run_deletions.test_suite_run_id = test_case_run_results.test_suite_run_id
);

CREATE VIEW test_case_eval_summaries_active AS
SELECT *
FROM test_case_eval_summaries
WHERE NOT EXISTS (
    SELECT 1
    FROM run_deletions
    WHERE run_deletions.test_suite_run_id = test_case_eval_summaries.test_suite_run_id
);

CREATE VIEW test_case_eval_scores_active AS
SELECT *
FROM test_case_eval_scores
WHERE NOT EXISTS (
    SELECT 1
    FROM run_deletions
    WHERE run_deletions.test_suite_run_id = test_case_eval_scores.test_suite_run_id
);

CREATE VIEW test_case_metric_scores_aggregated_active AS
SELECT *
FROM test_case_metric_scores_aggregated
WHERE NOT EXISTS (
    SELECT 1
    FROM run_deletions
    WHERE run_deletions.test_suite_run_id = test_case_metric_scores_aggregated.test_suite_run_id
);

CREATE VIEW metric_score_result_active AS
SELECT *
FROM metric_score_result
WHERE NOT EXISTS (
    SELECT 1
    FROM run_deletions
    WHERE run_deletions.test_suite_run_id = metric_score_result.test_suite_run_id
);
