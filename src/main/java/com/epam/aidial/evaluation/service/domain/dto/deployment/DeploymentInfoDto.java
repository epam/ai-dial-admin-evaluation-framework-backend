package com.epam.aidial.evaluation.service.domain.dto.deployment;

import com.epam.aidial.evaluation.client.dialcore.dto.InterfaceType;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Base DTO for deployment info (model, application, or toolset).
 * Polymorphic serialization uses {@code $type} discriminator with values
 * "dial-model" | "dial-application" | "dial-toolset".
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "$type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = DialModelInfoDto.class, name = "dial-model"),
    @JsonSubTypes.Type(value = DialApplicationInfoDto.class, name = "dial-application"),
    @JsonSubTypes.Type(value = ToolsetInfoDto.class, name = "dial-toolset")
})
@Schema(description = "Deployment info (model, application, or toolset)", discriminatorProperty = "$type")
public abstract class DeploymentInfoDto {

    @Schema(description = "Deployment ID", example = "gpt-5-mini-2025-08-07")
    private String deploymentId;

    @Schema(description = "Display name", example = "GPT-5 mini")
    private String displayName;

    @Schema(description = "Version string", example = "2025-08-07")
    private String version;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Owner", example = "organization-owner")
    private String owner;

    @Schema(description = "Created at (epoch ms)", example = "1768856213216")
    private Long createdAt;

    @Schema(description = "Updated at (epoch ms)", example = "1768856213216")
    private Long updatedAt;

    @Schema(description = "Description keywords")
    private List<String> descriptionKeywords;

    @Schema(description = "Allowed input attachment MIME types")
    private List<String> inputAttachmentTypes;

    @Schema(description = "Reference to use for opening the application")
    private String reference;

    @Schema(
            description = "Interfaces the deployment supports, as reported by DIAL Core. "
                    + "Populated on single-deployment responses only; absent from listing entries.",
            example = "[\"chat\", \"openaiChatCompletions\", \"openaiResponses\"]")
    private List<InterfaceType> interfaces;

    private Map<String, Object> features;
}
