package com.epam.aidial.evaluation.service.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeploymentCostsResponseDto {

    @Schema(
            description = "Total cost of test-case execution calls for this deployment over the requested "
                    + "time range, in DIAL Core's pricing currency unit. Null when no matching dial-adas "
                    + "usage-log rows exist for this phase.",
            example = "0.0007125")
    private Double totalTestCaseCost;

    @Schema(
            description = "Total cost of metric-evaluation (judge model) calls for this deployment over the "
                    + "requested time range. Null when no matching dial-adas usage-log rows exist for this "
                    + "phase.",
            example = "0.000231")
    private Double totalMetricEvalCost;
}
