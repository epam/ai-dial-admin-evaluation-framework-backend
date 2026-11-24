package com.epam.aidial.evaluation.service.domain.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.properties.csv.CsvExportProperties;
import com.epam.aidial.evaluation.data.db.analytics.model.EvalSummary;
import com.epam.aidial.evaluation.data.db.analytics.model.cursor.CursorPage;
import com.epam.aidial.evaluation.data.db.analytics.repository.EvalSummaryRepository;
import com.epam.aidial.evaluation.data.db.model.RunMetricSnapshot;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.data.db.repository.RunMetricSnapshotRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRunRepository;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SuiteSnapshotDto;
import com.epam.aidial.evaluation.service.domain.OutputSchemaFieldExtractor;
import com.epam.aidial.evaluation.service.domain.ResponseColumnUnionResolver;
import com.epam.aidial.evaluation.service.domain.csv.CsvDelimiterParser;
import com.epam.aidial.evaluation.service.domain.csv.CsvTimestampCodec;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryExportRequestDto;
import com.epam.aidial.evaluation.service.domain.filter.FilterParser;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

class EvalSummaryExportServiceTest {
    private final UUID runId = UUID.randomUUID();
    private final UUID computationId = UUID.randomUUID();
    private EvalSummaryExportService service;

    @BeforeEach
    void setUp() {
        TestSuiteRunRepository runs = mock(TestSuiteRunRepository.class);
        RunMetricSnapshotRepository metrics = mock(RunMetricSnapshotRepository.class);
        EvalSummaryRepository summaries = mock(EvalSummaryRepository.class);
        ComputationResolver computationResolver = mock(ComputationResolver.class);
        FilterParser filterParser = mock(FilterParser.class);
        ObjectMapper mapper = new ObjectMapper();
        final SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder()
                .testCaseSchema(List.of(
                        FieldDefinitionDto.builder().name("timestamp").build(),
                        FieldDefinitionDto.builder().name("enabled").build(),
                        FieldDefinitionDto.builder().name("metadata").build()))
                .build();
        TestSuiteRun run = TestSuiteRun.builder()
                .id(runId)
                .status("COMPLETED")
                .createdAt(123L)
                .suiteSnapshot(mapper.writeValueAsString(snapshot))
                .build();
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(metrics.findByRunId(runId))
                .thenReturn(List.of(RunMetricSnapshot.builder()
                        .computationId(computationId)
                        .tsmdName("Accuracy")
                        .outputSchema("schema")
                        .build()));
        when(computationResolver.resolve(null, runId)).thenReturn(Optional.of(computationId));
        when(filterParser.parse(anyList())).thenReturn(List.of());
        CursorPage<EvalSummary> page = new CursorPage<>(
                List.of(
                        EvalSummary.builder()
                                .createdAtMs(0L)
                                .computedAtMs(-1L)
                                .execDurationMs(42L)
                                .metricEvalDurationMs(43L)
                                .responseStatusCode(200)
                                .testCaseData("{\"timestamp\":1779704532226,\"enabled\":true,\"metadata\":{}}")
                                .extractedColumns("{\"answer\":1779704532226}")
                                .metricValues("{\"Accuracy\":{\"score\":1779704532226}}")
                                .metricInfos("{\"Accuracy\":{\"score\":{\"reason\":\"matches\"}}}")
                                .extractionWarnings("[]")
                                .build(),
                        EvalSummary.builder()
                                .createdAtMs(null)
                                .computedAtMs(1779704532226L)
                                .build()),
                null,
                false);
        lenient()
                .when(summaries.findAllForExport(anyList(), eq(computationId), eq(123L), isNull(), anyInt()))
                .thenReturn(page);
        lenient()
                .when(summaries.findAllForExportWithBodies(anyList(), eq(computationId), eq(123L), isNull(), anyInt()))
                .thenReturn(page);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        CsvExportProperties properties = new CsvExportProperties();
        properties.setPageSize(100);
        final OutputSchemaFieldExtractor extractor = mock(OutputSchemaFieldExtractor.class);
        when(extractor.extractFieldNames("schema")).thenReturn(List.of("score"));
        final ResponseColumnUnionResolver unionResolver = mock(ResponseColumnUnionResolver.class);
        when(unionResolver.unionFrom(any(SuiteSnapshotDto.class)))
                .thenReturn(List.of(
                        ResponseColumnDefinitionDto.builder().name("answer").build()));
        final EvalSummaryExportColumnPlanner planner = new EvalSummaryExportColumnPlanner(extractor, unionResolver);
        service = new EvalSummaryExportService(
                runs,
                metrics,
                computationResolver,
                summaries,
                planner,
                new EvalSummaryExportColumnSelector(),
                filterParser,
                mapper,
                properties,
                new CsvDelimiterParser(),
                new CsvTimestampCodec(),
                tx,
                tx);
    }

    @Test
    @DisplayName("default CSV selection formats both timestamps and preserves duration and data numbers")
    void defaultSelection() throws IOException {
        List<CSVRecord> records = export(null);
        CSVRecord headers = records.get(0);
        CSVRecord first = records.get(1);
        CSVRecord second = records.get(2);
        assertThat(first.get(index(headers, "createdAt"))).isEqualTo("1970-01-01T00:00:00.000Z");
        assertThat(first.get(index(headers, "computedAt"))).isEqualTo("1969-12-31T23:59:59.999Z");
        assertThat(first.get(index(headers, "execDurationMs"))).isEqualTo("42");
        assertThat(first.get(index(headers, "data::timestamp"))).isEqualTo("1779704532226");
        assertThat(second.get(index(headers, "createdAt"))).isEmpty();
        assertThat(second.get(index(headers, "computedAt"))).isEqualTo("2026-05-25T10:22:12.226Z");
    }

    @Test
    @DisplayName("explicit selection preserves requested order and formats only timestamp columns")
    void explicitSelection() throws IOException {
        List<CSVRecord> records = export(List.of("data::timestamp", "computedAt", "execDurationMs", "createdAt"));
        assertThat(records.get(0)).containsExactly("data::timestamp", "computedAt", "execDurationMs", "createdAt");
        assertThat(records.get(1))
                .containsExactly("1779704532226", "1969-12-31T23:59:59.999Z", "42", "1970-01-01T00:00:00.000Z");
    }

    @Test
    @DisplayName("JSON preview formats only non-null timestamps and preserves typed values")
    void previewFormatsOnlyTimestamps() {
        final List<List<Object>> preview = service.previewAsJson(runId, null, null);
        final List<Object> headers = preview.get(0);
        final List<Object> first = preview.get(1);
        final ObjectMapper mapper = new ObjectMapper();
        assertThat(first.get(headers.indexOf("createdAt"))).isEqualTo("1970-01-01T00:00:00.000Z");
        assertThat(first.get(headers.indexOf("computedAt"))).isEqualTo("1969-12-31T23:59:59.999Z");
        assertThat(first.get(headers.indexOf("execDurationMs"))).isEqualTo(42L);
        assertThat(first.get(headers.indexOf("metricEvalDurationMs"))).isEqualTo(43L);
        assertThat(first.get(headers.indexOf("responseStatusCode"))).isEqualTo(200);
        assertThat(first.get(headers.indexOf("data::timestamp"))).isEqualTo(mapper.readTree("1779704532226"));
        assertThat(first.get(headers.indexOf("data::enabled"))).isEqualTo(mapper.readTree("true"));
        assertThat(first.get(headers.indexOf("data::metadata"))).isEqualTo(mapper.readTree("{}"));
        assertThat(first.get(headers.indexOf("response::answer"))).isEqualTo(mapper.readTree("1779704532226"));
        assertThat(first.get(headers.indexOf("metric::Accuracy::score"))).isEqualTo(mapper.readTree("1779704532226"));
        assertThat(first.get(headers.indexOf("metricInfo::Accuracy::score")))
                .isEqualTo(mapper.readTree("{\"reason\":\"matches\"}"));
        assertThat(first.get(headers.indexOf("extractionWarnings"))).isEqualTo(mapper.readTree("[]"));
        assertThat(preview.get(2).get(headers.indexOf("createdAt"))).isNull();
        assertThat(preview.get(2).get(headers.indexOf("computedAt"))).isEqualTo("2026-05-25T10:22:12.226Z");
    }

    private List<CSVRecord> export(List<String> columns) throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        service.exportToCsv(
                EvalSummaryExportRequestDto.builder()
                        .runId(runId)
                        .columns(columns)
                        .build(),
                response);
        try (CSVParser parser = CSVFormat.DEFAULT.parse(new StringReader(response.getContentAsString()))) {
            return parser.getRecords();
        }
    }

    private int index(CSVRecord record, String value) {
        for (int i = 0; i < record.size(); i++) {
            if (value.equals(record.get(i))) {
                return i;
            }
        }
        throw new AssertionError("Missing column: " + value);
    }
}
