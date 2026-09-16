package com.epam.aidial.evaluation.client.dialadas.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response body for dial-adas {@code POST /v1/queries/execute} in {@code "mode": "aggregate"}. Generic
 * over the row shape, since each {@code AdasCostQueryBuilder} query selects a different set of aliases —
 * see {@link AdasRunAvgCostRowDto}, {@link AdasDeploymentCostRowDto}, {@link AdasBatchRunCostRowDto}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdasAggregateResponseDto<T> {

    private List<T> rows;
}
