package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.runner.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunResponseDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Functional tests for array-based multi-turn test cases: authoring round-trip + mutual exclusivity, and
 * end-to-end sequential turn-loop execution (history accumulation via the JSONata request-template frame,
 * per-turn result rows, fail-fast) against a mocked chat-completions deployment.
 */
@DisplayName("Multi-turn Run Functional Tests")
public abstract class MultiTurnRunFunctionalTests extends AbstractMultiTurnFunctionalTest {

    /**
     * Chat suite whose request-template body is authored as a JSONata source string that accumulates
     * history via the {@code $history} frame variable (bound from the previous turn's {@code history}
     * response column) instead of the old hardcoded {@code messages}-array auto-accumulation. Turn 0
     * evaluates with {@code $history} unbound (undefined-append), matching the new per-turn contract.
     */
    private TestSuiteResponseDto createHistoryAccumulatingChatSuite(String name) {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name(name + " " + UUID.randomUUID())
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .requestBodySchema(JsonRequestBodySchemaDto.builder()
                                .schema(Map.of("type", "object", "properties", Map.of()))
                                .build())
                        .build())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .perTurn(true)
                        .build())))
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/v1/chat")
                        .body(JsonRequestBodyDto.builder()
                                .content("{\"messages\": $append($history, "
                                        + "[{\"role\": \"user\", \"content\": \"${{prompt}}\"}])}")
                                .build())
                        .build())
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("prompt")
                        .dataField("prompt")
                        .build()))
                .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                        .name("history")
                        .expression("$append($request.messages, [$response.choices[0].message])")
                        .type(SchemaFieldType.ARRAY)
                        .build()))
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    // -------------------- Authoring --------------------

    @Test
    @DisplayName("Multi-turn case round-trips multiTurnData and omits it for single-turn")
    void multiTurnRoundTrip() {
        TestSuiteResponseDto suite = createChatSuite("MT round-trip");
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());

        TestCaseResponseDto created =
                createMultiTurnCase(datasetId, "conv-1", List.of(Map.of("prompt", "hi"), Map.of("prompt", "again")));
        assertThat(created.getMultiTurnData()).hasSize(2);
        assertThat(created.getData()).isNullOrEmpty();

        ResponseEntity<TestCaseResponseDto> read = restTemplate.getForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases/" + created.getId()), TestCaseResponseDto.class);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody().getMultiTurnData()).hasSize(2);

        // A single-turn case in the same dataset omits multiTurnData
        TestCaseResponseDto single = createSingleTurnCase(datasetId, "single-1", Map.of("prompt", "hi"));
        assertThat(single.getMultiTurnData()).isNull();
    }

    @Test
    @DisplayName("A per-turn field placed in shared data is rejected with 400")
    void misplacedPerTurnFieldRejected() {
        // `prompt` is a per-turn field in this dataset; placing it in the shared `data` map is a structural
        // placement error (data and multiTurnData themselves may coexist — only misplacement is a 400).
        TestSuiteResponseDto suite = createChatSuite("MT placement");
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases"),
                jsonEntity(TestCaseRequestDto.builder()
                        .testCaseName("bad-1")
                        .data(Map.of("prompt", "hi"))
                        .multiTurnData(List.of(Map.of("prompt", "hi")))
                        .build()),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("empty multiTurnData array is rejected with 400")
    void emptyMultiTurnRejected() {
        TestSuiteResponseDto suite = createChatSuite("MT empty");
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases"),
                jsonEntity(TestCaseRequestDto.builder()
                        .testCaseName("empty-1")
                        .multiTurnData(List.of())
                        .build()),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // -------------------- Execution --------------------

    @Test
    @DisplayName("2-turn test case persists two SUCCESS rows and accumulates history via the JSONata frame")
    void twoTurnCase_accumulatesHistory() {
        // Per the jsonata-request-templates change, history accumulation is no longer a hardcoded
        // messages-array concatenation: it is entirely the author's JSONata expression
        // ($append($history, [...])), fed by the previous turn's `history` response column via the
        // request-template frame (Decision 5).
        TestSuiteResponseDto suite = createHistoryAccumulatingChatSuite("MT 2-turn");
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        createMultiTurnCase(datasetId, "conv-2turn", List.of(Map.of("prompt", "q0"), Map.of("prompt", "q1")));

        AtomicInteger call = new AtomicInteger();
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> chatReply("reply-" + call.getAndIncrement()));

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 30);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(2);
        assertThat(results).allSatisfy(r -> {
            assertThat(String.valueOf(r.get("execution_status"))).isEqualTo("SUCCESS");
            assertThat(((Number) r.get("total_turns")).intValue()).isEqualTo(2);
        });
        assertThat(results.stream().map(r -> ((Number) r.get("turn_index")).intValue()))
                .containsExactlyInAnyOrder(0, 1);

        // Turn 1's request body carries the accumulated history: turn 0's user message + assistant reply.
        Map<String, Object> turn1 = results.stream()
                .filter(r -> ((Number) r.get("turn_index")).intValue() == 1)
                .findFirst()
                .orElseThrow();
        String turn1Request = String.valueOf(turn1.get("request_body"));
        assertThat(turn1Request).contains("q0").contains("reply-0").contains("q1");
    }

    @Test
    @DisplayName("2-turn metric-less case yields one eval summary per turn with turn_index 0..N-1")
    void twoTurnCase_writesOneMetricLessEvalSummaryPerTurn() {
        // This suite carries no TSMDs, so the run is metric-less and every turn must still be readable.
        TestSuiteResponseDto suite = createChatSuite("MT 2-turn summaries");
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        createMultiTurnCase(datasetId, "conv-summaries", List.of(Map.of("prompt", "q0"), Map.of("prompt", "q1")));

        AtomicInteger call = new AtomicInteger();
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> chatReply("reply-" + call.getAndIncrement()));

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 30);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Map<String, Object>> summaries = analyticsTestDataHelper.findEvalSummariesByRunId(run.getId());
        assertThat(summaries).hasSize(2);
        assertThat(summaries).allSatisfy(summary -> {
            assertThat(((Number) summary.get("total_turns")).intValue()).isEqualTo(2);
            assertThat((String) summary.get("metric_values")).isEqualTo("{}");
        });
        assertThat(summaries.stream().map(s -> ((Number) s.get("turn_index")).intValue()))
                .containsExactlyInAnyOrder(0, 1);
        assertThat(analyticsTestDataHelper.findRunMetricSnapshotsByRunId(run.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("Fail-fast: a failing turn stops the run with earlier SUCCESS rows kept")
    void failFast_stopsRun() {
        TestSuiteResponseDto suite = createChatSuite("MT fail-fast");
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        createMultiTurnCase(
                datasetId,
                "conv-fail",
                List.of(Map.of("prompt", "q0"), Map.of("prompt", "q1"), Map.of("prompt", "q2")));

        AtomicInteger call = new AtomicInteger();
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    if (call.getAndIncrement() == 0) {
                        return chatReply("reply-0");
                    }
                    return new DeploymentInvocationResult(500, false, Map.of("error", "boom"), null, new HttpHeaders());
                });

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 30);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        // Turn 0 SUCCESS, turn 1 ERROR, turn 2 never sent.
        assertThat(results).hasSize(2);
        Map<String, Object> turn0 = results.stream()
                .filter(r -> ((Number) r.get("turn_index")).intValue() == 0)
                .findFirst()
                .orElseThrow();
        Map<String, Object> turn1 = results.stream()
                .filter(r -> ((Number) r.get("turn_index")).intValue() == 1)
                .findFirst()
                .orElseThrow();
        assertThat(String.valueOf(turn0.get("execution_status"))).isEqualTo("SUCCESS");
        // HTTP 500 maps deterministically to FAILED (only 401/403 → ERROR).
        assertThat(String.valueOf(turn1.get("execution_status"))).isEqualTo("FAILED");
        assertThat(results.stream().anyMatch(r -> ((Number) r.get("turn_index")).intValue() == 2))
                .as("turn 2 must not be sent after turn 1 fails")
                .isFalse();
    }
}
