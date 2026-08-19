package com.epam.aidial.evaluation.client.dialadas.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response body for dial-adas {@code POST /v1/queries/execute} in {@code "mode": "aggregate"}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdasAggregateResponseDto {

    private List<AdasAggregateRowDto> rows;
}
