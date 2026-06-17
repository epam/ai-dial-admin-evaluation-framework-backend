package com.epam.aidial.evaluation.experimental.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.AggregatedMetricDefinition;
import com.epam.aidial.evaluation.experimental.query.service.dto.QueryEntityDto;
import com.epam.aidial.evaluation.experimental.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.experimental.query.service.dto.QuerySchemaFieldDto;
import com.epam.aidial.evaluation.service.domain.DatasetSchemaProvider;
import com.epam.aidial.evaluation.service.domain.OutputSchemaFieldExtractor;
import com.epam.aidial.evaluation.service.domain.TestSuiteMetricDefinitionService;
import com.epam.aidial.evaluation.service.domain.TestSuiteService;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class EvalSummariesSchemaProviderTest {

    private static final UUID SUITE_ID = UUID.fromString("3f1c1a39-9c1b-4c11-8b3e-2a4e2c3d4e5f");
    private static final UUID DATASET_ID = UUID.fromString("7a2b3c4d-5e6f-4a1b-9c8d-1e2f3a4b5c6d");

    @Mock
    private TestSuiteService testSuiteService;

    @Mock
    private DatasetSchemaProvider datasetSchemaProvider;

    @Mock
    private TestSuiteMetricDefinitionService testSuiteMetricDefinitionService;

    private final OutputSchemaFieldExtractor outputSchemaFieldExtractor =
            new OutputSchemaFieldExtractor(new ObjectMapper());

    private EvalSummariesSchemaProvider provider;

    @Test
    @DisplayName("describes eval_summaries as a complex entity keyed by test_suite_id")
    void shouldDescribeComplexEntity() {
        createProvider();

        assertThat(provider.descriptor()).isEqualTo(new QueryEntityDto("eval_summaries", true, "test_suite_id"));
    }

    @Test
    @DisplayName("base schema lists plain columns and JSONB fields as-is")
    void shouldExposeBaseSchemaWithJsonbFieldsAsIs() {
        createProvider();

        List<QuerySchemaFieldDto> fields = provider.baseSchema();

        assertThat(fields)
                .contains(
                        new QuerySchemaFieldDto("test_suite_id", QueryFieldType.UUID, "test_suite_id"),
                        new QuerySchemaFieldDto("execution_status", QueryFieldType.STRING, "execution_status"),
                        new QuerySchemaFieldDto("test_case_data", QueryFieldType.OBJECT, "test_case_data"),
                        new QuerySchemaFieldDto("metric_values", QueryFieldType.OBJECT, "metric_values"),
                        new QuerySchemaFieldDto("metric_infos", QueryFieldType.OBJECT, "metric_infos"),
                        new QuerySchemaFieldDto("extraction_warnings", QueryFieldType.ARRAY, "extraction_warnings"))
                .allSatisfy(field -> assertThat(field.source()).isEqualTo(field.name()));
    }

    @Test
    @DisplayName("detailed schema flattens dataset, response-column, and metric fields from current suite state")
    void shouldFlattenDetailedSchemaFromCurrentSuiteState() {
        createProvider();
        TestSuiteResponseDto suite = new TestSuiteResponseDto();
        suite.setId(SUITE_ID);
        suite.setDatasetId(DATASET_ID);
        suite.setResponseColumns(List.of(responseColumn("answer", SchemaFieldType.STRING)));
        when(testSuiteService.getById(SUITE_ID)).thenReturn(suite);
        when(datasetSchemaProvider.getSchema(DATASET_ID))
                .thenReturn(List.of(
                        fieldDefinition("question", SchemaFieldType.STRING),
                        fieldDefinition("expectedScore", SchemaFieldType.NUMBER)));
        when(testSuiteMetricDefinitionService.findAllEnabledAndValidAggregatedByTestSuiteId(SUITE_ID))
                .thenReturn(List.of(aggregatedDefinition("Accuracy", """
                        {"properties": {"score": {"type": "number"}, "explanation": {"type": "string"}}}
                        """)));

        List<QuerySchemaFieldDto> fields =
                provider.detailedSchema(Map.of(EvalSummariesSchemaProvider.SCHEMA_ID_FIELD, SUITE_ID.toString()));

        assertThat(fields)
                .contains(
                        new QuerySchemaFieldDto("test_suite_run_id", QueryFieldType.UUID, "test_suite_run_id"),
                        new QuerySchemaFieldDto("data:question", QueryFieldType.STRING, "test_case_data"),
                        new QuerySchemaFieldDto("data:expectedScore", QueryFieldType.DECIMAL, "test_case_data"),
                        new QuerySchemaFieldDto("response:answer", QueryFieldType.STRING, "extracted_columns"),
                        new QuerySchemaFieldDto("metric:Accuracy:score", QueryFieldType.DECIMAL, "metric_values"),
                        new QuerySchemaFieldDto("metric:Accuracy:explanation", QueryFieldType.DECIMAL, "metric_values"),
                        new QuerySchemaFieldDto("metricInfo:Accuracy", QueryFieldType.OBJECT, "metric_infos"))
                .noneMatch(field -> field.name().equals("test_case_data"))
                .noneMatch(field -> field.name().equals("extracted_columns"))
                .noneMatch(field -> field.name().equals("metric_values"))
                .noneMatch(field -> field.name().equals("metric_infos"));
    }

    @Test
    @DisplayName("detailed schema omits data fields when the suite has no bound dataset")
    void shouldOmitDataFields_whenSuiteHasNoDataset() {
        createProvider();
        TestSuiteResponseDto suite = new TestSuiteResponseDto();
        suite.setId(SUITE_ID);
        suite.setDatasetId(null);
        suite.setResponseColumns(List.of());
        when(testSuiteService.getById(SUITE_ID)).thenReturn(suite);
        when(testSuiteMetricDefinitionService.findAllEnabledAndValidAggregatedByTestSuiteId(SUITE_ID))
                .thenReturn(List.of());

        List<QuerySchemaFieldDto> fields =
                provider.detailedSchema(Map.of(EvalSummariesSchemaProvider.SCHEMA_ID_FIELD, SUITE_ID.toString()));

        assertThat(fields).noneMatch(field -> field.name().startsWith("data:"));
    }

    @Test
    @DisplayName("rejects a malformed suite id with a validation error")
    void shouldThrowValidation_whenSuiteIdMalformed() {
        createProvider();

        assertThatThrownBy(() ->
                        provider.detailedSchema(Map.of(EvalSummariesSchemaProvider.SCHEMA_ID_FIELD, "not-a-uuid")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("test suite UUID");
    }

    @Test
    @DisplayName("propagates not-found when the suite does not exist")
    void shouldPropagateNotFound_whenSuiteMissing() {
        createProvider();
        when(testSuiteService.getById(SUITE_ID)).thenThrow(new EntityNotFoundException("TestSuite not found"));

        assertThatThrownBy(() -> provider.detailedSchema(
                        Map.of(EvalSummariesSchemaProvider.SCHEMA_ID_FIELD, SUITE_ID.toString())))
                .isInstanceOf(EntityNotFoundException.class);
    }

    private void createProvider() {
        provider = new EvalSummariesSchemaProvider(
                testSuiteService,
                datasetSchemaProvider,
                testSuiteMetricDefinitionService,
                outputSchemaFieldExtractor,
                new JooqTableSchemaResolver());
    }

    private static FieldDefinitionDto fieldDefinition(String name, SchemaFieldType type) {
        FieldDefinitionDto field = new FieldDefinitionDto();
        field.setName(name);
        field.setType(type);
        return field;
    }

    private static ResponseColumnDefinitionDto responseColumn(String name, SchemaFieldType type) {
        return ResponseColumnDefinitionDto.builder()
                .name(name)
                .expression("response." + name)
                .type(type)
                .build();
    }

    private static AggregatedMetricDefinition aggregatedDefinition(String name, String outputSchema) {
        return AggregatedMetricDefinition.builder()
                .name(name)
                .versionOutputSchema(outputSchema)
                .build();
    }
}
