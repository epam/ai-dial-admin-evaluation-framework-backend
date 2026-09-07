package com.epam.aidial.evaluation.constants;

import java.time.format.DateTimeFormatter;

/**
 * Naming and bounds shared by the analytics table partitioning bounded context: the partitioned
 * parent table names, the monthly-partition naming convention, and the per-run removal cap
 * enforced by {@code AnalyticsPartitionMaintenanceService}.
 */
public final class AnalyticsPartitioningConstants {

    // Partitioned parent table names.
    public static final String TABLE_TEST_CASE_RUN_RESULTS = "test_case_run_results";
    public static final String TABLE_TEST_CASE_EVAL_SUMMARIES = "test_case_eval_summaries";
    public static final String TABLE_TEST_CASE_EVAL_SCORES = "test_case_eval_scores";

    // Naming convention: <parent>_p<yyyyMM>, plus the fixed "_p_default" suffix.
    public static final String PARTITION_PREFIX = "_p";
    public static final String DEFAULT_PARTITION_SUFFIX = "_p_default";
    public static final DateTimeFormatter MONTH_LABEL_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");

    /** Upper bound on how many partitions a single maintenance run will drop or detach. */
    public static final int MAX_REMOVALS_PER_RUN = 6;

    private AnalyticsPartitioningConstants() {}
}
