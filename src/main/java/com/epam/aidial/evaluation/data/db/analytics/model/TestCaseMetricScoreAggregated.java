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
public class TestCaseMetricScoreAggregated {
    private UUID id;
    private UUID testSuiteRunId;
    private UUID testCaseId;
    private UUID computationId;
    private String metricScores;
    private Long createdAtMs;
    private Long computedAtMs;
}
