package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.client.metricprovider.MetricProviderClient;
import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationRequestDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationResponseDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.MetricOutputFieldDto;
import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.data.db.model.DatasetVisibility;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetricDeclarationTestDataProvider;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.service.domain.dto.ParameterDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.ParameterLocation;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteCloneRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteUpdateResultDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalResultsImportItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalResultsImportRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.ExecutionInfoRequestDto;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

@DisplayName("Eval Results Import Functional Tests")
public abstract class EvalResultsImportFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private MetricDeclarationTestDataProvider metricDeclarationTestDataProvider;

    @Autowired
    private DialCoreDeploymentInvoker deploymentInvoker;

    @Autowired
    private MetricProviderClient metricProviderClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName(
            "Should import eval results, extract response columns, and complete Phase 2+3 without invoking a deployment")
    void shouldImportResultsAndCompleteRun() {
        TestSuiteResponseDto suite = createSuiteWithResponseColumnAndMetric("Suite For Eval Import");
        createTestCaseForSuite(suite.getId(), "tc-1", Map.of("expected", "answer1"));
        createTestCaseForSuite(suite.getId(), "tc-2", Map.of("expected", "answer2"));

        when(metricProviderClient.evaluate(anyString(), any(EvaluationRequestDto.class)))
                .thenReturn(EvaluationResponseDto.builder()
                        .metricName("Accuracy")
                        .output(Map.of(
                                "Accuracy",
                                MetricOutputFieldDto.builder()
                                        .type("value")
                                        .value(BigDecimal.ONE)
                                        .build()))
                        .build());

        EvalResultsImportRequestDto request = EvalResultsImportRequestDto.builder()
                .results(List.of(
                        importItem(
                                "tc-1",
                                Map.of("expected", "answer1"),
                                Map.of("choices", List.of(Map.of("message", Map.of("content", "Mocked answer 1."))))),
                        importItem(
                                "tc-2",
                                Map.of("expected", "answer2"),
                                Map.of("choices", List.of(Map.of("message", Map.of("content", "Mocked answer 2.")))))))
                .build();

        ResponseEntity<TestSuiteRunResponseDto> importResponse = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs/import"),
                jsonEntity(request),
                TestSuiteRunResponseDto.class);

        assertThat(importResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(importResponse.getBody()).isNotNull();
        assertThat(importResponse.getBody().getStatus()).isEqualTo(RunStatus.PENDING.name());
        assertThat(importResponse.getBody().getNumberOfTestCases()).isEqualTo(2);

        UUID runId = importResponse.getBody().getId();
        TestSuiteRunResponseDto completed = awaitRunTerminal(runId, 15);
        assertThat(completed.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        verify(deploymentInvoker, never()).invokeWithStreaming(any(), any(), any(), any(), any());

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(runId);
        assertThat(results).hasSize(2);
        assertThat(results)
                .extracting(r -> (String) r.get("extracted_columns"))
                .allMatch(cols -> cols != null && cols.contains("Mocked answer"));

        List<Map<String, Object>> evalSummaries = analyticsTestDataHelper.findEvalSummariesByRunId(runId);
        assertThat(evalSummaries).hasSize(2);

        List<Map<String, Object>> snapshots = analyticsTestDataHelper.findRunMetricSnapshotsByRunId(runId);
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).get("tsmd_name")).isEqualTo("Accuracy");
    }

    @Test
    @DisplayName("Should return 400 when testCaseData is not a JSON object")
    void shouldReturn400WhenTestCaseDataNotAnObject() {
        TestSuiteResponseDto suite = createSuiteWithResponseColumnAndMetric("Suite For Bad TestCaseData");

        EvalResultsImportItemDto badItem = EvalResultsImportItemDto.builder()
                .testCaseName("tc")
                .runIndex(0)
                .testCaseData(JsonNodeFactory.instance.stringNode("not-an-object"))
                .responseBody(toJsonNode(Map.of("answer", "x")))
                .responseStatusCode(200)
                .executionInfo(ExecutionInfoRequestDto.builder()
                        .status(ExecutionStatus.SUCCESS)
                        .startedAt(1000L)
                        .completedAt(1500L)
                        .build())
                .build();
        EvalResultsImportRequestDto request =
                EvalResultsImportRequestDto.builder().results(List.of(badItem)).build();

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs/import"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 when testCaseData does not conform to dataset schema")
    void shouldReturn400WhenTestCaseDataViolatesSchema() {
        // Create a dataset with a required "question" STRING field
        UUID datasetId = newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                .name("question")
                .type(SchemaFieldType.STRING)
                .required(true)
                .build()));

        TestSuiteResponseDto suite = createSuiteWithResponseColumnAndMetric("Suite For Schema Validation", datasetId);

        // Import with testCaseData missing the required "question" field
        EvalResultsImportItemDto invalidItem = EvalResultsImportItemDto.builder()
                .testCaseName("tc-invalid")
                .runIndex(0)
                .testCaseData(toJsonNode(Map.of("wrong_field", "some value"))) // Missing required "question"
                .responseBody(toJsonNode(Map.of("answer", "x")))
                .responseStatusCode(200)
                .executionInfo(ExecutionInfoRequestDto.builder()
                        .status(ExecutionStatus.SUCCESS)
                        .startedAt(1000L)
                        .completedAt(1500L)
                        .build())
                .build();
        EvalResultsImportRequestDto request = EvalResultsImportRequestDto.builder()
                .results(List.of(invalidItem))
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs/import"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("testCaseData validation failed");
        assertThat(response.getBody()).contains("tc-invalid");
    }

    @Test
    @DisplayName("Should return 404 when test suite does not exist")
    void shouldReturn404WhenSuiteNotFound() {
        EvalResultsImportRequestDto request = EvalResultsImportRequestDto.builder()
                .results(List.of(importItem("tc", Map.of(), Map.of("answer", "x"))))
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + UUID.randomUUID() + "/runs/import"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName(
            "Should import by testCaseName only into a cloned suite whose PRIVATE dataset has entirely new test-case "
                    + "ids, and complete evaluation against the clone's own data")
    void shouldImportByNameIntoClonedPrivateDatasetSuite() {
        UUID privateDatasetId = newDatasetWithSchema(
                List.of(FieldDefinitionDto.builder()
                        .name("expected")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build()),
                DatasetVisibility.PRIVATE);
        TestSuiteResponseDto source =
                createSuiteWithResponseColumnAndMetric("Suite For Clone Replay", privateDatasetId);
        createTestCaseForSuite(source.getId(), "tc-clone", Map.of("expected", "cloned-answer"));

        ResponseEntity<TestSuiteUpdateResultDto> cloneResponse = restTemplate.postForEntity(
                apiUrl("/test-suites/" + source.getId() + "/clone"),
                jsonEntity(TestSuiteCloneRequestDto.builder()
                        .name("Clone Of " + source.getName())
                        .build()),
                TestSuiteUpdateResultDto.class);
        assertThat(cloneResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TestSuiteResponseDto clone = cloneResponse.getBody().getSuite();

        // The clone's dataset is a brand-new PRIVATE dataset — the source's test-case id is meaningless here.
        assertThat(clone.getDatasetId()).isNotEqualTo(privateDatasetId);

        when(metricProviderClient.evaluate(anyString(), any(EvaluationRequestDto.class)))
                .thenReturn(EvaluationResponseDto.builder()
                        .metricName("Accuracy")
                        .output(Map.of(
                                "Accuracy",
                                MetricOutputFieldDto.builder()
                                        .type("value")
                                        .value(BigDecimal.ONE)
                                        .build()))
                        .build());

        EvalResultsImportRequestDto request = EvalResultsImportRequestDto.builder()
                .results(List.of(importItem(
                        "tc-clone",
                        Map.of("expected", "cloned-answer"),
                        Map.of("choices", List.of(Map.of("message", Map.of("content", "Cloned mocked answer.")))))))
                .build();

        ResponseEntity<TestSuiteRunResponseDto> importResponse = restTemplate.postForEntity(
                apiUrl("/test-suites/" + clone.getId() + "/runs/import"),
                jsonEntity(request),
                TestSuiteRunResponseDto.class);
        assertThat(importResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        UUID runId = importResponse.getBody().getId();
        TestSuiteRunResponseDto completed = awaitRunTerminal(runId, 15);
        assertThat(completed.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(runId);
        assertThat(results).hasSize(1);

        List<Map<String, Object>> snapshots = analyticsTestDataHelper.findRunMetricSnapshotsByRunId(runId);
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).get("tsmd_name")).isEqualTo("Accuracy");
    }

    private EvalResultsImportItemDto importItem(
            String testCaseName, Map<String, Object> testCaseData, Map<String, Object> responseBody) {
        return EvalResultsImportItemDto.builder()
                .testCaseName(testCaseName)
                .runIndex(0)
                .testCaseData(toJsonNode(testCaseData))
                .responseBody(toJsonNode(responseBody))
                .responseStatusCode(200)
                .executionInfo(ExecutionInfoRequestDto.builder()
                        .status(ExecutionStatus.SUCCESS)
                        .startedAt(1000L)
                        .completedAt(1500L)
                        .build())
                .build();
    }

    private JsonNode toJsonNode(Map<String, Object> value) {
        return objectMapper.valueToTree(value);
    }

    private TestSuiteRunResponseDto awaitRunTerminal(UUID runId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<TestSuiteRunResponseDto> get =
                    restTemplate.getForEntity(apiUrl("/test-suite-runs/" + runId), TestSuiteRunResponseDto.class);
            if (get.getStatusCode() == HttpStatus.OK
                    && get.getBody() != null
                    && RunStatus.isTerminal(get.getBody().getStatus())) {
                return get.getBody();
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while polling run", e);
            }
        }
        throw new AssertionError("Run did not reach terminal status within " + timeoutSeconds + "s");
    }

    private UUID newDatasetWithSchema(List<FieldDefinitionDto> schema) {
        return newDatasetWithSchema(schema, DatasetVisibility.PUBLIC);
    }

    private UUID newDatasetWithSchema(List<FieldDefinitionDto> schema, DatasetVisibility visibility) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            return metaTestDataHelper
                    .createDataset("import-" + UUID.randomUUID(), schemaJson, visibility)
                    .getId();
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
    }

    private TestSuiteResponseDto createSuiteWithResponseColumnAndMetric(String name) {
        UUID datasetId = newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                .name("expected")
                .type(SchemaFieldType.STRING)
                .required(true)
                .build()));
        return createSuiteWithResponseColumnAndMetric(name, datasetId);
    }

    private TestSuiteResponseDto createSuiteWithResponseColumnAndMetric(String name, UUID datasetId) {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name(name)
                .description("Description for " + name)
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .parameters(List.of(ParameterDefinitionDto.builder()
                                .name("q")
                                .in(ParameterLocation.QUERY)
                                .required(false)
                                .schema(Map.of("type", "string"))
                                .build()))
                        .requestBodySchema(JsonRequestBodySchemaDto.builder()
                                .schema(Map.of("type", "object", "properties", Map.of()))
                                .build())
                        .build())
                .datasetId(datasetId)
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                        .name("answer")
                        .expression("choices[0].message.content")
                        .type(SchemaFieldType.STRING)
                        .build()))
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TestSuiteResponseDto suite = response.getBody();
        assertThat(suite).isNotNull();

        metricDeclarationTestDataProvider.insertSeedMetricDeclarations();
        metricDeclarationTestDataProvider.insertSeedVersionForAccuracy();
        UUID declarationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID versionId = UUID.fromString("770e8400-e29b-41d4-a716-446655440001");
        String inputBindings = """
                [{"property": "actual", "source": {"$type": "Response", "columnName": "answer"}}]
                """;
        metaTestDataHelper.createTestSuiteMetricDefinition(
                suite.getId(), declarationId, versionId, "Accuracy", "[]", inputBindings.trim());

        return suite;
    }

    private TestCaseResponseDto createTestCaseForSuite(UUID suiteId, String name, Map<String, Object> data) {
        UUID datasetId = metaTestDataHelper.getDatasetId(suiteId);
        ResponseEntity<TestCaseResponseDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases"),
                jsonEntity(TestCaseRequestDto.builder()
                        .testCaseName(name)
                        .data(data)
                        .build()),
                TestCaseResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}
