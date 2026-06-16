package com.epam.aidial.evaluation.service.domain.dto;

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
@Schema(description = "Aggregated test suite metric definition with full metric declaration and version details")
public class AggregatedMetricDefinitionResponseDto {

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

    @Schema(description = "Bindings for metric config schema properties")
    private List<MetricParameterBindingDto> configBindings;

    @Schema(description = "Bindings for metric input schema properties")
    private List<MetricParameterBindingDto> inputBindings;

    @Schema(example = "1704067200000")
    private Long createdAt;

    @Schema(example = "1704067200000")
    private Long updatedAt;

    @Schema(description = "Full metric declaration details")
    private MetricDeclarationResponseDto metricDeclaration;

    @Schema(description = "Full metric declaration version details including schemas")
    private MetricDeclarationVersionResponseDto metricDeclarationVersion;
}
