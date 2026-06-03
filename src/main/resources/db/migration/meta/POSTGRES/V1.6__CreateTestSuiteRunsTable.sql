-- Create test_suite_runs table
CREATE TABLE IF NOT EXISTS test_suite_runs (
    id VARCHAR(36) PRIMARY KEY,
    test_suite_id VARCHAR(36) NOT NULL REFERENCES test_suites(id) ON DELETE CASCADE,
    test_run_name VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    run_config JSONB NOT NULL,
    number_of_test_cases INTEGER NOT NULL,
    started_at_ms BIGINT,
    completed_at_ms BIGINT,
    error_message TEXT,
    error_details JSONB,
    created_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL
);

-- Sequence for auto-generated run names
CREATE SEQUENCE test_suite_run_name_seq START WITH 1 INCREMENT BY 1;

-- Indexes
CREATE INDEX IF NOT EXISTS idx_test_suite_runs_test_suite_id ON test_suite_runs(test_suite_id);
CREATE INDEX IF NOT EXISTS idx_test_suite_runs_status ON test_suite_runs(status);
CREATE INDEX IF NOT EXISTS idx_test_suite_runs_created_at_ms ON test_suite_runs(created_at_ms DESC);

-- Unique constraint: one name per suite
ALTER TABLE test_suite_runs ADD CONSTRAINT uq_test_suite_runs_suite_name UNIQUE (test_suite_id, test_run_name);
