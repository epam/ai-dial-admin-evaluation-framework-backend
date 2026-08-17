package com.epam.aidial.evaluation.data.db.analytics.mapper;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_EVAL_SUMMARIES;
import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_RUN_RESULTS;

import com.epam.aidial.evaluation.data.db.analytics.model.EvalSummary;
import com.epam.aidial.evaluation.data.db.jooq.analytics.tables.records.TestCaseEvalSummariesRecord;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import java.util.UUID;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.stereotype.Component;

@Component
@LogExecution
public class EvalSummaryRecordMapper {

    /**
     * Maps a typed {@link TestCaseEvalSummariesRecord} — used when all own columns are present
     * (no JOIN for request_body/response_body).
     */
    public EvalSummary map(TestCaseEvalSummariesRecord r) {
        return EvalSummary.builder()
                .id(UUID.fromString(r.getId()))
                .testSuiteId(UUID.fromString(r.getTestSuiteId()))
                .testSuiteRunId(UUID.fromString(r.getTestSuiteRunId()))
                .testCaseRunResultId(UUID.fromString(r.getTestCaseRunResultId()))
                .testCaseId(UUID.fromString(r.getTestCaseId()))
                .testCaseName(r.getTestCaseName())
                .runIndex(r.getRunIndex())
                .turnIndex(r.getTurnIndex())
                .totalTurns(r.getTotalTurns())
                .computationId(UUID.fromString(r.getComputationId()))
                .testCaseData(toJsonString(r.getTestCaseData()))
                .extractedColumns(toJsonString(r.getExtractedColumns()))
                .executionStatus(ExecutionStatus.valueOf(r.getExecutionStatus()))
                .execDurationMs(r.getExecDurationMs())
                .metricEvalDurationMs(r.getMetricEvalDurationMs())
                .responseStatusCode(r.getResponseStatusCode())
                .metricValues(toJsonString(r.getMetricValues()))
                .metricInfos(toJsonString(r.getMetricInfos()))
                .extractionWarnings(toJsonString(r.getExtractionWarnings()))
                .createdAtMs(r.getCreatedAtMs())
                .computedAtMs(r.getComputedAtMs())
                .build();
    }

    /**
     * Maps a generic {@link Record} from a LIST query (excludes metric_infos, extraction_warnings,
     * request_body, response_body for TOAST optimization).
     */
    public EvalSummary mapList(Record r) {
        return EvalSummary.builder()
                .id(UUID.fromString(r.getValue(TEST_CASE_EVAL_SUMMARIES.ID)))
                .testSuiteId(UUID.fromString(r.getValue(TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_ID)))
                .testSuiteRunId(UUID.fromString(r.getValue(TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_RUN_ID)))
                .testCaseRunResultId(UUID.fromString(r.getValue(TEST_CASE_EVAL_SUMMARIES.TEST_CASE_RUN_RESULT_ID)))
                .testCaseId(UUID.fromString(r.getValue(TEST_CASE_EVAL_SUMMARIES.TEST_CASE_ID)))
                .testCaseName(r.getValue(TEST_CASE_EVAL_SUMMARIES.TEST_CASE_NAME))
                .runIndex(r.getValue(TEST_CASE_EVAL_SUMMARIES.RUN_INDEX))
                .turnIndex(r.getValue(TEST_CASE_EVAL_SUMMARIES.TURN_INDEX))
                .totalTurns(r.getValue(TEST_CASE_EVAL_SUMMARIES.TOTAL_TURNS))
                .computationId(UUID.fromString(r.getValue(TEST_CASE_EVAL_SUMMARIES.COMPUTATION_ID)))
                .testCaseData(toJsonString(r.getValue(TEST_CASE_EVAL_SUMMARIES.TEST_CASE_DATA)))
                .extractedColumns(toJsonString(r.getValue(TEST_CASE_EVAL_SUMMARIES.EXTRACTED_COLUMNS)))
                .executionStatus(ExecutionStatus.valueOf(r.getValue(TEST_CASE_EVAL_SUMMARIES.EXECUTION_STATUS)))
                .execDurationMs(r.getValue(TEST_CASE_EVAL_SUMMARIES.EXEC_DURATION_MS))
                .metricEvalDurationMs(r.getValue(TEST_CASE_EVAL_SUMMARIES.METRIC_EVAL_DURATION_MS))
                .responseStatusCode(r.getValue(TEST_CASE_EVAL_SUMMARIES.RESPONSE_STATUS_CODE))
                .metricValues(toJsonString(r.getValue(TEST_CASE_EVAL_SUMMARIES.METRIC_VALUES)))
                .createdAtMs(r.getValue(TEST_CASE_EVAL_SUMMARIES.CREATED_AT_MS))
                .computedAtMs(r.getValue(TEST_CASE_EVAL_SUMMARIES.COMPUTED_AT_MS))
                .build();
    }

    /**
     * Maps a generic {@link Record} from an EXPORT query (includes metric_infos and
     * extraction_warnings, but no request_body/response_body).
     */
    public EvalSummary mapExport(Record r) {
        return EvalSummary.builder()
                .id(UUID.fromString(r.getValue(TEST_CASE_EVAL_SUMMARIES.ID)))
                .testSuiteId(UUID.fromString(r.getValue(TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_ID)))
                .testSuiteRunId(UUID.fromString(r.getValue(TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_RUN_ID)))
                .testCaseRunResultId(UUID.fromString(r.getValue(TEST_CASE_EVAL_SUMMARIES.TEST_CASE_RUN_RESULT_ID)))
                .testCaseId(UUID.fromString(r.getValue(TEST_CASE_EVAL_SUMMARIES.TEST_CASE_ID)))
                .testCaseName(r.getValue(TEST_CASE_EVAL_SUMMARIES.TEST_CASE_NAME))
                .runIndex(r.getValue(TEST_CASE_EVAL_SUMMARIES.RUN_INDEX))
                .turnIndex(r.getValue(TEST_CASE_EVAL_SUMMARIES.TURN_INDEX))
                .totalTurns(r.getValue(TEST_CASE_EVAL_SUMMARIES.TOTAL_TURNS))
                .computationId(UUID.fromString(r.getValue(TEST_CASE_EVAL_SUMMARIES.COMPUTATION_ID)))
                .testCaseData(toJsonString(r.getValue(TEST_CASE_EVAL_SUMMARIES.TEST_CASE_DATA)))
                .extractedColumns(toJsonString(r.getValue(TEST_CASE_EVAL_SUMMARIES.EXTRACTED_COLUMNS)))
                .executionStatus(ExecutionStatus.valueOf(r.getValue(TEST_CASE_EVAL_SUMMARIES.EXECUTION_STATUS)))
                .execDurationMs(r.getValue(TEST_CASE_EVAL_SUMMARIES.EXEC_DURATION_MS))
                .metricEvalDurationMs(r.getValue(TEST_CASE_EVAL_SUMMARIES.METRIC_EVAL_DURATION_MS))
                .responseStatusCode(r.getValue(TEST_CASE_EVAL_SUMMARIES.RESPONSE_STATUS_CODE))
                .metricValues(toJsonString(r.getValue(TEST_CASE_EVAL_SUMMARIES.METRIC_VALUES)))
                .metricInfos(toJsonString(r.getValue(TEST_CASE_EVAL_SUMMARIES.METRIC_INFOS)))
                .extractionWarnings(toJsonString(r.getValue(TEST_CASE_EVAL_SUMMARIES.EXTRACTION_WARNINGS)))
                .createdAtMs(r.getValue(TEST_CASE_EVAL_SUMMARIES.CREATED_AT_MS))
                .computedAtMs(r.getValue(TEST_CASE_EVAL_SUMMARIES.COMPUTED_AT_MS))
                .build();
    }

    /**
     * Maps a generic {@link Record} from an EXPORT WITH BODIES query (includes
     * metric_infos, extraction_warnings, request_body, response_body via LEFT JOIN).
     */
    public EvalSummary mapExportWithBodies(Record r) {
        return EvalSummary.builder()
                .id(UUID.fromString(r.getValue(TEST_CASE_EVAL_SUMMARIES.ID)))
                .testSuiteId(UUID.fromString(r.getValue(TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_ID)))
                .testSuiteRunId(UUID.fromString(r.getValue(TEST_CASE_EVAL_SUMMARIES.TEST_SUITE_RUN_ID)))
                .testCaseRunResultId(UUID.fromString(r.getValue(TEST_CASE_EVAL_SUMMARIES.TEST_CASE_RUN_RESULT_ID)))
                .testCaseId(UUID.fromString(r.getValue(TEST_CASE_EVAL_SUMMARIES.TEST_CASE_ID)))
                .testCaseName(r.getValue(TEST_CASE_EVAL_SUMMARIES.TEST_CASE_NAME))
                .runIndex(r.getValue(TEST_CASE_EVAL_SUMMARIES.RUN_INDEX))
                .turnIndex(r.getValue(TEST_CASE_EVAL_SUMMARIES.TURN_INDEX))
                .totalTurns(r.getValue(TEST_CASE_EVAL_SUMMARIES.TOTAL_TURNS))
                .computationId(UUID.fromString(r.getValue(TEST_CASE_EVAL_SUMMARIES.COMPUTATION_ID)))
                .testCaseData(toJsonString(r.getValue(TEST_CASE_EVAL_SUMMARIES.TEST_CASE_DATA)))
                .extractedColumns(toJsonString(r.getValue(TEST_CASE_EVAL_SUMMARIES.EXTRACTED_COLUMNS)))
                .executionStatus(ExecutionStatus.valueOf(r.getValue(TEST_CASE_EVAL_SUMMARIES.EXECUTION_STATUS)))
                .execDurationMs(r.getValue(TEST_CASE_EVAL_SUMMARIES.EXEC_DURATION_MS))
                .metricEvalDurationMs(r.getValue(TEST_CASE_EVAL_SUMMARIES.METRIC_EVAL_DURATION_MS))
                .responseStatusCode(r.getValue(TEST_CASE_EVAL_SUMMARIES.RESPONSE_STATUS_CODE))
                .metricValues(toJsonString(r.getValue(TEST_CASE_EVAL_SUMMARIES.METRIC_VALUES)))
                .metricInfos(toJsonString(r.getValue(TEST_CASE_EVAL_SUMMARIES.METRIC_INFOS)))
                .extractionWarnings(toJsonString(r.getValue(TEST_CASE_EVAL_SUMMARIES.EXTRACTION_WARNINGS)))
                .requestBody(toJsonString(r.getValue(TEST_CASE_RUN_RESULTS.REQUEST_BODY)))
                .responseBody(toJsonString(r.getValue(TEST_CASE_RUN_RESULTS.RESPONSE_BODY)))
                .createdAtMs(r.getValue(TEST_CASE_EVAL_SUMMARIES.CREATED_AT_MS))
                .computedAtMs(r.getValue(TEST_CASE_EVAL_SUMMARIES.COMPUTED_AT_MS))
                .build();
    }

    private static String toJsonString(JSONB jsonb) {
        return jsonb != null ? jsonb.data() : null;
    }
}
