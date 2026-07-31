package com.epam.aidial.evaluation.service.domain.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import com.epam.aidial.evaluation.runner.util.ValidationWarningsSerializer;
import com.epam.aidial.evaluation.service.domain.dto.analytics.ExecutionInfoRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.TestCaseRunResultItemDto;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("TestCaseRunResultMapper")
class TestCaseRunResultMapperTest {

    private TestCaseRunResultMapper mapper;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        JacksonMapper jacksonMapper = new JacksonMapper(objectMapper);
        ValidationWarningsSerializer warningsSerializer = new ValidationWarningsSerializer(objectMapper);
        TestCaseRunResultMapperImpl mapperImpl = new TestCaseRunResultMapperImpl();
        setField(mapperImpl, "jacksonMapper", jacksonMapper);
        setField(mapperImpl, "validationWarningsSerializer", warningsSerializer);
        mapperImpl.grafanaLinkBuilder = null;
        mapper = mapperImpl;
    }

    @Test
    @DisplayName("toEntity maps all fields from TestCaseRunResultItemDto correctly")
    void shouldMapAllFieldsFromItemDto() {
        UUID testSuiteId = UUID.randomUUID();
        UUID testSuiteRunId = UUID.randomUUID();

        TestCaseRunResultItemDto item = TestCaseRunResultItemDto.builder()
                .testCaseId(UUID.randomUUID())
                .runIndex(0)
                .executionInfo(ExecutionInfoRequestDto.builder()
                        .status(ExecutionStatus.SUCCESS)
                        .startedAt(1000L)
                        .completedAt(1500L)
                        .traceId("trace-1")
                        .retryCount(2)
                        .build())
                .build();

        TestCaseRunResult entity = mapper.toEntity(item, testSuiteId, testSuiteRunId, 9000L);

        assertThat(entity.getId()).isNotNull();
        assertThat(entity.getTestSuiteId()).isEqualTo(testSuiteId);
        assertThat(entity.getTestSuiteRunId()).isEqualTo(testSuiteRunId);
        assertThat(entity.getCreatedAtMs()).isEqualTo(9000L);
        assertThat(entity.getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(entity.getExecStartedAtMs()).isEqualTo(1000L);
        assertThat(entity.getExecCompletedAtMs()).isEqualTo(1500L);
        assertThat(entity.getExecDurationMs()).isEqualTo(500L);
        assertThat(entity.getTraceId()).isEqualTo("trace-1");
        assertThat(entity.getRetryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("toEntity defaults retryCount to 0 when the item does not supply one")
    void shouldDefaultRetryCount() {
        TestCaseRunResultItemDto item = TestCaseRunResultItemDto.builder()
                .testCaseId(UUID.randomUUID())
                .runIndex(0)
                .executionInfo(ExecutionInfoRequestDto.builder()
                        .status(ExecutionStatus.SUCCESS)
                        .startedAt(0L)
                        .completedAt(0L)
                        .build())
                .build();

        TestCaseRunResult entity = mapper.toEntity(item, UUID.randomUUID(), UUID.randomUUID(), 0L);

        assertThat(entity.getRetryCount()).isEqualTo(0);
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
