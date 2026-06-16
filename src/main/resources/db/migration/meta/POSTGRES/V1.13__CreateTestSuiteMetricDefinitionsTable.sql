-- Create test_suite_metric_definitions table for metric applications within test suites
CREATE TABLE IF NOT EXISTS test_suite_metric_definitions
(
    id                            VARCHAR(36) PRIMARY KEY,
    test_suite_id                 VARCHAR(36)  NOT NULL,
    metric_declaration_id         VARCHAR(36)  NOT NULL,
    metric_declaration_version_id VARCHAR(36)  NOT NULL,
    name                          VARCHAR(255) NOT NULL,
    config_bindings               JSONB        NOT NULL DEFAULT '[]',
    input_bindings                JSONB        NOT NULL DEFAULT '[]',
    created_at_ms                 BIGINT       NOT NULL,
    updated_at_ms                 BIGINT       NOT NULL,
    CONSTRAINT fk_tsmd_test_suite
        FOREIGN KEY (test_suite_id) REFERENCES test_suites (id) ON DELETE CASCADE,
    CONSTRAINT fk_tsmd_metric_declaration
        FOREIGN KEY (metric_declaration_id) REFERENCES metric_declarations (id),
    CONSTRAINT fk_tsmd_metric_declaration_version
        FOREIGN KEY (metric_declaration_version_id) REFERENCES metric_declaration_versions (id)
);

CREATE INDEX IF NOT EXISTS idx_tsmd_test_suite_id
    ON test_suite_metric_definitions (test_suite_id);

CREATE INDEX IF NOT EXISTS idx_tsmd_metric_declaration_id
    ON test_suite_metric_definitions (metric_declaration_id);

-- Case-insensitive unique name within a test suite
CREATE UNIQUE INDEX uq_tsmd_suite_name
    ON test_suite_metric_definitions (test_suite_id, LOWER(name));
