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

    /**
     * Regex enforced on user-defined identifier names (test case schema fields, response columns, metric definitions).
     * Forbids ':' — reserved as the column-family separator in CSV export
     * (see {@code EvalSummaryExportColumnConstants.COLUMN_SEPARATOR}).
     * Layered with {@code @NotBlank}: blank values trigger NotBlank, non-blank colon-bearing values trigger this pattern.
     */
    public static final String IDENTIFIER_NAME_NO_COLON_PATTERN = "^[^:]*$";
    /** Message paired with {@link #IDENTIFIER_NAME_NO_COLON_PATTERN}. */
    public static final String IDENTIFIER_NAME_NO_COLON_MESSAGE =
            "Name must not contain ':' (reserved as CSV export column separator)";

    private ValidationConstants() {}
}
