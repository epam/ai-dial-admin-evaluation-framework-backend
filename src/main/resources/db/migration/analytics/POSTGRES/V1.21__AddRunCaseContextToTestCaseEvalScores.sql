-- Recreates test_case_eval_scores as one row per test case per computation, keyed by a surrogate id,
-- with the run/test-case context denormalized onto it so it can be read directly as its own Query DSL
-- entity. The table exists only in dev, so it is dropped rather than migrated in place; eval_summary_id
-- (the previous key) no longer exists.
DROP TABLE IF EXISTS test_case_eval_scores;

CREATE TABLE test_case_eval_scores (
    id                VARCHAR(36)      NOT NULL,
    test_suite_run_id VARCHAR(36)      NOT NULL,
    test_case_id      VARCHAR(36)      NOT NULL,
    test_case_name    VARCHAR(255)     NOT NULL,
    computation_id    VARCHAR(36)      NOT NULL,
    execution_status  VARCHAR(20)      NOT NULL,
    score             DOUBLE PRECISION,
    passed            BOOLEAN,
    computed_at_ms    BIGINT           NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_test_case_eval_scores_natural_key UNIQUE (test_suite_run_id, test_case_id, computation_id)
);
