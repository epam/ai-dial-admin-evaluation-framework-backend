package com.epam.aidial.evaluation.client.dialadas.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row of a dial-adas aggregate query response, matching the {@code count} and {@code avg_cost}
 * aliases emitted by {@code RunCostQueryBuilder}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdasAggregateRowDto {

    private Long count;

    @JsonProperty("avg_cost")
    private Double avgCost;
}
