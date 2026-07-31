package com.epam.aidial.evaluation.runner.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolReferenceDto {

    @NotBlank
    @Size(max = 255)
    @Schema(example = "search", description = "Tool name")
    private String name;

    @Size(max = 2000)
    @Schema(example = "Search the web for information", description = "Tool description")
    private String description;

    @NotNull
    @Schema(description = "Tool input JSON schema")
    private Map<String, Object> inputSchema;

    @Schema(description = "Tool output JSON schema (nullable)")
    private Map<String, Object> outputSchema;
}
