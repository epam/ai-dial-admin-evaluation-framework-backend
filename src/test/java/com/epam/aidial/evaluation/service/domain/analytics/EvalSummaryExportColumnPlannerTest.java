package com.epam.aidial.evaluation.service.domain.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.analytics.model.RunMetricSnapshot;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.SuiteSnapshotDto;
import com.epam.aidial.evaluation.service.domain.OutputSchemaFieldExtractor;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("EvalSummaryExportColumnPlanner")
class EvalSummaryExportColumnPlannerTest {

    private static final String METRIC_ONE_SCHEMA = "{\"properties\":{\"z_score\":{},\"a_metric\":{},\"m_value\":{}}}";
    private static final String METRIC_TWO_SCHEMA = "{\"properties\":{\"score\":{},\"explanation\":{}}}";

    @Mock
    private OutputSchemaFieldExtractor outputSchemaFieldExtractor;

    private EvalSummaryExportColumnPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new EvalSummaryExportColumnPlanner(outputSchemaFieldExtractor);
    }

    @Test
    @DisplayName("Identity, timestamp, and execution columns appear at the front in fixed order")
    void identityTimestampExecutionOrder() {
        SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder().build();

        List<ColumnDescriptor> result = planner.plan(snapshot, List.of());

        assertThat(result)
                .extracting(ColumnDescriptor::name)
                .startsWith(
                        "id",
                        "testSuiteId",
                        "testSuiteRunId",
                        "testCaseRunResultId",
                        "testCaseId",
                        "testCaseName",
                        "runIndex",
                        "computationId",
                        "createdAt",
                        "computedAt",
                        "executionStatus",
                        "execDurationMs",
                        "avgMetricEvalDurationMs",
                        "responseStatusCode");
    }

    @Test
    @DisplayName("data::<field> columns are derived from testCaseSchema in declaration order")
    void dataColumnsDerivedFromTestCaseSchema() {
        SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder()
                .testCaseSchema(List.of(
                        FieldDefinitionDto.builder()
                                .name("prompt")
                                .type(SchemaFieldType.STRING)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("attachment")
                                .type(SchemaFieldType.FILE)
                                .build()))
                .build();

        List<ColumnDescriptor> result = planner.plan(snapshot, List.of());

        assertThat(result).extracting(ColumnDescriptor::name).contains("data::prompt", "data::attachment");
        assertThat(indexOf(result, "data::prompt")).isLessThan(indexOf(result, "data::attachment"));
    }

    @Test
    @DisplayName("Snapshot field names containing dots are preserved unmodified in data: column headers")
    void dataColumnPreservesEmbeddedDotInFieldName() {
        SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder()
                .testCaseSchema(List.of(FieldDefinitionDto.builder()
                        .name("meta.tags")
                        .type(SchemaFieldType.STRING)
                        .build()))
                .build();

        List<ColumnDescriptor> result = planner.plan(snapshot, List.of());

        assertThat(result).extracting(ColumnDescriptor::name).contains("data::meta.tags");
    }

    @Test
    @DisplayName("response::<column> columns are derived from responseColumns in declaration order")
    void responseColumnsDerivedFromSnapshot() {
        SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder()
                .responseColumns(List.of(
                        ResponseColumnDefinitionDto.builder()
                                .name("answer")
                                .expression("$.answer")
                                .build(),
                        ResponseColumnDefinitionDto.builder()
                                .name("file")
                                .expression("$.file")
                                .build()))
                .build();

        List<ColumnDescriptor> result = planner.plan(snapshot, List.of());

        assertThat(result).extracting(ColumnDescriptor::name).contains("response::answer", "response::file");
        assertThat(indexOf(result, "response::answer")).isLessThan(indexOf(result, "response::file"));
    }

    @Test
    @DisplayName("metric::<m>::<field> value columns preserve the OutputSchemaFieldExtractor's insertion order")
    void metricColumnsPreserveExtractorOrder() {
        SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder().build();
        RunMetricSnapshot metric = RunMetricSnapshot.builder()
                .tsmdName("Accuracy")
                .outputSchema(METRIC_ONE_SCHEMA)
                .build();
        when(outputSchemaFieldExtractor.extractFieldNames(eq(METRIC_ONE_SCHEMA)))
                .thenReturn(List.of("z_score", "a_metric", "m_value"));

        List<ColumnDescriptor> result = planner.plan(snapshot, List.of(metric));

        assertThat(result)
                .extracting(ColumnDescriptor::name)
                .containsSubsequence(
                        "metric::Accuracy::z_score", "metric::Accuracy::a_metric", "metric::Accuracy::m_value");
    }

    @Test
    @DisplayName("Per-metric block emits values first, then info columns, then a single metricError column")
    void perMetricBlockOrdering() {
        SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder().build();
        RunMetricSnapshot metric = RunMetricSnapshot.builder()
                .tsmdName("Accuracy")
                .outputSchema(METRIC_TWO_SCHEMA)
                .build();
        when(outputSchemaFieldExtractor.extractFieldNames(eq(METRIC_TWO_SCHEMA)))
                .thenReturn(List.of("score", "explanation"));

        List<ColumnDescriptor> result = planner.plan(snapshot, List.of(metric));

        assertThat(result)
                .extracting(ColumnDescriptor::name)
                .containsSubsequence(
                        "metric::Accuracy::score",
                        "metric::Accuracy::explanation",
                        "metricInfo::Accuracy::score",
                        "metricInfo::Accuracy::explanation",
                        "metricError::Accuracy");
    }

    @Test
    @DisplayName("Metric names containing dots are preserved unmodified in metric: column headers")
    void metricNameWithEmbeddedDotIsPreserved() {
        SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder().build();
        String schema = "{\"properties\":{\"precision\":{}}}";
        RunMetricSnapshot metric = RunMetricSnapshot.builder()
                .tsmdName("bert.score")
                .outputSchema(schema)
                .build();
        when(outputSchemaFieldExtractor.extractFieldNames(eq(schema))).thenReturn(List.of("precision"));

        List<ColumnDescriptor> result = planner.plan(snapshot, List.of(metric));

        assertThat(result)
                .extracting(ColumnDescriptor::name)
                .containsSubsequence(
                        "metric::bert.score::precision",
                        "metricInfo::bert.score::precision",
                        "metricError::bert.score");
    }

    @Test
    @DisplayName("Multiple metrics emit one full block per snapshot in snapshot order")
    void multipleMetricsEmitInOrder() {
        SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder().build();
        RunMetricSnapshot metricOne = RunMetricSnapshot.builder()
                .tsmdName("Accuracy")
                .outputSchema(METRIC_ONE_SCHEMA)
                .build();
        RunMetricSnapshot metricTwo = RunMetricSnapshot.builder()
                .tsmdName("Relevance")
                .outputSchema(METRIC_TWO_SCHEMA)
                .build();
        when(outputSchemaFieldExtractor.extractFieldNames(eq(METRIC_ONE_SCHEMA)))
                .thenReturn(List.of("z_score", "a_metric"));
        when(outputSchemaFieldExtractor.extractFieldNames(eq(METRIC_TWO_SCHEMA)))
                .thenReturn(List.of("score", "explanation"));

        List<ColumnDescriptor> result = planner.plan(snapshot, List.of(metricOne, metricTwo));

        assertThat(result)
                .extracting(ColumnDescriptor::name)
                .containsSubsequence(
                        "metric::Accuracy::z_score",
                        "metric::Accuracy::a_metric",
                        "metricInfo::Accuracy::z_score",
                        "metricInfo::Accuracy::a_metric",
                        "metricError::Accuracy",
                        "metric::Relevance::score",
                        "metric::Relevance::explanation",
                        "metricInfo::Relevance::score",
                        "metricInfo::Relevance::explanation",
                        "metricError::Relevance");
    }

    @Test
    @DisplayName(
            "metricInfos JSON-blob column is not emitted; extractionWarnings sits immediately before the body columns")
    void legacyMetricInfosBlobIsDropped() {
        SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder().build();

        List<ColumnDescriptor> result = planner.plan(snapshot, List.of());

        List<String> names = result.stream().map(ColumnDescriptor::name).toList();
        assertThat(names).doesNotContain("metricInfos");

        int warningsIdx = names.indexOf("extractionWarnings");
        int requestBodyIdx = names.indexOf("requestBody");
        int responseBodyIdx = names.indexOf("responseBody");

        assertThat(warningsIdx).isLessThan(requestBodyIdx);
        assertThat(requestBodyIdx).isLessThan(responseBodyIdx);
        assertThat(responseBodyIdx).isEqualTo(names.size() - 1);
    }

    @Test
    @DisplayName("requestBody and responseBody are always emitted at the tail regardless of input")
    void bodyColumnsAlwaysEmitted() {
        SuiteSnapshotDto emptySnapshot = SuiteSnapshotDto.builder().build();
        SuiteSnapshotDto richSnapshot = SuiteSnapshotDto.builder()
                .testCaseSchema(List.of(FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .build()))
                .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                        .name("answer")
                        .expression("$.a")
                        .build()))
                .build();

        assertThat(planner.plan(emptySnapshot, List.of()))
                .extracting(ColumnDescriptor::name)
                .endsWith("requestBody", "responseBody");
        assertThat(planner.plan(richSnapshot, List.of()))
                .extracting(ColumnDescriptor::name)
                .endsWith("requestBody", "responseBody");
        assertThat(planner.plan(emptySnapshot, null))
                .extracting(ColumnDescriptor::name)
                .endsWith("requestBody", "responseBody");
    }

    @Test
    @DisplayName(
            "isBodyColumn and requiresJoinProjection are true exactly on requestBody/responseBody, false elsewhere")
    void bodyFlagsExactlyOnBodyColumns() {
        SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder()
                .testCaseSchema(List.of(FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .build()))
                .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                        .name("answer")
                        .expression("$.a")
                        .build()))
                .build();
        RunMetricSnapshot metric = RunMetricSnapshot.builder()
                .tsmdName("Accuracy")
                .outputSchema(METRIC_ONE_SCHEMA)
                .build();
        when(outputSchemaFieldExtractor.extractFieldNames(eq(METRIC_ONE_SCHEMA)))
                .thenReturn(List.of("score"));

        List<ColumnDescriptor> result = planner.plan(snapshot, List.of(metric));

        for (ColumnDescriptor descriptor : result) {
            boolean expectedBody = "requestBody".equals(descriptor.name()) || "responseBody".equals(descriptor.name());
            assertThat(descriptor.isBodyColumn())
                    .as("isBodyColumn for column '%s'", descriptor.name())
                    .isEqualTo(expectedBody);
            assertThat(descriptor.requiresJoinProjection())
                    .as("requiresJoinProjection for column '%s'", descriptor.name())
                    .isEqualTo(expectedBody);
        }
    }

    @Test
    @DisplayName(
            "Planner emits more than MAX_EXPORT_COLUMNS when fed an oversized snapshot — service-side cap precondition")
    void oversizedSnapshotProducesCountAboveCap() {
        int testCaseFieldCount = 300;
        int responseColumnCount = 300;
        List<FieldDefinitionDto> testCaseSchema = new ArrayList<>(testCaseFieldCount);
        for (int i = 0; i < testCaseFieldCount; i++) {
            testCaseSchema.add(FieldDefinitionDto.builder()
                    .name("f" + i)
                    .type(SchemaFieldType.STRING)
                    .build());
        }
        List<ResponseColumnDefinitionDto> responseColumns = new ArrayList<>(responseColumnCount);
        for (int i = 0; i < responseColumnCount; i++) {
            responseColumns.add(ResponseColumnDefinitionDto.builder()
                    .name("r" + i)
                    .expression("$.r" + i)
                    .build());
        }
        SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder()
                .testCaseSchema(testCaseSchema)
                .responseColumns(responseColumns)
                .build();

        List<ColumnDescriptor> result = planner.plan(snapshot, List.of());

        assertThat(result).hasSizeGreaterThan(512);
    }

    @Test
    @DisplayName("A single metric with K fields contributes exactly 2K + 1 columns (K value + K info + 1 error)")
    void perMetricColumnCountMath() {
        SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder().build();
        RunMetricSnapshot metric = RunMetricSnapshot.builder()
                .tsmdName("M")
                .outputSchema(METRIC_TWO_SCHEMA)
                .build();
        when(outputSchemaFieldExtractor.extractFieldNames(eq(METRIC_TWO_SCHEMA)))
                .thenReturn(List.of("score", "explanation"));

        List<ColumnDescriptor> result = planner.plan(snapshot, List.of(metric));
        long metricBlockColumns = result.stream()
                .map(ColumnDescriptor::name)
                .filter(n -> n.startsWith("metric::M::")
                        || n.startsWith("metricInfo::M::")
                        || n.startsWith("metricError::M"))
                .count();

        assertThat(metricBlockColumns).isEqualTo(2L * 2 + 1);
    }

    @Test
    @DisplayName("Null and empty metricSnapshots both yield no metric:/metricInfo:/metricError: columns")
    void nullOrEmptyMetricSnapshotsEmitNoMetricColumns() {
        SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder().build();

        List<ColumnDescriptor> nullResult = planner.plan(snapshot, null);
        List<ColumnDescriptor> emptyResult = planner.plan(snapshot, List.of());

        assertThat(nullResult)
                .extracting(ColumnDescriptor::name)
                .noneMatch(
                        n -> n.startsWith("metric::") || n.startsWith("metricInfo::") || n.startsWith("metricError::"));
        assertThat(emptyResult)
                .extracting(ColumnDescriptor::name)
                .noneMatch(
                        n -> n.startsWith("metric::") || n.startsWith("metricInfo::") || n.startsWith("metricError::"));
    }

    @Test
    @DisplayName("Extractor receives the exact outputSchema string from each RunMetricSnapshot")
    void extractorReceivesUnmodifiedOutputSchema() {
        SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder().build();
        RunMetricSnapshot metric = RunMetricSnapshot.builder()
                .id(UUID.randomUUID())
                .tsmdName("MyMetric")
                .outputSchema(METRIC_TWO_SCHEMA)
                .build();
        when(outputSchemaFieldExtractor.extractFieldNames(eq(METRIC_TWO_SCHEMA)))
                .thenReturn(List.of("score", "explanation"));

        List<ColumnDescriptor> result = planner.plan(snapshot, List.of(metric));

        assertThat(result)
                .extracting(ColumnDescriptor::name)
                .contains(
                        "metric::MyMetric::score",
                        "metric::MyMetric::explanation",
                        "metricInfo::MyMetric::score",
                        "metricInfo::MyMetric::explanation",
                        "metricError::MyMetric");
    }

    private static int indexOf(List<ColumnDescriptor> descriptors, String name) {
        for (int i = 0; i < descriptors.size(); i++) {
            if (name.equals(descriptors.get(i).name())) {
                return i;
            }
        }
        return -1;
    }
}
