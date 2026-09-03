package com.epam.aidial.evaluation.service.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A metric declaration with its latest schema version nested under {@code latestVersion}. Returned by
 * GET /api/v1/metric-declarations/versions/latest, one item per metric declaration that has at least
 * one version.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricDeclarationWithLatestVersionResponseDto {

    @Schema(example = "550e8400-e29b-41d4-a716-446655440000", description = "Metric declaration ID")
    private UUID id;

    @Schema(example = "test-provider", description = "Provider id (synced from metric provider configuration)")
    private String providerId;

    @Schema(example = "Accuracy")
    private String name;

    @Schema(example = "Exact Match")
    private String displayName;

    @Schema(example = "Percentage of correct predictions")
    private String description;

    @Schema(example = "1704067200000", description = "Declaration creation timestamp (epoch milliseconds)")
    private Long createdAt;

    @Schema(description = "Latest schema version of this declaration (greatest schemaVersion)")
    private MetricDeclarationVersionResponseDto latestVersion;
}
