package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants;
import com.epam.aidial.evaluation.data.db.analytics.model.EvalSummary;
import com.epam.aidial.evaluation.data.db.analytics.model.RunMetricSnapshot;
import com.epam.aidial.evaluation.service.domain.OutputSchemaFieldExtractor;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.SuiteSnapshotDto;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Builds the ordered full column manifest for an EvalSummary export from a run's frozen
 * {@link SuiteSnapshotDto} plus the resolved computation's {@link RunMetricSnapshot}s.
 *
 * <p>The planner is a pure function of its inputs and always emits the FULL manifest, including
 * the two body descriptors ({@code requestBody}, {@code responseBody}) at the tail. The
 * default-vs-explicit decision (whether bodies are emitted) lives entirely in
 * {@link EvalSummaryExportColumnSelector} — the planner does not see the request and has no
 * {@code detailed} flag.
 *
 * <p>Metric-field column derivation delegates to {@link OutputSchemaFieldExtractor} (the canonical
 * shared component per the {@code metric-evaluation} spec) — the planner does NOT parse
 * {@code outputSchema} JSON itself.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class EvalSummaryExportColumnPlanner {

    private final OutputSchemaFieldExtractor outputSchemaFieldExtractor;

    public List<ColumnDescriptor> plan(SuiteSnapshotDto snapshot, List<RunMetricSnapshot> metricSnapshots) {
        List<ColumnDescriptor> descriptors = new ArrayList<>();

        // 1. Identity columns
        descriptors.add(plain("id", row -> row.getSummary().getId()));
        descriptors.add(plain("testSuiteId", row -> row.getSummary().getTestSuiteId()));
        descriptors.add(plain("testSuiteRunId", row -> row.getSummary().getTestSuiteRunId()));
        descriptors.add(plain("testCaseRunResultId", row -> row.getSummary().getTestCaseRunResultId()));
        descriptors.add(plain("testCaseId", row -> row.getSummary().getTestCaseId()));
        descriptors.add(plain("testCaseName", row -> row.getSummary().getTestCaseName()));
        descriptors.add(plain("runIndex", row -> row.getSummary().getRunIndex()));
        descriptors.add(plain("computationId", row -> row.getSummary().getComputationId()));

        // 2. Timestamps (epoch ms — preserved as Long per AGENTS.md "API Timestamp Convention")
        descriptors.add(plain("createdAt", row -> row.getSummary().getCreatedAtMs()));
        descriptors.add(plain("computedAt", row -> row.getSummary().getComputedAtMs()));

        // 3. Execution columns
        descriptors.add(plain("executionStatus", row -> {
            EvalSummary s = row.getSummary();
            return s.getExecutionStatus() == null
                    ? null
                    : s.getExecutionStatus().name();
        }));
        descriptors.add(plain("execDurationMs", row -> row.getSummary().getExecDurationMs()));
        descriptors.add(plain("responseStatusCode", row -> row.getSummary().getResponseStatusCode()));

        // 4. Inlined data:<fieldName> per snapshot testCaseSchema
        if (snapshot.getTestCaseSchema() != null) {
            for (FieldDefinitionDto field : snapshot.getTestCaseSchema()) {
                String fieldName = field.getName();
                descriptors.add(plain(
                        EvalSummaryExportColumnConstants.DATA_COLUMN_PREFIX + fieldName,
                        row -> jsonFieldValue(row.testCaseData(), fieldName)));
            }
        }

        // 5. Inlined response:<columnName> per snapshot responseColumns
        if (snapshot.getResponseColumns() != null) {
            for (ResponseColumnDefinitionDto column : snapshot.getResponseColumns()) {
                String columnName = column.getName();
                descriptors.add(plain(
                        EvalSummaryExportColumnConstants.RESPONSE_COLUMN_PREFIX + columnName,
                        row -> jsonFieldValue(row.extractedColumns(), columnName)));
            }
        }

        // 6. Per-metric block: metric:<m>:<f> values, then metricInfo:<m>:<f> details,
        //    then metricError:<m> wholesale-error column. Field-key set captured once per
        //    metric and closed into both descriptor families so the routing rule on the row
        //    accessors can do an O(fields_per_metric) membership check per row.
        if (metricSnapshots != null) {
            for (RunMetricSnapshot snap : metricSnapshots) {
                String metricName = snap.getTsmdName();
                List<String> fieldNames = outputSchemaFieldExtractor.extractFieldNames(snap.getOutputSchema());
                Set<String> fieldKeySet = new LinkedHashSet<>(fieldNames);
                for (String fieldName : fieldNames) {
                    descriptors.add(plain(
                            EvalSummaryExportColumnConstants.METRIC_COLUMN_PREFIX
                                    + metricName
                                    + EvalSummaryExportColumnConstants.COLUMN_SEPARATOR
                                    + fieldName,
                            row -> metricValueAt(row.metricValues(), metricName, fieldName)));
                }
                for (String fieldName : fieldNames) {
                    descriptors.add(plain(
                            EvalSummaryExportColumnConstants.METRIC_INFO_COLUMN_PREFIX
                                    + metricName
                                    + EvalSummaryExportColumnConstants.COLUMN_SEPARATOR
                                    + fieldName,
                            row -> row.metricInfo(metricName, fieldName, fieldKeySet)));
                }
                descriptors.add(plain(
                        EvalSummaryExportColumnConstants.METRIC_ERROR_COLUMN_PREFIX + metricName,
                        row -> row.metricWholesaleError(metricName, fieldKeySet)));
            }
        }

        // 7. JSON-blob cells — metricInfos blob removed; extractionWarnings retained.
        descriptors.add(plain("extractionWarnings", EvalSummaryExportRow::extractionWarnings));

        // 8. Body columns — always emitted; selector strips them on empty-input branch
        descriptors.add(bodyColumn("requestBody", row -> row.getSummary().getRequestBody()));
        descriptors.add(bodyColumn("responseBody", row -> row.getSummary().getResponseBody()));

        return descriptors;
    }

    private static ColumnDescriptor plain(String name, Function<EvalSummaryExportRow, Object> extractor) {
        return new ColumnDescriptor(name, false, extractor);
    }

    private static ColumnDescriptor bodyColumn(String name, Function<EvalSummaryExportRow, Object> extractor) {
        return new ColumnDescriptor(name, true, extractor);
    }

    private static Object jsonFieldValue(JsonNode node, String fieldName) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode child = node.get(fieldName);
        return child == null || child.isNull() ? null : child;
    }

    private static Object metricValueAt(JsonNode metricValues, String metricName, String fieldName) {
        if (metricValues == null || !metricValues.isObject()) {
            return null;
        }
        JsonNode metricNode = metricValues.get(metricName);
        if (metricNode == null || !metricNode.isObject()) {
            return null;
        }
        JsonNode value = metricNode.get(fieldName);
        return value == null || value.isNull() ? null : value;
    }
}
