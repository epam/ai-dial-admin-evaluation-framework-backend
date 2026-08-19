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
public class RunCostsResponseDto {

    @Schema(
            description = "Average per-call price of test-case execution calls for this run, in DIAL Core's "
                    + "pricing currency unit. Null when no matching dial-adas usage-log rows exist for this phase.",
            example = "0.0007125")
    private Double avgTestCaseCost;

    @Schema(
            description = "Average per-call price of metric-evaluation (judge model) calls for this run. "
                    + "Null when no matching dial-adas usage-log rows exist for this phase.",
            example = "0.000231")
    private Double avgMetricEvalCost;
}
