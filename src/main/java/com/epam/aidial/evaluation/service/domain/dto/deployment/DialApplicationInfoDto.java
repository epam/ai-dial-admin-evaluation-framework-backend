package com.epam.aidial.evaluation.service.domain.dto.deployment;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description = "DIAL application deployment info",
        example =
                "{\"$type\":\"dial-application\",\"deploymentId\":\"EntityExtractor\",\"displayName\":\"Entity Extractor\"}")
public class DialApplicationInfoDto extends DeploymentInfoDto {

    @Schema(description = "Application type schema ID")
    private String applicationTypeSchemaId;

    @Schema(description = "Application properties")
    private Map<String, Object> applicationProperties;

    @Schema(description = "Application routes by name")
    private Map<String, ApplicationRouteDto> routes;
}
