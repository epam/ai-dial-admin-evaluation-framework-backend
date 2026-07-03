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
public class MetricScoreResult {
    private UUID id;
    private UUID testSuiteRunId;
    private UUID computationId;
    private String metricScoreName;
    private String metricName;
    private Double value;
}
