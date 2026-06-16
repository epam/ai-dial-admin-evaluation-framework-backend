package com.epam.aidial.evaluation.service.domain.analytics;

import java.util.function.Function;

/**
 * Describes one column in an EvalSummary export.
 *
 * @param name           CSV header / API column identifier (e.g. {@code testCaseName},
 *                       {@code data.prompt}, {@code Accuracy.score}, {@code requestBody})
 * @param isBodyColumn   {@code true} only for the {@code requestBody}/{@code responseBody}
 *                       descriptors. Used by {@link EvalSummaryExportColumnSelector}'s
 *                       empty-input branch to strip body columns from the default set
 * @param valueExtractor function that extracts the column's raw value from a row's working
 *                       object; the CSV writer applies cell-serialization rules to this
 *                       raw value, while the preview path returns it as-is for Jackson
 */
public record ColumnDescriptor(
        String name, boolean isBodyColumn, Function<EvalSummaryExportRow, Object> valueExtractor) {

    /**
     * Whether populating this column requires the LEFT JOIN to {@code test_case_run_results}.
     * In V1 this is precisely the body columns (the body payloads live on the joined table and
     * nowhere else), so it is derived from {@link #isBodyColumn}. If a future column needs the
     * JOIN without being a body column (e.g. a trace-metadata field on
     * {@code test_case_run_results}) or vice versa, split this back into an independent record
     * component — call sites can stay unchanged.
     */
    public boolean requiresJoinProjection() {
        return isBodyColumn;
    }
}
