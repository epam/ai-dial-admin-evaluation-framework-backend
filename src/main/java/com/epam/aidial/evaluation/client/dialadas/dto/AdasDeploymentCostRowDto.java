package com.epam.aidial.evaluation.client.dialadas.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row of a dial-adas aggregate query response for
 * {@code AdasCostQueryBuilder#buildDeploymentAggregateQuery} — {@code count} and {@code total_cost}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdasDeploymentCostRowDto {

    private Long count;

    @JsonProperty("total_cost")
    private Double totalCost;
}
