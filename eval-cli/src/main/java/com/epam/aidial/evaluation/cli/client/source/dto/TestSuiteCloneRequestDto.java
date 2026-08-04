package com.epam.aidial.evaluation.cli.client.source.dto;

import com.epam.aidial.evaluation.runner.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.ToolReferenceDto;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Local mirror of the EF backend's {@code TestSuiteCloneRequestDto}.
 *
 * <p>Manually kept in sync with
 * {@code com.epam.aidial.evaluation.service.domain.dto.TestSuiteCloneRequestDto}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestSuiteCloneRequestDto {

    private String name;
    private String description;
    private DeploymentReferenceDto deploymentRef;
    private EndpointContractDto endpointRef;
    private List<ResponseColumnDefinitionDto> responseColumns;
    private RequestTemplateDto requestTemplate;
    private List<InputBindingDto> inputBindings;
    private McpDeploymentReferenceDto mcpDeploymentRef;
    private ToolReferenceDto toolRef;
    private ArgumentTemplateDto argumentTemplate;
}
