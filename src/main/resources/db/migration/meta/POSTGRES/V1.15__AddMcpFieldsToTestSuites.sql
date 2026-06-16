ALTER TABLE test_suites
    ADD COLUMN suite_type VARCHAR(20) NOT NULL DEFAULT 'DEPLOYMENT',
    ADD COLUMN mcp_deployment_ref JSONB,
    ADD COLUMN tool_ref JSONB,
    ADD COLUMN argument_template JSONB;
