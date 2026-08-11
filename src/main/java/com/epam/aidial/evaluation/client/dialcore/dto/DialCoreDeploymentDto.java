package com.epam.aidial.evaluation.client.dialcore.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Polymorphic base DTO for DIAL Core deployment payloads: unified {@code GET /v1/deployments}
 * entries and single-entity {@code GET /openai/{models|applications|toolsets}/{id}} responses.
 * Polymorphic deserialization uses the existing {@code object} discriminator property with
 * values "model" | "application" | "toolset" per the DIAL Core OpenAPI {@code DeploymentData}
 * oneOf/discriminator.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "object",
        visible = true,
        defaultImpl = DialCoreUnknownDeploymentDto.class)
@JsonSubTypes({
    @JsonSubTypes.Type(value = DialCoreModelDto.class, name = "model"),
    @JsonSubTypes.Type(value = DialCoreApplicationDto.class, name = "application"),
    @JsonSubTypes.Type(value = DialCoreToolsetDto.class, name = "toolset")
})
public abstract class DialCoreDeploymentDto {

    private String object;
    private String id;
    private String displayName;
    private String displayVersion;
    private String description;
    private List<String> descriptionKeywords;
    private String iconUrl;
    private String reference;
    private String owner;
    private String status;
    private Long createdAt;
    private Long updatedAt;
    private Map<String, Object> defaults;
    private Integer maxRetryAttempts;
    private List<String> inputAttachmentTypes;
    private List<InterfaceType> interfaces;
    private Map<String, Object> features;
}
