package com.epam.aidial.evaluation.cli.client.source.dto;

import com.epam.aidial.evaluation.runner.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.ToolReferenceDto;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Local mirror of the EF backend's {@code TestSuiteResponseDto}.
 *
 * <p>Manually kept in sync with {@code com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestSuiteResponseDto {

    private UUID id;
    private String name;
    private String description;
    private SuiteType suiteType;
    private UUID datasetId;
    private List<UUID> disabledTestCaseIds;
    private DeploymentReferenceDto deploymentRef;
    private EndpointContractDto endpointRef;
    private List<ResponseColumnDefinitionDto> responseColumns;
    private RequestTemplateDto requestTemplate;
    private List<InputBindingDto> inputBindings;
    private McpDeploymentReferenceDto mcpDeploymentRef;
    private ToolReferenceDto toolRef;
    private ArgumentTemplateDto argumentTemplate;
    private boolean valid;
    private Long version;
    private String createdBy;
    private Long createdAt;
    private Long updatedAt;
}
