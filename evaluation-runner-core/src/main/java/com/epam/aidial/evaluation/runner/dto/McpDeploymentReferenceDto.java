package com.epam.aidial.evaluation.runner.dto;

import com.epam.aidial.evaluation.runner.client.mcp.McpTransport;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpDeploymentReferenceDto {

    @NotBlank
    @Size(max = 255)
    @Schema(example = "my-toolset-name", description = "Deployment ID")
    private String id;

    @NotBlank
    @Size(max = 50)
    @Schema(example = "dial-toolset", description = "Deployment type: dial-toolset or dial-application")
    private String type;

    @Size(max = 255)
    @Schema(example = "My Toolset", description = "Display name")
    private String name;

    @Schema(example = "streamable-http", description = "Transport type")
    private McpTransport transport;
}
