CREATE TABLE test_case_run_inputs (
    run_id                    VARCHAR(36)  NOT NULL,
    position                  INTEGER      NOT NULL,
    test_case_id              VARCHAR(36)  NOT NULL,
    test_case_name            VARCHAR(255) NOT NULL,
    test_case_data            JSONB        NOT NULL,
    request_template_override JSONB,
    input_bindings_override   JSONB,
    PRIMARY KEY (run_id, position),
    FOREIGN KEY (run_id) REFERENCES test_suite_runs(id) ON DELETE CASCADE
);

CREATE INDEX idx_test_case_run_inputs_run_id ON test_case_run_inputs (run_id);
