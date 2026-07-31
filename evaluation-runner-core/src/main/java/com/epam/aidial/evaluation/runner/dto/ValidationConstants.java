package com.epam.aidial.evaluation.runner.dto;

/**
 * Non-configurable validation limits.
 */
public final class ValidationConstants {

    /**
     * Regex enforced on test-case schema field names.
     * Forbids ':' — reserved as the filter operator separator.
     */
    public static final String IDENTIFIER_NAME_NO_COLON_PATTERN = "^[^:]*$";

    /** Message paired with {@link #IDENTIFIER_NAME_NO_COLON_PATTERN}. */
    public static final String IDENTIFIER_NAME_NO_COLON_MESSAGE =
            "Name must not contain ':' (reserved as the filter operator separator)";

    /**
     * Regex enforced on response-column names and metric-definition names.
     * Forbids the '::' sequence — reserved as the column-family separator in CSV export.
     */
    public static final String NAME_NO_TWO_COLON_PATTERN = "(?!.*::).*";

    /** Message paired with {@link #NAME_NO_TWO_COLON_PATTERN}. */
    public static final String NAME_NO_TWO_COLON_MESSAGE =
            "Name must not contain '::' (reserved as CSV export column separator)";

    private ValidationConstants() {}
}
