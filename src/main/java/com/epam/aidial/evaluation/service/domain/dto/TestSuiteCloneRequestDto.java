package com.epam.aidial.evaluation.service.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for cloning a test suite.
 * Only {@code name} is required. All other fields are optional — {@code null} means "inherit from source".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestSuiteCloneRequestDto {

    @NotBlank(message = "Name is required")
    @Size(max = 255, message = "Name must be less than 255 characters")
    @Schema(example = "My Cloned Test Suite")
    private String name;

    @Size(max = 2000, message = "Description must be less than 2000 characters")
    @Schema(example = "Clone of original suite")
    private String description;

    @Valid
    private DeploymentReferenceDto deploymentRef;

    @Valid
    private EndpointContractDto endpointRef;

    @Schema(
            example = "550e8400-e29b-41d4-a716-446655440000",
            description =
                    "Optional override of the source suite's datasetId. When omitted, the clone shares the source's dataset.")
    private UUID datasetId;

    @Valid
    @Size(max = 50)
    private List<ResponseColumnDefinitionDto> responseColumns;

    @Valid
    private RequestTemplateDto requestTemplate;

    @Valid
    private List<InputBindingDto> inputBindings;

    @Valid
    @Schema(description = "MCP deployment reference override")
    private McpDeploymentReferenceDto mcpDeploymentRef;

    @Valid
    @Schema(description = "MCP tool reference override")
    private ToolReferenceDto toolRef;

    @Valid
    @Schema(description = "MCP argument template override")
    private ArgumentTemplateDto argumentTemplate;
}
