package com.epam.aidial.evaluation.client.dialcore.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * DIAL Core toolset deployment payload, discriminator {@code object=toolset}. Returned by
 * {@code GET /openai/toolsets/{id}} (single toolset detail) and as the toolset variant of
 * unified {@code GET /v1/deployments} entries.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DialCoreToolsetDto extends DialCoreDeploymentDto {

    private DialTransport transport;
    private List<String> allowedTools;
}
