CREATE TABLE IF NOT EXISTS test_case_run_results (
    id                    VARCHAR(36)  NOT NULL,
    test_suite_run_id     VARCHAR(36)  NOT NULL,
    test_suite_id         VARCHAR(36)  NOT NULL,
    test_case_id          VARCHAR(36)  NOT NULL,
    test_case_name        VARCHAR(255) NOT NULL,
    run_index             INTEGER      NOT NULL,
    test_case_data        JSONB        NOT NULL,
    request_body          JSONB,
    response_body         JSONB,
    response_status_code  INTEGER,
    execution_status      VARCHAR(20)  NOT NULL,
    exec_started_at_ms    BIGINT       NOT NULL,
    exec_completed_at_ms  BIGINT       NOT NULL,
    exec_duration_ms      BIGINT       NOT NULL,
    trace_id              VARCHAR(128),
    created_at_ms         BIGINT       NOT NULL, -- Run creation timestamp from meta DB — all results for a run share this value

    PRIMARY KEY (created_at_ms, id)
);

-- Idempotent writes: INSERT ... ON CONFLICT DO NOTHING
-- Includes created_at_ms for future time-based partitioning (no constraint migration needed)
ALTER TABLE test_case_run_results
    ADD CONSTRAINT uq_results_run_case_index
        UNIQUE (test_suite_run_id, test_case_id, run_index, created_at_ms);

-- Composite index for suite/run/case filtering
CREATE INDEX IF NOT EXISTS idx_results_suite_run_case
    ON test_case_run_results (test_suite_id, test_suite_run_id, test_case_name);

-- Standalone index on id for efficient findById lookups
-- (the composite PK has created_at_ms as leading column, so WHERE id = :id cannot use it efficiently)
CREATE INDEX IF NOT EXISTS idx_results_id
    ON test_case_run_results (id);
