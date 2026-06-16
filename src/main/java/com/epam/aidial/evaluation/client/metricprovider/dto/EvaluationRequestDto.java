package com.epam.aidial.evaluation.client.metricprovider.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for metric provider POST /evaluate.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationRequestDto {

    @JsonProperty("metric_name")
    private String metricName;

    private Map<String, Object> config;

    private Map<String, Object> input;
}
