package com.epam.aidial.evaluation.client.metricprovider.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Base type for metric output entries. Discriminated by the {@code type} field:
 * {@code "value"} → {@link MetricOutputFieldDto}, {@code "error"} → {@link MetricErrorDto}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = MetricOutputFieldDto.class, name = "value"),
    @JsonSubTypes.Type(value = MetricErrorDto.class, name = "error")
})
@JsonIgnoreProperties(ignoreUnknown = true)
public abstract class MetricOutputDto {

    public abstract String getType();
}
