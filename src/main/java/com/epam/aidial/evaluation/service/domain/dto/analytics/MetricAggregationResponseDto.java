package com.epam.aidial.evaluation.service.domain.dto.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricAggregationResponseDto {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private UUID computationId;

    private List<MetricAggregationItemDto> metrics;
}
