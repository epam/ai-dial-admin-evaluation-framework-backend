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
public class TotalCostResponseDto {

    @Schema(
            description = "Total evaluation cost across test-case execution and metric-evaluation calls for the "
                    + "requested period and deployments, in DIAL Core's pricing currency unit. Without "
                    + "deploymentIds it sums each request's own price; with deploymentIds it sums the price of "
                    + "each listed deployment including the requests it initiated. Null when no matching "
                    + "dial-adas usage-log rows exist.",
            example = "0.0123")
    private Double totalCost;

    @Schema(
            description = "Portion of the total cost spent on test-case execution calls. Null when no matching "
                    + "usage-log rows exist; 0 when rows exist but none belong to this phase.",
            example = "0.0100")
    private Double testCaseCost;

    @Schema(
            description = "Portion of the total cost spent on metric-evaluation (judge model) calls. Null when no "
                    + "matching usage-log rows exist; 0 when rows exist but none belong to this phase.",
            example = "0.0023")
    private Double metricEvalCost;
}
