package com.epam.aidial.evaluation.client.metricprovider.dto;

import com.epam.aidial.evaluation.configuration.jackson.JsonSchemaStringDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Single metric description from a metric provider GET /metrics response.
 * All fields are required by the provider contract.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetricsDescriptionDto {

    private String name;
    private String displayName;
    private String description;
    /** JSON schema; required. Accepts string or object in provider response; normalized to string. */
    @JsonDeserialize(using = JsonSchemaStringDeserializer.class)
    private String configSchema;
    /** JSON schema; required. Accepts string or object in provider response; normalized to string. */
    @JsonDeserialize(using = JsonSchemaStringDeserializer.class)
    private String inputSchema;
    /** JSON schema; required. Accepts string or object in provider response; normalized to string. */
    @JsonDeserialize(using = JsonSchemaStringDeserializer.class)
    private String outputSchema;
}
