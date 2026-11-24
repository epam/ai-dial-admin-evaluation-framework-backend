package com.epam.aidial.evaluation.client.dialadas.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row of a dial-adas aggregate query response for
 * {@code AdasCostQueryBuilder#buildTotalAggregateQuery} — {@code count}, {@code total_cost} and its
 * execution/metric-evaluation phase split. {@code count} drives the "no usage" ({@code null}) result.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdasTotalCostRowDto {

    private Long count;

    @JsonProperty("total_cost")
    private Double totalCost;

    @JsonProperty("test_case_cost")
    private Double testCaseCost;

    @JsonProperty("metric_eval_cost")
    private Double metricEvalCost;
}
