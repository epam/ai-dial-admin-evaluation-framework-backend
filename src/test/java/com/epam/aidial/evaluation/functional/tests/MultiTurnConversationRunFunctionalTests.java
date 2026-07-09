package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.JsonRequestBodySchemaDto;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end functional test for multi-turn conversations: create → run a multi-turn suite (single
 * {@code inputBindings} bound to an array-valued dataset column) against a mocked DIAL Core deployment. The
 * number of turns is derived per test case from the array-column length. Each turn is persisted as its own
 * scalar result row carrying {@code turn_index}/{@code total_turns} (its raw per-turn {@code response_body}
 * and scalar {@code extracted_columns}), and two test cases in the same run can execute different turn counts.
 */
@DisplayName("Multi-turn Conversation Run Functional Tests")
public abstract class MultiTurnConversationRunFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private DialCoreDeploymentInvoker deploymentInvoker;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Should persist one scalar result row per turn with turn_index/total_turns for a 2-turn conversation")
    void shouldRunTwoTurnConversation() throws JacksonException {
        TestSuiteResponseDto suite = createMultiTurnSuite();
        assertThat(suite.isMultiTurn()).isTrue();
        assertThat(suite.isValid()).isTrue();
        createTestCase(suite.getId(), "TC1", Map.of("turns", List.of("hello", "how are you")));

        // Each turn returns a distinct assistant reply so we can verify per-turn rows/extraction.
        AtomicInteger callCount = new AtomicInteger();
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    int i = callCount.getAndIncrement();
                    return new DeploymentInvocationResult(
                            200,
                            false,
                            Map.of(
                                    "id",
                                    "mock",
                                    "choices",
                                    List.of(Map.of("message", Map.of("role", "assistant", "content", "reply-" + i)))),
                            null,
                            new HttpHeaders());
                });

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId());
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        // Two turns → two per-turn result rows and two deployment calls
        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(2);
        assertThat(callCount.get()).isEqualTo(2);

        results.sort(Comparator.comparingInt(r -> ((Number) r.get("turn_index")).intValue()));

        Map<String, Object> turn0 = results.get(0);
        assertThat(String.valueOf(turn0.get("execution_status"))).isEqualTo("SUCCESS");
        assertThat(((Number) turn0.get("turn_index")).intValue()).isEqualTo(0);
        assertThat(((Number) turn0.get("total_turns")).intValue()).isEqualTo(2);
        // scalar extraction per turn: {"answer":"reply-0"}
        assertThat(objectMapper
                        .readTree(String.valueOf(turn0.get("extracted_columns")))
                        .get("answer")
                        .asString())
                .isEqualTo("reply-0");
        // response_body = that turn's raw chat-completion response (technical "id" preserved)
        JsonNode turn0Response = objectMapper.readTree(String.valueOf(turn0.get("response_body")));
        assertThat(turn0Response.get("id").asString()).isEqualTo("mock");
        assertThat(contentOf(turn0Response)).isEqualTo("reply-0");

        Map<String, Object> turn1 = results.get(1);
        assertThat(((Number) turn1.get("turn_index")).intValue()).isEqualTo(1);
        assertThat(((Number) turn1.get("total_turns")).intValue()).isEqualTo(2);
        assertThat(objectMapper
                        .readTree(String.valueOf(turn1.get("extracted_columns")))
                        .get("answer")
                        .asString())
                .isEqualTo("reply-1");
        assertThat(contentOf(objectMapper.readTree(String.valueOf(turn1.get("response_body")))))
                .isEqualTo("reply-1");
    }

    @Test
    @DisplayName("Should derive turn count per test case: 2 vs 3 turns yields 2 vs 3 per-turn rows")
    void shouldRunPerTestCaseTurnCounts() {
        TestSuiteResponseDto suite = createMultiTurnSuite();
        createTestCase(suite.getId(), "TC2", Map.of("turns", List.of("a", "b")));
        createTestCase(suite.getId(), "TC3", Map.of("turns", List.of("a", "b", "c")));

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new DeploymentInvocationResult(
                        200,
                        false,
                        Map.of(
                                "id",
                                "mock",
                                "choices",
                                List.of(Map.of("message", Map.of("role", "assistant", "content", "reply")))),
                        null,
                        new HttpHeaders()));

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId());
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        // TC2 → 2 per-turn rows, TC3 → 3 per-turn rows: 5 rows total
        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(5);

        Map<String, Integer> rowCountByName = new HashMap<>();
        for (Map<String, Object> row : results) {
            assertThat(String.valueOf(row.get("execution_status"))).isEqualTo("SUCCESS");
            String name = String.valueOf(row.get("test_case_name"));
            rowCountByName.merge(name, 1, Integer::sum);
            // total_turns on each row equals that test case's turn count
            int expectedTurns = "TC2".equals(name) ? 2 : 3;
            assertThat(((Number) row.get("total_turns")).intValue()).isEqualTo(expectedTurns);
        }
        assertThat(rowCountByName).containsEntry("TC2", 2).containsEntry("TC3", 3);
    }

    private String contentOf(JsonNode responseBody) {
        return responseBody
                .path("choices")
                .get(0)
                .path("message")
                .get("content")
                .asString();
    }

    private TestSuiteResponseDto createMultiTurnSuite() {
        String schemaJson;
        try {
            schemaJson = objectMapper.writeValueAsString(List.of(FieldDefinitionDto.builder()
                    .name("turns")
                    .type(SchemaFieldType.ARRAY)
                    .required(true)
                    .build()));
        } catch (JacksonException e) {
            throw new AssertionError("Failed to serialize test-case schema", e);
        }
        Dataset dataset = metaTestDataHelper.createDataset("multiturn-" + UUID.randomUUID(), schemaJson);

        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Multi-turn Suite " + UUID.randomUUID())
                .description("multi-turn POC")
                .datasetId(dataset.getId())
                .multiTurn(true)
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("turn")
                        .dataField("turns")
                        .build()))
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat/completions")
                        .requestBodySchema(JsonRequestBodySchemaDto.builder()
                                .schema(Map.of("type", "object", "properties", Map.of()))
                                .build())
                        .build())
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/v1/chat/completions")
                        .body(JsonRequestBodyDto.builder()
                                .content(Map.of(
                                        "model",
                                        "gpt-4",
                                        "messages",
                                        List.of(Map.of("role", "user", "content", "${{turn}}"))))
                                .build())
                        .build())
                .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                        .name("answer")
                        .expression("choices[0].message.content")
                        .type(SchemaFieldType.STRING)
                        .build()))
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private TestCaseResponseDto createTestCase(UUID suiteId, String name, Map<String, Object> data) {
        ResponseEntity<TestCaseResponseDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suiteId) + "/test-cases"),
                jsonEntity(TestCaseRequestDto.builder()
                        .testCaseName(name)
                        .data(data)
                        .build()),
                TestCaseResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private TestSuiteRunResponseDto createRunAndAwaitTerminal(UUID suiteId) {
        ResponseEntity<TestSuiteRunResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suiteId + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder()
                        .runConfig(RunConfigDto.builder().numberOfRuns(1).build())
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
}
