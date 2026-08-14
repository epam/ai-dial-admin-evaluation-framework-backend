package com.epam.aidial.evaluation.service.domain.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.configuration.properties.grafana.GrafanaProperties;
import com.epam.aidial.evaluation.data.db.analytics.model.EvalSummary;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.service.domain.GrafanaLinkBuilder;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryDetailResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryResponseDto;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@DisplayName("EvalSummaryMapper")
class EvalSummaryMapperTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.ofEpochMilli(1700000120000L), ZoneOffset.UTC);

    private EvalSummaryMapper mapper;

    @BeforeEach
    void setUp() {
        JacksonMapper jacksonMapper = new JacksonMapper(OBJECT_MAPPER);
        EvalSummaryMapperImpl mapperImpl = new EvalSummaryMapperImpl();
        setField(mapperImpl, "jacksonMapper", jacksonMapper);
        // Default: Grafana disabled (blank base-url)
        setField(mapperImpl, "grafanaLinkBuilder", disabledGrafanaLinkBuilder());
        mapper = mapperImpl;
    }

    private static GrafanaLinkBuilder enabledGrafanaLinkBuilder() {
        GrafanaProperties props = new GrafanaProperties();
        props.setBaseUrl("http://grafana:3000");
        props.setTempoDatasourceUid("tempo");
        props.setOrgId(1);
        return new GrafanaLinkBuilder(props, OBJECT_MAPPER, FIXED_CLOCK);
    }

    private static GrafanaLinkBuilder disabledGrafanaLinkBuilder() {
        GrafanaProperties props = new GrafanaProperties();
        props.setBaseUrl("");
        props.setTempoDatasourceUid("tempo");
        props.setOrgId(1);
        return new GrafanaLinkBuilder(props, OBJECT_MAPPER, FIXED_CLOCK);
    }

    @Test
    @DisplayName("Should map item DTO to entity with all fields")
    void shouldMapItemDtoToEntity() {
        UUID testCaseRunResultId = UUID.randomUUID();
        UUID testCaseId = UUID.randomUUID();
        UUID testSuiteId = UUID.randomUUID();
        UUID testSuiteRunId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();

        ObjectNode testCaseData = JsonNodeFactory.instance.objectNode();
        testCaseData.put("prompt", "test");

        ObjectNode metricValues = JsonNodeFactory.instance.objectNode();
        metricValues.putObject("Accuracy").put("score", 0.95);

        ObjectNode extractedColumns = JsonNodeFactory.instance.objectNode();
        extractedColumns.put("col1", "val1");

        ObjectNode metricInfos = JsonNodeFactory.instance.objectNode();
        metricInfos.putObject("Accuracy").put("version", "1.0");

        EvalSummaryBatchWriteItemDto item = EvalSummaryBatchWriteItemDto.builder()
                .testCaseRunResultId(testCaseRunResultId)
                .testCaseId(testCaseId)
                .testCaseName("test-case")
                .runIndex(5)
                .testCaseData(testCaseData)
                .extractedColumns(extractedColumns)
                .executionStatus(ExecutionStatus.SUCCESS)
                .execDurationMs(1234L)
                .avgMetricEvalDurationMs(150L)
                .responseStatusCode(200)
                .metricValues(metricValues)
                .metricInfos(metricInfos)
                .build();

        EvalSummary entity = mapper.toEntity(item, testSuiteId, testSuiteRunId, computationId, 1000L, 2000L);

        assertThat(entity).isNotNull();
        assertThat(entity.getId()).isNotNull();
        assertThat(entity.getTestCaseRunResultId()).isEqualTo(testCaseRunResultId);
        assertThat(entity.getTestCaseId()).isEqualTo(testCaseId);
        assertThat(entity.getTestSuiteId()).isEqualTo(testSuiteId);
        assertThat(entity.getTestSuiteRunId()).isEqualTo(testSuiteRunId);
        assertThat(entity.getComputationId()).isEqualTo(computationId);
        assertThat(entity.getTestCaseName()).isEqualTo("test-case");
        assertThat(entity.getRunIndex()).isEqualTo(5);
        assertThat(entity.getTestCaseData()).contains("prompt");
        assertThat(entity.getExtractedColumns()).contains("col1");
        assertThat(entity.getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(entity.getExecDurationMs()).isEqualTo(1234L);
        assertThat(entity.getAvgMetricEvalDurationMs()).isEqualTo(150L);
        assertThat(entity.getResponseStatusCode()).isEqualTo(200);
        assertThat(entity.getMetricValues()).contains("Accuracy");
        assertThat(entity.getMetricInfos()).contains("version");
        assertThat(entity.getCreatedAtMs()).isEqualTo(1000L);
        assertThat(entity.getComputedAtMs()).isEqualTo(2000L);
    }

    @Test
    @DisplayName("Should set extractedColumns to empty object when null")
    void shouldDefaultExtractedColumnsWhenNull() {
        EvalSummaryBatchWriteItemDto item = buildMinimalItem();
        item.setExtractedColumns(null);

        EvalSummary entity =
                mapper.toEntity(item, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1000L, 2000L);

        assertThat(entity.getExtractedColumns()).isEqualTo("{}");
    }

    @Test
    @DisplayName("Should map entity to response DTO")
    void shouldMapEntityToResponseDto() {
        UUID entityId = UUID.randomUUID();
        UUID testSuiteId = UUID.randomUUID();
        UUID testSuiteRunId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();

        EvalSummary entity = EvalSummary.builder()
                .id(entityId)
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .testCaseRunResultId(UUID.randomUUID())
                .testCaseId(UUID.randomUUID())
                .testCaseName("mapped-case")
                .runIndex(1)
                .computationId(computationId)
                .testCaseData("{\"key\":\"value\"}")
                .extractedColumns("{}")
                .executionStatus(ExecutionStatus.FAILED)
                .execDurationMs(500L)
                .avgMetricEvalDurationMs(75L)
                .responseStatusCode(500)
                .metricValues("{\"Accuracy\":{\"score\":0.5}}")
                .createdAtMs(3000L)
                .computedAtMs(4000L)
                .build();

        EvalSummaryResponseDto dto = mapper.toDto(entity);

        assertThat(dto).isNotNull();
        assertThat(dto.getId()).isEqualTo(entityId);
        assertThat(dto.getTestSuiteId()).isEqualTo(testSuiteId);
        assertThat(dto.getTestSuiteRunId()).isEqualTo(testSuiteRunId);
        assertThat(dto.getComputationId()).isEqualTo(computationId);
        assertThat(dto.getTestCaseName()).isEqualTo("mapped-case");
        assertThat(dto.getRunIndex()).isEqualTo(1);
        assertThat(dto.getExecutionStatus()).isEqualTo("FAILED");
        assertThat(dto.getExecDurationMs()).isEqualTo(500L);
        assertThat(dto.getAvgMetricEvalDurationMs()).isEqualTo(75L);
        assertThat(dto.getResponseStatusCode()).isEqualTo(500);
        assertThat(dto.getCreatedAt()).isEqualTo(3000L);
        assertThat(dto.getComputedAt()).isEqualTo(4000L);
        assertThat(dto.getTestCaseData()).isNotNull();
        assertThat(dto.getMetricValues()).isNotNull();
    }

    @Test
    @DisplayName("Should map entity to detail DTO including metricInfos")
    void shouldMapEntityToDetailDto() {
        EvalSummary entity = EvalSummary.builder()
                .id(UUID.randomUUID())
                .testSuiteId(UUID.randomUUID())
                .testSuiteRunId(UUID.randomUUID())
                .testCaseRunResultId(UUID.randomUUID())
                .testCaseId(UUID.randomUUID())
                .testCaseName("detail-case")
                .runIndex(0)
                .computationId(UUID.randomUUID())
                .testCaseData("{\"prompt\":\"test\"}")
                .extractedColumns("{}")
                .executionStatus(ExecutionStatus.SUCCESS)
                .execDurationMs(100L)
                .avgMetricEvalDurationMs(25L)
                .metricValues("{\"Accuracy\":{\"score\":1.0}}")
                .metricInfos("{\"Accuracy\":{\"version\":\"2.0\"}}")
                .createdAtMs(5000L)
                .computedAtMs(6000L)
                .build();

        EvalSummaryDetailResponseDto dto = mapper.toDetailDto(entity);

        assertThat(dto).isNotNull();
        assertThat(dto.getTestCaseName()).isEqualTo("detail-case");
        assertThat(dto.getMetricInfos()).isNotNull();
        assertThat(dto.getMetricInfos().toString()).contains("version");
        assertThat(dto.getAvgMetricEvalDurationMs()).isEqualTo(25L);
        assertThat(dto.getCreatedAt()).isEqualTo(5000L);
        assertThat(dto.getComputedAt()).isEqualTo(6000L);
    }

    @Test
    @DisplayName("Should map executionStatus as enum name string")
    void shouldMapExecutionStatusAsString() {
        EvalSummary entity = buildMinimalEntity(ExecutionStatus.TIMEOUT);

        EvalSummaryResponseDto dto = mapper.toDto(entity);

        assertThat(dto.getExecutionStatus()).isEqualTo("TIMEOUT");
    }

    @Test
    @DisplayName("Should map createdAtMs to createdAt and computedAtMs to computedAt")
    void shouldMapTimestampFields() {
        EvalSummary entity = EvalSummary.builder()
                .id(UUID.randomUUID())
                .testSuiteId(UUID.randomUUID())
                .testSuiteRunId(UUID.randomUUID())
                .testCaseRunResultId(UUID.randomUUID())
                .testCaseId(UUID.randomUUID())
                .testCaseName("ts-case")
                .runIndex(0)
                .computationId(UUID.randomUUID())
                .testCaseData("{}")
                .extractedColumns("{}")
                .executionStatus(ExecutionStatus.SUCCESS)
                .execDurationMs(0L)
                .metricValues("{}")
                .createdAtMs(111111L)
                .computedAtMs(222222L)
                .build();

        EvalSummaryResponseDto dto = mapper.toDto(entity);

        assertThat(dto.getCreatedAt()).isEqualTo(111111L);
        assertThat(dto.getComputedAt()).isEqualTo(222222L);
    }

    @Nested
    @DisplayName("grafanaTraceUrl population")
    class GrafanaTraceUrlPopulation {

        @Test
        @DisplayName("Should populate grafanaTraceUrl on response DTO when Grafana enabled")
        void shouldPopulateGrafanaTraceUrlOnDto() {
            EvalSummaryMapperImpl mapperImpl = new EvalSummaryMapperImpl();
            setField(mapperImpl, "jacksonMapper", new JacksonMapper(OBJECT_MAPPER));
            setField(mapperImpl, "grafanaLinkBuilder", enabledGrafanaLinkBuilder());

            EvalSummary entity = buildMinimalEntity(ExecutionStatus.SUCCESS);
            entity.setCreatedAtMs(1700000000000L);
            entity.setComputedAtMs(1700000060000L);

            EvalSummaryResponseDto dto = mapperImpl.toDto(entity);

            assertThat(dto.getGrafanaTraceUrl()).isNotNull().startsWith("http://grafana:3000/explore?");
        }

        @Test
        @DisplayName("Should populate grafanaTraceUrl on detail DTO when Grafana enabled")
        void shouldPopulateGrafanaTraceUrlOnDetailDto() {
            EvalSummaryMapperImpl mapperImpl = new EvalSummaryMapperImpl();
            setField(mapperImpl, "jacksonMapper", new JacksonMapper(OBJECT_MAPPER));
            setField(mapperImpl, "grafanaLinkBuilder", enabledGrafanaLinkBuilder());

            EvalSummary entity = buildMinimalEntity(ExecutionStatus.SUCCESS);
            entity.setCreatedAtMs(1700000000000L);
            entity.setComputedAtMs(1700000060000L);

            EvalSummaryDetailResponseDto dto = mapperImpl.toDetailDto(entity);

            assertThat(dto.getGrafanaTraceUrl()).isNotNull().startsWith("http://grafana:3000/explore?");
        }

        @Test
        @DisplayName("Should leave grafanaTraceUrl null when Grafana disabled")
        void shouldLeaveNullWhenDisabled() {
            EvalSummary entity = buildMinimalEntity(ExecutionStatus.SUCCESS);

            EvalSummaryResponseDto dto = mapper.toDto(entity);

            assertThat(dto.getGrafanaTraceUrl()).isNull();
        }

        @Test
        @DisplayName("Should leave grafanaTraceUrl null on detail DTO when Grafana disabled")
        void shouldLeaveNullOnDetailWhenDisabled() {
            EvalSummary entity = buildMinimalEntity(ExecutionStatus.SUCCESS);

            EvalSummaryDetailResponseDto dto = mapper.toDetailDto(entity);

            assertThat(dto.getGrafanaTraceUrl()).isNull();
        }
    }

    // --- Helpers ---

    private EvalSummaryBatchWriteItemDto buildMinimalItem() {
        ObjectNode testCaseData = JsonNodeFactory.instance.objectNode();
        testCaseData.put("key", "value");
        ObjectNode metricValues = JsonNodeFactory.instance.objectNode();
        metricValues.putObject("M1").put("out", 1.0);

        return EvalSummaryBatchWriteItemDto.builder()
                .testCaseRunResultId(UUID.randomUUID())
                .testCaseId(UUID.randomUUID())
                .testCaseName("minimal")
                .runIndex(0)
                .testCaseData(testCaseData)
                .executionStatus(ExecutionStatus.SUCCESS)
                .execDurationMs(0L)
                .metricValues(metricValues)
                .build();
    }

    private EvalSummary buildMinimalEntity(ExecutionStatus status) {
        return EvalSummary.builder()
                .id(UUID.randomUUID())
                .testSuiteId(UUID.randomUUID())
                .testSuiteRunId(UUID.randomUUID())
                .testCaseRunResultId(UUID.randomUUID())
                .testCaseId(UUID.randomUUID())
                .testCaseName("minimal")
                .runIndex(0)
                .computationId(UUID.randomUUID())
                .testCaseData("{}")
                .extractedColumns("{}")
                .executionStatus(status)
                .execDurationMs(0L)
                .metricValues("{}")
                .createdAtMs(0L)
                .computedAtMs(0L)
                .build();
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (NoSuchFieldException | IllegalAccessException ex) {
            throw new RuntimeException("Failed to set field " + fieldName, ex);
        }
    }

    private static Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                // continue to superclass
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
