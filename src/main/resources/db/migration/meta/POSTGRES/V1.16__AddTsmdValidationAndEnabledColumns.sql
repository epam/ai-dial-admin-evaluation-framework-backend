ALTER TABLE test_suite_metric_definitions
    ADD COLUMN is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN is_valid BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN validation_warnings JSONB NOT NULL DEFAULT '[]';
