CREATE TABLE metric_score_result (
    id                  VARCHAR(36)   NOT NULL PRIMARY KEY,
    test_suite_run_id   VARCHAR(36)   NOT NULL,
    computation_id      VARCHAR(36)   NOT NULL,
    metric_score_name   VARCHAR(255)  NOT NULL,
    metric_name         VARCHAR(255)  NOT NULL,
    value               DOUBLE PRECISION
);

-- Append-only per computation: one result per (run, computation, score, metric).
CREATE UNIQUE INDEX uq_metric_score_result_natural_key
    ON metric_score_result (test_suite_run_id, computation_id, metric_score_name, metric_name);

CREATE INDEX idx_metric_score_result_run_computation
    ON metric_score_result (test_suite_run_id, computation_id);
