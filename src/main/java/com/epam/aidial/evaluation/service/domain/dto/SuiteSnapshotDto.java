package com.epam.aidial.evaluation.service.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SuiteSnapshotDto {

    /**
     * Stored snapshot version. Defaults to {@link #CURRENT_VERSION} via {@link lombok.Builder.Default}
     * so JSON missing the {@code snapshotVersion} field deserializes as the current version (treating
     * the omission as a producer bug, not as a legacy v1 snapshot). Genuine legacy v1 snapshots set
     * the field explicitly to {@code "1"} and are rejected by {@code resolveSnapshot}.
     */
    @Builder.Default
    @Schema(description = "Snapshot schema version", example = "2")
    private String snapshotVersion = CURRENT_VERSION;

    @Schema(description = "Suite type at snapshot time")
    private String suiteType;

    @Schema(
            description = "Reference to the dataset the suite was bound to at snapshot time. "
                    + "Present for snapshots written under the dataset-rooted model (version 2+); "
                    + "absent for legacy version-1 snapshots.")
    private DatasetReferenceDto datasetRef;

    @Schema(description = "Deployment reference (DEPLOYMENT suites)")
    private DeploymentReferenceDto deploymentRef;

    @Schema(description = "Endpoint contract (DEPLOYMENT suites)")
    private EndpointContractDto endpointRef;

    @Schema(description = "Request template (DEPLOYMENT suites)")
    private RequestTemplateDto requestTemplate;

    @Schema(description = "Input bindings")
    private List<InputBindingDto> inputBindings;

    @Schema(description = "Response column definitions")
    private List<ResponseColumnDefinitionDto> responseColumns;

    @Schema(description = "Test case schema field definitions")
    private List<FieldDefinitionDto> testCaseSchema;

    @Schema(description = "MCP deployment reference (MCP_TOOL suites)")
    private McpDeploymentReferenceDto mcpDeploymentRef;

    @Schema(description = "MCP tool reference (MCP_TOOL suites)")
    private ToolReferenceDto toolRef;

    @Schema(description = "MCP argument template (MCP_TOOL suites)")
    private ArgumentTemplateDto argumentTemplate;

    public static final String CURRENT_VERSION = "2";
}
