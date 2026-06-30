package com.epam.aidial.evaluation.constants;

/**
 * Constants for the metric-score-statistics bounded context: the reserved query parameter names bound
 * at computation time and the structured-query wiring (entity, output alias, run-scoping field names).
 *
 * <p>The per-metric statistic queries (AVG/P10/P90/MIN/MAX) and the default {@code overall} query are
 * constructed in code in {@code BuiltInMetricStatistics} (no DB table, no seed migration); the executor
 * runs whatever that catalog provides. The reserved parameter names, entity, and output alias below are
 * the shared vocabulary those built-in queries are built from.
 */
public final class MetricScoreConstants {

    // Reserved query parameter names (bound per computation).
    public static final String PARAM_RUN_ID = "runId";
    public static final String PARAM_COMPUTATION_ID = "computationId";
    public static final String PARAM_METRIC_FIELD = "metricField";

    /** Per-run overall score name. The default overall is the single metric's average. */
    public static final String SCORE_OVERALL = "overall";

    // Structured-query wiring shared by the built-in queries.
    public static final String ENTITY_EVAL_SUMMARIES = "eval_summaries";
    public static final String VALUE_ALIAS = "value";

    // Run-scoping filter field names (columns of the eval_summaries entity).
    public static final String FIELD_TEST_SUITE_RUN_ID = "test_suite_run_id";
    public static final String FIELD_COMPUTATION_ID = "computation_id";

    private MetricScoreConstants() {}
}
