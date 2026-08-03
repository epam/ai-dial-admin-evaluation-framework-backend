package com.epam.aidial.evaluation.service.domain.dto;

import com.epam.aidial.evaluation.runner.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.ToolReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.overallscore.OverallScoreDefinition;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

/**
 * Suite snapshot DTO. Jackson 3 deserializes via the Lombok builder so that {@code @Builder.Default}
 * is honored for missing JSON fields (e.g. {@code snapshotVersion} defaults to {@link #CURRENT_VERSION}).
 *
 * <p>{@code @Jacksonized} cannot be used because both Jackson 2 and Jackson 3 are on the classpath
 * (MCP SDK dependency), causing Lombok ambiguity errors. The {@code @JsonDeserialize(builder = ...)}
 * + {@code @JsonPOJOBuilder} annotations replicate what {@code @Jacksonized} would generate.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonDeserialize(builder = SuiteSnapshotDto.SuiteSnapshotDtoBuilder.class)
public class SuiteSnapshotDto {

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

    @Schema(
            description = "Per-suite 'overall' metric-score definition captured at snapshot time; null = system "
                    + "default (single-metric only). Added in a backward-compatible way — absent in older "
                    + "version-2 snapshots, which fall back to the default.")
    private OverallScoreDefinition overallScore;

    public static final String CURRENT_VERSION = "2";

    @JsonPOJOBuilder(withPrefix = "")
    public static class SuiteSnapshotDtoBuilder {}
}
