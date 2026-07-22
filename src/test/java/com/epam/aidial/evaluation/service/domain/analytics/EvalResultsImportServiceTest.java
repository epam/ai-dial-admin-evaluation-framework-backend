package com.epam.aidial.evaluation.service.domain.analytics;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.properties.analytics.AnalyticsResultsProperties;
import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseRunResultRepository;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.service.domain.DatasetSchemaProvider;
import com.epam.aidial.evaluation.service.domain.ResponseColumnExtractor;
import com.epam.aidial.evaluation.service.domain.SchemaValidationService;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalResultsImportItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.ExecutionInfoRequestDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.mapper.JacksonMapper;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import com.epam.aidial.evaluation.service.domain.mapper.TestCaseRunResultMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

@DisplayName("EvalResultsImportService")
@ExtendWith(MockitoExtension.class)
class EvalResultsImportServiceTest {

    @Mock
    private ResponseColumnExtractor responseColumnExtractor;

    @Mock
    private TestCaseRunResultMapper resultMapper;

    @Mock
    private TestCaseRunResultRepository resultRepository;

    @Mock
    private JacksonMapper jacksonMapper;

    @Mock
    private JsonbMapper jsonbMapper;

    @Mock
    private SchemaValidationService schemaValidationService;

    @Mock
    private DatasetSchemaProvider datasetSchemaProvider;

    private EvalResultsImportService service;
    private UUID datasetId;

    @BeforeEach
    void setUp() {
        AnalyticsResultsProperties.Batch batch = new AnalyticsResultsProperties.Batch();
        batch.setMaxItems(1000);
        AnalyticsResultsProperties analyticsResultsProperties = new AnalyticsResultsProperties();
        analyticsResultsProperties.setBatch(batch);

        service = new EvalResultsImportService(
                responseColumnExtractor,
                resultMapper,
                resultRepository,
                jacksonMapper,
                jsonbMapper,
                analyticsResultsProperties,
                schemaValidationService,
                datasetSchemaProvider,
                new ObjectMapper());

        datasetId = UUID.randomUUID();
    }

    private EvalResultsImportItemDto.EvalResultsImportItemDtoBuilder itemBuilder(String testCaseName) {
        return EvalResultsImportItemDto.builder()
                .testCaseName(testCaseName)
                .runIndex(0)
                .testCaseData(JsonNodeFactory.instance.objectNode().put("expected", "answer"))
                .executionInfo(ExecutionInfoRequestDto.builder()
                        .status(ExecutionStatus.SUCCESS)
                        .startedAt(1000L)
                        .completedAt(1500L)
                        .build());
    }

    @Test
    @DisplayName(
            "Should extract response columns per item using the suite's responseColumns and persist the mapped entities")
    void shouldExtractAndPersistPerItem() {
        UUID testSuiteId = UUID.randomUUID();
        TestSuiteRun run =
                TestSuiteRun.builder().id(UUID.randomUUID()).createdAt(1000L).build();

        String responseColumnsJson = "[{\"name\":\"answer\",\"expression\":\"$.answer\"}]";
        List<ResponseColumnDefinitionDto> responseColumns = List.of(ResponseColumnDefinitionDto.builder()
                .name("answer")
                .expression("$.answer")
                .build());
        when(jsonbMapper.mapResponseColumns(responseColumnsJson)).thenReturn(responseColumns);

        JsonNode responseBody1 = JsonNodeFactory.instance.objectNode().put("answer", "a1");
        JsonNode responseBody2 = JsonNodeFactory.instance.objectNode().put("answer", "a2");
        JsonNode testCaseData = JsonNodeFactory.instance.objectNode();

        EvalResultsImportItemDto item1 = EvalResultsImportItemDto.builder()
                .testCaseName("tc1")
                .testCaseData(testCaseData)
                .responseBody(responseBody1)
                .runIndex(0)
                .build();
        EvalResultsImportItemDto item2 = EvalResultsImportItemDto.builder()
                .testCaseName("tc2")
                .testCaseData(testCaseData)
                .responseBody(responseBody2)
                .runIndex(1)
                .build();

        when(jacksonMapper.asString(responseBody1)).thenReturn("{\"answer\":\"a1\"}");
        when(jacksonMapper.asString(responseBody2)).thenReturn("{\"answer\":\"a2\"}");

        ResponseColumnExtractor.ExtractionResult extraction1 =
                new ResponseColumnExtractor.ExtractionResult("{\"answer\":\"a1\"}", "[]");
        ResponseColumnExtractor.ExtractionResult extraction2 =
                new ResponseColumnExtractor.ExtractionResult("{\"answer\":\"a2\"}", "[]");
        when(responseColumnExtractor.extract(responseColumns, "{\"answer\":\"a1\"}"))
                .thenReturn(extraction1);
        when(responseColumnExtractor.extract(responseColumns, "{\"answer\":\"a2\"}"))
                .thenReturn(extraction2);

        TestCaseRunResult entity1 =
                TestCaseRunResult.builder().testCaseName("tc1").build();
        TestCaseRunResult entity2 =
                TestCaseRunResult.builder().testCaseName("tc2").build();
        when(resultMapper.toEntity(item1, testSuiteId, run.getId(), run.getCreatedAt(), extraction1))
                .thenReturn(entity1);
        when(resultMapper.toEntity(item2, testSuiteId, run.getId(), run.getCreatedAt(), extraction2))
                .thenReturn(entity2);

        service.persistResults(testSuiteId, run, List.of(item1, item2), responseColumnsJson);

        verify(responseColumnExtractor).extract(responseColumns, "{\"answer\":\"a1\"}");
        verify(responseColumnExtractor).extract(responseColumns, "{\"answer\":\"a2\"}");
        verify(resultRepository).saveAll(List.of(entity1, entity2));
    }

    @Nested
    @DisplayName("validateBatch")
    class ValidateBatchTests {

        @BeforeEach
        void noDatasetSchema() {
            lenient().when(datasetSchemaProvider.getSchema(datasetId)).thenReturn(List.of());
        }

        @Test
        @DisplayName("throws ValidationException when results batch is empty")
        void throwsWhenBatchEmpty() {
            assertThatThrownBy(() -> service.validateBatch(datasetId, List.of()))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("throws ValidationException when batch size exceeds the configured max")
        void throwsWhenBatchTooLarge() {
            AnalyticsResultsProperties.Batch batch = new AnalyticsResultsProperties.Batch();
            batch.setMaxItems(1);
            AnalyticsResultsProperties props = new AnalyticsResultsProperties();
            props.setBatch(batch);
            EvalResultsImportService smallBatchService = new EvalResultsImportService(
                    responseColumnExtractor,
                    resultMapper,
                    resultRepository,
                    jacksonMapper,
                    jsonbMapper,
                    props,
                    schemaValidationService,
                    datasetSchemaProvider,
                    new ObjectMapper());

            List<EvalResultsImportItemDto> results =
                    List.of(itemBuilder("tc1").build(), itemBuilder("tc2").build());

            assertThatThrownBy(() -> smallBatchService.validateBatch(datasetId, results))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("throws ValidationException on duplicate (testCaseName, runIndex) within the batch")
        void throwsOnDuplicateWithinBatch() {
            EvalResultsImportItemDto item = itemBuilder("tc1").build();

            assertThatThrownBy(() -> service.validateBatch(datasetId, List.of(item, item)))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("throws ValidationException when completedAt is before startedAt")
        void throwsWhenCompletedBeforeStarted() {
            EvalResultsImportItemDto item = itemBuilder("tc1")
                    .executionInfo(ExecutionInfoRequestDto.builder()
                            .status(ExecutionStatus.SUCCESS)
                            .startedAt(2000L)
                            .completedAt(1000L)
                            .build())
                    .build();

            assertThatThrownBy(() -> service.validateBatch(datasetId, List.of(item)))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("throws ValidationException when testCaseData is not a JSON object")
        void throwsWhenTestCaseDataNotAnObject() {
            EvalResultsImportItemDto item = itemBuilder("tc1")
                    .testCaseData(JsonNodeFactory.instance.stringNode("not-an-object"))
                    .build();

            assertThatThrownBy(() -> service.validateBatch(datasetId, List.of(item)))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("throws ValidationException when testCaseId and testCaseName are both missing")
        void throwsWhenIdentityMissing() {
            EvalResultsImportItemDto item = itemBuilder(null).build();

            assertThatThrownBy(() -> service.validateBatch(datasetId, List.of(item)))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("throws ValidationException when testCaseData violates the dataset schema")
        void throwsWhenSchemaViolated() {
            List<FieldDefinitionDto> schema = List.of(FieldDefinitionDto.builder()
                    .name("question")
                    .type(SchemaFieldType.STRING)
                    .required(true)
                    .build());
            when(datasetSchemaProvider.getSchema(datasetId)).thenReturn(schema);
            when(schemaValidationService.validate(any(), any()))
                    .thenReturn(ValidationResult.builder()
                            .valid(false)
                            .warnings(List.of(ValidationWarningDto.builder()
                                    .path("$.question")
                                    .message("required property 'question' not found")
                                    .build()))
                            .build());

            EvalResultsImportItemDto item = itemBuilder("tc1").build();

            assertThatThrownBy(() -> service.validateBatch(datasetId, List.of(item)))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("testCaseData validation failed")
                    .hasMessageContaining("tc1");
        }

        @Test
        @DisplayName("does not throw when testCaseData satisfies the dataset schema")
        void doesNotThrowWhenSchemaSatisfied() {
            List<FieldDefinitionDto> schema = List.of(FieldDefinitionDto.builder()
                    .name("expected")
                    .type(SchemaFieldType.STRING)
                    .required(true)
                    .build());
            when(datasetSchemaProvider.getSchema(datasetId)).thenReturn(schema);
            when(schemaValidationService.validate(any(), any()))
                    .thenReturn(ValidationResult.builder()
                            .valid(true)
                            .warnings(List.of())
                            .build());

            EvalResultsImportItemDto item = itemBuilder("tc1").build();

            service.validateBatch(datasetId, List.of(item));
        }
    }
}
