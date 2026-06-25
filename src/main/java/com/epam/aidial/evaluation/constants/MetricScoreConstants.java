package com.epam.aidial.evaluation.constants;

/**
 * Constants for the metric-score-statistics bounded context: definition types, the reserved query
 * parameter names bound at computation time, the predefined statistic names, and the structured-query
 * wiring (entity + output alias) used by seeded definitions.
 *
 * <p>The statistic names and the reserved parameter names MUST match the seed JSON in
 * {@code V1.11__SeedGlobalMetricScoreDefinitions.sql} (cross-checked by a guard test).
 */
public final class MetricScoreConstants {

    // Definition types (scope): DEFAULT applies to every suite; TEST_SUITE is scoped via target_id.
    public static final String TYPE_DEFAULT = "DEFAULT";
    public static final String TYPE_TEST_SUITE = "TEST_SUITE";

    // Reserved query parameter names (bound per computation).
    public static final String PARAM_RUN_ID = "runId";
    public static final String PARAM_COMPUTATION_ID = "computationId";
    public static final String PARAM_METRIC_FIELD = "metricField";
    /** Bound to an array of the run's per-metric {@code avg(...)} terms for run-level reductions. */
    public static final String PARAM_METRIC_AVGS = "metricAvgs";

    // Predefined per-metric statistic names.
    public static final String STAT_AVG = "AVG";
    public static final String STAT_P10 = "P10";
    public static final String STAT_P90 = "P90";
    public static final String STAT_MIN = "MIN";
    public static final String STAT_MAX = "MAX";

    /** Per-run overall score (unweighted mean of the per-metric averages), computed via the DSL. */
    public static final String SCORE_OVERALL = "overall";

    // Structured-query wiring shared by seeded definitions.
    public static final String ENTITY_EVAL_SUMMARIES = "eval_summaries";
    public static final String VALUE_ALIAS = "value";

    /** DSL function that averages an array of expressions (used by the overall definition). */
    public static final String FN_MEAN = "mean";

    private MetricScoreConstants() {}
}
