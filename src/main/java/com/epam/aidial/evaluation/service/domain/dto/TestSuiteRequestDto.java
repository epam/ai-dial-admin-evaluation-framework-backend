package com.epam.aidial.evaluation.service.domain.dto;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.runner.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RunnerValidationConstants;
import com.epam.aidial.evaluation.runner.dto.ToolReferenceDto;
import com.epam.aidial.evaluation.runner.dto.overallscore.OverallScoreDefinition;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
    private DeploymentReferenceDto deploymentRef;

    @Valid
    private EndpointContractDto endpointRef;

    @Valid
    @Size(max = RunnerValidationConstants.MAX_RESPONSE_COLUMNS)
    private List<ResponseColumnDefinitionDto> responseColumns;

    @Valid
    private RequestTemplateDto requestTemplate;

    @Valid
    private List<InputBindingDto> inputBindings;

    @Size(max = 255)
    @Schema(
            example = "configure",
            description = "Optional user-facing label for request #0, so it can be targeted by name from a "
                    + "metric condition's `request.name` (mirrors `RequestDefinitionDto.name` on each additional "
                    + "request). Null when request #0 is unlabelled.")
    private String requestName;

    @Valid
    @Size(
            max = RunnerValidationConstants.MAX_ADDITIONAL_REQUESTS,
            message = "additionalRequests must not exceed " + RunnerValidationConstants.MAX_ADDITIONAL_REQUESTS
                    + " entries")
    @Schema(
            description = "Ordered chain of requests 1..N executed after request #0 against the same "
                    + "`deploymentRef`. Response columns across request #0 and every additional request share one "
                    + "flat, globally-unique namespace, capped at " + RunnerValidationConstants.MAX_RESPONSE_COLUMNS
                    + " columns in total. Rejected (400) when non-empty on an `MCP_TOOL` suite. Capped at "
                    + RunnerValidationConstants.MAX_ADDITIONAL_REQUESTS + " entries.",
            example = "[]")
    private List<RequestDefinitionDto> additionalRequests;

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

    @Valid
    @Schema(
            description = "Optional per-suite definition used for per-test-case score computation "
                    + "(`test_case_eval_scores.score`/`.passed`) instead of `overallScore`. Same discriminated "
                    + "shape as `overallScore` (`mean`/`weighted_mean`/`custom_function`). When omitted, "
                    + "per-test-case scoring falls back to `overallScore`. Does not affect the run-level "
                    + "`overall` aggregate, which always uses `overallScore` unconditionally. "
                    + "`weighted_mean`/`custom_function` are not validated as referencing real metrics at "
                    + "write time.",
            example = "{\"type\":\"mean\"}")
    private OverallScoreDefinition testCaseOverallScore;

    @DecimalMin(
            value = ValidationConstants.MIN_OVERALL_SCORE_THRESHOLD,
            message = ValidationConstants.OVERALL_SCORE_THRESHOLD_RANGE_MESSAGE)
    @DecimalMax(
            value = ValidationConstants.MAX_OVERALL_SCORE_THRESHOLD,
            message = ValidationConstants.OVERALL_SCORE_THRESHOLD_RANGE_MESSAGE)
    @Schema(
            description = "Optional threshold the run-level `overall` metric score is compared against "
                    + "(e.g. for pass/fail evaluation). Same numeric type as the computed overall score result. "
                    + "Must be between 0.0 and 1.0 (inclusive). Null = no threshold configured.",
            example = "0.8")
    private Double overallScoreThreshold;

    @Schema(
            description =
                    "Optional per-suite test-case filter, as a Structured Query DSL filter subtree "
                            + "(the `filter` of a `test_cases` query). Selects which of the bound dataset's test cases run: "
                            + "combined (AND) with `is_valid` at run-creation count and snapshot. "
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
