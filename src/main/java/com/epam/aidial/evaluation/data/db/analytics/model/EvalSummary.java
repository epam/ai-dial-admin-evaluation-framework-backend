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
public class EvalSummary {
    private UUID id;
    private UUID testSuiteId;
    private UUID testSuiteRunId;
    private UUID testCaseRunResultId;
    private UUID testCaseId;
    private String testCaseName;
    private int runIndex;

    /** 0-based turn position within a multi-turn conversation; 0 for single-turn summaries. */
    @Builder.Default
    private int turnIndex = 0;

    /** Planned turn count of the conversation; 1 for single-turn summaries. */
    @Builder.Default
    private int totalTurns = 1;

    private UUID computationId;
    private String testCaseData;
    private String extractedColumns;
    private ExecutionStatus executionStatus;
    private Long execDurationMs;
    private Integer responseStatusCode;
    private String metricValues;
    private String metricInfos;
    private String extractionWarnings;
    private String requestBody;
    private String responseBody;
    private Long createdAtMs;
    private Long computedAtMs;
}
