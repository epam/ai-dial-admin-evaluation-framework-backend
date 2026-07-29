CREATE INDEX idx_eval_summaries_run_computed_at
    ON test_case_eval_summaries (test_suite_run_id, computed_at_ms DESC, computation_id);
