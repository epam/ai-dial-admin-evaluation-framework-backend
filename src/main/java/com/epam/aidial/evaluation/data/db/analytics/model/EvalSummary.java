package com.epam.aidial.evaluation.data.db.analytics.model;

import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalSummary {
    private UUID id;
    private UUID testSuiteId;
    private UUID testSuiteRunId;
    private UUID testCaseRunResultId;
    private UUID testCaseId;
    private String testCaseName;
    private int runIndex;

    /** 0-based position within a suite's request chain; 0 for single-request summaries. */
    @Builder.Default
    private int requestIndex = 0;

    /** Chain length (request count); 1 for single-request summaries. */
    @Builder.Default
    private int totalRequests = 1;

    /** 0-based turn position within a multi-turn test case; 0 for single-turn summaries. */
    @Builder.Default
    private int turnIndex = 0;

    /** Planned turn count of the test case; 1 for single-turn summaries. */
    @Builder.Default
    private int totalTurns = 1;

    private UUID computationId;
    private String testCaseData;
    private String extractedColumns;
    private ExecutionStatus executionStatus;
    private Long execDurationMs;
    private Long metricEvalDurationMs;
    private Integer responseStatusCode;
    private String metricValues;
    private String metricInfos;
    private String extractionWarnings;
    private String requestBody;
    private String responseBody;
    private Long createdAtMs;
    private Long computedAtMs;
}
