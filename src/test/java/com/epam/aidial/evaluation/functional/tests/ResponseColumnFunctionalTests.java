package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.service.domain.dto.ParameterDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.ParameterLocation;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.RunConfigDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.CursorPageResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.TestCaseRunResultResponseDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Response Column CRUD & Extraction Functional Tests")
public abstract class ResponseColumnFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private DialCoreDeploymentInvoker deploymentInvoker;

    @Autowired
    private com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID newDatasetWithSchema(List<FieldDefinitionDto> schema) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            return metaTestDataHelper
                    .createDataset("rcol-" + UUID.randomUUID(), schemaJson)
                    .getId();
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
    }

    private com.epam.aidial.evaluation.service.domain.dto.DatasetResponseDto getDataset(UUID datasetId) {
        return restTemplate
                .getForEntity(
                        apiUrl("/datasets/" + datasetId),
                        com.epam.aidial.evaluation.service.domain.dto.DatasetResponseDto.class)
                .getBody();
    }

    @BeforeEach
    void setUp() {
        analyticsTestDataHelper.cleanupResults();
        // Configure mock invoker to return a standard OpenAI-style response
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(new DeploymentInvocationResult(
                        200,
                        false,
                        Map.of(
                                "id",
                                "mock-chatcmpl-1",
                                "choices",
                                List.of(Map.of("message", Map.of("content", "Mocked answer.")))),
                        null,
                        new HttpHeaders()));
    }

    // --- Task 9.3: create suite with responseColumns → GET returns them ---

    @Test
    @DisplayName("Should persist responseColumns on create and return them via GET")
    void shouldPersistResponseColumnsOnCreate() {
        // Given
        List<ResponseColumnDefinitionDto> columns = List.of(ResponseColumnDefinitionDto.builder()
                .name("answer")
                .displayName("Model Answer")
                .expression("choices[0].message.content")
                .type(SchemaFieldType.STRING)
                .build());

        // When
        ResponseEntity<TestSuiteResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites"),
                jsonEntity(buildRequestWithColumns("Suite With Response Cols", columns)),
                TestSuiteResponseDto.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getResponseColumns()).hasSize(1);
        assertThat(response.getBody().getResponseColumns().get(0).getName()).isEqualTo("answer");
        assertThat(response.getBody().getResponseColumns().get(0).getDisplayName())
                .isEqualTo("Model Answer");
        assertThat(response.getBody().getResponseColumns().get(0).getExpression())
                .isEqualTo("choices[0].message.content");
        assertThat(response.getBody().getResponseColumns().get(0).getType()).isEqualTo(SchemaFieldType.STRING);

        // Verify GET also returns the columns
        UUID id = response.getBody().getId();
        ResponseEntity<TestSuiteResponseDto> get =
                restTemplate.getForEntity(apiUrl("/test-suites/" + id), TestSuiteResponseDto.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get.getBody()).isNotNull();
        assertThat(get.getBody().getResponseColumns()).hasSize(1);
        assertThat(get.getBody().getResponseColumns().get(0).getName()).isEqualTo("answer");
    }

    // --- Task 9.4: update suite responseColumns → new columns persisted ---

    @Test
    @DisplayName("Should update responseColumns and persist the new set")
    void shouldUpdateResponseColumns() {
        // Given: create suite with one column
        TestSuiteResponseDto created = createTestSuiteWithColumns(
                "Suite For Column Update",
                List.of(ResponseColumnDefinitionDto.builder()
                        .name("answer")
                        .expression("choices[0].message.content")
                        .build()));

        // When: update with two different columns
        List<ResponseColumnDefinitionDto> newColumns = List.of(
                ResponseColumnDefinitionDto.builder()
                        .name("answer")
                        .expression("choices[0].message.content")
                        .build(),
                ResponseColumnDefinitionDto.builder()
                        .name("finish_reason")
                        .expression("choices[0].finish_reason")
                        .build());
        HttpHeaders headers = new HttpHeaders();
        headers.setIfMatch("\"" + created.getVersion() + "\"");
        ResponseEntity<TestSuiteResponseDto> response = restTemplate.exchange(
                apiUrl("/test-suites/" + created.getId()),
                HttpMethod.PUT,
                new HttpEntity<>(buildRequestWithColumns(created.getName(), newColumns), headers),
                TestSuiteResponseDto.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getResponseColumns()).hasSize(2);
        assertThat(response.getBody().getResponseColumns())
                .extracting(ResponseColumnDefinitionDto::getName)
                .containsExactly("answer", "finish_reason");
    }

    // --- Task 9.5: create suite with invalid JSONata expression → 400 ---

    @Test
    @DisplayName("Should return 400 when responseColumns contains invalid JSONata expression")
    void shouldReturn400ForInvalidJsonataExpression() {
        // Given: unclosed bracket makes expression invalid
        List<ResponseColumnDefinitionDto> columns = List.of(ResponseColumnDefinitionDto.builder()
                .name("answer")
                .expression("choices[0.message.content")
                .build());

        // When
        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites"),
                jsonEntity(buildRequestWithColumns("Suite Invalid Expr", columns)),
                String.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("answer");
    }

    // --- Task 9.6: create suite with duplicate column names → 400 ---

    @Test
    @DisplayName("Should return 400 when responseColumns contains duplicate column names")
    void shouldReturn400ForDuplicateColumnNames() {
        // Given: two columns with the same name
        List<ResponseColumnDefinitionDto> columns = List.of(
                ResponseColumnDefinitionDto.builder()
                        .name("answer")
                        .expression("choices[0].message.content")
                        .build(),
                ResponseColumnDefinitionDto.builder()
                        .name("answer")
                        .expression("usage.total_tokens")
                        .build());

        // When
        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites"),
                jsonEntity(buildRequestWithColumns("Suite Duplicate Cols", columns)),
                String.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("answer");
    }

    // --- Task 9.7: create suite without responseColumns → defaults to empty array ---

    @Test
    @DisplayName("Should default responseColumns to empty list when not provided")
    void shouldDefaultResponseColumnsToEmptyList() {
        // Given: request with no responseColumns field
        TestSuiteRequestDto request = buildBaseRequest("Suite No Response Cols");

        // When
        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getResponseColumns()).isNotNull();
        assertThat(response.getBody().getResponseColumns()).isEmpty();
    }

    // --- Task 9.8: create suite with >50 responseColumns → 400 ---

    @Test
    @DisplayName("Should return 400 when responseColumns exceeds 50 items")
    void shouldReturn400WhenResponseColumnsExceedMaxSize() {
        // Given: 51 response columns (max is 50)
        List<ResponseColumnDefinitionDto> columns = IntStream.range(0, 51)
                .mapToObj(i -> ResponseColumnDefinitionDto.builder()
                        .name("col_" + i)
                        .expression("field_" + i)
                        .build())
                .toList();

        // When
        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites"),
                jsonEntity(buildRequestWithColumns("Suite Too Many Cols", columns)),
                String.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- jsonata-request-templates WP3: write-time 400s for JSONata request bodies + reserved names ---

    @Test
    @DisplayName("Should return 400 when requestTemplate.body.content is a syntactically invalid JSONata String")
    void shouldReturn400ForInvalidJsonataStringContentBody() {
        // Given: unclosed bracket makes the JSONata source invalid
        TestSuiteRequestDto request = buildRequestWithJsonataBody("Suite Invalid JSONata Body", "{\"a\": [1, 2}");

        // When
        ResponseEntity<String> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), String.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("requestTemplate.body.content");
    }

    @Test
    @DisplayName("Should return 400 when a response column name collides with the reserved frame variable 'request'")
    void shouldReturn400ForReservedResponseColumnName() {
        // Given: a response column named "request" collides with the $request frame variable
        List<ResponseColumnDefinitionDto> columns = List.of(ResponseColumnDefinitionDto.builder()
                .name("request")
                .expression("choices[0].message.content")
                .build());

        // When
        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites"),
                jsonEntity(buildRequestWithColumns("Suite Reserved Column Name", columns)),
                String.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("request").contains("reserved");
    }

    @Test
    @DisplayName("Should accept a suite whose requestTemplate.body.content is a valid JSONata source String")
    void shouldAcceptValidJsonataStringContentBody() {
        // Given: a valid JSONata source string using $append (object-constructor authoring style)
        TestSuiteRequestDto request = buildRequestWithJsonataBody(
                "Suite Valid JSONata Body",
                "{\"messages\": $append([], [{\"role\": \"user\", \"content\": \"${{q:hi}}\"}])}");

        // When
        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
    }

    // --- Task 9.9: run execution produces extractedColumns in results ---

    @Test
    @DisplayName("Should populate extractedColumns in analytics results after mock run")
    void shouldPopulateExtractedColumnsInResults() {
        // Given: suite with a response column that extracts from mock SUCCESS body
        // Mock SUCCESS body: {"id":"mock-...","choices":[{"message":{"content":"Mocked answer."}}]}
        TestSuiteResponseDto suite = createTestSuiteWithColumns(
                "Suite For Extraction",
                List.of(ResponseColumnDefinitionDto.builder()
                        .name("answer")
                        .expression("choices[0].message.content")
                        .type(SchemaFieldType.STRING)
                        .build()));
        createTestCase(suite.getId(), "TC Extraction");
        TestSuiteRunResponseDto run = createRunAndAwaitCompleted(suite.getId(), 1);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        // When
        List<TestCaseRunResultResponseDto> results = fetchResults(suite.getId());

        // Then: extractedColumns has "answer" = "Mocked answer."
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getExtractedColumns()).isNotNull();
        assertThat(results.get(0).getExtractedColumns().has("answer")).isTrue();
        assertThat(results.get(0).getExtractedColumns().get("answer").asString())
                .isEqualTo("Mocked answer.");
        assertThat(results.get(0).getExtractionWarnings()).isEmpty();
    }

    // --- Task 9.10: extraction failure → null value + warning entry ---

    @Test
    @DisplayName("Should produce null value and extraction warning when expression fails at runtime")
    void shouldProduceWarningWhenExtractionFails() {
        // Given: expression that throws at runtime — calling undefined function
        TestSuiteResponseDto suite = createTestSuiteWithColumns(
                "Suite For Extraction Failure",
                List.of(ResponseColumnDefinitionDto.builder()
                        .name("score")
                        .expression("$undefinedFunctionXyz123()")
                        .type(SchemaFieldType.NUMBER)
                        .build()));
        createTestCase(suite.getId(), "TC Failure");
        TestSuiteRunResponseDto run = createRunAndAwaitCompleted(suite.getId(), 1);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        // When
        List<TestCaseRunResultResponseDto> results = fetchResults(suite.getId());

        // Then: score is null and there is one extraction warning
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getExtractedColumns()).isNotNull();
        assertThat(results.get(0).getExtractedColumns().get("score").isNull()).isTrue();
        assertThat(results.get(0).getExtractionWarnings()).hasSize(1);
        assertThat(results.get(0).getExtractionWarnings().get(0).getColumn()).isEqualTo("score");
        assertThat(results.get(0).getExtractionWarnings().get(0).getExpression())
                .isEqualTo("$undefinedFunctionXyz123()");
        assertThat(results.get(0).getExtractionWarnings().get(0).getError()).isNotBlank();
    }

    // --- Task 9.11: displayName on FieldDefinitionDto persisted and returned ---

    @Test
    @DisplayName("Should persist and return displayName in testCaseSchema FieldDefinitionDto")
    void shouldPersistDisplayNameInTestCaseSchema() {
        // testCaseSchema lives on the Dataset since introduce-dataset-entity. Build it on a fresh
        // dataset, bind the suite via datasetId, and read schema state via GET /datasets/{id}.
        UUID datasetId = newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                .name("expected_status")
                .displayName("Expected HTTP Status")
                .type(SchemaFieldType.INTEGER)
                .required(true)
                .build()));
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Suite With DisplayName")
                .datasetId(datasetId)
                .deploymentRef(
                        DeploymentReferenceDto.builder().id("d1").name("D1").build())
                .endpointRef(buildEndpointContract("/v1/chat"))
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                .build();

        // When
        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        // Then: displayName is in the dataset schema (read via GET /datasets/{id})
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        com.epam.aidial.evaluation.service.domain.dto.DatasetResponseDto dataset = getDataset(datasetId);
        assertThat(dataset.getTestCaseSchema()).hasSize(1);
        assertThat(dataset.getTestCaseSchema().get(0).getDisplayName()).isEqualTo("Expected HTTP Status");

        // And persists across a fresh GET on the dataset
        com.epam.aidial.evaluation.service.domain.dto.DatasetResponseDto reread = getDataset(datasetId);
        assertThat(reread.getTestCaseSchema().get(0).getDisplayName()).isEqualTo("Expected HTTP Status");
    }

    // --- Issue #883 regression: ARRAY-declared column + JSONata returning single match ---

    @Test
    @DisplayName("Issue #883: ARRAY column + single JSONata match → singleton list, no warning")
    void shouldWrapSingleMatchIntoSingletonForArrayColumn() {
        // Given: ARRAY-typed response column whose JSONata expression returns ONE match.
        // The mock body has a single string at choices[0].message.content; JSONata flattens
        // the single-element sequence to the bare value — pre-fix this surfaced as a scalar
        // and broke metric inputs that required list[str].
        TestSuiteResponseDto suite = createTestSuiteWithColumns(
                "Suite Issue 883",
                List.of(ResponseColumnDefinitionDto.builder()
                        .name("files")
                        .expression("choices[0].message.content")
                        .type(SchemaFieldType.ARRAY)
                        .build()));
        createTestCase(suite.getId(), "TC 883");

        TestSuiteRunResponseDto run = createRunAndAwaitCompleted(suite.getId(), 1);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<TestCaseRunResultResponseDto> results = fetchResults(suite.getId());
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getExtractedColumns().get("files").isArray()).isTrue();
        assertThat(results.get(0).getExtractedColumns().get("files").size()).isEqualTo(1);
        assertThat(results.get(0).getExtractedColumns().get("files").get(0).asString())
                .isEqualTo("Mocked answer.");
        assertThat(results.get(0).getExtractionWarnings()).isEmpty();
    }

    @Test
    @DisplayName("Type mismatch: STRING column + array result → null cell + structured warning")
    void shouldRecordTypeMismatchWarningWhenStringColumnReceivesArray() {
        // Given: STRING-declared column whose expression returns an array (multiple matches via list literal).
        // The reconciler must surface this as a structured warning instead of silently storing the array.
        TestSuiteResponseDto suite = createTestSuiteWithColumns(
                "Suite Type Mismatch",
                List.of(ResponseColumnDefinitionDto.builder()
                        .name("name")
                        .expression("[choices[0].message.content, choices[0].message.content]")
                        .type(SchemaFieldType.STRING)
                        .build()));
        createTestCase(suite.getId(), "TC Mismatch");

        TestSuiteRunResponseDto run = createRunAndAwaitCompleted(suite.getId(), 1);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<TestCaseRunResultResponseDto> results = fetchResults(suite.getId());
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getExtractedColumns().get("name").isNull()).isTrue();
        assertThat(results.get(0).getExtractionWarnings()).hasSize(1);
        assertThat(results.get(0).getExtractionWarnings().get(0).getColumn()).isEqualTo("name");
        assertThat(results.get(0).getExtractionWarnings().get(0).getError())
                .isEqualTo("Type mismatch: expected STRING, got ARRAY");
    }

    // --- FILE type regression ---

    @Test
    @DisplayName("Should accept and persist FILE type on response column")
    void shouldAcceptFileTypeOnResponseColumn() {
        // Given: response column with FILE type (display hint for DIAL file references)
        List<ResponseColumnDefinitionDto> columns = List.of(ResponseColumnDefinitionDto.builder()
                .name("attachment")
                .displayName("Attachment")
                .expression("choices[0].message.custom_content.attachments[0].url")
                .type(SchemaFieldType.FILE)
                .build());

        // When: create suite
        ResponseEntity<TestSuiteResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites"),
                jsonEntity(buildRequestWithColumns("Suite With FILE Column", columns)),
                TestSuiteResponseDto.class);

        // Then: FILE type is accepted and persisted
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getResponseColumns()).hasSize(1);
        assertThat(response.getBody().getResponseColumns().get(0).getType()).isEqualTo(SchemaFieldType.FILE);

        // Verify GET also returns FILE type
        UUID id = response.getBody().getId();
        ResponseEntity<TestSuiteResponseDto> get =
                restTemplate.getForEntity(apiUrl("/test-suites/" + id), TestSuiteResponseDto.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get.getBody()).isNotNull();
        assertThat(get.getBody().getResponseColumns().get(0).getType()).isEqualTo(SchemaFieldType.FILE);
    }

    // --- helpers ---

    private TestSuiteResponseDto createTestSuiteWithColumns(String name, List<ResponseColumnDefinitionDto> columns) {
        ResponseEntity<TestSuiteResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites"), jsonEntity(buildRequestWithColumns(name, columns)), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private TestSuiteRequestDto buildRequestWithColumns(String name, List<ResponseColumnDefinitionDto> columns) {
        return TestSuiteRequestDto.builder()
                .name(name)
                .deploymentRef(
                        DeploymentReferenceDto.builder().id("d1").name("D1").build())
                .endpointRef(buildEndpointContract("/v1/chat"))
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("expected")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build())))
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                .responseColumns(columns)
                .build();
    }

    private TestSuiteRequestDto buildRequestWithJsonataBody(String name, String jsonataBodyContent) {
        return TestSuiteRequestDto.builder()
                .name(name)
                .deploymentRef(
                        DeploymentReferenceDto.builder().id("d1").name("D1").build())
                .endpointRef(buildEndpointContract("/v1/chat"))
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("expected")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build())))
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/v1/chat")
                        .body(JsonRequestBodyDto.builder()
                                .content(jsonataBodyContent)
                                .build())
                        .build())
                .build();
    }

    private TestSuiteRequestDto buildBaseRequest(String name) {
        return TestSuiteRequestDto.builder()
                .name(name)
                .deploymentRef(
                        DeploymentReferenceDto.builder().id("d1").name("D1").build())
                .endpointRef(buildEndpointContract("/v1/chat"))
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("expected")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build())))
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                .build();
    }

    private EndpointContractDto buildEndpointContract(String path) {
        return EndpointContractDto.builder()
                .method(HttpMethod.POST)
                .relativeUrlPattern(path)
                .parameters(List.of(ParameterDefinitionDto.builder()
                        .name("q")
                        .in(ParameterLocation.QUERY)
                        .required(false)
                        .schema(Map.of("type", "string"))
                        .build()))
                .requestBodySchema(JsonRequestBodySchemaDto.builder()
                        .schema(Map.of("type", "object", "properties", Map.of()))
                        .build())
                .build();
    }

    private TestCaseResponseDto createTestCase(UUID suiteId, String name) {
        UUID datasetId = metaTestDataHelper.getDatasetId(suiteId);
        ResponseEntity<TestCaseResponseDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases"),
                jsonEntity(TestCaseRequestDto.builder()
                        .testCaseName(name)
                        .data(Map.of("expected", "test answer"))
                        .build()),
                TestCaseResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private TestSuiteRunResponseDto createRunAndAwaitCompleted(UUID suiteId, int numberOfRuns) {
        ResponseEntity<TestSuiteRunResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suiteId + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder()
                        .runConfig(RunConfigDto.builder()
                                .numberOfRuns(numberOfRuns)
                                .build())
                        .build()),
                TestSuiteRunResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        return awaitRunTerminal(response.getBody().getId(), 30);
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

    private List<TestCaseRunResultResponseDto> fetchResults(UUID suiteId) {
        var response = restTemplate.exchange(
                apiUrl("/analytics/test-case-results?filter=suiteId:eq:" + suiteId + "&size=100"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<TestCaseRunResultResponseDto>>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().getContent();
    }
}
