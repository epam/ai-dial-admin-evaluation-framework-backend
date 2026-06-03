package com.epam.aidial.evaluation.service.domain.dto.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
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
public class RunMetricSnapshotResponseDto {

    private UUID id;
    private UUID computationId;
    private UUID testSuiteRunId;
    private UUID tsmdId;
    private String tsmdName;
    private UUID metricDeclarationId;
    private UUID metricDeclarationVersionId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode configBindings;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode inputBindings;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, Object> outputSchema;

    private Long computedAtMs;
}
