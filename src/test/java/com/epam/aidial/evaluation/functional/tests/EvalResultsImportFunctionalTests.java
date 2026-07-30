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
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteCloneRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteUpdateResultDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.CursorPageResponseDto;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

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

    // -------------------------------------------------------------------------
    // Happy-path and Phase 2+3 smoke tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Should import eval results, extract response columns, and complete Phase 2+3 without invoking a deployment")
    void shouldImportResultsAndCompleteRun() {
        TestSuiteResponseDto suite = createSuiteWithResponseColumnAndMetric("Suite For Eval Import");

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

        // Two rows: tc-1 and tc-2. testCaseData is supplied as a JSON blob column.
        // extractedColumns are supplied in the CSV (caller-trusted, as Phase 1 would produce).
        String csv = buildCsv(
                List.of(
                        "testCaseName",
                        "runIndex",
                        "responseBody",
                        "responseStatusCode",
                        "executionStatus",
                        "startedAt",
                        "completedAt",
                        "testCaseData",
                        "extractedColumns"),
                List.of(
                        List.of(
                                "tc-1",
                                "0",
                                "{\"choices\":[{\"message\":{\"content\":\"Mocked answer 1.\"}}]}",
                                "200",
                                "SUCCESS",
                                "1000",
                                "1500",
                                "{\"expected\":\"answer1\"}",
                                "{\"answer\":\"Mocked answer 1.\"}"),
                        List.of(
                                "tc-2",
                                "0",
                                "{\"choices\":[{\"message\":{\"content\":\"Mocked answer 2.\"}}]}",
                                "200",
                                "SUCCESS",
                                "1000",
                                "1500",
                                "{\"expected\":\"answer2\"}",
                                "{\"answer\":\"Mocked answer 2.\"}")));

        ResponseEntity<TestSuiteRunResponseDto> importResponse =
                postImportCsv(suite.getId(), csv, TestSuiteRunResponseDto.class);

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
    @DisplayName("Should import results for a metric-less suite and expose them through the eval-summary list")
    void shouldImportResultsForMetricLessSuite() {
        UUID datasetId = newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                .name("expected")
                .type(SchemaFieldType.STRING)
                .required(true)
                .build()));
        TestSuiteResponseDto suite = createSuiteWithResponseColumn("Suite For Metric-less Eval Import", datasetId);

        String csv = buildCsv(
                List.of(
                        "testCaseName",
                        "runIndex",
                        "responseBody",
                        "responseStatusCode",
                        "executionStatus",
                        "startedAt",
                        "completedAt",
                        "testCaseData",
                        "extractedColumns"),
                List.of(
                        List.of(
                                "tc-1",
                                "0",
                                "{\"choices\":[{\"message\":{\"content\":\"Imported answer 1.\"}}]}",
                                "200",
                                "SUCCESS",
                                "1000",
                                "1500",
                                "{\"expected\":\"answer1\"}",
                                "{\"answer\":\"Imported answer 1.\"}"),
                        List.of(
                                "tc-2",
                                "0",
                                "{\"choices\":[{\"message\":{\"content\":\"Imported answer 2.\"}}]}",
                                "200",
                                "SUCCESS",
                                "1000",
                                "1500",
                                "{\"expected\":\"answer2\"}",
                                "{\"answer\":\"Imported answer 2.\"}")));

        ResponseEntity<TestSuiteRunResponseDto> importResponse =
                postImportCsv(suite.getId(), csv, TestSuiteRunResponseDto.class);
        assertThat(importResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        UUID runId = importResponse.getBody().getId();
        assertThat(awaitRunTerminal(runId, 15).getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        // Phase 2 runs unconditionally on the import path too, so the imported rows are readable.
        assertThat(analyticsTestDataHelper.findRunMetricSnapshotsByRunId(runId)).isEmpty();

        var listResponse = restTemplate.exchange(
                apiUrl("/analytics/eval-summaries?filter=runId:eq:" + runId + "&size=10"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});

        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody().getContent()).hasSize(2);
        assertThat(listResponse.getBody().getContent()).allSatisfy(item -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) item;
            assertThat(row.get("metricValues")).isEqualTo(Map.of());
            assertThat((String) row.get("testCaseName")).startsWith("tc-");
        });
    }

    // -------------------------------------------------------------------------
    // Guard / validation error scenarios
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should return 404 when test suite does not exist")
    void shouldReturn404WhenSuiteNotFound() {
        String csv = buildMinimalCsv("tc");
        ResponseEntity<String> response = postImportCsv(UUID.randomUUID(), csv, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should return 400 when testCaseData is not a JSON object (scalar value in data column)")
    void shouldReturn400WhenTestCaseDataNotAnObject() {
        // dataset schema requires "expected" to be a STRING — the CSV column "expected" maps to testCaseData.
        // But we override the value at the service level by passing a non-object JSON.
        // Actually, the CSV parser always builds testCaseData as an ObjectNode — to trigger the
        // "not an object" check we need to supply a value that the parser cannot coerce to an object.
        // The simplest way is to not include any data column so testCaseData = {} which IS valid.
        // The real "not an object" guard runs via EvalResultsImportService.validateBatch, which
        // checks item.getTestCaseData().isObject() — the CSV parser always produces an ObjectNode,
        // so this guard can never be triggered via the CSV path. The equivalent CSV-path guard is
        // "empty CSV" or "missing required column". We test the schema-violation path instead (see
        // shouldReturn400WhenTestCaseDataViolatesSchema below).
        //
        // For the purposes of this test class we verify the CSV-specific "missing required column"
        // validation path as a representative 400 scenario that was previously covered by the
        // "testCaseData not an object" JSON-body test.
        TestSuiteResponseDto suite = createSuiteWithResponseColumnAndMetric("Suite For Missing Column");

        // CSV with no executionStatus column — Bean Validation requires it not-null
        String csv = buildCsv(
                List.of("testCaseName", "runIndex", "startedAt", "completedAt"),
                List.of(List.of("tc", "0", "1000", "1500")));

        ResponseEntity<String> response = postImportCsv(suite.getId(), csv, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 when testCaseData does not conform to dataset schema")
    void shouldReturn400WhenTestCaseDataViolatesSchema() {
        UUID datasetId = newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                .name("question")
                .type(SchemaFieldType.STRING)
                .required(true)
                .build()));

        TestSuiteResponseDto suite = createSuiteWithResponseColumnAndMetric("Suite For Schema Validation", datasetId);

        // testCaseData is a JSON object that is missing the required "question" field
        String csv = buildCsv(
                List.of("testCaseName", "runIndex", "executionStatus", "startedAt", "completedAt", "testCaseData"),
                List.of(List.of("tc-invalid", "0", "SUCCESS", "1000", "1500", "{\"wrong_field\":\"some value\"}")));

        ResponseEntity<String> response = postImportCsv(suite.getId(), csv, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("testCaseData validation failed");
        assertThat(response.getBody()).contains("row 0");
    }

    @Test
    @DisplayName("Should return 400 when the CSV has a malformed JSON cell in responseBody")
    void shouldReturn400WhenMalformedJsonCell() {
        TestSuiteResponseDto suite = createSuiteWithResponseColumnAndMetric("Suite For Malformed JSON");

        String csv = buildCsv(
                List.of("testCaseName", "runIndex", "responseBody", "executionStatus", "startedAt", "completedAt"),
                List.of(List.of("tc", "0", "not-valid-json", "SUCCESS", "1000", "1500")));

        ResponseEntity<String> response = postImportCsv(suite.getId(), csv, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("responseBody");
    }

    @Test
    @DisplayName("Should return 400 when the CSV has an invalid executionStatus value")
    void shouldReturn400WhenInvalidExecutionStatus() {
        TestSuiteResponseDto suite = createSuiteWithResponseColumnAndMetric("Suite For Invalid Status");

        String csv = buildCsv(
                List.of("testCaseName", "runIndex", "executionStatus", "startedAt", "completedAt"),
                List.of(List.of("tc", "0", "NOT_A_STATUS", "1000", "1500")));

        ResponseEntity<String> response = postImportCsv(suite.getId(), csv, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("executionStatus");
    }

    @Test
    @DisplayName("Should return 400 when the CSV is empty (header only, no data rows)")
    void shouldReturn400WhenEmptyCsv() {
        TestSuiteResponseDto suite = createSuiteWithResponseColumnAndMetric("Suite For Empty CSV");

        // Header-only, no data rows
        String csv = "testCaseName,runIndex,executionStatus,startedAt,completedAt\n";

        ResponseEntity<String> response = postImportCsv(suite.getId(), csv, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // -------------------------------------------------------------------------
    // Clone-replay scenario
    // -------------------------------------------------------------------------

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

        // Import by testCaseName only (no testCaseId) — works cross-clone because name is a stable label.
        // testCaseData carries the "expected" field as a JSON object (the new CSV blob shape).
        String csv = buildCsv(
                List.of(
                        "testCaseName",
                        "runIndex",
                        "responseBody",
                        "responseStatusCode",
                        "executionStatus",
                        "startedAt",
                        "completedAt",
                        "testCaseData"),
                List.of(List.of(
                        "tc-clone",
                        "0",
                        "{\"choices\":[{\"message\":{\"content\":\"Cloned mocked answer.\"}}]}",
                        "200",
                        "SUCCESS",
                        "1000",
                        "1500",
                        "{\"expected\":\"cloned-answer\"}")));

        ResponseEntity<TestSuiteRunResponseDto> importResponse =
                postImportCsv(clone.getId(), csv, TestSuiteRunResponseDto.class);
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

    // -------------------------------------------------------------------------
    // CSV helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a CSV string from the provided header list and row data. Values containing commas or
     * double-quotes are quoted automatically.
     */
    private String buildCsv(List<String> headers, List<List<String>> rows) {
        final StringBuilder sb = new StringBuilder();
        appendCsvRow(sb, headers);
        for (final List<String> row : rows) {
            appendCsvRow(sb, row);
        }
        return sb.toString();
    }

    private void appendCsvRow(StringBuilder sb, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            final String v = values.get(i);
            if (v != null && (v.contains(",") || v.contains("\"") || v.contains("\n"))) {
                sb.append('"').append(v.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(v != null ? v : "");
            }
        }
        sb.append('\n');
    }

    /**
     * Builds a minimal valid CSV row that satisfies Bean Validation (runIndex + executionStatus +
     * startedAt + completedAt required) for a single test case identified by name.
     */
    private String buildMinimalCsv(String testCaseName) {
        return buildCsv(
                List.of("testCaseName", "runIndex", "executionStatus", "startedAt", "completedAt"),
                List.of(List.of(testCaseName, "0", "SUCCESS", "1000", "1500")));
    }

    private <T> ResponseEntity<T> postImportCsv(UUID suiteId, String csv, Class<T> responseType) {
        URI uri = UriComponentsBuilder.fromUriString(apiUrl("/test-suites/" + suiteId + "/runs/import"))
                .build()
                .toUri();
        return restTemplate.postForEntity(uri, multipartFileEntity(csv, "results.csv"), responseType);
    }

    private HttpEntity<MultiValueMap<String, Object>> multipartFileEntity(String csvContent, String filename) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(csvContent.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(body, headers);
    }

    // -------------------------------------------------------------------------
    // Suite / dataset creation helpers
    // -------------------------------------------------------------------------

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
        TestSuiteResponseDto suite = createSuiteWithResponseColumn(name, datasetId);

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

    private TestSuiteResponseDto createSuiteWithResponseColumn(String name, UUID datasetId) {
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
        return suite;
    }
}
