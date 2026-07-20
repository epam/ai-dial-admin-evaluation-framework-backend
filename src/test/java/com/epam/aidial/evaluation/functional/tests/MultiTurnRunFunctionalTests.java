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
 * End-to-end functional test for row-based multi-turn multiTurns: create → run a DEPLOYMENT suite against
 * a mocked DIAL Core deployment where a multiTurn is an ordered group of discrete {@code test_cases} rows
 * (one row per turn, sharing a {@code multiTurnId}). Surviving turns run in ascending authored
 * {@code turnIndex} order with gaps allowed (a disabled/filtered start or middle turn simply drops); multi-turn
 * is emergent from the data — there is no suite-level flag. Each turn is persisted as its own scalar result row
 * carrying {@code turn_index}/{@code total_turns}/{@code last_turn_index}, its raw per-turn {@code response_body},
 * and scalar {@code extracted_columns}; a broken multiTurn (an invalid surviving turn, or over the turn cap)
 * surfaces as one degenerate {@code 0/0} ERROR row and the run still completes.
 */
@DisplayName("Multi-turn MultiTurn Run Functional Tests (row-based)")
public abstract class MultiTurnRunFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private DialCoreDeploymentInvoker deploymentInvoker;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Should persist one scalar result row per turn with turn_index/total_turns for a 2-turn multiTurn")
    void shouldRunTwoTurnMultiTurn() throws JacksonException {
        TestSuiteResponseDto suite = createMultiTurnSuite();
        assertThat(suite.isValid()).isTrue();
        UUID multiTurnId = UUID.randomUUID();
        createTurn(suite.getId(), "conv1 / turn 0", multiTurnId, 0, "hello");
        createTurn(suite.getId(), "conv1 / turn 1", multiTurnId, 1, "how are you");

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
    @DisplayName("Should run per-multiTurn turn counts: 2-turn and 3-turn multiTurns yield 2 and 3 per-turn rows")
    void shouldRunPerMultiTurnTurnCounts() {
        TestSuiteResponseDto suite = createMultiTurnSuite();
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
    @DisplayName("A gap in turn indexes no longer breaks the multiTurn — survivors run, authored indices kept")
    void shouldRunMultiTurnWithGapPreservingAuthoredIndices() {
        TestSuiteResponseDto suite = createMultiTurnSuite();
        UUID multiTurnId = UUID.randomUUID();
        createTurn(suite.getId(), "gap / turn 0", multiTurnId, 0, "hello");
        createTurn(suite.getId(), "gap / turn 2", multiTurnId, 2, "third");

        stubConstantReply();

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId());
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(2);
        assertThat(results).allMatch(r -> "SUCCESS".equals(String.valueOf(r.get("execution_status"))));
        assertThat(results).allMatch(r -> ((Number) r.get("total_turns")).intValue() == 2);
        // Authored indices preserved (0 and 2, not renumbered), and last_turn_index is the max authored (2).
        assertThat(results.stream()
                        .map(r -> ((Number) r.get("turn_index")).intValue())
                        .sorted()
                        .toList())
                .containsExactly(0, 2);
        assertThat(results).allMatch(r -> ((Number) r.get("last_turn_index")).intValue() == 2);
    }

    @Test
    @DisplayName("An invalid surviving turn breaks the multiTurn — one 0/0 ERROR row; run completes")
    void shouldBreakMultiTurnWithInvalidSurvivingTurn() {
        TestSuiteResponseDto suite = createMultiTurnSuite();
        UUID multiTurnId = UUID.randomUUID();
        createTurn(suite.getId(), "invalid / turn 0", multiTurnId, 0, "hello");
        TestCaseResponseDto t1 = createTurn(suite.getId(), "invalid / turn 1", multiTurnId, 1, "second");
        metaTestDataHelper.forceTestCaseInvalid(t1.getId(), "[\"forced invalid\"]");

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId());
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(1);
        Map<String, Object> row = results.get(0);
        assertThat(String.valueOf(row.get("execution_status"))).isEqualTo("ERROR");
        assertThat(((Number) row.get("turn_index")).intValue()).isEqualTo(0);
        assertThat(((Number) row.get("total_turns")).intValue()).isEqualTo(0);
        assertThat(((Number) row.get("last_turn_index")).intValue()).isEqualTo(0);
    }

    @Test
    @DisplayName("A fully-disabled multiTurn is excluded from the run entirely (no unit, no ERROR row)")
    void shouldExcludeFullyDisabledMultiTurn() {
        TestSuiteResponseDto suite = createMultiTurnSuite();
        UUID runnable = UUID.randomUUID();
        createTurn(suite.getId(), "runnable / turn 0", runnable, 0, "a");
        createTurn(suite.getId(), "runnable / turn 1", runnable, 1, "b");
        UUID disabled = UUID.randomUUID();
        TestCaseResponseDto d0 = createTurn(suite.getId(), "disabled / turn 0", disabled, 0, "x");
        TestCaseResponseDto d1 = createTurn(suite.getId(), "disabled / turn 1", disabled, 1, "y");
        metaTestDataHelper.appendDisabledTestCaseIds(suite.getId(), List.of(d0.getId(), d1.getId()));

        stubConstantReply();

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId());
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());
        // Only the runnable multiTurn survives as a single execution unit.
        assertThat(run.getNumberOfTestCases()).isEqualTo(1);

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(2);
        assertThat(results).allMatch(r -> "SUCCESS".equals(String.valueOf(r.get("execution_status"))));
        assertThat(results).allMatch(r -> ((Number) r.get("total_turns")).intValue() == 2);
    }

    @Test
    @DisplayName("Tail-only disable truncates the multiTurn to its surviving prefix (2 of 3 turns run)")
    void shouldTruncateMultiTurnOnTailDisable() {
        TestSuiteResponseDto suite = createMultiTurnSuite();
        UUID multiTurnId = UUID.randomUUID();
        createTurn(suite.getId(), "conv / turn 0", multiTurnId, 0, "a");
        createTurn(suite.getId(), "conv / turn 1", multiTurnId, 1, "b");
        TestCaseResponseDto lastTurn = createTurn(suite.getId(), "conv / turn 2", multiTurnId, 2, "c");
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
    @DisplayName("testCaseFilter applies row-level: a non-matching tail turn truncates the multiTurn (still runnable)")
    void shouldTruncateMultiTurnWhenFilterDropsTailTurn() {
        TestSuiteResponseDto suite = createMultiTurnSuite();
        UUID full = UUID.randomUUID();
        createTurn(suite.getId(), "full / turn 0", full, 0, Map.of("question", "a", "topic", "keep"));
        createTurn(suite.getId(), "full / turn 1", full, 1, Map.of("question", "b", "topic", "keep"));
        UUID tail = UUID.randomUUID();
        createTurn(suite.getId(), "tail / turn 0", tail, 0, Map.of("question", "a", "topic", "keep"));
        createTurn(suite.getId(), "tail / turn 1", tail, 1, Map.of("question", "b", "topic", "drop"));
        metaTestDataHelper.setSuiteTestCaseFilter(suite.getId(), keepTopicFilter());

        stubConstantReply();

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId());
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());
        // Both multiTurns have >=1 matching turn, so both are runnable units: full (2 turns) + tail (1 turn).
        assertThat(run.getNumberOfTestCases()).isEqualTo(2);

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(3);
        assertThat(results).allMatch(r -> "SUCCESS".equals(String.valueOf(r.get("execution_status"))));
        assertThat(results.stream()
                        .filter(r -> ((Number) r.get("total_turns")).intValue() == 2)
                        .count())
                .isEqualTo(2);
        assertThat(results.stream()
                        .filter(r -> ((Number) r.get("total_turns")).intValue() == 1)
                        .count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("testCaseFilter applies row-level: a non-matching middle turn is honored — survivors run")
    void shouldRunMultiTurnWhenFilterLeavesMiddleHole() {
        TestSuiteResponseDto suite = createMultiTurnSuite();
        UUID mid = UUID.randomUUID();
        createTurn(suite.getId(), "mid / turn 0", mid, 0, Map.of("question", "a", "topic", "keep"));
        createTurn(suite.getId(), "mid / turn 1", mid, 1, Map.of("question", "b", "topic", "drop"));
        createTurn(suite.getId(), "mid / turn 2", mid, 2, Map.of("question", "c", "topic", "keep"));
        metaTestDataHelper.setSuiteTestCaseFilter(suite.getId(), keepTopicFilter());

        stubConstantReply();

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId());
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());
        // Turns 0 and 2 match; the filtered-out middle turn simply drops → survivors 0,2 run as one unit.
        assertThat(run.getNumberOfTestCases()).isEqualTo(1);

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(2);
        assertThat(results).allMatch(r -> "SUCCESS".equals(String.valueOf(r.get("execution_status"))));
        assertThat(results).allMatch(r -> ((Number) r.get("total_turns")).intValue() == 2);
        assertThat(results.stream()
                        .map(r -> ((Number) r.get("turn_index")).intValue())
                        .sorted()
                        .toList())
                .containsExactly(0, 2);
        assertThat(results).allMatch(r -> ((Number) r.get("last_turn_index")).intValue() == 2);
    }

    private static String keepTopicFilter() {
        return "{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"data::topic\"},"
                + "{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"keep\"}]}";
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

    private TestSuiteResponseDto createMultiTurnSuite() {
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
        Dataset dataset = metaTestDataHelper.createDataset("multiTurn-" + UUID.randomUUID(), schemaJson);

        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("MultiTurn Suite " + UUID.randomUUID())
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
            UUID suiteId, String name, UUID multiTurnId, int turnIndex, String question) {
        return createTurn(suiteId, name, multiTurnId, turnIndex, Map.of("question", question));
    }

    private TestCaseResponseDto createTurn(
            UUID suiteId, String name, UUID multiTurnId, int turnIndex, Map<String, Object> data) {
        ResponseEntity<TestCaseResponseDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suiteId) + "/test-cases"),
                jsonEntity(TestCaseRequestDto.builder()
                        .testCaseName(name)
                        .multiTurnId(multiTurnId)
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
