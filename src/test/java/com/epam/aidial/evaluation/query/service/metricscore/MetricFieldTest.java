package com.epam.aidial.evaluation.query.service.metricscore;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.model.MetricScoreAggregation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MetricField")
class MetricFieldTest {

    private final MetricField field = new MetricField("metric::Accuracy::score", "Accuracy.score");

    @Test
    @DisplayName("aggregatedFieldName targets the leaf selected by the aggregation")
    void aggregatedFieldNameUsesLeaf() {
        assertThat(field.aggregatedFieldName(MetricScoreAggregation.AVG))
                .isEqualTo("metric_scores::Accuracy.score::avg");
        assertThat(field.aggregatedFieldName(MetricScoreAggregation.MIN))
                .isEqualTo("metric_scores::Accuracy.score::min");
        assertThat(field.aggregatedFieldName(MetricScoreAggregation.MAX))
                .isEqualTo("metric_scores::Accuracy.score::max");
    }
}
