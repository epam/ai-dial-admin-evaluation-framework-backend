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
public class TestCaseRunResult {
    private UUID id;
    private UUID testSuiteRunId;
    private UUID testSuiteId;
    private UUID testCaseId;
    private String testCaseName;
    private int runIndex;
    private int turnIndex;
    private int totalTurns;
    private int lastTurnIndex;
    private UUID multiTurnId;
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
