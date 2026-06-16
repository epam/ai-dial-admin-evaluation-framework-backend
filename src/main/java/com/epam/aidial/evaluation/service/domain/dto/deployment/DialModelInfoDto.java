package com.epam.aidial.evaluation.service.domain.dto.deployment;

import io.swagger.v3.oas.annotations.media.Schema;
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
        description = "DIAL model deployment info",
        example = "{\"$type\":\"dial-model\",\"deploymentId\":\"gpt-5-mini\",\"displayName\":\"GPT-5 mini\"}")
public class DialModelInfoDto extends DeploymentInfoDto {

    @Schema(description = "Model capabilities")
    private ModelCapabilitiesDto capabilities;

    @Schema(description = "Model token limits")
    private ModelLimitsDto limits;

    @Schema(description = "Model pricing")
    private ModelPricingDto pricing;
}
