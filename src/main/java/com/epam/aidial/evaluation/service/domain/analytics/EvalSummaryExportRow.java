package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.data.db.analytics.model.EvalSummary;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Transient per-row working object wrapping a single {@link EvalSummary} for export.
 *
 * <p>The five JSONB-backed columns on {@code EvalSummary} are persisted as raw {@code String}.
 * This wrapper lazily parses each one via the shared {@link ObjectMapper} on first access and
 * memoizes the result so descriptor {@code valueExtractor}s never re-parse. Parse failures are
 * logged (with the exception as the last SLF4J argument per AGENTS.md) and the affected accessor
 * returns {@code null} so the CSV cell renders as empty.
 *
 * <p>Not thread-safe — one instance per row, only ever read on the row's writing thread.
 */
@Slf4j
@Getter(AccessLevel.PACKAGE)
final class EvalSummaryExportRow {

    private final EvalSummary summary;
    private final ObjectMapper objectMapper;

    private JsonNode testCaseDataNode;
    private boolean testCaseDataParsed;

    private JsonNode extractedColumnsNode;
    private boolean extractedColumnsParsed;

    private JsonNode metricValuesNode;
    private boolean metricValuesParsed;

    private JsonNode metricInfosNode;
    private boolean metricInfosParsed;

    private JsonNode extractionWarningsNode;
    private boolean extractionWarningsParsed;

    EvalSummaryExportRow(EvalSummary summary, ObjectMapper objectMapper) {
        this.summary = summary;
        this.objectMapper = objectMapper;
    }

    JsonNode testCaseData() {
        if (!testCaseDataParsed) {
            testCaseDataNode = parse(summary.getTestCaseData(), "testCaseData");
            testCaseDataParsed = true;
        }
        return testCaseDataNode;
    }

    JsonNode extractedColumns() {
        if (!extractedColumnsParsed) {
            extractedColumnsNode = parse(summary.getExtractedColumns(), "extractedColumns");
            extractedColumnsParsed = true;
        }
        return extractedColumnsNode;
    }

    JsonNode metricValues() {
        if (!metricValuesParsed) {
            metricValuesNode = parse(summary.getMetricValues(), "metricValues");
            metricValuesParsed = true;
        }
        return metricValuesNode;
    }

    JsonNode metricInfos() {
        if (!metricInfosParsed) {
            metricInfosNode = parse(summary.getMetricInfos(), "metricInfos");
            metricInfosParsed = true;
        }
        return metricInfosNode;
    }

    /**
     * Per-field info accessor implementing the routing rule from {@code design.md} §Decisions/8.
     *
     * <p>Returns {@code metricInfos[metricName][fieldName]} when {@code metricInfos[metricName]}
     * is a JSON object whose top-level keys overlap with {@code schemaFieldKeys} (i.e. it is
     * interpretable as a per-field map). In every other case — wholesale-error payload,
     * non-object value, missing key, no schema-key overlap — returns {@code null} so the CSV
     * cell renders as empty and the wholesale payload routes through
     * {@link #metricWholesaleError(String, Set)}.
     */
    JsonNode metricInfo(String metricName, String fieldName, Set<String> schemaFieldKeys) {
        JsonNode perMetric = perMetricInfoIfPerFieldMap(metricName, schemaFieldKeys);
        if (perMetric == null) {
            return null;
        }
        JsonNode value = perMetric.get(fieldName);
        return value == null || value.isNull() ? null : value;
    }

    /**
     * Wholesale-error accessor — the inverse of {@link #metricInfo(String, String, Set)}.
     *
     * <p>Returns the row's whole {@code metricInfos[metricName]} payload only when it cannot be
     * interpreted as a per-field map (not a JSON object, or none of its top-level keys overlap
     * with {@code schemaFieldKeys}). Returns {@code null} otherwise — including when the metric
     * has no entry at all, in which case both per-field and wholesale-error cells render empty.
     */
    JsonNode metricWholesaleError(String metricName, Set<String> schemaFieldKeys) {
        JsonNode infos = metricInfos();
        if (infos == null || !infos.isObject()) {
            return null;
        }
        JsonNode perMetric = infos.get(metricName);
        if (perMetric == null || perMetric.isNull()) {
            return null;
        }
        if (isPerFieldMap(perMetric, schemaFieldKeys)) {
            return null;
        }
        return perMetric;
    }

    private JsonNode perMetricInfoIfPerFieldMap(String metricName, Set<String> schemaFieldKeys) {
        JsonNode infos = metricInfos();
        if (infos == null || !infos.isObject()) {
            return null;
        }
        JsonNode perMetric = infos.get(metricName);
        if (perMetric == null || perMetric.isNull() || !isPerFieldMap(perMetric, schemaFieldKeys)) {
            return null;
        }
        return perMetric;
    }

    private static boolean isPerFieldMap(JsonNode perMetric, Set<String> schemaFieldKeys) {
        if (perMetric == null || !perMetric.isObject() || schemaFieldKeys == null || schemaFieldKeys.isEmpty()) {
            return false;
        }
        for (String key : perMetric.propertyNames()) {
            if (schemaFieldKeys.contains(key)) {
                return true;
            }
        }
        return false;
    }

    JsonNode extractionWarnings() {
        if (!extractionWarningsParsed) {
            extractionWarningsNode = parse(summary.getExtractionWarnings(), "extractionWarnings");
            extractionWarningsParsed = true;
        }
        return extractionWarningsNode;
    }

    private JsonNode parse(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (JacksonException e) {
            log.warn(
                    "Failed to parse JSONB field '{}' on EvalSummary id={}: {}",
                    fieldName,
                    summary.getId(),
                    e.getMessage(),
                    e);
            return null;
        }
    }
}
