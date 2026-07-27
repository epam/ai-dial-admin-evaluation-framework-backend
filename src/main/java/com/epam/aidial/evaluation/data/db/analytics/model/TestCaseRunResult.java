package com.epam.aidial.evaluation.data.db.analytics.model;

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

    /**
     * 0-based chain position of the multi-request suite request that produced this row; 0 for single-request
     * suites. Part of the row's natural key, so two chain requests of one test-case run do not collide.
     */
    @Builder.Default
    private int requestIndex = 0;

    /**
     * That request's resolved label, denormalized onto the row so analytics consumers never need a
     * cross-datasource lookup into the meta DB to render it. Deliberately NOT part of the natural key: it is
     * a mutable display value, following the convention where {@code test_case_name} sits beside the keyed
     * {@code test_case_id}. Nullable for pre-existing and imported rows.
     */
    private String requestLabel;

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
