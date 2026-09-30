-- Per-test-case, per-computation aggregation of raw eval-summary metric values: one row per
-- (test_suite_run_id, test_case_id, computation_id), collapsing every run_index/request_index/turn_index
-- combination for that test case into a single JSONB map of per-metric avg/min/max/count. A metric never
-- fired for that test case is simply absent from the map (never a zero/null entry). Rewritten in full on
-- every re-aggregation (see TestCaseMetricScoreAggregator), so writes are upserts, not append-only.
-- created_at_ms is set once at the row's first insert and left untouched by later upserts (so a row's
-- eventual partition placement, if this table is range-partitioned by created_at_ms later, stays fixed
-- across re-aggregations); computed_at_ms tracks the most recent computation.
CREATE TABLE test_case_metric_scores_aggregated (
    id                   VARCHAR(36)      NOT NULL PRIMARY KEY,
    test_suite_run_id    VARCHAR(36)      NOT NULL,
    test_case_id         VARCHAR(36)      NOT NULL,
    computation_id       VARCHAR(36)      NOT NULL,
    metric_scores        JSONB            NOT NULL DEFAULT '{}',
    created_at_ms        BIGINT           NOT NULL,
    computed_at_ms       BIGINT           NOT NULL
);

CREATE UNIQUE INDEX uq_tc_metric_scores_agg_natural_key
    ON test_case_metric_scores_aggregated (test_suite_run_id, test_case_id, computation_id);

CREATE INDEX idx_tc_metric_scores_agg_computation
    ON test_case_metric_scores_aggregated (computation_id);
