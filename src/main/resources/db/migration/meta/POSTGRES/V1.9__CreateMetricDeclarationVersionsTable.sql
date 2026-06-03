-- Create metric_declaration_versions table for versioned schema and description per declaration
CREATE TABLE IF NOT EXISTS metric_declaration_versions (
    id VARCHAR(36) PRIMARY KEY,
    metric_declaration_id VARCHAR(36) NOT NULL,
    schema_version INT NOT NULL,
    config_schema JSONB NOT NULL,
    input_schema JSONB NOT NULL,
    output_schema JSONB NOT NULL,
    description TEXT,
    created_at_ms BIGINT NOT NULL,
    CONSTRAINT fk_metric_declaration_versions_declaration
        FOREIGN KEY (metric_declaration_id) REFERENCES metric_declarations(id)
);

CREATE INDEX IF NOT EXISTS idx_metric_declaration_versions_declaration_id
    ON metric_declaration_versions(metric_declaration_id);

CREATE INDEX IF NOT EXISTS idx_metric_declaration_versions_declaration_version
    ON metric_declaration_versions(metric_declaration_id, schema_version DESC);
