CREATE TABLE run_metric_snapshots (
    id                             VARCHAR(36)  NOT NULL PRIMARY KEY,
    computation_id                 VARCHAR(36)  NOT NULL,
    test_suite_run_id              VARCHAR(36)  NOT NULL,
    tsmd_id                        VARCHAR(36)  NOT NULL,
    tsmd_name                      VARCHAR(255) NOT NULL,
    metric_declaration_id          VARCHAR(36)  NOT NULL,
    metric_declaration_version_id  VARCHAR(36)  NOT NULL,
    config_bindings                JSONB        NOT NULL DEFAULT '[]',
    input_bindings                 JSONB        NOT NULL DEFAULT '[]',
    output_schema                  JSONB        NOT NULL DEFAULT '{}',
    computed_at_ms                 BIGINT       NOT NULL
);

CREATE UNIQUE INDEX uq_run_metric_snapshots_computation_tsmd
    ON run_metric_snapshots (computation_id, tsmd_id);

CREATE INDEX idx_run_metric_snapshots_run
    ON run_metric_snapshots (test_suite_run_id);
