-- Per-run pass counts of completed runs, one row per (run, computation); append-only.
-- Lets dashboards sort and page "all suites by latest pass rate" without counting test cases per request.
-- computation_id is NULL only for a run without eval summaries (0 of 0, computed_at_ms = 0).
CREATE TABLE run_pass_counts (
    test_suite_run_id VARCHAR(36) NOT NULL,
    test_suite_id VARCHAR(36) NOT NULL,
    computation_id VARCHAR(36),
    run_created_at_ms BIGINT NOT NULL,
    passed_count INTEGER NOT NULL,
    total_count INTEGER NOT NULL,
    computed_at_ms BIGINT NOT NULL
);

-- Expression index: inserts use ON CONFLICT DO NOTHING without a conflict target.
CREATE UNIQUE INDEX uq_run_pass_counts_run_computation
    ON run_pass_counts (test_suite_run_id, COALESCE(computation_id, ''));

CREATE INDEX idx_run_pass_counts_suite_run_created
    ON run_pass_counts (test_suite_id, run_created_at_ms DESC);
