package com.epam.aidial.evaluation.cli.csv;

import com.epam.aidial.evaluation.runner.job.ResultBatchWriter;
import com.epam.aidial.evaluation.runner.model.ResultCsvColumns;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

/**
 * {@link ResultBatchWriter} implementation that writes {@link TestCaseRunResult} rows to a CSV file in
 * a column order the EF backend's {@code POST /api/v1/test-suites/{id}/runs/import} endpoint accepts
 * ({@code EvalResultsCsvParser} resolves columns by header name, not position).
 *
 * <p>Column order: {@code testCaseName, runIndex, testCaseData, requestBody, responseBody,
 * responseStatusCode, executionStatus, startedAt, completedAt, traceId, retryCount, logDetails,
 * extractedColumns, extractionWarnings, requestIndex, totalRequests, turnIndex, totalTurns}.
 *
 * <p>{@code testCaseId} is deliberately omitted. The test case IDs eval-cli reads come from the
 * <em>source</em> suite's bound dataset; the destination clone's dataset is not guaranteed to be the
 * same one (a clone of a suite bound to a PRIVATE dataset gets a freshly cloned dataset with new test
 * case IDs) — those source-side IDs would not correspond to anything in the destination. This is safe
 * because the import contract now derives one stable identifier per distinct {@code testCaseName} in
 * the file ({@code EvalResultsCsvParser}, {@code cli-multi-turn-multi-request-parity} design.md
 * Decision 4) — every row naming the same test case shares that generated id, so omitting {@code
 * testCaseId} no longer fragments a repetition's rows across several identities; the import contract
 * only requires <em>one</em> of {@code testCaseId}/{@code testCaseName}, never both.
 *
 * <p>{@code testCaseData} is a <strong>required</strong> JSON-object column on the import contract
 * (validated against the destination dataset's schema, if any) — a {@code null} value here is written
 * as {@code "{}"} rather than left blank, since a blank cell fails import validation.
 *
 * <p>{@code extractedColumns}/{@code extractionWarnings} ARE included — an earlier draft of this
 * writer omitted them on the assumption extraction runs server-side after import, but it does not:
 * extraction ({@code ResponseColumnExtractor.extract(responseColumns, responseBody)}) happens
 * exclusively inside {@code EvaluationWorker.buildResult} during Phase 1 execution. Phase 2
 * (server-side, post-import) only ever reads the already-persisted {@code extractedColumns} value
 * verbatim ({@code InProcessMetricEvaluationExecutor}/{@code MetricEvaluationWorker}) — it never
 * re-extracts. Since eval-cli's own {@code RunOrchestrationService} already runs Phase 1 locally (via
 * the shared {@code TestCaseRunner}/{@code EvaluationWorker}), every {@link TestCaseRunResult} handed
 * to this writer already carries real extraction output; omitting it from the CSV would silently
 * throw away extraction for every imported run, breaking any metric whose binding reads an extracted
 * column.
 *
 * <p>{@code requestIndex}/{@code totalRequests}/{@code turnIndex}/{@code totalTurns} ARE included —
 * they are now part of {@code EvalResultsCsvParser.RESERVED_COLUMNS} as optional, defaulted columns
 * (design.md Decision 1). Without them every row of a multi-request or multi-turn suite's test-case
 * repetition would import as an indistinguishable single-request, single-turn row. {@link
 * TestCaseRunResult} already carries these four fields with the correct single-request/single-turn
 * defaults ({@code 0}/{@code 1}/{@code 0}/{@code 1}), so this writer emits them verbatim.
 *
 * <p>{@link #addResults(List)} is {@code synchronized} to allow concurrent calls from multiple
 * virtual threads without interleaved/corrupted rows.
 */
@Slf4j
public class CsvResultBatchWriter implements ResultBatchWriter, Closeable {

    private static final String EMPTY_JSON_OBJECT = "{}";
    private static final String EMPTY_JSON_ARRAY = "[]";

    /**
     * Exact import-contract header in the required column order — the shared {@link
     * ResultCsvColumns#CANONICAL_ORDER} with {@code testCaseId} dropped (see class javadoc).
     */
    static final String[] HEADERS = ResultCsvColumns.CANONICAL_ORDER.stream()
            .filter(column -> !ResultCsvColumns.TEST_CASE_ID.equals(column))
            .toArray(String[]::new);

    private final CSVPrinter printer;

    public CsvResultBatchWriter(Writer writer) throws IOException {
        this.printer = CSVFormat.DEFAULT.builder().setHeader(HEADERS).build().print(writer);
    }

    /**
     * Appends result rows to the CSV. Synchronized to prevent interleaved writes from concurrent
     * virtual threads (see {@link com.epam.aidial.evaluation.runner.job.TestCaseRunner}).
     *
     * @param results the batch of results to write
     * @throws RuntimeException if an {@link IOException} occurs during writing (fail-fast per design)
     */
    @Override
    public synchronized void addResults(List<TestCaseRunResult> results) {
        for (TestCaseRunResult result : results) {
            try {
                printer.printRecord(
                        result.getTestCaseName(),
                        result.getRunIndex(),
                        result.getTestCaseData() != null ? result.getTestCaseData() : EMPTY_JSON_OBJECT,
                        result.getRequestBody(),
                        result.getResponseBody(),
                        result.getResponseStatusCode(),
                        result.getExecutionStatus() != null
                                ? result.getExecutionStatus().name()
                                : null,
                        result.getExecStartedAtMs(),
                        result.getExecCompletedAtMs(),
                        result.getTraceId(),
                        result.getRetryCount(),
                        result.getLogDetails(),
                        result.getExtractedColumns() != null ? result.getExtractedColumns() : EMPTY_JSON_OBJECT,
                        result.getExtractionWarnings() != null ? result.getExtractionWarnings() : EMPTY_JSON_ARRAY,
                        result.getRequestIndex(),
                        result.getTotalRequests(),
                        result.getTurnIndex(),
                        result.getTotalTurns());
            } catch (IOException e) {
                throw new RuntimeException(
                        "Failed to write CSV result row for test case " + result.getTestCaseId() + ": "
                                + e.getMessage(),
                        e);
            }
        }
    }

    /**
     * Flushes the underlying {@link CSVPrinter}. Called after all results have been written to
     * ensure the file is complete before import.
     *
     * @throws RuntimeException if an {@link IOException} occurs during flush (fail-fast per design)
     */
    @Override
    public synchronized void flush() {
        try {
            printer.flush();
        } catch (IOException e) {
            throw new RuntimeException("Failed to flush CSV result writer: " + e.getMessage(), e);
        }
    }

    /** Closes the underlying {@link CSVPrinter} and its writer. */
    @Override
    public void close() throws IOException {
        printer.close(true);
    }
}
