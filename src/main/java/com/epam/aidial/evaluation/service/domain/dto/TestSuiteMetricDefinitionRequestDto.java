package com.epam.aidial.evaluation.service.domain.dto;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.fasterxml.jackson.annotation.JsonCreator;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
@Schema(description = "Request body for creating or updating a test suite metric definition")
public class TestSuiteMetricDefinitionRequestDto {

    /**
     * Default constructor used by Jackson for request-body deserialization. {@code enabled} defaults to {@code true}
     * here so that omitting it from the JSON body yields an enabled metric definition. The Lombok builder defaults
     * {@code enabled} to {@code false}, so builder call-sites must set it explicitly.
     *
     * <p>{@code @JsonCreator} forces Jackson onto this no-arg + setter path; otherwise Jackson 3 binds via the
     * all-args constructor, which would default the missing {@code enabled} primitive to {@code false}.
     */
    @JsonCreator
    public TestSuiteMetricDefinitionRequestDto() {
        this.enabled = true;
    }

    @NotBlank(message = "Name is required")
    @Size(max = 255, message = "Name must be less than 255 characters")
    @Pattern(
            regexp = ValidationConstants.NAME_NO_TWO_COLON_PATTERN,
            message = ValidationConstants.NAME_NO_TWO_COLON_MESSAGE)
    @Schema(description = "Display name for this metric application", example = "Accuracy Check")
    private String name;

    @NotNull(message = "Metric declaration ID is required")
    @Schema(description = "ID of the metric declaration to apply", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID metricDeclarationId;

    @NotNull(message = "Metric declaration version ID is required")
    @Schema(
            description = "ID of the metric declaration version to use",
            example = "660e8400-e29b-41d4-a716-446655440001")
    private UUID metricDeclarationVersionId;

    @Schema(description = "Whether this metric definition is enabled for evaluation", example = "true")
    private boolean enabled;

    @Valid
    @Schema(description = "Bindings for metric config schema properties")
    private List<MetricParameterBindingDto> configBindings;

    @Valid
    @Schema(description = "Bindings for metric input schema properties")
    private List<MetricParameterBindingDto> inputBindings;

    @Size(max = 2000, message = "Condition must be less than 2000 characters")
    @Schema(
            description = "Optional execution condition evaluated per test case against a namespaced dictionary "
                    + "{ data: <test-case columns>, response: <extracted columns> }. A bare name() (e.g. "
                    + "isLastTurn()) is a custom/system function; anything else is a JSONata expression. When it "
                    + "evaluates to boolean true the metric runs; false skips it. Omit or leave blank to always run. "
                    + "Rejected with 400 if malformed.",
            example = "$exists(response.answer)")
    private String condition;
}
