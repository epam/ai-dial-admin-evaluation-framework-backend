package com.epam.aidial.evaluation.cli.csv;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CsvResultBatchWriterTest {

    // ────────────────────────────────────────────────────────────────────────────────
    // 5.2 — header/column order and per-field value formatting
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("header matches import contract exactly")
    void headerMatchesImportContract() throws Exception {
        final StringWriter out = new StringWriter();
        try (CsvResultBatchWriter writer = new CsvResultBatchWriter(out)) {
            writer.flush();
        }
        // Parse with auto-header detection and header-record skipping (header is extracted
        // into CSVParser.getHeaderNames(), not returned as a data record)
        try (org.apache.commons.csv.CSVParser parser = CSVFormat.DEFAULT
                .builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(new java.io.StringReader(out.toString()))) {
            assertThat(parser.getHeaderNames())
                    .containsExactly(
                            "testCaseName",
                            "runIndex",
                            "testCaseData",
                            "requestBody",
                            "responseBody",
                            "responseStatusCode",
                            "executionStatus",
                            "startedAt",
                            "completedAt",
                            "traceId",
                            "retryCount",
                            "logDetails",
                            "extractedColumns",
                            "extractionWarnings",
                            "requestIndex",
                            "totalRequests",
                            "turnIndex",
                            "totalTurns");
        }
    }

    @Test
    @DisplayName("header is the exact 18-column string in order")
    void headerIsExactEighteenColumnString() throws Exception {
        final StringWriter out = new StringWriter();
        try (CsvResultBatchWriter writer = new CsvResultBatchWriter(out)) {
            writer.flush();
        }
        final String headerLine = out.toString().split("\r\n|\n", 2)[0];
        assertThat(headerLine)
                .isEqualTo("testCaseName,runIndex,testCaseData,requestBody,responseBody,responseStatusCode,"
                        + "executionStatus,startedAt,completedAt,traceId,retryCount,logDetails,"
                        + "extractedColumns,extractionWarnings,requestIndex,totalRequests,turnIndex,"
                        + "totalTurns");
        assertThat(CsvResultBatchWriter.HEADERS).hasSize(18);
    }

    @Test
    @DisplayName("a single-request single-turn result writes identity columns as 0,1,0,1")
    void singleRequestSingleTurnResultWritesDefaultIdentity() throws Exception {
        final StringWriter out = new StringWriter();
        final TestCaseRunResult result = TestCaseRunResult.builder()
                .testCaseId(UUID.randomUUID())
                .testCaseName("TC1")
                .runIndex(0)
                .executionStatus(ExecutionStatus.SUCCESS)
                .build();

        try (CsvResultBatchWriter writer = new CsvResultBatchWriter(out)) {
            writer.addResults(List.of(result));
            writer.flush();
        }

        final List<CSVRecord> records = parseRecords(out.toString());
        final CSVRecord row = records.get(0);
        assertThat(row.get("requestIndex")).isEqualTo("0");
        assertThat(row.get("totalRequests")).isEqualTo("1");
        assertThat(row.get("turnIndex")).isEqualTo("0");
        assertThat(row.get("totalTurns")).isEqualTo("1");
    }

    @Test
    @DisplayName("a multi-request multi-turn result writes its own position values")
    void multiRequestMultiTurnResultWritesOwnPositionValues() throws Exception {
        final StringWriter out = new StringWriter();
        final TestCaseRunResult result = TestCaseRunResult.builder()
                .testCaseId(UUID.randomUUID())
                .testCaseName("TC1")
                .runIndex(0)
                .executionStatus(ExecutionStatus.SUCCESS)
                .requestIndex(1)
                .totalRequests(3)
                .turnIndex(2)
                .totalTurns(4)
                .build();

        try (CsvResultBatchWriter writer = new CsvResultBatchWriter(out)) {
            writer.addResults(List.of(result));
            writer.flush();
        }

        final List<CSVRecord> records = parseRecords(out.toString());
        final CSVRecord row = records.get(0);
        assertThat(row.get("requestIndex")).isEqualTo("1");
        assertThat(row.get("totalRequests")).isEqualTo("3");
        assertThat(row.get("turnIndex")).isEqualTo("2");
        assertThat(row.get("totalTurns")).isEqualTo("4");
    }

    @Test
    @DisplayName("column values are formatted correctly")
    void columnValuesFormattedCorrectly() throws Exception {
        final StringWriter out = new StringWriter();
        final TestCaseRunResult result = TestCaseRunResult.builder()
                .testCaseId(UUID.randomUUID())
                .testCaseName("My Test")
                .runIndex(2)
                .testCaseData("{\"prompt\":\"hi\"}")
                .requestBody("{\"prompt\":\"hi\"}")
                .responseBody("{\"text\":\"hello\"}")
                .responseStatusCode(200)
                .executionStatus(ExecutionStatus.SUCCESS)
                .execStartedAtMs(1700000000000L)
                .execCompletedAtMs(1700000001000L)
                .traceId("trace-abc")
                .retryCount(1)
                .logDetails("no errors")
                .extractedColumns("{\"answer\":\"hello\"}")
                .extractionWarnings("[\"missing field 'score'\"]")
                .build();

        try (CsvResultBatchWriter writer = new CsvResultBatchWriter(out)) {
            writer.addResults(List.of(result));
            writer.flush();
        }

        final List<CSVRecord> records = parseRecords(out.toString());
        // header is skipped — records[0] is the first data row
        assertThat(records).hasSize(1);
        final CSVRecord row = records.get(0);
        assertThat(row.get("testCaseName")).isEqualTo("My Test");
        assertThat(row.get("runIndex")).isEqualTo("2");
        assertThat(row.get("testCaseData")).isEqualTo("{\"prompt\":\"hi\"}");
        assertThat(row.get("requestBody")).isEqualTo("{\"prompt\":\"hi\"}");
        assertThat(row.get("responseBody")).isEqualTo("{\"text\":\"hello\"}");
        assertThat(row.get("responseStatusCode")).isEqualTo("200");
        assertThat(row.get("executionStatus")).isEqualTo("SUCCESS");
        assertThat(row.get("startedAt")).isEqualTo("1700000000000");
        assertThat(row.get("completedAt")).isEqualTo("1700000001000");
        assertThat(row.get("traceId")).isEqualTo("trace-abc");
        assertThat(row.get("retryCount")).isEqualTo("1");
        assertThat(row.get("logDetails")).isEqualTo("no errors");
        assertThat(row.get("extractedColumns")).isEqualTo("{\"answer\":\"hello\"}");
        assertThat(row.get("extractionWarnings")).isEqualTo("[\"missing field 'score'\"]");
    }

    @Test
    @DisplayName("null fields are written as empty strings")
    void nullFieldsWrittenAsEmpty() throws Exception {
        final StringWriter out = new StringWriter();
        final TestCaseRunResult result = TestCaseRunResult.builder()
                .testCaseId(UUID.randomUUID())
                .testCaseName("Null test")
                .runIndex(0)
                .executionStatus(ExecutionStatus.ERROR)
                .build();

        try (CsvResultBatchWriter writer = new CsvResultBatchWriter(out)) {
            writer.addResults(List.of(result));
            writer.flush();
        }

        final List<CSVRecord> records = parseRecords(out.toString());
        final CSVRecord row = records.get(0);
        assertThat(row.get("requestBody")).isEmpty();
        assertThat(row.get("responseBody")).isEmpty();
        assertThat(row.get("responseStatusCode")).isEmpty();
        assertThat(row.get("retryCount")).isEmpty();
    }

    @Test
    @DisplayName("a null testCaseData is written as an empty JSON object, not a blank cell")
    void nullTestCaseDataWrittenAsEmptyJsonObject() throws Exception {
        final StringWriter out = new StringWriter();
        final TestCaseRunResult result = TestCaseRunResult.builder()
                .testCaseId(UUID.randomUUID())
                .testCaseName("No data")
                .runIndex(0)
                .executionStatus(ExecutionStatus.SUCCESS)
                .build();

        try (CsvResultBatchWriter writer = new CsvResultBatchWriter(out)) {
            writer.addResults(List.of(result));
            writer.flush();
        }

        final List<CSVRecord> records = parseRecords(out.toString());
        assertThat(records.get(0).get("testCaseData")).isEqualTo("{}");
    }

    @Test
    @DisplayName("testCaseId is NOT written to the CSV — identity is carried by testCaseName instead")
    void testCaseIdNotWritten() throws Exception {
        final StringWriter out = new StringWriter();
        try (CsvResultBatchWriter writer = new CsvResultBatchWriter(out)) {
            writer.flush();
        }
        assertThat(out.toString()).doesNotContain("testCaseId");
    }

    @Test
    @DisplayName("null extractedColumns/extractionWarnings are written as empty JSON object/array")
    void nullExtractionFieldsWrittenAsEmptyJson() throws Exception {
        final StringWriter out = new StringWriter();
        final TestCaseRunResult result = TestCaseRunResult.builder()
                .testCaseId(UUID.randomUUID())
                .testCaseName("No extraction")
                .runIndex(0)
                .executionStatus(ExecutionStatus.SUCCESS)
                .build();

        try (CsvResultBatchWriter writer = new CsvResultBatchWriter(out)) {
            writer.addResults(List.of(result));
            writer.flush();
        }

        final List<CSVRecord> records = parseRecords(out.toString());
        assertThat(records.get(0).get("extractedColumns")).isEqualTo("{}");
        assertThat(records.get(0).get("extractionWarnings")).isEqualTo("[]");
    }

    @Test
    @DisplayName("testCaseId is omitted even when set on the result — testCaseName carries identity instead")
    void testCaseIdOmittedEvenWhenSetOnResult() throws Exception {
        final StringWriter out = new StringWriter();
        final TestCaseRunResult result = TestCaseRunResult.builder()
                .testCaseId(UUID.randomUUID())
                .testCaseName("My Test")
                .runIndex(0)
                .executionStatus(ExecutionStatus.SUCCESS)
                .build();

        try (CsvResultBatchWriter writer = new CsvResultBatchWriter(out)) {
            writer.addResults(List.of(result));
            writer.flush();
        }

        final List<CSVRecord> records = parseRecords(out.toString());
        assertThat(records.get(0).isMapped("testCaseId")).isFalse();
        assertThat(records.get(0).get("testCaseName")).isEqualTo("My Test");
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // 5.3 — concurrency test: no interleaved/corrupted rows
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("concurrent addResults writes produce no interleaved or corrupted rows")
    void concurrentWritesProduceNoCorruptedRows() throws Exception {
        final int threadCount = 20;
        final int resultsPerThread = 50;
        final StringWriter out = new StringWriter();

        try (CsvResultBatchWriter writer = new CsvResultBatchWriter(out)) {
            final CyclicBarrier barrier = new CyclicBarrier(threadCount);
            final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

            for (int t = 0; t < threadCount; t++) {
                final int threadIdx = t;
                executor.submit(() -> {
                    try {
                        // All threads start writing simultaneously to maximize contention
                        barrier.await(10, TimeUnit.SECONDS);
                        final List<TestCaseRunResult> batch = new ArrayList<>(resultsPerThread);
                        for (int i = 0; i < resultsPerThread; i++) {
                            batch.add(TestCaseRunResult.builder()
                                    .testCaseId(UUID.randomUUID())
                                    .testCaseName("Thread-" + threadIdx + "-Case-" + i)
                                    .runIndex(i)
                                    .executionStatus(ExecutionStatus.SUCCESS)
                                    .build());
                        }
                        writer.addResults(batch);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            executor.shutdown();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            writer.flush();
        }

        // Parse and verify: expect (threadCount * resultsPerThread) data rows, all well-formed
        // (header is skipped by the parser; records[] are data rows only)
        final List<CSVRecord> records = parseRecords(out.toString());
        final int expectedDataRows = threadCount * resultsPerThread;
        assertThat(records).hasSize(expectedDataRows);

        // Every data row must have exactly HEADERS.length columns
        for (int i = 0; i < expectedDataRows; i++) {
            assertThat(records.get(i).size())
                    .as("Row %d has wrong column count", i)
                    .isEqualTo(CsvResultBatchWriter.HEADERS.length);
        }
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────────────────────────────────────

    private List<CSVRecord> parseRecords(String csv) throws Exception {
        final Iterable<CSVRecord> iterable = CSVFormat.DEFAULT
                .builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(new java.io.StringReader(csv));
        final List<CSVRecord> list = new ArrayList<>();
        for (CSVRecord r : iterable) {
            list.add(r);
        }
        return list;
    }
}
