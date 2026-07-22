package com.epam.aidial.evaluation.service.domain.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.service.domain.ResponseColumnExtractor;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalResultsImportItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.ExecutionInfoRequestDto;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

@DisplayName("TestCaseRunResultMapper")
class TestCaseRunResultMapperTest {

    private TestCaseRunResultMapper mapper;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        JacksonMapper jacksonMapper = new JacksonMapper(objectMapper);
        TestCaseRunResultMapperImpl mapperImpl = new TestCaseRunResultMapperImpl();
        setField(mapperImpl, "jacksonMapper", jacksonMapper);
        mapperImpl.grafanaLinkBuilder = null;
        mapper = mapperImpl;
    }

    @Test
    @DisplayName("Should source testCaseId/testCaseName/testCaseData straight from the item, caller-trusted")
    void shouldSourceTestCaseFieldsFromItem() {
        UUID testCaseId = UUID.randomUUID();
        UUID testSuiteId = UUID.randomUUID();
        UUID testSuiteRunId = UUID.randomUUID();

        EvalResultsImportItemDto item = EvalResultsImportItemDto.builder()
                .testCaseId(testCaseId)
                .testCaseName("caller-supplied-name")
                .runIndex(0)
                .testCaseData(JsonNodeFactory.instance.objectNode().put("question", "caller-data"))
                .responseBody(JsonNodeFactory.instance.objectNode().put("answer", "hi"))
                .responseStatusCode(200)
                .executionInfo(ExecutionInfoRequestDto.builder()
                        .status(ExecutionStatus.SUCCESS)
                        .startedAt(1000L)
                        .completedAt(1500L)
                        .traceId("trace-1")
                        .retryCount(2)
                        .build())
                .build();

        ResponseColumnExtractor.ExtractionResult extraction =
                new ResponseColumnExtractor.ExtractionResult("{\"answer\":\"hi\"}", "[]");

        TestCaseRunResult entity = mapper.toEntity(item, testSuiteId, testSuiteRunId, 9000L, extraction);

        assertThat(entity.getId()).isNotNull();
        assertThat(entity.getTestCaseId()).isEqualTo(testCaseId);
        assertThat(entity.getTestCaseName()).isEqualTo("caller-supplied-name");
        assertThat(entity.getTestCaseData()).isEqualTo("{\"question\":\"caller-data\"}");
        assertThat(entity.getTestSuiteId()).isEqualTo(testSuiteId);
        assertThat(entity.getTestSuiteRunId()).isEqualTo(testSuiteRunId);
        assertThat(entity.getResponseStatusCode()).isEqualTo(200);
        assertThat(entity.getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(entity.getExecStartedAtMs()).isEqualTo(1000L);
        assertThat(entity.getExecCompletedAtMs()).isEqualTo(1500L);
        assertThat(entity.getExecDurationMs()).isEqualTo(500L);
        assertThat(entity.getTraceId()).isEqualTo("trace-1");
        assertThat(entity.getRetryCount()).isEqualTo(2);
        assertThat(entity.getExtractedColumns()).isEqualTo("{\"answer\":\"hi\"}");
        assertThat(entity.getExtractionWarnings()).isEqualTo("[]");
    }

    @Test
    @DisplayName("Should synthesize a non-null testCaseId when the item only supplies testCaseName")
    void shouldSynthesizeTestCaseIdWhenNameOnly() {
        EvalResultsImportItemDto item = EvalResultsImportItemDto.builder()
                .testCaseName("name-only")
                .runIndex(0)
                .testCaseData(JsonNodeFactory.instance.objectNode())
                .executionInfo(ExecutionInfoRequestDto.builder()
                        .status(ExecutionStatus.SUCCESS)
                        .startedAt(0L)
                        .completedAt(0L)
                        .build())
                .build();

        ResponseColumnExtractor.ExtractionResult extraction = new ResponseColumnExtractor.ExtractionResult("{}", "[]");

        TestCaseRunResult entity1 = mapper.toEntity(item, UUID.randomUUID(), UUID.randomUUID(), 0L, extraction);
        TestCaseRunResult entity2 = mapper.toEntity(item, UUID.randomUUID(), UUID.randomUUID(), 0L, extraction);

        assertThat(entity1.getTestCaseId()).isNotNull();
        assertThat(entity2.getTestCaseId()).isNotNull();
        assertThat(entity1.getTestCaseId()).isNotEqualTo(entity2.getTestCaseId());
        assertThat(entity1.getTestCaseName()).isEqualTo("name-only");
    }

    @Test
    @DisplayName("Should default retryCount to 0 when the item does not supply one")
    void shouldDefaultRetryCount() {
        EvalResultsImportItemDto item = EvalResultsImportItemDto.builder()
                .testCaseName("tc")
                .runIndex(0)
                .testCaseData(JsonNodeFactory.instance.objectNode())
                .executionInfo(ExecutionInfoRequestDto.builder()
                        .status(ExecutionStatus.SUCCESS)
                        .startedAt(0L)
                        .completedAt(0L)
                        .build())
                .build();

        ResponseColumnExtractor.ExtractionResult extraction = new ResponseColumnExtractor.ExtractionResult("{}", "[]");

        TestCaseRunResult entity = mapper.toEntity(item, UUID.randomUUID(), UUID.randomUUID(), 0L, extraction);

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
