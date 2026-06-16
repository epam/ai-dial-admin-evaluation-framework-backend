package com.epam.aidial.evaluation.data.db.analytics.model;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunMetricSnapshot {
    private UUID id;
    private UUID computationId;
    private UUID testSuiteRunId;
    private UUID tsmdId;
    private String tsmdName;
    private UUID metricDeclarationId;
    private UUID metricDeclarationVersionId;
    private String configBindings;
    private String inputBindings;
    private String outputSchema;
    private Long computedAtMs;
}
