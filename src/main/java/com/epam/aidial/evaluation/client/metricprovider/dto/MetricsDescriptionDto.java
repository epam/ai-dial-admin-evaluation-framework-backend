package com.epam.aidial.evaluation.client.metricprovider.dto;

import com.epam.aidial.evaluation.configuration.jackson.JsonSchemaStringDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonNaming;

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
