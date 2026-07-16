package com.epam.aidial.evaluation.service.domain.dto;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.data.db.model.SuiteType;
import com.epam.aidial.evaluation.service.domain.dto.overallscore.OverallScoreDefinition;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
public class TestSuiteRequestDto {

    @NotBlank(message = "Name is required")
    @Size(max = 255, message = "Name must be less than 255 characters")
    @Schema(example = "My Test Suite")
    private String name;

    @Size(max = 2000, message = "Description must be less than 2000 characters")
    @Schema(example = "Suite for endpoint smoke tests")
    private String description;

    @Schema(example = "DEPLOYMENT", description = "Suite type: DEPLOYMENT (default) or MCP_TOOL")
    private SuiteType suiteType;

    @Schema(
            example = "550e8400-e29b-41d4-a716-446655440000",
            description = "Identifier of the Dataset that owns this suite's test cases and test-case schema. "
                    + "Optional — suites with no dataset are persisted in the unbound state and can be configured "
                    + "but cannot run; POST /api/v1/test-suites/{id}/runs returns HTTP 409 SUITE_HAS_NO_DATASET")
    private UUID datasetId;

    @Valid
    @Size(
            max = ValidationConstants.MAX_DISABLED_TC_IDS,
            message = "disabledTestCaseIds must not exceed " + ValidationConstants.MAX_DISABLED_TC_IDS + " entries")
    @Schema(
            description = "Subset of the dataset's test cases that this suite skips during evaluation runs. "
                    + "Capped at " + ValidationConstants.MAX_DISABLED_TC_IDS + " entries.")
    private List<UUID> disabledTestCaseIds;

    @Valid
    private DeploymentReferenceDto deploymentRef;

    @Valid
    private EndpointContractDto endpointRef;

    @Valid
    @Size(max = 50)
    private List<ResponseColumnDefinitionDto> responseColumns;

    @Valid
    private RequestTemplateDto requestTemplate;

    @Valid
    private List<InputBindingDto> inputBindings;

    @Valid
    @Schema(description = "MCP deployment reference (required for MCP_TOOL suites)")
    private McpDeploymentReferenceDto mcpDeploymentRef;

    @Valid
    @Schema(description = "MCP tool reference (required for MCP_TOOL suites)")
    private ToolReferenceDto toolRef;

    @Valid
    @Schema(description = "MCP argument template (recommended for MCP_TOOL suites)")
    private ArgumentTemplateDto argumentTemplate;

    /** Optional. When provided on update, reassigns createdBy to another maintainer. */
    @Size(max = 255)
    @Schema(example = "maintainer@example.com")
    private String createdBy;

    @Valid
    @Schema(
            description =
                    "Optional per-suite definition of the run-level `overall` metric score. Discriminated by `type`: "
                            + "`mean` (no parameters, resolved against whatever metric fields the run currently has), "
                            + "`weighted_mean` (an explicit `{metricName, outputField, weight}` list — weights need not "
                            + "already sum to 1), or `custom_function` (a raw Structured Query DSL expression, the "
                            + "free-form escape hatch, referencing metric columns by their flattened name "
                            + "`metric::<metricName>::<outputField>`). When omitted, `overall` falls back to the "
                            + "built-in default (single-metric only). `weighted_mean`/`custom_function` are not "
                            + "validated as referencing real metrics at write time.",
            example = "{\"type\":\"mean\"}")
    private OverallScoreDefinition overallScore;

    @Schema(
            description =
                    "Optional per-suite test-case filter, as a Structured Query DSL filter subtree "
                            + "(the `filter` of a `test_cases` query). Selects which of the bound dataset's test cases run: "
                            + "combined (AND) with `is_valid` and `disabledTestCaseIds` at run-creation count and snapshot. "
                            + "References base columns and flattened `data::<field>` fields. Validated at write time against "
                            + "the bound dataset's test-case schema (unknown field/type/malformed → HTTP 400). Null = no filter.",
            example =
                    "{\"op\":\"or\",\"args\":[{\"op\":\"in\",\"args\":[{\"type\":\"field\",\"name\":\"data::category\"},"
                            + "{\"type\":\"array\",\"items\":[{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"A\"},"
                            + "{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"B\"}]}]},"
                            + "{\"op\":\"co\",\"args\":[{\"type\":\"field\",\"name\":\"data::tags\"},"
                            + "{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"text\"}]}]}")
    private Map<String, Object> testCaseFilter;
}
