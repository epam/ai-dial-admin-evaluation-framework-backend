package com.epam.aidial.evaluation.service.domain.dto;

import com.epam.aidial.evaluation.data.db.model.SuiteType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestSuiteResponseDto {

    @Schema(example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(example = "My Test Suite")
    private String name;

    @Schema(example = "Suite for endpoint smoke tests")
    private String description;

    @Schema(example = "DEPLOYMENT")
    private SuiteType suiteType;

    @Schema(
            example = "550e8400-e29b-41d4-a716-446655440000",
            description = "Identifier of the Dataset that owns this suite's test cases and test-case schema")
    private UUID datasetId;

    @Schema(description = "Subset of the dataset's test cases that this suite skips during evaluation runs")
    private List<UUID> disabledTestCaseIds;

    private DeploymentReferenceDto deploymentRef;
    private EndpointContractDto endpointRef;
    private List<ResponseColumnDefinitionDto> responseColumns;
    private RequestTemplateDto requestTemplate;
    private List<InputBindingDto> inputBindings;

    @Schema(description = "Whether this suite is multi-turn (POC)", example = "true")
    private boolean multiTurn;

    @Schema(description = "MCP deployment reference (MCP_TOOL suites only)")
    private McpDeploymentReferenceDto mcpDeploymentRef;

    @Schema(description = "MCP tool reference (MCP_TOOL suites only)")
    private ToolReferenceDto toolRef;

    @Schema(description = "MCP argument template with variable placeholders (MCP_TOOL suites only)")
    private ArgumentTemplateDto argumentTemplate;

    @Schema(example = "true")
    private boolean valid;

    private List<ValidationWarningDto> validationWarnings;

    @Schema(example = "1")
    private Long version;

    @Schema(example = "maintainer@example.com")
    private String createdBy;

    @Schema(example = "1704067200000")
    private Long createdAt;

    @Schema(example = "1704067200000")
    private Long updatedAt;

    @Schema(
            description = "Per-suite definition of the run-level `overall` metric score (a structured-query "
                    + "expression), as a JSON object. Null when the suite uses the built-in default.")
    private Map<String, Object> overallScore;

    @Schema(
            description = "Per-suite test-case filter (a Structured Query DSL filter subtree), as a JSON object. "
                    + "Selects which of the bound dataset's test cases run (combined with `disabledTestCaseIds` and "
                    + "validity). Null when the suite applies no filter.")
    private Map<String, Object> testCaseFilter;
}
