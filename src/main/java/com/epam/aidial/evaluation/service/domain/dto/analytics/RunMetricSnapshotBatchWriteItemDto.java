package com.epam.aidial.evaluation.service.domain.dto.analytics;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunMetricSnapshotBatchWriteItemDto {

    @NotNull(message = "tsmdId is required")
    private UUID tsmdId;

    @NotBlank(message = "tsmdName is required")
    @Size(max = 255, message = "tsmdName must be less than 255 characters")
    private String tsmdName;

    @NotNull(message = "metricDeclarationId is required")
    private UUID metricDeclarationId;

    @NotNull(message = "metricDeclarationVersionId is required")
    private UUID metricDeclarationVersionId;

    private JsonNode configBindings;

    private JsonNode inputBindings;

    private JsonNode outputSchema;
}
