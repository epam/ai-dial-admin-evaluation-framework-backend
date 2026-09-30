package com.epam.aidial.evaluation.query.service.metricscore;

import com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants;
import com.epam.aidial.evaluation.constants.MetricScoreConstants;
import com.epam.aidial.evaluation.runner.model.MetricScoreAggregation;

/**
 * A flattened numeric metric column of the {@code eval_summaries} entity.
 *
 * @param flattenedName the DSL field name, {@code metric::<tsmd>::<field>}
 * @param metricName the stored {@code metric_name}, {@code <tsmd>.<field>}
 */
public record MetricField(String flattenedName, String metricName) {

    /**
     * The {@code test_case_metric_scores} DSL field holding this metric's per-test-case value for the given
     * aggregation, {@code metric_scores::<tsmd>.<field>::<leaf>}.
     */
    public String aggregatedFieldName(MetricScoreAggregation aggregation) {
        return MetricScoreConstants.FIELD_METRIC_SCORES
                + EvalSummaryExportColumnConstants.COLUMN_SEPARATOR
                + metricName
                + EvalSummaryExportColumnConstants.COLUMN_SEPARATOR
                + aggregation.leaf();
    }
}
