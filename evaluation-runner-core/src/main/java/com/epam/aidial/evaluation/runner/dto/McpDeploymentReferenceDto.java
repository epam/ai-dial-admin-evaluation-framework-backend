package com.epam.aidial.evaluation.runner.dto;

import com.epam.aidial.evaluation.runner.client.mcp.McpTransport;
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
    private String id;

    @NotBlank
    @Size(max = 50)
    private String type;

    @Size(max = 255)
    private String name;

    private McpTransport transport;
}
