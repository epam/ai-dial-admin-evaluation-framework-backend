package com.epam.aidial.evaluation.service.domain.dto.analytics;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One recomputed metric-score value. Never carries a null {@code value} — a null aggregate is omitted. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A single metric-score statistic recomputed over the matched rows")
public class MetricScoreValueDto {

    @Schema(
            description = "Statistic name, as persisted in metric_score_results.metric_score_name",
            example = "AVG",
            allowableValues = {"AVG", "P10", "P90", "MIN", "MAX", "overall"})
    private String metricScoreName;

    @Schema(
            description = "Metric this statistic is over, as '<metric>.<outputField>'; 'overall' for the "
                    + "run-level score. Never split on '.' to recover the parts — a metric name may itself "
                    + "contain dots.",
            example = "Accuracy.score")
    private String metricName;

    @Schema(description = "The computed value", example = "0.83")
    private Double value;
}
