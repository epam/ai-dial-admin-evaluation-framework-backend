package com.epam.aidial.evaluation.runner.dto;

/**
 * Non-configurable validation limits.
 */
public final class RunnerValidationConstants {

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

    /** Max length enforced on a test suite run's user-provided {@code testRunName}. */
    public static final int MAX_TEST_RUN_NAME_LENGTH = 255;

    /**
     * Maximum number of entries in {@code TestSuiteRequestDto.additionalRequests}. Bounds the request
     * chain's length (request #0 plus up to this many additional requests).
     */
    public static final int MAX_ADDITIONAL_REQUESTS = 10;

    /**
     * Maximum number of response columns across the whole request chain — the union of request #0's
     * {@code responseColumns} and every {@code additionalRequests[i].responseColumns}. Extracted from the
     * literal previously hardcoded on {@code TestSuiteRequestDto.responseColumns}.
     */
    public static final int MAX_RESPONSE_COLUMNS = 50;

    private RunnerValidationConstants() {}
}
