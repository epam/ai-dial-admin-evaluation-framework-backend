package com.epam.aidial.evaluation.runner.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MetricScoreAggregation")
class MetricScoreAggregationTest {

    @Test
    @DisplayName("orDefault resolves null to AVG and keeps an explicit value")
    void orDefaultResolvesNullToAvg() {
        assertThat(MetricScoreAggregation.orDefault(null)).isEqualTo(MetricScoreAggregation.AVG);
        assertThat(MetricScoreAggregation.orDefault(MetricScoreAggregation.MAX)).isEqualTo(MetricScoreAggregation.MAX);
    }

    @Test
    @DisplayName("fromValue is case-insensitive and rejects unknown values")
    void fromValueParses() {
        assertThat(MetricScoreAggregation.fromValue("min")).isEqualTo(MetricScoreAggregation.MIN);
        assertThatThrownBy(() -> MetricScoreAggregation.fromValue("median"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AVG, MIN, MAX");
    }
}
