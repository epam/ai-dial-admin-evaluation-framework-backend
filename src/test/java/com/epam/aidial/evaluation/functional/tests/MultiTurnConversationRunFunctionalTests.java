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
 * End-to-end functional test for row-based multi-turn conversations: create → run a DEPLOYMENT suite against
 * a mocked DIAL Core deployment where a conversation is an ordered group of discrete {@code test_cases} rows
 * (one row per turn, sharing a {@code conversationId} with contiguous {@code turnIndex} from 0). Multi-turn is
 * emergent from the data — there is no suite-level flag. Each turn is persisted as its own scalar result row
 * carrying {@code turn_index}/{@code total_turns}, its raw per-turn {@code response_body}, and scalar
 * {@code extracted_columns}; a broken conversation surfaces as one degenerate {@code 0/0} ERROR row and the
 * run still completes.
 */
@DisplayName("Multi-turn Conversation Run Functional Tests (row-based)")
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
        TestSuiteResponseDto suite = createConversationSuite();
        assertThat(suite.isValid()).isTrue();
        UUID conversationId = UUID.randomUUID();
        createTurn(suite.getId(), "conv1 / turn 0", conversationId, 0, "hello");
        createTurn(suite.getId(), "conv1 / turn 1", conversationId, 1, "how are you");

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

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(2);
        assertThat(callCount.get()).isEqualTo(2);

        results.sort(Comparator.comparingInt(r -> ((Number) r.get("turn_index")).intValue()));

        Map<String, Object> turn0 = results.get(0);
        assertThat(String.valueOf(turn0.get("execution_status"))).isEqualTo("SUCCESS");
        assertThat(((Number) turn0.get("turn_index")).intValue()).isEqualTo(0);
        assertThat(((Number) turn0.get("total_turns")).intValue()).isEqualTo(2);
        assertThat(objectMapper
                        .readTree(String.valueOf(turn0.get("extracted_columns")))
                        .get("answer")
                        .asString())
                .isEqualTo("reply-0");
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
    @DisplayName("Should run per-conversation turn counts: 2-turn and 3-turn conversations yield 2 and 3 per-turn rows")
    void shouldRunPerConversationTurnCounts() {
        TestSuiteResponseDto suite = createConversationSuite();
        UUID convA = UUID.randomUUID();
        createTurn(suite.getId(), "convA / turn 0", convA, 0, "a");
        createTurn(suite.getId(), "convA / turn 1", convA, 1, "b");
        UUID convB = UUID.randomUUID();
        createTurn(suite.getId(), "convB / turn 0", convB, 0, "a");
        createTurn(suite.getId(), "convB / turn 1", convB, 1, "b");
        createTurn(suite.getId(), "convB / turn 2", convB, 2, "c");

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
        assertThat(run.getNumberOfTestCases()).isEqualTo(2);

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(5);

        long twoTurnRows = results.stream()
                .peek(row ->
                        assertThat(String.valueOf(row.get("execution_status"))).isEqualTo("SUCCESS"))
                .filter(row -> ((Number) row.get("total_turns")).intValue() == 2)
                .count();
        long threeTurnRows = results.stream()
                .filter(row -> ((Number) row.get("total_turns")).intValue() == 3)
                .count();
        assertThat(twoTurnRows).isEqualTo(2);
        assertThat(threeTurnRows).isEqualTo(3);
    }

    @Test
    @DisplayName("Should surface a broken conversation (gap in turn indexes) as one 0/0 ERROR row; run completes")
    void shouldSurfaceBrokenConversationAsErrorRow() {
        TestSuiteResponseDto suite = createConversationSuite();
        UUID conversationId = UUID.randomUUID();
        createTurn(suite.getId(), "broken / turn 0", conversationId, 0, "hello");
        createTurn(suite.getId(), "broken / turn 2", conversationId, 2, "third");

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId());
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(1);
        Map<String, Object> row = results.get(0);
        assertThat(String.valueOf(row.get("execution_status"))).isEqualTo("ERROR");
        assertThat(((Number) row.get("turn_index")).intValue()).isEqualTo(0);
        assertThat(((Number) row.get("total_turns")).intValue()).isEqualTo(0);
    }

    @Test
    @DisplayName("Tail-only disable truncates the conversation to its surviving prefix (2 of 3 turns run)")
    void shouldTruncateConversationOnTailDisable() {
        TestSuiteResponseDto suite = createConversationSuite();
        UUID conversationId = UUID.randomUUID();
        createTurn(suite.getId(), "conv / turn 0", conversationId, 0, "a");
        createTurn(suite.getId(), "conv / turn 1", conversationId, 1, "b");
        TestCaseResponseDto lastTurn = createTurn(suite.getId(), "conv / turn 2", conversationId, 2, "c");
        metaTestDataHelper.appendDisabledTestCaseIds(suite.getId(), List.of(lastTurn.getId()));

        stubConstantReply();

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId());
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(2);
        assertThat(results).allMatch(r -> ((Number) r.get("total_turns")).intValue() == 2);
        assertThat(results).allMatch(r -> "SUCCESS".equals(String.valueOf(r.get("execution_status"))));
    }

    @Test
    @DisplayName("testCaseFilter includes a conversation only when ALL its turns match")
    void shouldIncludeConversationOnlyWhenAllTurnsMatchFilter() {
        TestSuiteResponseDto suite = createConversationSuite();
        UUID matching = UUID.randomUUID();
        createTurn(suite.getId(), "match / turn 0", matching, 0, Map.of("question", "a", "topic", "keep"));
        createTurn(suite.getId(), "match / turn 1", matching, 1, Map.of("question", "b", "topic", "keep"));
        UUID partial = UUID.randomUUID();
        createTurn(suite.getId(), "partial / turn 0", partial, 0, Map.of("question", "a", "topic", "keep"));
        createTurn(suite.getId(), "partial / turn 1", partial, 1, Map.of("question", "b", "topic", "drop"));
        metaTestDataHelper.setSuiteTestCaseFilter(
                suite.getId(),
                "{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"data::topic\"},"
                        + "{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"keep\"}]}");

        stubConstantReply();

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId());
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());
        assertThat(run.getNumberOfTestCases()).isEqualTo(1);

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(2);
        assertThat(results).allMatch(r -> ((Number) r.get("total_turns")).intValue() == 2);
    }

    private void stubConstantReply() {
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
    }

    private String contentOf(JsonNode responseBody) {
        return responseBody
                .path("choices")
                .get(0)
                .path("message")
                .get("content")
                .asString();
    }

    private TestSuiteResponseDto createConversationSuite() {
        String schemaJson;
        try {
            schemaJson = objectMapper.writeValueAsString(List.of(
                    FieldDefinitionDto.builder()
                            .name("question")
                            .type(SchemaFieldType.STRING)
                            .required(true)
                            .build(),
                    FieldDefinitionDto.builder()
                            .name("topic")
                            .type(SchemaFieldType.STRING)
                            .required(false)
                            .build()));
        } catch (JacksonException e) {
            throw new AssertionError("Failed to serialize test-case schema", e);
        }
        Dataset dataset = metaTestDataHelper.createDataset("conversation-" + UUID.randomUUID(), schemaJson);

        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Conversation Suite " + UUID.randomUUID())
                .description("row-based multi-turn")
                .datasetId(dataset.getId())
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("question")
                        .dataField("question")
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
                                        List.of(Map.of("role", "user", "content", "${{question}}"))))
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

    private TestCaseResponseDto createTurn(
            UUID suiteId, String name, UUID conversationId, int turnIndex, String question) {
        return createTurn(suiteId, name, conversationId, turnIndex, Map.of("question", question));
    }

    private TestCaseResponseDto createTurn(
            UUID suiteId, String name, UUID conversationId, int turnIndex, Map<String, Object> data) {
        ResponseEntity<TestCaseResponseDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suiteId) + "/test-cases"),
                jsonEntity(TestCaseRequestDto.builder()
                        .testCaseName(name)
                        .conversationId(conversationId)
                        .turnIndex(turnIndex)
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
