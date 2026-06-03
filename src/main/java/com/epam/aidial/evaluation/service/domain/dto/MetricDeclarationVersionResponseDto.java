package com.epam.aidial.evaluation.service.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
public class MetricDeclarationVersionResponseDto {

    @Schema(example = "660e8400-e29b-41d4-a716-446655440001", description = "Version record ID")
    private UUID id;

    @Schema(example = "550e8400-e29b-41d4-a716-446655440000", description = "Metric declaration ID")
    private UUID metricDeclarationId;

    @Schema(example = "1", description = "Schema version number (monotonically increasing per declaration)")
    private int schemaVersion;

    @Schema(description = "JSON schema for metric configuration")
    private Map<String, Object> configSchema;

    @Schema(description = "JSON schema for metric input")
    private Map<String, Object> inputSchema;

    @Schema(description = "JSON schema for metric output")
    private Map<String, Object> outputSchema;

    @Schema(example = "Exact Match")
    private String displayName;

    @Schema(example = "Percentage of correct predictions")
    private String description;

    @Schema(example = "1704067200000", description = "Creation timestamp (epoch milliseconds)")
    private Long createdAt;
}
