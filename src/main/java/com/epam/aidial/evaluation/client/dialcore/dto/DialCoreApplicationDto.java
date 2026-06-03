package com.epam.aidial.evaluation.client.dialcore.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DialCoreApplicationDto {

    private String id;
    private String application;
    private String displayName;
    private String description;
    private String reference;
    private String owner;
    private String object;
    private String status;
    private Long createdAt;
    private Long updatedAt;
    private Map<String, Object> features;
    private Map<String, Object> defaults;
    private List<String> descriptionKeywords;
    private Integer maxRetryAttempts;
    private Map<String, Object> applicationProperties;
    private String applicationTypeSchemaId;
    private Map<String, DialCoreRouteDto> routes;
}
