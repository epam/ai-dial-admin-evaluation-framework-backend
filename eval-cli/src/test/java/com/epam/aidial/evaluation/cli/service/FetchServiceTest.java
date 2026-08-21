package com.epam.aidial.evaluation.cli.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.cli.client.source.DatasetApiClient;
import com.epam.aidial.evaluation.cli.client.source.TestCaseApiClient;
import com.epam.aidial.evaluation.cli.client.source.TestSuiteApiClient;
import com.epam.aidial.evaluation.cli.config.properties.EvalCliProperties;
import com.epam.aidial.evaluation.cli.model.SuiteFetchBundle;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class FetchServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private TestSuiteApiClient testSuiteApiClient;

    @Mock
    private TestCaseApiClient testCaseApiClient;

    @Mock
    private DatasetApiClient datasetApiClient;

    @Mock
    private EvalCliProperties cliProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private FetchService fetchService;

    @BeforeEach
    void setUp() {
        fetchService =
                new FetchService(testSuiteApiClient, testCaseApiClient, datasetApiClient, cliProperties, objectMapper);
        when(cliProperties.getWorkDir()).thenReturn(tempDir.toString());
    }

    @Test
    @DisplayName("fetch retrieves and persists the dataset's test-case schema with perTurn intact")
    void fetchRetrievesAndPersistsSchemaWithPerTurnIntact() {
        final UUID sourceSuiteId = UUID.randomUUID();
        final UUID destinationSuiteId = UUID.randomUUID();
        final UUID datasetId = UUID.randomUUID();

        final TestSuiteResponseDto suite = TestSuiteResponseDto.builder()
                .id(sourceSuiteId)
                .name("Suite")
                .datasetId(datasetId)
                .build();
        final TestCaseResponseDto testCase = TestCaseResponseDto.builder()
                .id(UUID.randomUUID())
                .testCaseName("TC1")
                .build();
        final FieldDefinitionDto sharedField = FieldDefinitionDto.builder()
                .name("prompt")
                .type(SchemaFieldType.STRING)
                .build();
        final FieldDefinitionDto perTurnField = FieldDefinitionDto.builder()
                .name("turnPrompt")
                .type(SchemaFieldType.STRING)
                .perTurn(true)
                .build();

        when(testSuiteApiClient.findById(sourceSuiteId)).thenReturn(Optional.of(suite));
        when(testCaseApiClient.fetchAll(datasetId)).thenReturn(List.of(testCase));
        when(datasetApiClient.fetchTestCaseSchema(datasetId)).thenReturn(List.of(sharedField, perTurnField));

        final SuiteFetchBundle bundle = fetchService.fetch(sourceSuiteId, destinationSuiteId);

        assertThat(bundle.getTestCaseSchema()).hasSize(2);
        assertThat(bundle.getTestCaseSchema().get(1).getPerTurn()).isTrue();

        // Persisted bundle round-trips with perTurn intact
        final SuiteFetchBundle reloaded = fetchService.load(sourceSuiteId);
        assertThat(reloaded.getTestCaseSchema()).hasSize(2);
        assertThat(reloaded.getTestCaseSchema().get(0).getPerTurn()).isNull();
        assertThat(reloaded.getTestCaseSchema().get(1).getName()).isEqualTo("turnPrompt");
        assertThat(reloaded.getTestCaseSchema().get(1).getPerTurn()).isTrue();
    }

    @Test
    @DisplayName("load still succeeds for a bundle persisted before the testCaseSchema field existed")
    void loadSucceedsForLegacyBundleWithoutSchemaField() throws Exception {
        final UUID sourceSuiteId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        final String legacyJson =
                Files.readString(Path.of("src/test/resources/fixtures/legacy-suite-fetch-bundle.json"));
        Files.writeString(tempDir.resolve(sourceSuiteId + ".json"), legacyJson);

        final SuiteFetchBundle bundle = fetchService.load(sourceSuiteId);

        assertThat(bundle.getSourceSuiteId()).isEqualTo(sourceSuiteId);
        assertThat(bundle.getSuite().getName()).isEqualTo("Legacy Suite");
        assertThat(bundle.getTestCases()).hasSize(1);
        assertThat(bundle.getTestCaseSchema()).isNull();
    }
}
