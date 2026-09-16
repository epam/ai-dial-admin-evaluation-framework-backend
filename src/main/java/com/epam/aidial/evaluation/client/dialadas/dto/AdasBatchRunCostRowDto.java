package com.epam.aidial.evaluation.client.dialadas.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row of a dial-adas aggregate query response for
 * {@code AdasCostQueryBuilder#buildPageTotalCostQuery} — the {@code case}-derived {@code run_id} and
 * {@code total_cost}. {@code count} is selected on the wire but unused by any caller, so it has no field
 * here (Jackson ignores unrecognized properties).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdasBatchRunCostRowDto {

    @JsonProperty("run_id")
    private String runId;

    @JsonProperty("total_cost")
    private Double totalCost;
}
