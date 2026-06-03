package com.epam.aidial.evaluation.service.domain.dto;

import com.epam.aidial.evaluation.constants.ValidationConstants;
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
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request body for creating or updating a test suite metric definition")
public class TestSuiteMetricDefinitionRequestDto {

    @NotBlank(message = "Name is required")
    @Size(max = 255, message = "Name must be less than 255 characters")
    @Pattern(
            regexp = ValidationConstants.IDENTIFIER_NAME_NO_COLON_PATTERN,
            message = ValidationConstants.IDENTIFIER_NAME_NO_COLON_MESSAGE)
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

    @Builder.Default
    @Schema(description = "Whether this metric definition is enabled for evaluation", example = "true")
    private boolean enabled = true;

    @Valid
    @Schema(description = "Bindings for metric config schema properties")
    private List<MetricParameterBindingDto> configBindings;

    @Valid
    @Schema(description = "Bindings for metric input schema properties")
    private List<MetricParameterBindingDto> inputBindings;
}
