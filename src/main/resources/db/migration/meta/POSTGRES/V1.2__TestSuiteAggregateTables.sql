-- Update test_suites table for the new aggregate model
ALTER TABLE test_suites
    DROP COLUMN IF EXISTS status;

ALTER TABLE test_suites
    ADD COLUMN IF NOT EXISTS deployment_ref JSONB,
    ADD COLUMN IF NOT EXISTS endpoint_ref JSONB,
    ADD COLUMN IF NOT EXISTS test_cases_definition JSONB,
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- Drop obsolete indexes
DROP INDEX IF EXISTS idx_test_suites_status;

-- Create test_cases table
CREATE TABLE IF NOT EXISTS test_cases (
    id VARCHAR(36) PRIMARY KEY,
    test_suite_id VARCHAR(36) NOT NULL REFERENCES test_suites(id) ON DELETE CASCADE,
    test_case_name VARCHAR(255) NOT NULL,
    parameters JSONB NOT NULL DEFAULT '{}'::jsonb,
    facts JSONB NOT NULL DEFAULT '{}'::jsonb,
    is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    is_valid BOOLEAN NOT NULL,
    validation_warnings TEXT[],
    created_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL
);

-- Create revalidation_tasks table
CREATE TABLE IF NOT EXISTS revalidation_tasks (
    id VARCHAR(36) PRIMARY KEY,
    test_suite_id VARCHAR(36) NOT NULL REFERENCES test_suites(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL,
    total_cases INTEGER NOT NULL DEFAULT 0,
    processed_cases INTEGER NOT NULL DEFAULT 0,
    valid_count INTEGER NOT NULL DEFAULT 0,
    invalid_count INTEGER NOT NULL DEFAULT 0,
    started_at_ms BIGINT,
    completed_at_ms BIGINT,
    error_message TEXT
);

-- Create metric_definitions table
CREATE TABLE IF NOT EXISTS metric_definitions (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(2000),
    created_at_ms BIGINT NOT NULL
);

-- Seed metric definitions
INSERT INTO metric_definitions (id, name, description, created_at_ms)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'Accuracy', 'Measures correctness of responses',
     CAST(EXTRACT(EPOCH FROM clock_timestamp()) * 1000 AS BIGINT)),
    ('00000000-0000-0000-0000-000000000002', 'Latency', 'Measures response time in milliseconds',
     CAST(EXTRACT(EPOCH FROM clock_timestamp()) * 1000 AS BIGINT)),
    ('00000000-0000-0000-0000-000000000003', 'Relevance', 'Measures relevance score',
     CAST(EXTRACT(EPOCH FROM clock_timestamp()) * 1000 AS BIGINT))
ON CONFLICT (id) DO NOTHING;

-- Indexes
CREATE INDEX IF NOT EXISTS idx_test_cases_test_suite_id ON test_cases(test_suite_id);
CREATE INDEX IF NOT EXISTS idx_test_cases_created_at_ms ON test_cases(created_at_ms DESC);
CREATE INDEX IF NOT EXISTS idx_test_suites_created_at_ms ON test_suites(created_at_ms DESC);
CREATE INDEX IF NOT EXISTS idx_test_cases_parameters ON test_cases USING GIN (parameters);
CREATE INDEX IF NOT EXISTS idx_test_cases_facts ON test_cases USING GIN (facts);
