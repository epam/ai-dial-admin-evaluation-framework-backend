package com.epam.aidial.evaluation.runner.model;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class TestCaseRunResult {
    private UUID id;
    private UUID testSuiteRunId;
    private UUID testSuiteId;
    private UUID testCaseId;
    private String testCaseName;
    private int runIndex;

    /** 0-based turn position within a multi-turn test case; 0 for single-turn results. */
    @Builder.Default
    private int turnIndex = 0;

    /** Planned turn count of the test case; 1 for single-turn results. */
    @Builder.Default
    private int totalTurns = 1;

    private String testCaseData;
    private String requestBody;
    private String responseBody;
    private Integer responseStatusCode;
    private ExecutionStatus executionStatus;
    private Long execStartedAtMs;
    private Long execCompletedAtMs;
    private Long execDurationMs;
    private String traceId;
    private String extractedColumns;
    private String extractionWarnings;
    private Integer retryCount;
    private String logDetails;
    private Long createdAtMs;
}
