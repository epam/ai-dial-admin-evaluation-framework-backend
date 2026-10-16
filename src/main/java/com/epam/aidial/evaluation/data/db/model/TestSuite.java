package com.epam.aidial.evaluation.data.db.model;

import com.epam.aidial.evaluation.runner.model.MetricScoreAggregation;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestSuite {

    private UUID id;
    private String name;
    private String description;
    /** Never null: mirrors the DB column's NOT NULL DEFAULT 'DEPLOYMENT'. */
    @Builder.Default
    private SuiteType suiteType = SuiteType.DEPLOYMENT;

    private UUID datasetId;
    private String deploymentRef;
    private String routesRef;
    private String endpointRef;
    private String responseColumns;
    private String requestTemplate;
    private String inputBindings;
    private String mcpDeploymentRef;
    private String toolRef;
    private String argumentTemplate;
    private String additionalRequests;
    private String requestName;
    private String overallScore;
    private String testCaseOverallScore;
    private Double overallScoreThreshold;
    private MetricScoreAggregation metricScoreAggregation;
    private String testCaseFilter;
    private boolean valid;
    private String validationWarnings;
    private Long version;
    private String createdBy;
    private Long createdAt;
    private Long updatedAt;
}
