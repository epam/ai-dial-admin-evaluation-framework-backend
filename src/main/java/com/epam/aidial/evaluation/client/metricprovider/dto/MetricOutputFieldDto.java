package com.epam.aidial.evaluation.client.metricprovider.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Metric output of type "value" — a numeric result with optional details.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetricOutputFieldDto extends MetricOutputDto {

    private String type;

    private BigDecimal value;

    private Map<String, Object> details;
}
