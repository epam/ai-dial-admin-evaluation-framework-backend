package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.configuration.properties.analytics.AnalyticsResultsProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import com.epam.aidial.evaluation.service.domain.DatasetSchemaProvider;
import com.epam.aidial.evaluation.service.domain.SchemaValidationService;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses an uploaded CSV file into a {@code List<TestCaseRunResult>} for the eval-results import
 * endpoint ({@code POST /api/v1/test-suites/{id}/runs/import}). The CSV shape is a flat 1:1 mirror
 * of the {@code test_case_run_results} DB columns (camelCase): every column is a reserved field,
 * there are no individual data-field columns.
 *
 * <p>Reserved columns ({@link #RESERVED_COLUMNS}): {@code testCaseId}, {@code testCaseName},
 * {@code runIndex}, {@code testCaseData}, {@code requestBody}, {@code responseBody},
 * {@code responseStatusCode}, {@code executionStatus}, {@code startedAt}, {@code completedAt},
 * {@code traceId}, {@code retryCount}, {@code logDetails}, {@code extractedColumns},
 * {@code extractionWarnings}.
 *
 * <p>{@code testCaseData} is a required JSON object column. When the suite's bound dataset has a
 * schema configured, each row's {@code testCaseData} is validated against that schema inline.
 *
 * <p>Validation is all-or-nothing: per-row JSON-parse errors, type errors, and field-level
 * constraint violations are collected across every row and surfaced together in a single
 * {@link ValidationException}.
 *
 * <p>Run-context fields ({@code id}, {@code testSuiteRunId}, {@code testSuiteId},
 * {@code createdAtMs}) are left as {@code null}/{@code 0} on the returned stubs — they are
 * filled in by {@link EvalResultsImportService#persistResults} once the run has been created.
 */
@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class EvalResultsCsvParser {

    /** Exact, case-sensitive reserved column names — mirrors DB columns in camelCase. */
    static final Set<String> RESERVED_COLUMNS = Set.of(
            "testCaseId",
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
            "extractionWarnings");

    private final DatasetSchemaProvider datasetSchemaProvider;
    private final ObjectMapper objectMapper;
    private final AnalyticsResultsProperties analyticsResultsProperties;
    private final SchemaValidationService schemaValidationService;

    /**
     * Parses the uploaded CSV stream into a validated list of {@link TestCaseRunResult} stubs.
     *
     * @param datasetId     the bound dataset's id — used for {@code testCaseData} schema validation
     * @param in            the CSV upload stream
     * @param contentLength the uploaded file size in bytes
     * @param delimiter     the parsed delimiter character
     * @return validated stubs ready for {@link EvalResultsImportService#validateBatch} and
     *         {@link EvalResultsImportService#persistResults}
     * @throws ValidationException if the file is too large, the CSV is empty/header-only, item
     *                             count exceeds the cap, any row has a missing/malformed field,
     *                             or any item's {@code testCaseData} violates the dataset schema
     */
    public List<TestCaseRunResult> parse(UUID datasetId, InputStream in, long contentLength, char delimiter) {
        validateFileSize(contentLength);

        List<FieldDefinitionDto> schema = datasetSchemaProvider.getSchema(datasetId);
        Map<String, Object> schemaMap = schema.isEmpty() ? null : SchemaValidationService.buildFieldSchema(schema);

        CSVFormat format = CSVFormat.DEFAULT
                .builder()
                .setDelimiter(delimiter)
                .setQuote('"')
                .setTrim(true)
                .setIgnoreEmptyLines(false)
                .get();

        try (CSVParser parser = CSVParser.builder()
                .setFormat(format)
                .setReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                .get()) {
            List<String> headers = parseHeader(parser);
            if (headers.isEmpty()) {
                throw new ValidationException("CSV has no header row");
            }

            List<ColumnBinding> bindings = resolveColumnBindings(headers);
            int maxItems = analyticsResultsProperties.getBatch().getMaxItems();

            List<TestCaseRunResult> items = new ArrayList<>();
            List<String> rowErrors = new ArrayList<>();
            int rowIndex = 0;

            for (CSVRecord record : parser) {
                if (items.size() >= maxItems) {
                    throw new ValidationException("Item count exceeds the configured maximum of " + maxItems);
                }
                parseRecord(record, bindings, schemaMap, rowIndex, items, rowErrors);
                rowIndex++;
            }

            if (rowIndex == 0) {
                throw new ValidationException("Empty CSV (header only, no data rows)");
            }

            if (!rowErrors.isEmpty()) {
                throw new ValidationException("CSV import validation failed:\n" + String.join("\n", rowErrors));
            }

            return items;
        } catch (UncheckedIOException e) {
            final Throwable cause = e.getCause();
            final String msg = cause != null ? cause.getMessage() : e.getMessage();
            log.warn("CSV parse error: {}", msg, e);
            throw new ValidationException("Malformed CSV: " + msg);
        } catch (IOException e) {
            log.warn("CSV parse error: {}", e.getMessage(), e);
            throw new ValidationException("Malformed CSV: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Row parsing
    // -------------------------------------------------------------------------

    private void parseRecord(
            CSVRecord record,
            List<ColumnBinding> bindings,
            Map<String, Object> schemaMap,
            int rowIndex,
            List<TestCaseRunResult> items,
            List<String> rowErrors) {
        String testCaseIdRaw = null;
        String testCaseNameRaw = null;
        String runIndexRaw = null;
        String testCaseDataRaw = null;
        String requestBodyRaw = null;
        String responseBodyRaw = null;
        String responseStatusCodeRaw = null;
        String executionStatusRaw = null;
        String startedAtRaw = null;
        String completedAtRaw = null;
        String traceIdRaw = null;
        String retryCountRaw = null;
        String logDetailsRaw = null;
        String extractedColumnsRaw = null;
        String extractionWarningsRaw = null;

        for (int i = 0; i < bindings.size() && i < record.size(); i++) {
            final String raw = record.get(i);
            switch (bindings.get(i).mappedTo()) {
                case "testCaseId" -> testCaseIdRaw = raw;
                case "testCaseName" -> testCaseNameRaw = raw;
                case "runIndex" -> runIndexRaw = raw;
                case "testCaseData" -> testCaseDataRaw = raw;
                case "requestBody" -> requestBodyRaw = raw;
                case "responseBody" -> responseBodyRaw = raw;
                case "responseStatusCode" -> responseStatusCodeRaw = raw;
                case "executionStatus" -> executionStatusRaw = raw;
                case "startedAt" -> startedAtRaw = raw;
                case "completedAt" -> completedAtRaw = raw;
                case "traceId" -> traceIdRaw = raw;
                case "retryCount" -> retryCountRaw = raw;
                case "logDetails" -> logDetailsRaw = raw;
                case "extractedColumns" -> extractedColumnsRaw = raw;
                case "extractionWarnings" -> extractionWarningsRaw = raw;
                default -> {}
            }
        }

        // Parse reserved fields — collect errors but continue building the item
        UUID testCaseId = parseOptionalUuid(testCaseIdRaw, rowIndex, "testCaseId", rowErrors);
        Integer runIndex = parseOptionalInt(runIndexRaw, rowIndex, "runIndex", rowErrors);
        String testCaseDataJson = parseRequiredJsonObject(testCaseDataRaw, rowIndex, "testCaseData", rowErrors);
        String requestBodyJson = parseOptionalJsonToString(requestBodyRaw, rowIndex, "requestBody", rowErrors);
        String responseBodyJson = parseOptionalJsonToString(responseBodyRaw, rowIndex, "responseBody", rowErrors);
        Integer responseStatusCode = parseOptionalInt(responseStatusCodeRaw, rowIndex, "responseStatusCode", rowErrors);
        ExecutionStatus executionStatus =
                parseExecutionStatus(executionStatusRaw, rowIndex, "executionStatus", rowErrors);
        Long startedAt = parseOptionalLong(startedAtRaw, rowIndex, "startedAt", rowErrors);
        Long completedAt = parseOptionalLong(completedAtRaw, rowIndex, "completedAt", rowErrors);
        Integer retryCount = parseOptionalInt(retryCountRaw, rowIndex, "retryCount", rowErrors);
        String logDetailsJson = parseOptionalJsonToString(logDetailsRaw, rowIndex, "logDetails", rowErrors);
        String extractedColumnsJson =
                parseOptionalJsonToString(extractedColumnsRaw, rowIndex, "extractedColumns", rowErrors);
        String extractionWarningsJson =
                parseOptionalJsonToString(extractionWarningsRaw, rowIndex, "extractionWarnings", rowErrors);

        // Inline field-level constraint checks
        if (runIndex == null) {
            rowErrors.add("row " + rowIndex + ": runIndex must not be null");
        } else if (runIndex < 0) {
            rowErrors.add("row " + rowIndex + ": runIndex must be >= 0, got: " + runIndex);
        } else if (runIndex > 99999) {
            rowErrors.add("row " + rowIndex + ": runIndex must be <= 99999, got: " + runIndex);
        }
        final String testCaseName = blankToNull(testCaseNameRaw);
        if (testCaseName != null && testCaseName.length() > 255) {
            rowErrors.add("row " + rowIndex + ": testCaseName must be <= 255 characters");
        }
        if (executionStatus == null && (executionStatusRaw == null || executionStatusRaw.isBlank())) {
            rowErrors.add("row " + rowIndex + ": executionStatus must not be null");
        }
        if (startedAt == null && (startedAtRaw == null || startedAtRaw.isBlank())) {
            rowErrors.add("row " + rowIndex + ": startedAt must not be null");
        }
        if (completedAt == null && (completedAtRaw == null || completedAtRaw.isBlank())) {
            rowErrors.add("row " + rowIndex + ": completedAt must not be null");
        }

        // Dataset schema validation on testCaseData
        if (schemaMap != null && testCaseDataJson != null) {
            validateTestCaseDataInline(testCaseDataJson, schemaMap, rowIndex, rowErrors);
        }

        final long execDurationMs = (startedAt != null && completedAt != null) ? completedAt - startedAt : 0L;

        items.add(TestCaseRunResult.builder()
                // run-context fields left null/0 — filled in by persistResults
                .testCaseId(testCaseId != null ? testCaseId : UUID.randomUUID())
                .testCaseName(testCaseName)
                .runIndex(runIndex != null ? runIndex : 0)
                .testCaseData(testCaseDataJson != null ? testCaseDataJson : "{}")
                .requestBody(requestBodyJson)
                .responseBody(responseBodyJson)
                .responseStatusCode(responseStatusCode)
                .executionStatus(executionStatus)
                .execStartedAtMs(startedAt)
                .execCompletedAtMs(completedAt)
                .execDurationMs(execDurationMs)
                .traceId(blankToNull(traceIdRaw))
                .retryCount(retryCount != null ? retryCount : 0)
                .logDetails(logDetailsJson)
                .extractedColumns(extractedColumnsJson != null ? extractedColumnsJson : "{}")
                .extractionWarnings(extractionWarningsJson != null ? extractionWarningsJson : "[]")
                .build());
    }

    // -------------------------------------------------------------------------
    // Header / binding helpers
    // -------------------------------------------------------------------------

    private List<String> parseHeader(CSVParser parser) throws IOException {
        final var it = parser.iterator();
        if (!it.hasNext()) {
            return List.of();
        }
        final CSVRecord first = it.next();
        final List<String> headers = new ArrayList<>(first.size());
        for (int i = 0; i < first.size(); i++) {
            headers.add(first.get(i).trim());
        }
        return headers;
    }

    private List<ColumnBinding> resolveColumnBindings(List<String> headers) {
        final List<ColumnBinding> bindings = new ArrayList<>(headers.size());
        for (final String header : headers) {
            bindings.add(new ColumnBinding(header, RESERVED_COLUMNS.contains(header) ? header : "unknown"));
        }
        return bindings;
    }

    // -------------------------------------------------------------------------
    // Schema validation (inline, adds to rowErrors)
    // -------------------------------------------------------------------------

    private void validateTestCaseDataInline(
            String testCaseDataJson, Map<String, Object> schemaMap, int rowIndex, List<String> rowErrors) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> dataMap = objectMapper.readValue(testCaseDataJson, Map.class);
            final var result = schemaValidationService.validate(dataMap, schemaMap);
            if (!result.isValid()) {
                String warnings = result.getWarnings().stream()
                        .map(w -> w.getPath() + ": " + w.getMessage())
                        .limit(5)
                        .reduce((a, b) -> a + "; " + b)
                        .orElse("unknown validation error");
                rowErrors.add("row " + rowIndex + ": testCaseData validation failed: " + warnings);
            }
        } catch (JacksonException e) {
            rowErrors.add(
                    "row " + rowIndex + ": failed to parse testCaseData for schema validation: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Field parsers
    // -------------------------------------------------------------------------

    private void validateFileSize(long contentLength) {
        final long maxBytes =
                analyticsResultsProperties.getCsvImport().getMaxFileSize().toBytes();
        if (contentLength > maxBytes) {
            throw new ValidationException("File size " + contentLength + " bytes exceeds limit "
                    + analyticsResultsProperties.getCsvImport().getMaxFileSize());
        }
    }

    private UUID parseOptionalUuid(String raw, int rowIndex, String field, List<String> errors) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            errors.add("row " + rowIndex + ": " + field + " is not a valid UUID: '" + raw.trim() + "'");
            return null;
        }
    }

    private Integer parseOptionalInt(String raw, int rowIndex, String field, List<String> errors) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            errors.add("row " + rowIndex + ": " + field + " must be an integer, got: '" + raw.trim() + "'");
            return null;
        }
    }

    private Long parseOptionalLong(String raw, int rowIndex, String field, List<String> errors) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            errors.add("row " + rowIndex + ": " + field + " must be a long integer, got: '" + raw.trim() + "'");
            return null;
        }
    }

    /**
     * Parses a required JSON object cell. Returns {@code null} and adds a row error when blank or
     * not a valid JSON object. Unlike the optional JSON helpers, this method does not accept
     * non-object JSON (arrays, primitives).
     */
    private String parseRequiredJsonObject(String raw, int rowIndex, String field, List<String> errors) {
        if (raw == null || raw.isBlank()) {
            errors.add("row " + rowIndex + ": " + field + " must not be null");
            return null;
        }
        try {
            JsonNode node = objectMapper.readValue(raw.trim(), JsonNode.class);
            if (!node.isObject()) {
                errors.add("row " + rowIndex + ": " + field + " must be a JSON object, got: "
                        + node.getNodeType().name().toLowerCase());
                return null;
            }
            return objectMapper.writeValueAsString(node);
        } catch (JacksonException e) {
            errors.add("row " + rowIndex + ": " + field + " is not valid JSON: " + e.getMessage());
            return null;
        }
    }

    /**
     * Parses a JSON cell to its serialized String form. Returns {@code null} when blank.
     * Adds a row error when the cell is non-blank but not valid JSON.
     */
    private String parseOptionalJsonToString(String raw, int rowIndex, String field, List<String> errors) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readValue(raw.trim(), JsonNode.class);
            return objectMapper.writeValueAsString(node);
        } catch (JacksonException e) {
            errors.add("row " + rowIndex + ": " + field + " is not valid JSON: " + e.getMessage());
            return null;
        }
    }

    private ExecutionStatus parseExecutionStatus(String raw, int rowIndex, String field, List<String> errors) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ExecutionStatus.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            errors.add("row " + rowIndex + ": " + field + " must be one of " + Set.of(ExecutionStatus.values())
                    + ", got: '" + raw.trim() + "'");
            return null;
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    // -------------------------------------------------------------------------
    // Records
    // -------------------------------------------------------------------------

    private record ColumnBinding(String headerName, String mappedTo) {}
}
