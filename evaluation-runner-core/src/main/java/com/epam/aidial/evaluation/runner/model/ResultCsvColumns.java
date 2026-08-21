package com.epam.aidial.evaluation.runner.model;

import java.util.List;

/**
 * The eval-results CSV import contract shared by the backend parser
 * ({@code EvalResultsCsvParser.RESERVED_COLUMNS}) and the CLI writer ({@code CsvResultBatchWriter}).
 * Order matters to the writer, which emits values positionally against its header row.
 */
public final class ResultCsvColumns {

    public static final String TEST_CASE_ID = "testCaseId";
    public static final String TEST_CASE_NAME = "testCaseName";
    public static final String RUN_INDEX = "runIndex";
    public static final String TEST_CASE_DATA = "testCaseData";
    public static final String REQUEST_BODY = "requestBody";
    public static final String RESPONSE_BODY = "responseBody";
    public static final String RESPONSE_STATUS_CODE = "responseStatusCode";
    public static final String EXECUTION_STATUS = "executionStatus";
    public static final String STARTED_AT = "startedAt";
    public static final String COMPLETED_AT = "completedAt";
    public static final String TRACE_ID = "traceId";
    public static final String RETRY_COUNT = "retryCount";
    public static final String LOG_DETAILS = "logDetails";
    public static final String EXTRACTED_COLUMNS = "extractedColumns";
    public static final String EXTRACTION_WARNINGS = "extractionWarnings";
    public static final String REQUEST_INDEX = "requestIndex";
    public static final String TOTAL_REQUESTS = "totalRequests";
    public static final String TURN_INDEX = "turnIndex";
    public static final String TOTAL_TURNS = "totalTurns";

    public static final List<String> CANONICAL_ORDER = List.of(
            TEST_CASE_ID,
            TEST_CASE_NAME,
            RUN_INDEX,
            TEST_CASE_DATA,
            REQUEST_BODY,
            RESPONSE_BODY,
            RESPONSE_STATUS_CODE,
            EXECUTION_STATUS,
            STARTED_AT,
            COMPLETED_AT,
            TRACE_ID,
            RETRY_COUNT,
            LOG_DETAILS,
            EXTRACTED_COLUMNS,
            EXTRACTION_WARNINGS,
            REQUEST_INDEX,
            TOTAL_REQUESTS,
            TURN_INDEX,
            TOTAL_TURNS);

    private ResultCsvColumns() {}
}
