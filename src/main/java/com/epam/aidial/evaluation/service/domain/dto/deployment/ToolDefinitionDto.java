package com.epam.aidial.evaluation.service.domain.dto.deployment;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "MCP tool definition returned by tool discovery")
public class ToolDefinitionDto {

    @Schema(description = "Tool name", example = "search")
    private String name;

    @Schema(description = "Tool description", example = "Search the web for information")
    private String description;

    @Schema(description = "JSON Schema for tool input parameters")
    private Map<String, Object> inputSchema;

    @Schema(description = "JSON Schema for tool output (nullable)")
    private Map<String, Object> outputSchema;
}
