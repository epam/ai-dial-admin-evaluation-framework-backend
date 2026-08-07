package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.configuration.properties.analytics.AnalyticsResultsProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import com.epam.aidial.evaluation.service.domain.DatasetSchemaProvider;
import com.epam.aidial.evaluation.service.domain.SchemaValidationService;
import com.epam.aidial.evaluation.service.domain.TestCaseFieldScopeResolver;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * {@code extractionWarnings}, {@code requestIndex}, {@code totalRequests}, {@code turnIndex},
 * {@code totalTurns}.
 *
 * <p>{@code testCaseData} is a required JSON object column. When the suite's bound dataset has a
 * schema configured, each row's {@code testCaseData} is validated against that schema inline,
 * scope-aware: a per-turn field (per {@link TestCaseFieldScopeResolver}) is type-checked when
 * present but never required, since a row may legitimately carry shared-only data (see
 * {@code design.md} Decision 6).
 *
 * <p>{@code requestIndex}/{@code totalRequests}/{@code turnIndex}/{@code totalTurns} are optional:
 * an absent header or blank cell defaults to {@code 0}/{@code 1}/{@code 0}/{@code 1} — the values a
 * single-request single-turn row already carries (see {@code design.md} Decision 1).
 *
 * <p>When a row supplies no {@code testCaseId}, its persisted identifier is derived from
 * {@code testCaseName}: every row naming the same test case within one {@link #parse} call shares
 * one generated id, so a multi-request/multi-turn repetition's rows group back together the same
 * way a live run's do (see {@code design.md} Decision 4).
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
            "extractionWarnings",
            "requestIndex",
            "totalRequests",
            "turnIndex",
            "totalTurns");

    private final DatasetSchemaProvider datasetSchemaProvider;
    private final ObjectMapper objectMapper;
    private final AnalyticsResultsProperties analyticsResultsProperties;
    private final SchemaValidationService schemaValidationService;
    private final TestCaseFieldScopeResolver testCaseFieldScopeResolver;

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
        Map<String, Object> schemaMap = schema.isEmpty() ? null : buildScopeAwareSchema(schema);

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
            // Parse-call-scoped: every id-less row naming the same test case shares one generated id,
            // and ids never leak between separate parse/import requests (design.md Decision 4).
            Map<String, UUID> nameToGeneratedId = new HashMap<>();
            int rowIndex = 0;

            for (CSVRecord record : parser) {
                if (items.size() >= maxItems) {
                    throw new ValidationException("Item count exceeds the configured maximum of " + maxItems);
                }
                parseRecord(record, bindings, schemaMap, rowIndex, items, rowErrors, nameToGeneratedId);
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
            List<String> rowErrors,
            Map<String, UUID> nameToGeneratedId) {
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
        String requestIndexRaw = null;
        String totalRequestsRaw = null;
        String turnIndexRaw = null;
        String totalTurnsRaw = null;

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
                case "requestIndex" -> requestIndexRaw = raw;
                case "totalRequests" -> totalRequestsRaw = raw;
                case "turnIndex" -> turnIndexRaw = raw;
                case "totalTurns" -> totalTurnsRaw = raw;
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
        Integer requestIndexParsed = parseOptionalInt(requestIndexRaw, rowIndex, "requestIndex", rowErrors);
        Integer totalRequestsParsed = parseOptionalInt(totalRequestsRaw, rowIndex, "totalRequests", rowErrors);
        Integer turnIndexParsed = parseOptionalInt(turnIndexRaw, rowIndex, "turnIndex", rowErrors);
        Integer totalTurnsParsed = parseOptionalInt(totalTurnsRaw, rowIndex, "totalTurns", rowErrors);

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

        // Effective identity values: an absent header or blank cell defaults to a single-request
        // single-turn row's own values (design.md Decision 1), then range-checked against those
        // defaulted effective values (design.md Decision 2) — no cross-row total* consistency check,
        // since totalTurns legitimately differs per requestIndex within one chain.
        final int requestIndex = requestIndexParsed != null ? requestIndexParsed : 0;
        final int totalRequests = totalRequestsParsed != null ? totalRequestsParsed : 1;
        final int turnIndex = turnIndexParsed != null ? turnIndexParsed : 0;
        final int totalTurns = totalTurnsParsed != null ? totalTurnsParsed : 1;
        validateIdentityColumns(requestIndex, totalRequests, turnIndex, totalTurns, rowIndex, rowErrors);

        // Dataset schema validation on testCaseData
        if (schemaMap != null && testCaseDataJson != null) {
            validateTestCaseDataInline(testCaseDataJson, schemaMap, rowIndex, rowErrors);
        }

        final long execDurationMs = (startedAt != null && completedAt != null) ? completedAt - startedAt : 0L;

        items.add(TestCaseRunResult.builder()
                // run-context fields left null/0 — filled in by persistResults
                .testCaseId(resolveTestCaseId(testCaseId, testCaseName, nameToGeneratedId))
                .testCaseName(testCaseName)
                .runIndex(runIndex != null ? runIndex : 0)
                .requestIndex(requestIndex)
                .totalRequests(totalRequests)
                .turnIndex(turnIndex)
                .totalTurns(totalTurns)
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

    /**
     * Range-checks the four identity columns' <b>effective</b> values (post-defaulting): a supplied
     * index is checked against a defaulted total, so e.g. {@code requestIndex=2} with a blank
     * {@code totalRequests} (defaulting to {@code 1}) is rejected. Deliberately does not check
     * cross-row consistency of {@code totalRequests}/{@code totalTurns} for one test-case identity —
     * {@code totalTurns} legitimately differs per {@code requestIndex} within one chain.
     */
    private void validateIdentityColumns(
            int requestIndex, int totalRequests, int turnIndex, int totalTurns, int rowIndex, List<String> errors) {
        if (requestIndex < 0) {
            errors.add("row " + rowIndex + ": requestIndex must be >= 0, got: " + requestIndex);
        }
        if (turnIndex < 0) {
            errors.add("row " + rowIndex + ": turnIndex must be >= 0, got: " + turnIndex);
        }
        if (totalRequests < 1) {
            errors.add("row " + rowIndex + ": totalRequests must be >= 1, got: " + totalRequests);
        }
        if (totalTurns < 1) {
            errors.add("row " + rowIndex + ": totalTurns must be >= 1, got: " + totalTurns);
        }
        if (requestIndex >= totalRequests) {
            errors.add("row " + rowIndex + ": requestIndex (" + requestIndex + ") must be less than totalRequests ("
                    + totalRequests + ")");
        }
        if (turnIndex >= totalTurns) {
            errors.add("row " + rowIndex + ": turnIndex (" + turnIndex + ") must be less than totalTurns (" + totalTurns
                    + ")");
        }
    }

    /**
     * Resolves the row's persisted {@code testCaseId}: a supplied id is used verbatim; otherwise one
     * generated id is shared by every row naming the same {@code testCaseName} within this
     * {@link #parse} call (via {@code nameToGeneratedId}), so a repetition's rows group back
     * together the same way a live run's do (design.md Decision 4). A row with neither id nor name
     * gets {@code null} — {@code EvalResultsImportService#testCaseIdentity}'s existing
     * identity-required check rejects it with a clean 400 before any run is created.
     */
    private UUID resolveTestCaseId(UUID testCaseId, String testCaseName, Map<String, UUID> nameToGeneratedId) {
        if (testCaseId != null) {
            return testCaseId;
        }
        if (testCaseName != null) {
            return nameToGeneratedId.computeIfAbsent(testCaseName, name -> UUID.randomUUID());
        }
        return null;
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

    /**
     * Builds the {@code testCaseData} validation schema, aware of each field's scope (design.md
     * Decision 6): shared fields keep {@link SchemaValidationService#buildFieldSchema}'s existing
     * behavior (present in {@code properties}, and in {@code required} when declared required);
     * per-turn fields stay in {@code properties} (type-checked when present) but are never added to
     * {@code required} — a row's {@code testCaseData} is the effective view for one
     * {@code (request, turn)} position, and a legitimate row may carry shared fields only.
     */
    private Map<String, Object> buildScopeAwareSchema(List<FieldDefinitionDto> schema) {
        final TestCaseFieldScopeResolver.SchemaSplit split = testCaseFieldScopeResolver.splitSchema(schema);
        final Map<String, Object> sharedSchema = SchemaValidationService.buildFieldSchema(split.shared());
        final Map<String, Object> perTurnSchema = SchemaValidationService.buildFieldSchema(split.perTurn());

        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.putAll(propertiesOf(sharedSchema));
        properties.putAll(propertiesOf(perTurnSchema));

        final Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("type", "object");
        merged.put("properties", properties);
        if (sharedSchema.containsKey("required")) {
            merged.put("required", sharedSchema.get("required"));
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> propertiesOf(Map<String, Object> fieldSchema) {
        final Object properties = fieldSchema.get("properties");
        return properties instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

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
