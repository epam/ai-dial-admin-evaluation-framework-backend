CREATE TABLE test_case_eval_summaries (
    id                       VARCHAR(36)  NOT NULL,
    test_suite_id            VARCHAR(36)  NOT NULL,
    test_suite_run_id        VARCHAR(36)  NOT NULL,
    test_case_run_result_id  VARCHAR(36)  NOT NULL,
    test_case_id             VARCHAR(36)  NOT NULL,
    test_case_name           VARCHAR(255) NOT NULL,
    run_index                INTEGER      NOT NULL,
    computation_id           VARCHAR(36)  NOT NULL,
    test_case_data           JSONB        NOT NULL,
    extracted_columns        JSONB        NOT NULL DEFAULT '{}',
    execution_status         VARCHAR(20)  NOT NULL,
    exec_duration_ms         BIGINT       NOT NULL,
    response_status_code     INTEGER,
    metric_values            JSONB        NOT NULL DEFAULT '{}',
    metric_infos             JSONB,
    created_at_ms            BIGINT       NOT NULL,
    computed_at_ms           BIGINT       NOT NULL,
    PRIMARY KEY (created_at_ms, id)
);

CREATE UNIQUE INDEX uq_eval_summaries_natural_key
    ON test_case_eval_summaries (test_suite_run_id, test_case_id, run_index, computation_id, created_at_ms);

CREATE INDEX idx_eval_summaries_run_computation
    ON test_case_eval_summaries (test_suite_run_id, computation_id);

CREATE INDEX idx_eval_summaries_computation
    ON test_case_eval_summaries (computation_id);

CREATE INDEX idx_eval_summaries_id
    ON test_case_eval_summaries (id);
