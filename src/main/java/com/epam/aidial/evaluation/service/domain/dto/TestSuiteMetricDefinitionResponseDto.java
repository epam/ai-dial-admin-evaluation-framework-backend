package com.epam.aidial.evaluation.service.domain.dto;

import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Test suite metric definition response")
public class TestSuiteMetricDefinitionResponseDto {

    @Schema(example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(example = "660e8400-e29b-41d4-a716-446655440000")
    private UUID testSuiteId;

    @Schema(example = "770e8400-e29b-41d4-a716-446655440000")
    private UUID metricDeclarationId;

    @Schema(example = "880e8400-e29b-41d4-a716-446655440000")
    private UUID metricDeclarationVersionId;

    @Schema(example = "Accuracy Check")
    private String name;

    @Schema(example = "Accuracy")
    private String metricDeclarationName;

    @Schema(description = "Whether this metric definition is enabled for evaluation", example = "true")
    private boolean enabled;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(
            description = "Optional JSONata condition gating whether this metric runs per turn; "
                    + "omitted when unconditional.",
            example = "turn.last")
    private String condition;

    @Schema(description = "Whether bindings passed soft validation", example = "true")
    private boolean valid;

    @Schema(description = "Soft validation warnings; always present (empty array when none)")
    private List<ValidationWarningDto> validationWarnings;

    @Schema(description = "Bindings for metric config schema properties")
    private List<MetricParameterBindingDto> configBindings;

    @Schema(description = "Bindings for metric input schema properties")
    private List<MetricParameterBindingDto> inputBindings;

    @Schema(example = "1704067200000")
    private Long createdAt;

    @Schema(example = "1704067200000")
    private Long updatedAt;
}
