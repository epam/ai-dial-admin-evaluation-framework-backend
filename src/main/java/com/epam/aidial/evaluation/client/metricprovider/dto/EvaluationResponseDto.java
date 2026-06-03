package com.epam.aidial.evaluation.client.metricprovider.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response body from metric provider POST /evaluate.
 * The output map keys are metric output field names; values are either
 * {@link MetricOutputFieldDto} (type="value") or {@link MetricErrorDto} (type="error").
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvaluationResponseDto {

    @JsonProperty("metric_name")
    private String metricName;

    private Map<String, MetricOutputDto> output;
}
