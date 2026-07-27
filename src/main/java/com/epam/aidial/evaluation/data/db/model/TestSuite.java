package com.epam.aidial.evaluation.data.db.model;

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
    private SuiteType suiteType;
    private UUID datasetId;
    private String disabledTestCaseIds;
    private String deploymentRef;
    private String endpointRef;
    private String responseColumns;
    private String requestTemplate;
    private String inputBindings;

    /**
     * Ordered chain of additional requests (JSONB array), holding chain elements {@code 1..N-1}; request 0
     * stays in the flat {@code endpointRef}/{@code requestTemplate}/{@code inputBindings}/
     * {@code responseColumns} columns. Null or empty ⇒ single-request suite (pre-existing behavior).
     */
    private String additionalRequests;

    /** Optional label naming request 0; defaulted to {@code request-1} during chain normalization. */
    private String requestLabel;

    private String mcpDeploymentRef;
    private String toolRef;
    private String argumentTemplate;
    private String overallScore;
    private Double overallScoreThreshold;
    private String testCaseFilter;
    private boolean valid;
    private String validationWarnings;
    private Long version;
    private String createdBy;
    private Long createdAt;
    private Long updatedAt;
}
