package com.epam.aidial.evaluation.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.analytics.model.RunMetricSnapshot;
import com.epam.aidial.evaluation.data.db.analytics.repository.RunMetricSnapshotRepository;
import com.epam.aidial.evaluation.query.service.dto.QueryEntityDto;
import com.epam.aidial.evaluation.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.query.service.dto.QuerySchemaFieldDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.SuiteSnapshotDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.service.domain.OutputSchemaFieldExtractor;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunService;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class EvalSummariesSchemaProviderTest {

    private static final UUID RUN_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SUITE_ID = UUID.fromString("3f1c1a39-9c1b-4c11-8b3e-2a4e2c3d4e5f");
    private static final UUID COMPUTATION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static final String ACCURACY_OUTPUT_SCHEMA = """
            {"properties": {"score": {"type": "number"}, "explanation": {"type": "string"}}}
            """;

    @Mock
    private TestSuiteRunService testSuiteRunService;

    @Mock
    private RunMetricSnapshotRepository runMetricSnapshotRepository;

    private final OutputSchemaFieldExtractor outputSchemaFieldExtractor =
            new OutputSchemaFieldExtractor(new ObjectMapper());

    private EvalSummariesSchemaProvider provider;

    @Test
    @DisplayName("describes eval_summaries as a complex entity keyed by test_suite_run_id")
    void shouldDescribeComplexEntity() {
        createProvider();

        assertThat(provider.descriptor()).isEqualTo(new QueryEntityDto("eval_summaries", true, "test_suite_run_id"));
    }

    @Test
    @DisplayName("base schema lists plain columns and JSONB fields as-is")
    void shouldExposeBaseSchemaWithJsonbFieldsAsIs() {
        createProvider();

        List<QuerySchemaFieldDto> fields = provider.baseSchema();

        assertThat(fields)
                .contains(
                        new QuerySchemaFieldDto("test_suite_run_id", QueryFieldType.UUID, "test_suite_run_id"),
                        new QuerySchemaFieldDto("execution_status", QueryFieldType.STRING, "execution_status"),
                        new QuerySchemaFieldDto("test_case_data", QueryFieldType.OBJECT, "test_case_data"),
                        new QuerySchemaFieldDto("metric_values", QueryFieldType.OBJECT, "metric_values"),
                        new QuerySchemaFieldDto("metric_infos", QueryFieldType.OBJECT, "metric_infos"),
                        new QuerySchemaFieldDto("extraction_warnings", QueryFieldType.ARRAY, "extraction_warnings"))
                .allSatisfy(field -> assertThat(field.source()).isEqualTo(field.name()));
    }

    @Test
    @DisplayName("detailed schema flattens dataset, response-column, and metric fields from the run snapshot")
    void shouldFlattenDetailedSchemaFromRunSnapshot() {
        createProvider();
        when(testSuiteRunService.getRun(RUN_ID)).thenReturn(runWithSnapshot(fullSnapshot()));
        when(runMetricSnapshotRepository.findLatestComputationId(RUN_ID)).thenReturn(Optional.of(COMPUTATION_ID));
        when(runMetricSnapshotRepository.findByRunIdAndComputationId(RUN_ID, COMPUTATION_ID))
                .thenReturn(List.of(metricSnapshot("Accuracy", ACCURACY_OUTPUT_SCHEMA)));

        List<QuerySchemaFieldDto> fields =
                provider.detailedSchema(Map.of(EvalSummariesSchemaProvider.RUN_ID_FIELD, RUN_ID.toString()));

        assertThat(fields)
                .contains(
                        new QuerySchemaFieldDto("test_suite_run_id", QueryFieldType.UUID, "test_suite_run_id"),
                        new QuerySchemaFieldDto("data::question", QueryFieldType.STRING, "test_case_data"),
                        new QuerySchemaFieldDto("data::expectedScore", QueryFieldType.DECIMAL, "test_case_data"),
                        new QuerySchemaFieldDto("response::answer", QueryFieldType.STRING, "extracted_columns"),
                        new QuerySchemaFieldDto("metric::Accuracy::score", QueryFieldType.DECIMAL, "metric_values"),
                        new QuerySchemaFieldDto(
                                "metric::Accuracy::explanation", QueryFieldType.DECIMAL, "metric_values"),
                        new QuerySchemaFieldDto("metricInfo::Accuracy", QueryFieldType.OBJECT, "metric_infos"))
                .noneMatch(field -> field.name().equals("test_case_data"))
                .noneMatch(field -> field.name().equals("extracted_columns"))
                .noneMatch(field -> field.name().equals("metric_values"))
                .noneMatch(field -> field.name().equals("metric_infos"));
    }

    @Test
    @DisplayName("detailed schema resolves the suite's latest run when only test_suite_id is given")
    void shouldResolveLatestRun_whenSuiteIdGiven() {
        createProvider();
        when(testSuiteRunService.getLatestRun(SUITE_ID)).thenReturn(runWithSnapshot(fullSnapshot()));
        when(runMetricSnapshotRepository.findLatestComputationId(RUN_ID)).thenReturn(Optional.of(COMPUTATION_ID));
        when(runMetricSnapshotRepository.findByRunIdAndComputationId(RUN_ID, COMPUTATION_ID))
                .thenReturn(List.of(metricSnapshot("Accuracy", ACCURACY_OUTPUT_SCHEMA)));

        List<QuerySchemaFieldDto> fields =
                provider.detailedSchema(Map.of(EvalSummariesSchemaProvider.SUITE_ID_FIELD, SUITE_ID.toString()));

        assertThat(fields)
                .contains(
                        new QuerySchemaFieldDto("data::question", QueryFieldType.STRING, "test_case_data"),
                        new QuerySchemaFieldDto("response::answer", QueryFieldType.STRING, "extracted_columns"),
                        new QuerySchemaFieldDto("metric::Accuracy::score", QueryFieldType.DECIMAL, "metric_values"));
    }

    @Test
    @DisplayName("detailed schema omits data fields when the run snapshot has no test-case schema")
    void shouldOmitDataFields_whenSnapshotHasNoTestCaseSchema() {
        createProvider();
        SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder()
                .snapshotVersion(SuiteSnapshotDto.CURRENT_VERSION)
                .responseColumns(List.of())
                .build();
        when(testSuiteRunService.getRun(RUN_ID)).thenReturn(runWithSnapshot(snapshot));
        when(runMetricSnapshotRepository.findLatestComputationId(RUN_ID)).thenReturn(Optional.empty());

        List<QuerySchemaFieldDto> fields =
                provider.detailedSchema(Map.of(EvalSummariesSchemaProvider.RUN_ID_FIELD, RUN_ID.toString()));

        assertThat(fields).noneMatch(field -> field.name().startsWith("data::"));
    }

    @Test
    @DisplayName("rejects a run with no suite snapshot with a validation error")
    void shouldThrowValidation_whenSnapshotMissing() {
        createProvider();
        when(testSuiteRunService.getRun(RUN_ID)).thenReturn(runWithSnapshot(null));

        assertThatThrownBy(() ->
                        provider.detailedSchema(Map.of(EvalSummariesSchemaProvider.RUN_ID_FIELD, RUN_ID.toString())))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no suite snapshot");
    }

    @Test
    @DisplayName("rejects a request that supplies neither a run id nor a suite id")
    void shouldThrowValidation_whenNoIdProvided() {
        createProvider();

        assertThatThrownBy(() -> provider.detailedSchema(Map.of()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("test_suite_run_id");
    }

    @Test
    @DisplayName("rejects a malformed run id with a validation error")
    void shouldThrowValidation_whenRunIdMalformed() {
        createProvider();

        assertThatThrownBy(
                        () -> provider.detailedSchema(Map.of(EvalSummariesSchemaProvider.RUN_ID_FIELD, "not-a-uuid")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("must be a UUID");
    }

    @Test
    @DisplayName("propagates not-found when the run does not exist")
    void shouldPropagateNotFound_whenRunMissing() {
        createProvider();
        when(testSuiteRunService.getRun(RUN_ID)).thenThrow(new EntityNotFoundException("TestSuiteRun not found"));

        assertThatThrownBy(() ->
                        provider.detailedSchema(Map.of(EvalSummariesSchemaProvider.RUN_ID_FIELD, RUN_ID.toString())))
                .isInstanceOf(EntityNotFoundException.class);
    }

    private void createProvider() {
        provider = new EvalSummariesSchemaProvider(
                testSuiteRunService,
                runMetricSnapshotRepository,
                outputSchemaFieldExtractor,
                new SchemaFieldTypeMapper(),
                new JooqTableSchemaResolver());
    }

    private static TestSuiteRunResponseDto runWithSnapshot(SuiteSnapshotDto snapshot) {
        return TestSuiteRunResponseDto.builder()
                .id(RUN_ID)
                .testSuiteId(SUITE_ID)
                .suiteSnapshot(snapshot)
                .build();
    }

    private static SuiteSnapshotDto fullSnapshot() {
        return SuiteSnapshotDto.builder()
                .snapshotVersion(SuiteSnapshotDto.CURRENT_VERSION)
                .testCaseSchema(List.of(
                        fieldDefinition("question", SchemaFieldType.STRING),
                        fieldDefinition("expectedScore", SchemaFieldType.NUMBER)))
                .responseColumns(List.of(responseColumn("answer", SchemaFieldType.STRING)))
                .build();
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

    private static RunMetricSnapshot metricSnapshot(String name, String outputSchema) {
        return RunMetricSnapshot.builder()
                .computationId(COMPUTATION_ID)
                .testSuiteRunId(RUN_ID)
                .tsmdName(name)
                .outputSchema(outputSchema)
                .build();
    }
}
