package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunResponseDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Functional tests for array-based multi-turn test cases: authoring round-trip + mutual exclusivity, and
 * end-to-end sequential turn-loop execution (history accumulation, per-turn result rows, fail-fast) against
 * a mocked chat-completions deployment.
 */
@DisplayName("Multi-turn Run Functional Tests")
public abstract class MultiTurnRunFunctionalTests extends AbstractMultiTurnFunctionalTest {

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
    @DisplayName("data + multiTurnData together is rejected with 400")
    void mutualExclusivityRejected() {
        TestSuiteResponseDto suite = createChatSuite("MT mutual-excl");
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
    @DisplayName("2-turn conversation persists two SUCCESS rows and accumulates history")
    void twoTurnConversation_accumulatesHistory() {
        TestSuiteResponseDto suite = createChatSuite("MT 2-turn");
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
    @DisplayName("Fail-fast: a failing turn stops the conversation with earlier SUCCESS rows kept")
    void failFast_stopsConversation() {
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
        assertThat(String.valueOf(turn1.get("execution_status"))).isIn("ERROR", "FAILED");
        assertThat(results.stream().anyMatch(r -> ((Number) r.get("turn_index")).intValue() == 2))
                .as("turn 2 must not be sent after turn 1 fails")
                .isFalse();
    }
}
