package com.epam.aidial.evaluation.service.domain.dto.deployment;

import com.epam.aidial.evaluation.client.mcp.McpTransport;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
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
@Schema(description = "DIAL toolset deployment info")
public class ToolsetInfoDto extends DeploymentInfoDto {

    @Schema(description = "Transport type", example = "streamable-http")
    private McpTransport transport;

    @Schema(description = "Allowed tool names")
    private List<String> allowedTools;
}
