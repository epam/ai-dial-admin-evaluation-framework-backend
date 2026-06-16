package com.epam.aidial.evaluation.client.dialcore.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DialCoreModelDto {

    private String id;
    private String model;
    private String displayName;
    private String displayVersion;
    private String iconUrl;
    private String description;
    private String reference;
    private String owner;
    private String object;
    private String status;
    private Long createdAt;
    private Long updatedAt;
    private DialCoreFeaturesDto features;
    private List<String> inputAttachmentTypes;
    private Map<String, Object> defaults;
    private List<String> descriptionKeywords;
    private Integer maxRetryAttempts;
    private String lifecycleStatus;
    private DialCoreCapabilitiesDto capabilities;
    private DialCoreLimitsDto limits;
    private DialCorePricingDto pricing;
}
