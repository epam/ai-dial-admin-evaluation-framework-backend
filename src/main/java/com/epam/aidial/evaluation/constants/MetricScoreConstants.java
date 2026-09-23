package com.epam.aidial.evaluation.constants;

/**
 * Constants for the metric-score bounded context: the reserved query parameter names bound at
 * computation time, the structured-query wiring (entities, output alias, run-scoping and
 * score-identifying field names) shared across both the {@code eval_summaries}-based statistics
 * queries and reads of the persisted {@code metric_score_results} entity.
 *
 * <p>The per-metric statistic queries (AVG/P10/P90/MIN/MAX) and the default {@code overall} query are
 * constructed in code in {@code BuiltInMetricStatistics} (no DB table, no seed migration); the executor
 * runs whatever that catalog provides. The reserved parameter names, entities, and output alias below
 * are the shared vocabulary those built-in queries are built from. {@code metric_score_results} is the
 * persisted entity those computations write to and that other read paths (e.g. the
 * {@code test_suite_runs} {@code overall_score_value} extension-derived key) read back through the
 * unified Query API — never via bespoke SQL.
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

    /** Wire name of the persisted metric-score-results structured-query entity. */
    public static final String ENTITY_METRIC_SCORE_RESULTS = "metric_score_results";

    // Run-scoping filter field names (columns of the eval_summaries entity).
    public static final String FIELD_TEST_SUITE_RUN_ID = "test_suite_run_id";
    public static final String FIELD_COMPUTATION_ID = "computation_id";

    // Score-identifying field names (columns of the metric_score_results entity).
    public static final String FIELD_METRIC_SCORE_NAME = "metric_score_name";
    public static final String FIELD_METRIC_NAME = "metric_name";

    private MetricScoreConstants() {}
}
