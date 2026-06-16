package com.epam.aidial.evaluation.constants;

/**
 * Column-naming convention for the EvalSummary CSV export and preview manifest.
 *
 * <p>Snapshot-derived and metric-derived columns use the form {@code <family>:<name>}, where
 * the family-separator is the colon character {@code ':'}. The colon is chosen because it
 * never appears in a Python identifier (analyst-side DataFrame attribute access stays
 * unambiguous) and because it matches the project's existing filter-token punctuation
 * ({@code field:op:value}). Identity, execution, and JSON-blob columns retain camelCase
 * names — they are not derived from snapshot or metric identifiers and therefore do not
 * participate in the {@code <family>:<name>} convention.
 */
public final class EvalSummaryExportColumnConstants {

    /** Family-separator emitted between a column family and its embedded identifier(s). */
    public static final String COLUMN_SEPARATOR = ":";

    /** Prefix for columns inlined from {@code suite_snapshot.testCaseSchema}. */
    public static final String DATA_COLUMN_PREFIX = "data" + COLUMN_SEPARATOR;

    /** Prefix for columns inlined from {@code suite_snapshot.responseColumns}. */
    public static final String RESPONSE_COLUMN_PREFIX = "response" + COLUMN_SEPARATOR;

    /** Prefix for flattened metric value columns: {@code metric:<metricName>:<fieldName>}. */
    public static final String METRIC_COLUMN_PREFIX = "metric" + COLUMN_SEPARATOR;

    /** Prefix for flattened metric info columns: {@code metricInfo:<metricName>:<fieldName>}. */
    public static final String METRIC_INFO_COLUMN_PREFIX = "metricInfo" + COLUMN_SEPARATOR;

    /** Prefix for per-metric wholesale-error columns: {@code metricError:<metricName>}. */
    public static final String METRIC_ERROR_COLUMN_PREFIX = "metricError" + COLUMN_SEPARATOR;

    private EvalSummaryExportColumnConstants() {}
}
