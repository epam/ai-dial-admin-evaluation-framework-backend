package com.epam.aidial.evaluation.client.dialcore.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * DIAL Core model deployment payload, discriminator {@code object=model}.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DialCoreModelDto extends DialCoreDeploymentDto {

    private String model;
    private String lifecycleStatus;
    private DialCoreCapabilitiesDto capabilities;
    private DialCoreLimitsDto limits;
    private DialCorePricingDto pricing;
}
