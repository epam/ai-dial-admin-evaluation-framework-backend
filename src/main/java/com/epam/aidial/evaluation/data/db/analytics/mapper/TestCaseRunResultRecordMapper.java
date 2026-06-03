package com.epam.aidial.evaluation.data.db.analytics.mapper;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.data.db.jooq.analytics.tables.records.TestCaseRunResultsRecord;
import java.util.UUID;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;

@Component
@LogExecution
public class TestCaseRunResultRecordMapper {

    public TestCaseRunResult map(TestCaseRunResultsRecord r) {
        return TestCaseRunResult.builder()
                .id(UUID.fromString(r.getId()))
                .testSuiteRunId(UUID.fromString(r.getTestSuiteRunId()))
                .testSuiteId(UUID.fromString(r.getTestSuiteId()))
                .testCaseId(UUID.fromString(r.getTestCaseId()))
                .testCaseName(r.getTestCaseName())
                .runIndex(r.getRunIndex())
                .testCaseData(toJsonString(r.getTestCaseData()))
                .requestBody(toJsonString(r.getRequestBody()))
                .responseBody(toJsonString(r.getResponseBody()))
                .responseStatusCode(r.getResponseStatusCode())
                .executionStatus(ExecutionStatus.valueOf(r.getExecutionStatus()))
                .execStartedAtMs(r.getExecStartedAtMs())
                .execCompletedAtMs(r.getExecCompletedAtMs())
                .execDurationMs(r.getExecDurationMs())
                .traceId(r.getTraceId())
                .extractedColumns(toJsonString(r.getExtractedColumns()))
                .extractionWarnings(toJsonString(r.getExtractionWarnings()))
                .retryCount(r.getRetryCount())
                .logDetails(toJsonString(r.getLogDetails()))
                .createdAtMs(r.getCreatedAtMs())
                .build();
    }

    private static String toJsonString(JSONB jsonb) {
        return jsonb != null ? jsonb.data() : null;
    }
}
