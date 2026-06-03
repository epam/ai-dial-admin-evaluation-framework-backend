package com.epam.aidial.evaluation.service.domain.dto.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricAggregationItemDto {

    private String metric;
    private String output;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double avg;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double min;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double max;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long count;
}
