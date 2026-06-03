-- Create test_suites table
CREATE TABLE IF NOT EXISTS test_suites (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(2000),
    status VARCHAR(50) NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    created_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL
);

-- Create index on name for faster lookups
CREATE INDEX IF NOT EXISTS idx_test_suites_name ON test_suites(name);

-- Create index on status for filtering
CREATE INDEX IF NOT EXISTS idx_test_suites_status ON test_suites(status);

-- Create index on created_at_ms for ordering
CREATE INDEX IF NOT EXISTS idx_test_suites_created_at_ms ON test_suites(created_at_ms DESC);
