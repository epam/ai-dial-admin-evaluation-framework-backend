package com.epam.aidial.evaluation.service.domain.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.RunMetricSnapshot;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunMetricSnapshotBatchWriteItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunMetricSnapshotResponseDto;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@DisplayName("RunMetricSnapshotMapper")
class RunMetricSnapshotMapperTest {

    private RunMetricSnapshotMapper mapper;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        JacksonMapper jacksonMapper = new JacksonMapper(objectMapper);
        JsonbMapper jsonbMapper = new JsonbMapper(objectMapper);
        RunMetricSnapshotMapperImpl mapperImpl = new RunMetricSnapshotMapperImpl();
        setField(mapperImpl, "jacksonMapper", jacksonMapper);
        mapperImpl.jsonbMapper = jsonbMapper;
        mapper = mapperImpl;
    }

    @Test
    @DisplayName("Should map item DTO to entity with all fields")
    void shouldMapItemDtoToEntity() {
        UUID tsmdId = UUID.randomUUID();
        UUID metricDeclarationId = UUID.randomUUID();
        UUID metricDeclarationVersionId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        UUID testSuiteRunId = UUID.randomUUID();

        var configArray = JsonNodeFactory.instance.arrayNode();
        configArray.addObject().put("param", "value");

        var inputArray = JsonNodeFactory.instance.arrayNode();
        inputArray.addObject().put("field", "input");

        ObjectNode outputSchema = JsonNodeFactory.instance.objectNode();
        outputSchema.put("type", "object");

        RunMetricSnapshotBatchWriteItemDto item = RunMetricSnapshotBatchWriteItemDto.builder()
                .tsmdId(tsmdId)
                .tsmdName("Accuracy")
                .metricDeclarationId(metricDeclarationId)
                .metricDeclarationVersionId(metricDeclarationVersionId)
                .configBindings(configArray)
                .inputBindings(inputArray)
                .outputSchema(outputSchema)
                .build();

        RunMetricSnapshot entity = mapper.toEntity(item, computationId, testSuiteRunId, 5000L);

        assertThat(entity).isNotNull();
        assertThat(entity.getId()).isNotNull();
        assertThat(entity.getTsmdId()).isEqualTo(tsmdId);
        assertThat(entity.getTsmdName()).isEqualTo("Accuracy");
        assertThat(entity.getMetricDeclarationId()).isEqualTo(metricDeclarationId);
        assertThat(entity.getMetricDeclarationVersionId()).isEqualTo(metricDeclarationVersionId);
        assertThat(entity.getComputationId()).isEqualTo(computationId);
        assertThat(entity.getTestSuiteRunId()).isEqualTo(testSuiteRunId);
        assertThat(entity.getComputedAtMs()).isEqualTo(5000L);
        assertThat(entity.getConfigBindings()).contains("param");
        assertThat(entity.getInputBindings()).contains("field");
        assertThat(entity.getOutputSchema()).contains("type");
    }

    @Test
    @DisplayName("Should set configBindings to [], inputBindings to [], outputSchema to {} when null")
    void shouldDefaultNullFields() {
        RunMetricSnapshotBatchWriteItemDto item = RunMetricSnapshotBatchWriteItemDto.builder()
                .tsmdId(UUID.randomUUID())
                .tsmdName("TestMetric")
                .metricDeclarationId(UUID.randomUUID())
                .metricDeclarationVersionId(UUID.randomUUID())
                .configBindings(null)
                .inputBindings(null)
                .outputSchema(null)
                .build();

        RunMetricSnapshot entity = mapper.toEntity(item, UUID.randomUUID(), UUID.randomUUID(), 1000L);

        assertThat(entity.getConfigBindings()).isEqualTo("[]");
        assertThat(entity.getInputBindings()).isEqualTo("[]");
        assertThat(entity.getOutputSchema()).isEqualTo("{}");
    }

    @Test
    @DisplayName("Should map entity to response DTO")
    void shouldMapEntityToResponseDto() {
        UUID entityId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        UUID testSuiteRunId = UUID.randomUUID();
        UUID tsmdId = UUID.randomUUID();
        UUID metricDeclarationId = UUID.randomUUID();
        UUID metricDeclarationVersionId = UUID.randomUUID();

        RunMetricSnapshot entity = RunMetricSnapshot.builder()
                .id(entityId)
                .computationId(computationId)
                .testSuiteRunId(testSuiteRunId)
                .tsmdId(tsmdId)
                .tsmdName("Precision")
                .metricDeclarationId(metricDeclarationId)
                .metricDeclarationVersionId(metricDeclarationVersionId)
                .configBindings("[{\"param\":\"val\"}]")
                .inputBindings("[{\"field\":\"in\"}]")
                .outputSchema("{\"type\":\"number\"}")
                .computedAtMs(7000L)
                .build();

        RunMetricSnapshotResponseDto dto = mapper.toDto(entity);

        assertThat(dto).isNotNull();
        assertThat(dto.getId()).isEqualTo(entityId);
        assertThat(dto.getComputationId()).isEqualTo(computationId);
        assertThat(dto.getTestSuiteRunId()).isEqualTo(testSuiteRunId);
        assertThat(dto.getTsmdId()).isEqualTo(tsmdId);
        assertThat(dto.getTsmdName()).isEqualTo("Precision");
        assertThat(dto.getMetricDeclarationId()).isEqualTo(metricDeclarationId);
        assertThat(dto.getMetricDeclarationVersionId()).isEqualTo(metricDeclarationVersionId);
        assertThat(dto.getComputedAtMs()).isEqualTo(7000L);
        assertThat(dto.getConfigBindings()).isNotNull();
        assertThat(dto.getInputBindings()).isNotNull();
    }

    @Test
    @DisplayName("Should map outputSchema String to Map via JsonbMapper")
    void shouldMapOutputSchemaToMap() {
        RunMetricSnapshot entity = RunMetricSnapshot.builder()
                .id(UUID.randomUUID())
                .computationId(UUID.randomUUID())
                .testSuiteRunId(UUID.randomUUID())
                .tsmdId(UUID.randomUUID())
                .tsmdName("Schema")
                .metricDeclarationId(UUID.randomUUID())
                .metricDeclarationVersionId(UUID.randomUUID())
                .configBindings("[]")
                .inputBindings("[]")
                .outputSchema("{\"type\":\"object\",\"properties\":{\"score\":{\"type\":\"number\"}}}")
                .computedAtMs(8000L)
                .build();

        RunMetricSnapshotResponseDto dto = mapper.toDto(entity);

        assertThat(dto.getOutputSchema()).isNotNull();
        assertThat(dto.getOutputSchema()).containsKey("type");
        assertThat(dto.getOutputSchema().get("type")).isEqualTo("object");
        assertThat(dto.getOutputSchema()).containsKey("properties");
    }

    // --- Helpers ---

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (NoSuchFieldException | IllegalAccessException ex) {
            throw new RuntimeException("Failed to set field " + fieldName, ex);
        }
    }
}
