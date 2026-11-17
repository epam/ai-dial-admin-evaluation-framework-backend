package com.epam.aidial.evaluation.constants;

public final class TestSuiteRunConstants {

    public static final int MAX_SORT_PARAMS = 32;
    public static final int MAX_FILTER_PARAMS = 32;

    /** Key of the per-run score threshold inside {@code test_suite_runs.suite_snapshot}. */
    public static final String SNAPSHOT_OVERALL_SCORE_THRESHOLD_KEY = "overallScoreThreshold";

    private TestSuiteRunConstants() {}
}
