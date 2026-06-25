package com.epam.aidial.evaluation.service.domain.dto.analytics;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A computed metric-score statistic for a run")
public class MetricScoreResultResponseDto {

    private UUID id;
    private UUID testSuiteRunId;
    private UUID computationId;

    @Schema(description = "The statistic / definition name", example = "P90")
    private String metricScoreName;

    @Schema(description = "The metric output field, as <metricName>.<outputField>", example = "Relevancy.score")
    private String metricName;

    @Schema(description = "The computed numeric value", example = "0.871")
    private Double value;
}
