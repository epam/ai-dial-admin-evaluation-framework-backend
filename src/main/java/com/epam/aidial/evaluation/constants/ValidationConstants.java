package com.epam.aidial.evaluation.constants;

/**
 * Non-configurable validation limits (list params, field sizes).
 */
public final class ValidationConstants {

    /** Maximum number of filter parameters on list endpoints. */
    public static final int MAX_LIST_FILTER_PARAMS = 32;
    /** Maximum number of sort parameters on list endpoints. */
    public static final int MAX_LIST_SORT_PARAMS = 32;
    /** Maximum number of fact fields in test cases definition. */
    public static final int MAX_FACT_FIELDS = 128;
    /** Maximum length of a dataset name. Matches the {@code datasets.name VARCHAR(263)} column. */
    public static final int MAX_DATASET_NAME_LENGTH = 263;
    /** Maximum number of columns in an EvalSummary CSV export request and in the planner's derived manifest. */
    public static final int MAX_EXPORT_COLUMNS = 512;
    /**
     * Maximum number of test-case ids that a single {@code TestSuite} can hold in {@code disabledTestCaseIds}.
     * Non-configurable: the cap exists to bound the JSONB array payload and the snapshot-phase
     * {@code NOT (id = ANY(?::text[]))} predicate.
     */
    public static final int MAX_DISABLED_TC_IDS = 10000;

    /** Minimum allowed value for {@code TestSuiteRequestDto.overallScoreThreshold} (inclusive). */
    public static final String MIN_OVERALL_SCORE_THRESHOLD = "0.0";

    /** Maximum allowed value for {@code TestSuiteRequestDto.overallScoreThreshold} (inclusive). */
    public static final String MAX_OVERALL_SCORE_THRESHOLD = "1.0";

    /** Message paired with {@link #MIN_OVERALL_SCORE_THRESHOLD} / {@link #MAX_OVERALL_SCORE_THRESHOLD}. */
    public static final String OVERALL_SCORE_THRESHOLD_RANGE_MESSAGE =
            "overallScoreThreshold must be between 0.0 and 1.0";

    private ValidationConstants() {}
}
