package com.epam.aidial.evaluation.constants;

/**
 * Constants for the metric-score-statistics bounded context: the reserved query parameter names bound
 * at computation time, the structured-query wiring (entity + output alias), and the default
 * {@code overall} expression.
 *
 * <p>The per-metric statistic definitions (AVG/P10/P90/MIN/MAX) live as literals in the seed migration
 * {@code meta/POSTGRES/V1.24__SeedMetricScoreDefinitions.sql} — the executor processes whatever the
 * definition repository returns generically and never names individual statistics. The reserved
 * parameter names, entity, and output alias below MUST match that seed JSON (cross-checked by a guard
 * test). The {@code overall} score is NOT seeded: it is a per-suite property
 * ({@code test_suites.overall_score}), defaulting to {@link #DEFAULT_OVERALL_EXPRESSION}.
 */
public final class MetricScoreConstants {

    // Reserved query parameter names (bound per computation).
    public static final String PARAM_RUN_ID = "runId";
    public static final String PARAM_COMPUTATION_ID = "computationId";
    public static final String PARAM_METRIC_FIELD = "metricField";
    /** Bound to an array of the run's per-metric {@code avg(...)} terms for run-level reductions. */
    public static final String PARAM_METRIC_AVGS = "metricAvgs";

    /** Per-run overall score name (the run-level mean of the per-metric averages). */
    public static final String SCORE_OVERALL = "overall";

    // Structured-query wiring shared by seeded definitions.
    public static final String ENTITY_EVAL_SUMMARIES = "eval_summaries";
    public static final String VALUE_ALIAS = "value";

    /**
     * Default {@code overall} definition used when a suite has no custom {@code overall_score}: the mean
     * of the run's per-metric {@code avg(...)} terms (bound to {@code :metricAvgs}), scoped by
     * {@code :runId}/{@code :computationId}. The executor only runs it when the run has exactly one
     * numeric metric field (so the mean is unambiguous); with more than one it is skipped.
     */
    public static final String DEFAULT_OVERALL_EXPRESSION = "{\"entity\":\"eval_summaries\",\"mode\":\"aggregate\","
            + "\"select\":[{\"expr\":{\"type\":\"fn\",\"name\":\"mean\","
            + "\"args\":[{\"type\":\"param\",\"name\":\"metricAvgs\"}]},\"as\":\"value\"}],"
            + "\"filter\":{\"op\":\"and\",\"args\":["
            + "{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"test_suite_run_id\"},"
            + "{\"type\":\"param\",\"name\":\"runId\"}]},"
            + "{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"computation_id\"},"
            + "{\"type\":\"param\",\"name\":\"computationId\"}]}]}}";

    private MetricScoreConstants() {}
}
